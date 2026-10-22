package me.nathanfallet.teamcode.cli

import com.github.ajalt.clikt.core.CliktError
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.PROFILE_ID
import me.nathanfallet.teamcode.cli.cloud.line
import me.nathanfallet.teamcode.cli.cloud.resolveProfile
import kotlin.test.*

/** `teamcode cloud profiles` lines, and `--profile` taking an id first, then a name. */
class CloudProfilesTest {
    private fun profile(id: String, name: String, clusterName: String? = "lab") =
        CloudProfileView(id, name, 3, CloudProfileSpecView("cluster-id", "teamcode", "registry.example/toolchain:1", agentVersion = "2.1.0"), emptyList(), 1, 1, clusterName)
    private val toolchain = profile("4f1c2d3e-0000-4000-8000-000000000001", "Default")
    private val gpu = profile("4f1c2d3e-0000-4000-8000-000000000002", "gpu")

    @Test fun `an id is used as is`() {
        assertEquals(toolchain.id, resolveProfile(toolchain.id, listOf(toolchain, gpu)))
    }

    @Test fun `an id wins over a profile named like it`() {
        val named = profile("4f1c2d3e-0000-4000-8000-000000000003", toolchain.id)
        assertEquals(toolchain.id, resolveProfile(toolchain.id, listOf(named, toolchain)))
    }

    @Test fun `a name is the fallback with an exact match before ignoring case`() {
        assertEquals(gpu.id, resolveProfile("gpu", listOf(toolchain, gpu)))
        assertEquals(toolchain.id, resolveProfile("default", listOf(toolchain, gpu)), "teamcode cloud create --profile default")
        val lower = profile("4f1c2d3e-0000-4000-8000-000000000004", "default")
        assertEquals(lower.id, resolveProfile("default", listOf(toolchain, lower)), "The exact name is not ambiguous")
    }

    @Test fun `an ambiguous name lists the ids`() {
        val lower = profile("4f1c2d3e-0000-4000-8000-000000000004", "default")
        val error = assertFailsWith<CliktError> { resolveProfile("DEFAULT", listOf(toolchain, lower, gpu)) }
        val text = error.message.orEmpty()
        assertContains(text, toolchain.id); assertContains(text, lower.id); assertFalse(gpu.id in text)
        assertContains(text, "--profile")
    }

    @Test fun `an unknown value goes to the server which tells missing from unassigned`() {
        assertEquals("other", resolveProfile("other", listOf(toolchain)))
        assertEquals("other", resolveProfile("other", emptyList()), "A key that cannot list profiles still passes an id")
    }

    @Test fun `a generated id is recognised without a list`() {
        assertTrue(PROFILE_ID.matches(toolchain.id))
        assertFalse(PROFILE_ID.matches("default"))
    }

    @Test fun `a line shows id name version cluster and namespace image and agent`() {
        assertEquals("${toolchain.id}\tDefault\tv3\tlab/teamcode\tregistry.example/toolchain:1\tclaude 2.1.0", toolchain.line())
        assertEquals("${gpu.id}\tgpu\tv3\tcluster-id/teamcode\tregistry.example/toolchain:1\tclaude", profile(gpu.id, "gpu", null).copy(spec = gpu.spec.copy(agentVersion = null)).line(),
            "An older server sends no cluster name")
    }
}
