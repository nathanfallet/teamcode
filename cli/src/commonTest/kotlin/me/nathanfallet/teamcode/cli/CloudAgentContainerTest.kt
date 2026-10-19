package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import me.nathanfallet.teamcode.cli.cloud.agent.*
import me.nathanfallet.teamcode.cli.platform.*
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.*

/** The agent's container: the exec supervisor and the credential broker, over real Unix sockets. */
class CloudAgentContainerTest {
    private class Child : AgentProcess {
        val written = mutableListOf<String>()
        val output = Channel<String?>(Channel.UNLIMITED)
        @kotlin.concurrent.Volatile var exit: Int? = null
        @kotlin.concurrent.Volatile var stops = 0
        override suspend fun writeLine(line: String): Boolean { if (exit != null) return false; written += line; return true }
        override suspend fun readLine(maxLine: Int) = if (exit != null && output.isEmpty) null else output.receive()
        override fun closeInput() = Unit
        override fun exitCode() = exit
        override suspend fun stop(graceMillis: Long): Int { stops++; if (exit == null) { exit = 0; output.trySend(null) }; return exit!! }
    }

    private class Started(val command: String, val arguments: List<String>, val environment: Map<String, String?>, val directory: String, val child: Child)

    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tc-${Random.nextInt().toUInt().toString(16)}"
    private fun socket(name: String): String { FileSystem.SYSTEM.createDirectories(directory); return (directory / name).toString() }
    @AfterTest fun cleanup() { FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false) }

    private suspend fun until(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        withTimeout(timeoutMillis) { while (!condition()) delay(10) }
    }

    @Test fun runnerStartsAndDrivesProcessesThroughTheSupervisor(): Unit = runBlocking {
        withTimeout(60_000) {
            val path = socket("agent.sock")
            val started = mutableListOf<Started>()
            val supervisor = AgentSupervisor(listenUnix(path), { command, arguments, environment, cwd -> Child().also { started += Started(command, arguments, environment, cwd, it) }.let { it } }, "/workspace")
            val served = async(Dispatchers.Default) { supervisor.serve() }
            val scope = CoroutineScope(SupervisorJob())
            val remote = RemoteProcesses(path, scope)
            val lifeline = remote.hold()

            val process = remote.start("claude", listOf("-p", "--verbose"), mapOf("KEEP" to "1", "DROP" to null), "/workspace/app")
            until { started.isNotEmpty() }
            val first = started.single()
            assertEquals("claude", first.command); assertEquals(listOf("-p", "--verbose"), first.arguments)
            assertEquals(mapOf("KEEP" to "1", "DROP" to null), first.environment, "The runner's overrides, removals included, reach the agent's environment")
            assertEquals("/workspace/app", first.directory)

            assertTrue(process.writeLine("""{"type":"user"}"""))
            until { first.child.written == listOf("""{"type":"user"}""") }
            first.child.output.send("line one")
            assertEquals("line one", process.readLine())
            assertNull(process.exitCode(), "Still running")

            assertEquals(0, process.stop(500))
            assertNull(process.readLine(), "The stream ends with the process")
            assertFalse(process.writeLine("late"), "Nothing is written to an exited process")
            assertTrue(first.child.stops >= 1)

            // Closing the runner's lifeline stops whatever still runs, and the supervisor with it.
            remote.start("sleep", emptyList(), emptyMap(), "/workspace")
            until { started.size == 2 }
            lifeline.close()
            assertEquals(0, served.await())
            until { started[1].child.stops > 0 }
            scope.cancel()
        }
    }

    @Test fun aProcessWhoseRunnerVanishedIsStopped(): Unit = runBlocking {
        withTimeout(60_000) {
            val path = socket("agent.sock")
            val started = mutableListOf<Started>()
            val supervisor = AgentSupervisor(listenUnix(path), { command, arguments, environment, cwd -> Child().also { started += Started(command, arguments, environment, cwd, it) } }, "/workspace")
            val served = async(Dispatchers.Default) { supervisor.serve() }
            val lifeline = RemoteProcesses(path, CoroutineScope(SupervisorJob())).hold()
            val crashed = connectUnix(path)
            crashed.writeLine("""{"t":"start","command":"claude"}""")
            until { started.isNotEmpty() }
            crashed.close()
            until { started.single().child.stops > 0 }
            lifeline.close()
            assertEquals(0, served.await())
        }
    }

    @Test fun aProcessThatCannotStartEndsWithTheStartFailureAndTheSupervisorStaysUp(): Unit = runBlocking {
        withTimeout(60_000) {
            val path = socket("agent.sock")
            val supervisor = AgentSupervisor(listenUnix(path), { _, _, _, _ -> throw IllegalStateException("no such binary") }, "/workspace")
            val served = async(Dispatchers.Default) { supervisor.serve() }
            val scope = CoroutineScope(SupervisorJob())
            val remote = RemoteProcesses(path, scope)
            val lifeline = remote.hold()
            val process = remote.start("missing", emptyList(), emptyMap(), "/workspace")
            assertNull(process.readLine())
            until { process.exitCode() != null }
            assertEquals(127, process.exitCode())
            assertTrue(served.isActive)
            lifeline.close()
            assertEquals(0, served.await())
            scope.cancel()
        }
    }

    @Test fun supervisorGivesUpWhenTheRunnerNeverComesAndRemovesItsSocket(): Unit = runBlocking {
        withTimeout(60_000) {
            val path = socket("agent.sock")
            val logged = mutableListOf<String>()
            val code = AgentSupervisor(listenUnix(path), { _, _, _, _ -> error("not reached") }, "/workspace", startupMillis = 200, log = { logged += it }).serve()
            assertEquals(1, code)
            assertFalse(FileSystem.SYSTEM.exists(directory / "agent.sock"), "The socket file is removed")
            assertEquals(listOf("The runner never connected."), logged)
        }
    }

    @Test fun runnerWaitsForTheAgentContainerThenGivesUp(): Unit = runBlocking {
        withTimeout(60_000) {
            val path = socket("late.sock")
            var attempts = 0
            val remote = RemoteProcesses(path, CoroutineScope(SupervisorJob()), connect = { target ->
                if (++attempts < 3) throw IllegalStateException("not yet") else connectUnix(target)
            }, sleep = { }, connectMillis = 10_000)
            val listener = listenUnix(path)
            val accepted = async(Dispatchers.Default) { listener.accept() }
            remote.hold().close()
            assertEquals(3, attempts)
            accepted.await()?.close(); listener.close()

            val never = RemoteProcesses(path, CoroutineScope(SupervisorJob()), connect = { throw IllegalStateException("down") }, sleep = { }, connectMillis = 1_000)
            assertFailsWith<IllegalStateException> { never.start("claude", emptyList(), emptyMap(), "/workspace") }
        }
    }

    private fun broker(path: String, timeoutMillis: Long = 15_000, vararg sources: CredentialSource) = CredentialBroker(listenUnix(path), sources.toList(), timeoutMillis)

    @Test fun brokerAnswersOnlyWhatASourceOwnsAndNeverFailsOpen(): Unit = runBlocking {
        withTimeout(60_000) {
            val path = socket("credential.sock")
            val asked = mutableListOf<CredentialRequest>()
            val github = CredentialSource { request -> asked += request; if (request.host == "github.com") Credential("x-access-token", "ghs_secret") else null }
            val serving = launch(Dispatchers.Default) { broker(path, 15_000, github).serve() }
            until { FileSystem.SYSTEM.exists(directory / "credential.sock") }

            assertEquals(Credential("x-access-token", "ghs_secret"), requestCredential(path, CredentialRequest("https", "github.com", "owner/repo.git")))
            assertNull(requestCredential(path, CredentialRequest("https", "evil.example.test")), "A host no source owns gets nothing")
            assertEquals("evil.example.test", asked.last().host)
            assertFalse("ghs_secret" in Credential("u", "ghs_secret").toString(), "The secret stays out of logs")

            // Garbage is dropped, and the broker keeps serving.
            val garbage = connectUnix(path)
            garbage.writeLine("not json"); assertNull(garbage.readLine()); garbage.close()
            assertEquals("ghs_secret", requestCredential(path, CredentialRequest("https", "github.com"))?.password)
            serving.cancelAndJoin()
            assertNull(requestCredential(path, CredentialRequest("https", "github.com")), "Nobody listening: no credential, no crash")
        }
    }

    @Test fun aFailingOrSlowSourceYieldsNothingAndTheNextSourceIsAsked(): Unit = runBlocking {
        withTimeout(60_000) {
            val path = socket("credential.sock")
            val logged = mutableListOf<String>()
            val failing = CredentialSource { throw IllegalStateException("token=ghs_leak") }
            val backup = CredentialSource { Credential("u", "from-backup") }
            val first = launch(Dispatchers.Default) { CredentialBroker(listenUnix(path), listOf(failing, backup), log = { logged += it }).serve() }
            until { FileSystem.SYSTEM.exists(directory / "credential.sock") }
            assertEquals("from-backup", requestCredential(path, CredentialRequest("https", "github.com"))?.password)
            assertEquals(listOf("A credential source failed."), logged, "The failure is logged without its message")
            first.cancelAndJoin()

            val slow = launch(Dispatchers.Default) { CredentialBroker(listenUnix(path), listOf(CredentialSource { delay(60_000); Credential("u", "late") }), timeoutMillis = 200).serve() }
            until { FileSystem.SYSTEM.exists(directory / "credential.sock") }
            assertNull(requestCredential(path, CredentialRequest("https", "github.com")))
            slow.cancelAndJoin()
        }
    }

    @Test fun gitHelperProtocolIsParsedAndFormatted() {
        val request = parseCredentialInput(sequenceOf("protocol=https", "host=github.com", "path=owner/repo.git", "username=ignored=still", "", "after=blank"))
        assertEquals(CredentialRequest("https", "github.com", "owner/repo.git"), request, "Reading stops at the blank line")
        assertEquals("username=x-access-token\npassword=ghs_secret\n", formatCredentialOutput(Credential("x-access-token", "ghs_secret")))
        assertEquals("", formatCredentialOutput(null), "No credential: nothing printed, so git tries its next helper")
        assertEquals(CredentialRequest(), parseCredentialInput(sequenceOf("garbage-without-equals")))
    }
}
