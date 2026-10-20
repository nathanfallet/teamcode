package me.nathanfallet.teamcode.client

import me.nathanfallet.teamcode.api.models.RunLeaseView
import me.nathanfallet.teamcode.api.models.StartRunRequest

/** Narrow lifecycle dependency for local wrappers; it grants no usage or administrator operations. */
interface RunLifecycleClient {
    suspend fun startRun(input: StartRunRequest): RunLeaseView
    suspend fun heartbeatRun(id: String): RunLeaseView
    suspend fun endRun(id: String, exitCode: Int?): RunLeaseView
}
