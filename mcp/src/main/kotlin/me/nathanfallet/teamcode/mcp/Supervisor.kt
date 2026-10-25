package me.nathanfallet.teamcode.mcp

import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.api.BuildInfo

/**
 * Owns every process of the host: one per key, started on demand (an operation or a warm-up), shared by concurrent
 * operations, stopped when idle, too old, over its memory limit, revoked or replaced. Nothing is kept on disk beyond
 * the per-key caches, and nothing is shared between hosts: a restart only loses this cache.
 */
class Supervisor(
    private val config: HostConfig,
    private val launchers: Launchers = Launchers(config),
    private val events: EventSink = EventSink.LOG,
    private val memory: MemoryProbe = MemoryProbe.SYSTEM,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val capacity = Mutex()
    private val activeOps = AtomicInteger()
    @Volatile private var draining = false
    @Volatile private var closed = false
    private val launchersPresent: Boolean = config.launchers.all { launcher ->
        if (launcher == Launchers.NPM) executable(config.npx) && executable("node") else executable(config.uvx)
    }

    private class Entry(val key: String) {
        val mutex = Mutex()
        @Volatile var process: StdioProcess? = null
        @Volatile var starting: Deferred<StdioProcess>? = null
        @Volatile var fingerprint: String? = null
        @Volatile var launcher = ""
        @Volatile var pkg = ""
        val failureTimes = ArrayDeque<Long>()
        @Volatile var backoffUntil = 0L
        @Volatile var failures = 0
        @Volatile var starts = 0
        @Volatile var lastFailure: String? = null
        @Volatile var lastStartFailed = false
        @Volatile var lastExitCode: Int? = null
        @Volatile var coldStartMillis: Long? = null
        @Volatile var rssBytes: Long? = null
        @Volatile var lastStderr = ""

        fun state(now: Long = System.currentTimeMillis()) = when {
            backoffUntil > now -> ProcessStates.BACKOFF
            starting != null -> ProcessStates.STARTING
            process?.alive == true -> ProcessStates.READY
            lastStartFailed -> ProcessStates.FAILED
            else -> ProcessStates.IDLE
        }
    }

    init {
        scope.launch {
            var lastMemoryCheck = 0L
            while (isActive) {
                delay(config.tickMillis)
                val now = System.currentTimeMillis()
                sweep(now)
                if (now - lastMemoryCheck >= config.memoryCheckMillis) {
                    lastMemoryCheck = now
                    withContext(Dispatchers.IO) { checkMemory() }
                }
            }
        }
    }

    /** One `list` or `call`, started or queued as needed, within [OpsRequest.deadlineMs]. */
    suspend fun op(request: OpsRequest): OpsResponse {
        validate(request.key)
        if (request.op != "list" && request.op != "call") throw HostFailure(HostErrors.BAD_REQUEST, "op must be list or call.")
        if (request.op == "call" && request.tool.isNullOrEmpty()) throw HostFailure(HostErrors.BAD_REQUEST, "A call names its tool.")
        if (request.deadlineMs !in 1..600_000L) throw HostFailure(HostErrors.BAD_REQUEST, "deadlineMs must be between 1 and 600000.")
        if (request.maxResponseBytes !in 1_024..64 * 1024 * 1024) throw HostFailure(HostErrors.BAD_REQUEST, "maxResponseBytes must be between 1 KiB and 64 MiB.")
        val entry = entries.computeIfAbsent(request.key) { Entry(it) }
        activeOps.incrementAndGet()
        try {
            var phase = Phase.START
            var cold = false
            val outcome = withTimeoutOrNull(request.deadlineMs) {
                try {
                    val (process, wasCold) = obtain(entry, request.spec)
                    cold = wasCold
                    process.users.incrementAndGet()
                    process.touch()
                    try {
                        phase = Phase.QUEUE
                        process.permits.withPermit {
                            phase = Phase.RUN
                            val result = if (request.op == "list") process.listTools(request.maxResponseBytes)
                                else process.callTool(request.tool!!, request.arguments, request.maxResponseBytes)
                            Result.success(process.scrubber.scrub(result) as JsonObject)
                        }
                    } finally {
                        process.touch()
                        process.users.decrementAndGet()
                    }
                } catch (failure: HostFailure) {
                    Result.failure(failure)
                }
            } ?: Result.failure(when (phase) {
                Phase.START -> HostFailure(HostErrors.TIMEOUT, "The MCP server did not finish starting in time; it keeps starting for the next call.")
                Phase.QUEUE -> HostFailure(HostErrors.BUSY, "The MCP server is busy with ${config.maxInFlightPerProcess} calls.")
                Phase.RUN -> HostFailure(HostErrors.TIMEOUT, "The MCP server did not answer in time.")
            })
            val failure = outcome.exceptionOrNull() as HostFailure?
            return OpsResponse(outcome.getOrNull(), failure?.let { HostError(it.code, it.message.orEmpty()) }, cold, entry.state())
        } finally {
            activeOps.decrementAndGet()
        }
    }

    private enum class Phase { START, QUEUE, RUN }

    /** Starts the process of [WarmRequest.key] in the background, so the agent's first tools/list finds it ready. */
    fun warm(request: WarmRequest): KeyState {
        validate(request.key)
        val entry = entries.computeIfAbsent(request.key) { Entry(it) }
        scope.launch { try { obtain(entry, request.spec) } catch (_: HostFailure) {} }
        return KeyState(request.key, entry.state())
    }

    /** Stops every process whose key is not in [keys] (revoked assignments, deleted variants) and removes its directory. */
    suspend fun retain(keys: Collection<String>): Int {
        keys.forEach { validate(it) }
        val keep = keys.toSet()
        val stopping = mutableListOf<StdioProcess>()
        for ((key, entry) in entries) {
            if (key in keep) continue
            entry.mutex.withLock {
                entries.remove(key, entry)
                entry.process?.let { if (retire(entry, it, StopReason.RETAINED)) stopping += it }
            }
        }
        scope.launch {
            stopping.forEach { withTimeoutOrNull(config.closeStdinWaitMillis + 2 * config.terminateWaitMillis) { it.exited.await() } }
            withContext(Dispatchers.IO) {
                if (!config.dataDir.isDirectory()) return@withContext
                Files.list(config.dataDir).use { list ->
                    list.filter { HostProtocol.KEY.matches(it.name) && it.name !in keep && !entries.containsKey(it.name) }.toList()
                }.forEach { it.toFile().deleteRecursively() }
            }
        }
        return stopping.size
    }

    /** The administrator's "Restart": stops the process and lifts a backoff. The next operation starts it again. */
    suspend fun stop(key: String): Boolean {
        validate(key)
        val entry = entries[key] ?: return false
        entry.mutex.withLock {
            entry.backoffUntil = 0
            synchronized(entry.failureTimes) { entry.failureTimes.clear() }
            entry.lastStartFailed = false
            entry.process?.let { retire(entry, it, StopReason.STOPPED) }
        }
        return true
    }

    fun status(): HostStatus = HostStatus(
        version = BuildInfo.VERSION,
        protocol = HostProtocol.VERSION,
        launchers = config.launchers.sorted(),
        maxProcesses = config.maxProcesses,
        draining = draining,
        processes = entries.values.sortedBy { it.key }.map { entry ->
            val process = entry.process
            ProcessStatus(
                key = entry.key, state = entry.state(), launcher = entry.launcher, pkg = entry.pkg,
                pid = process?.pid, startedAt = process?.startedAt, lastUsedAt = process?.lastUsed?.get(), inFlight = process?.users?.get() ?: 0,
                starts = entry.starts, failures = entry.failures, lastExitCode = entry.lastExitCode, lastFailure = entry.lastFailure,
                coldStartMillis = entry.coldStartMillis, rssBytes = entry.rssBytes, backoffUntil = entry.backoffUntil.takeIf { it > 0 },
                stderr = process?.stderr?.snapshot() ?: entry.lastStderr,
            )
        },
    )

    /** Ready while not draining, with the launchers' commands present. */
    fun ready(): Boolean = !draining && !closed && launchersPresent

    /**
     * Called on SIGTERM: readiness fails so the Service stops sending new operations, what arrives meanwhile is still
     * answered for at least [HostConfig.drainMinMillis], and running operations get up to [HostConfig.drainMillis].
     */
    suspend fun drain() {
        draining = true
        val started = System.currentTimeMillis()
        delay(config.drainMinMillis)
        while (activeOps.get() > 0 && System.currentTimeMillis() - started < config.drainMillis) delay(50)
    }

    /** Stops every process and the supervisor. Idempotent. */
    suspend fun shutdown() {
        if (closed) return
        closed = true
        draining = true
        val stopping = entries.values.mapNotNull { entry -> entry.process?.takeIf { retire(entry, it, StopReason.SHUTDOWN) } }
        stopping.map { process -> scope.async { withTimeoutOrNull(config.closeStdinWaitMillis + 2 * config.terminateWaitMillis + 1_000) { process.exited.await() } } }.awaitAll()
        scope.cancel()
    }

    private suspend fun obtain(entry: Entry, spec: ProcessSpec): Pair<StdioProcess, Boolean> {
        val fingerprint = fingerprint(spec)
        val starting = entry.mutex.withLock {
            if (closed) throw HostFailure(HostErrors.SHUTTING_DOWN, "The MCP host is shutting down.")
            if (entry.fingerprint != fingerprint) {
                // Another definition for the same key: the server rotated something it should have put in the key.
                entry.process?.let { retire(entry, it, StopReason.REPLACED) }
                entry.backoffUntil = 0
                synchronized(entry.failureTimes) { entry.failureTimes.clear() }
                entry.fingerprint = fingerprint
                entry.launcher = spec.launcher
                entry.pkg = spec.pkg
            }
            val now = System.currentTimeMillis()
            if (entry.backoffUntil > now) throw HostFailure(HostErrors.BACKOFF,
                "The MCP server failed more than ${config.crashLimit} times in ${config.crashWindowMillis / 60_000} minutes; the host starts it again in " +
                    "${(entry.backoffUntil - now + 59_999) / 60_000} minutes, or when an administrator restarts it.")
            entry.process?.takeIf { it.alive }?.let { return it to false }
            entry.starting ?: scope.async { start(entry, spec) }.also { entry.starting = it }
        }
        return starting.await() to true
    }

    private suspend fun start(entry: Entry, spec: ProcessSpec): StdioProcess {
        try {
            reserve(entry)
            val dirs = ProcessDirs(config.dataDir.resolve(entry.key))
            val plan = launchers.plan(spec, dirs)
            val describe = buildJsonObject { put("key", entry.key.take(12)); put("launcher", spec.launcher); put("package", spec.pkg) }
            val process = StdioProcess(entry.key, plan, dirs, config, events, describe,
                onExit = { process, reason, code -> exited(entry, process, reason, code) },
                requestStop = { process, reason -> retire(entry, process, reason) })
            val started = System.nanoTime()
            try {
                withTimeout(config.startTimeoutMillis) {
                    process.launch()
                    process.initialize()
                }
            } catch (_: TimeoutCancellationException) {
                retireStarting(entry, process)
                throw HostFailure(HostErrors.START_FAILED, "The MCP server did not finish starting within ${config.startTimeoutMillis / 1_000} seconds.")
            } catch (failure: HostFailure) {
                retireStarting(entry, process)
                if (failure.code != HostErrors.CRASHED) throw failure
                val code = withTimeoutOrNull(2_000) { process.exited.await() }
                throw HostFailure(HostErrors.START_FAILED, "The MCP server exited during startup" + (code?.let { " (exit code $it)." } ?: "."))
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            entry.mutex.withLock {
                entry.starting = null
                if (closed || entries[entry.key] !== entry) {
                    retireStarting(entry, process, StopReason.SHUTDOWN)
                    throw HostFailure(HostErrors.SHUTTING_DOWN, "The MCP server was revoked while it started.")
                }
                entry.process = process
                entry.starts++
                entry.coldStartMillis = elapsed
                entry.lastStartFailed = false
            }
            events.emit(process.event("started") { put("pid", process.pid ?: -1); put("coldStartMillis", elapsed) })
            return process
        } catch (failure: HostFailure) {
            entry.mutex.withLock {
                entry.starting = null
                if (failure.code !in setOf(HostErrors.CAPACITY, HostErrors.SHUTTING_DOWN)) {
                    entry.lastStartFailed = true
                    recordFailure(entry, failure.code)
                }
            }
            events.emit(buildJsonObject { put("event", "start_failed"); put("key", entry.key.take(12)); put("launcher", spec.launcher); put("package", spec.pkg); put("code", failure.code) })
            throw failure
        }
    }

    private fun retireStarting(entry: Entry, process: StdioProcess, reason: StopReason = StopReason.START_FAILED) {
        entry.lastStderr = process.stderr.snapshot()
        if (process.markStopping(reason)) scope.launch { process.stop(reason) }
    }

    /** Makes room under [HostConfig.maxProcesses]: the idle process used the longest ago is stopped, a busy one never. */
    private suspend fun reserve(entry: Entry) = capacity.withLock {
        val live = entries.values.filter { it !== entry && (it.process != null || it.starting != null) }
        if (live.size < config.maxProcesses) return@withLock
        val (victim, process) = live.mapNotNull { other -> other.process?.takeIf { it.users.get() == 0 }?.let { other to it } }
            .minByOrNull { it.second.lastUsed.get() }
            ?: throw HostFailure(HostErrors.CAPACITY, "The MCP host already runs ${config.maxProcesses} busy servers.")
        retire(victim, process, StopReason.CAPACITY)
    }

    /** Detaches [process] from its key and stops it in the background. False when it was already stopping. */
    private fun retire(entry: Entry, process: StdioProcess, reason: StopReason): Boolean {
        if (!process.markStopping(reason)) return false
        if (entry.process === process) entry.process = null
        scope.launch { process.stop(reason) }
        return true
    }

    private fun exited(entry: Entry, process: StdioProcess, reason: StopReason, code: Int) {
        scope.launch {
            entry.mutex.withLock {
                if (entry.process === process) entry.process = null
                entry.lastExitCode = code
                entry.lastStderr = process.stderr.snapshot()
                // A process that never became ready is counted once, by its failed start.
                if (process.ready && reason.failure) recordFailure(entry, reason.label)
            }
        }
    }

    /** Called under the entry's lock. More than [HostConfig.crashLimit] failures in the window: backoff. */
    private fun recordFailure(entry: Entry, label: String) {
        val now = System.currentTimeMillis()
        entry.failures++
        entry.lastFailure = label
        val recent = synchronized(entry.failureTimes) {
            entry.failureTimes.addLast(now)
            while (entry.failureTimes.isNotEmpty() && now - entry.failureTimes.first() > config.crashWindowMillis) entry.failureTimes.removeFirst()
            entry.failureTimes.size
        }
        if (recent > config.crashLimit) {
            entry.backoffUntil = now + config.backoffMillis
            synchronized(entry.failureTimes) { entry.failureTimes.clear() }
            events.emit(buildJsonObject {
                put("event", "backoff"); put("key", entry.key.take(12)); put("launcher", entry.launcher); put("package", entry.pkg)
                put("failures", recent); put("untilSeconds", config.backoffMillis / 1_000)
            })
        }
    }

    private fun sweep(now: Long) {
        for (entry in entries.values) {
            val process = entry.process ?: continue
            when {
                process.writeStalledMillis() > config.writeStallMillis -> retire(entry, process, StopReason.WRITE_STALL)
                process.users.get() > 0 -> {}
                now - process.lastUsed.get() > config.idleMillis -> retire(entry, process, StopReason.IDLE)
                now - process.startedAt > config.maxLifetimeMillis -> retire(entry, process, StopReason.LIFETIME)
            }
        }
    }

    private fun checkMemory() {
        for (entry in entries.values) {
            val process = entry.process ?: continue
            val rss = memory.rssBytes(process.pids()) ?: continue
            entry.rssBytes = rss
            if (rss > config.processMemoryBytes) retire(entry, process, StopReason.MEMORY)
        }
    }

    private fun validate(key: String) {
        if (!HostProtocol.KEY.matches(key)) throw HostFailure(HostErrors.BAD_REQUEST, "A key is 64 lowercase hexadecimal characters.")
    }

    private fun executable(command: String): Boolean =
        if ('/' in command) java.nio.file.Path.of(command).isExecutable()
        else config.path.split(':').any { it.isNotEmpty() && java.nio.file.Path.of(it, command).isExecutable() }

    private fun fingerprint(spec: ProcessSpec): String {
        val canonical = HostProtocol.json.encodeToString(ProcessSpec.serializer(), spec.copy(env = spec.env.toSortedMap(), secrets = spec.secrets.toSortedMap()))
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
