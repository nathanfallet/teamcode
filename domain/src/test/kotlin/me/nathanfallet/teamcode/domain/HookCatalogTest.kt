package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.domain.hooks.*
import me.nathanfallet.teamcode.domain.skills.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class HookCatalogTest {
    private val admin = Principal(Member("admin", "admin", Role.ADMIN, false, false), "admin-token", "team")
    private val member = Principal(Member("member", "member", Role.MEMBER, false, false), "member-token", "team")
    private val script = SkillFile("bin/check.sh", SkillFileMode.EXECUTABLE, "#!/bin/sh\necho ok\n".encodeToByteArray())
    private val stop = HookDefinition(setOf(HookEvent.STOP, HookEvent.SUBAGENT_STOP), null, "bin/check.sh", 30)

    private class FakeStore : HookStore {
        val candidates = mutableMapOf<String, List<HookCandidate>>()
        val files = mutableMapOf<String, List<SkillFile>>()
        val published = mutableListOf<HookArtifact>()
        val pins = mutableListOf<Pair<String, String>>()
        override suspend fun candidates(actor: Principal, memberId: String) = candidates[memberId].orEmpty()
        override suspend fun files(actor: Principal, versionId: String) = files.getValue(versionId)
        override suspend fun publish(actor: Principal, name: String, version: String, artifact: HookArtifact): HookPublication {
            published += artifact
            return HookPublication(Hook("h", name, null, 1, 1), HookVersion("v", "h", version, artifact.digest, artifact.definition,
                artifact.sizeBytes, artifact.entries.size, actor.member.id, 1), true)
        }
        override suspend fun pin(actor: Principal, name: String, version: String): Hook { pins += name to version; return Hook("h", name, version, 1, 1) }
        override suspend fun catalog(actor: Principal) = emptyList<Hook>()
        override suspend fun versions(actor: Principal, name: String) = error("unused")
        override suspend fun unpin(actor: Principal, name: String) = Hook("h", name, null, 1, 1)
        override suspend fun assignments(actor: Principal, name: String) = emptyList<HookAssignment>()
        override suspend fun assign(actor: Principal, name: String, memberId: String, decision: SkillDecision?) = null
    }

    private fun code(block: () -> Unit) = assertFailsWith<TeamFailure> { block() }.code

    @Test fun definitionsAreBoundedAndTheCommandIsAnExecutableOfTheArtifact() {
        assertEquals(stop, HookArtifacts.validate(stop, listOf(script)).definition)
        assertEquals("invalid_hook_definition", code { HookArtifacts.validate(stop.copy(events = emptySet()), listOf(script)) })
        assertEquals("invalid_hook_definition", code { HookArtifacts.validate(stop.copy(timeoutSeconds = 0), listOf(script)) })
        assertEquals("invalid_hook_definition", code { HookArtifacts.validate(stop.copy(timeoutSeconds = 601), listOf(script)) })
        assertEquals("invalid_hook_definition", code { HookArtifacts.validate(stop.copy(matcher = ""), listOf(script)) })
        assertEquals("invalid_hook_definition", code { HookArtifacts.validate(stop.copy(matcher = "Bash\n"), listOf(script)) })
        assertEquals("invalid_hook_definition", code { HookArtifacts.validate(stop.copy(matcher = "x".repeat(257)), listOf(script)) })
        // The command is a portable relative path, never a shell line, and must be published as executable.
        for (command in listOf("../bin/check.sh", "/bin/sh", "bin/check.sh; rm -rf ~", "bin/check.sh --flag", ".hidden"))
            assertEquals("invalid_hook_path", code { HookArtifacts.validate(stop.copy(command = command), listOf(script)) }, command)
        assertEquals("invalid_hook_definition", code { HookArtifacts.validate(stop.copy(command = "bin/other.sh"), listOf(script)) })
        assertEquals("invalid_hook_definition", code { HookArtifacts.validate(stop, listOf(SkillFile("bin/check.sh", SkillFileMode.FILE, script.content))) })
        // File tree rules are the skill catalog's, under hook error codes, with a 1 MiB / 50 files bound and no SKILL.md.
        assertEquals("invalid_hook_artifact", code { HookArtifacts.validate(stop, listOf(script) + (1..HookArtifacts.MAX_FILES).map { SkillFile("f$it", SkillFileMode.FILE, byteArrayOf()) }) })
        assertEquals(413, assertFailsWith<TeamFailure> { HookArtifacts.validate(stop, listOf(script, SkillFile("big", SkillFileMode.FILE, ByteArray(1024 * 1024)))) }.status)
        assertEquals("invalid_hook_path", code { HookArtifacts.validate(stop, listOf(script, SkillFile("../x", SkillFileMode.FILE, byteArrayOf()))) })
        assertEquals("invalid_hook_name", code { HookArtifacts.validateName("Stop_Guard") })
        assertEquals("invalid_hook_version", code { HookArtifacts.validateVersion("../1") })
    }

    @Test fun theDigestCoversTheDefinitionAndIsIndependentOfEventOrder() {
        val digest = HookArtifacts.validate(stop, listOf(script)).digest
        assertEquals(digest, HookArtifacts.validate(stop.copy(events = linkedSetOf(HookEvent.SUBAGENT_STOP, HookEvent.STOP)), listOf(script)).digest)
        for (changed in listOf(stop.copy(timeoutSeconds = 31), stop.copy(matcher = "*"), stop.copy(events = setOf(HookEvent.STOP))))
            assertNotEquals(digest, HookArtifacts.validate(changed, listOf(script)).digest)
        // A fixed vector, so clients that recompute it stay in step with the server.
        val entries = listOf(SkillFileEntry("bin/check.sh", SkillFileMode.EXECUTABLE, 3, SkillArtifacts.sha256("abc".encodeToByteArray())))
        val line = "hook\u0000Stop,SubagentStop\u0000\u0000bin/check.sh\u000030\nbin/check.sh\u0000executable\u00003\u0000${entries.single().sha256}\n"
        assertEquals("sha256:" + SkillArtifacts.sha256(line.encodeToByteArray()), HookArtifacts.digest(stop, entries))
    }

    @Test fun keysPublishButNeverPinAndOnlyAdministratorKeysPublish() = runTest {
        val store = FakeStore()
        val catalog = HookCatalog(store)
        val publisher = admin.copy(serviceScopes = setOf(ServiceScope.HOOKS_PUBLISH))
        assertTrue(catalog.publish(publisher, "stop-guard", "1", stop, listOf(script)).created)
        assertNull(catalog.publish(admin, "stop-guard", "2", stop, listOf(script)).hook.pinnedVersion, "Publication never pins")
        for ((actor, expected) in listOf(
            member to "forbidden",
            member.copy(serviceScopes = setOf(ServiceScope.HOOKS_PUBLISH)) to "forbidden",
            admin.copy(serviceScopes = setOf(ServiceScope.SKILLS_PUBLISH)) to "credential_scope",
            admin.copy(serviceScopes = setOf(ServiceScope.HOOKS_READ)) to "credential_scope",
        )) assertEquals(expected, assertFailsWith<TeamFailure> { catalog.publish(actor, "stop-guard", "3", stop, listOf(script)) }.code)
        assertEquals(2, store.published.size)
        // Pinning delivers code to members' computers: interactive administrators only, even for a publishing key.
        for (actor in listOf(publisher, admin.copy(serviceScopes = ServiceScope.entries.toSet()), member))
            assertFailsWith<TeamFailure> { catalog.manage.pin(actor, "stop-guard", "1") }
        assertEquals(emptyList(), store.pins)
        catalog.manage.pin(admin, "stop-guard", "1")
        assertEquals(listOf("stop-guard" to "1"), store.pins)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.manage.catalog(publisher) }.code)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.manage.assign(publisher, "stop-guard", "member", SkillDecision.ALLOW) }.code)
    }

    @Test fun onlyAnExplicitAllowOfAPinnedVersionIsDeliveredAndServedIntact() = runTest {
        val store = FakeStore()
        val catalog = HookCatalog(store)
        val artifact = HookArtifacts.validate(stop, listOf(script))
        fun version(id: String) = HookVersion(id, id, id, artifact.digest, stop, artifact.sizeBytes, 1, "admin", 1)
        store.candidates["member"] = listOf(
            HookCandidate(Hook("a", "allowed", "1", 1, 1), version("1"), SkillDecision.ALLOW),
            HookCandidate(Hook("b", "denied", "1", 1, 1), version("1"), SkillDecision.DENY),
            HookCandidate(Hook("c", "unassigned", "1", 1, 1), version("1"), null),
            HookCandidate(Hook("d", "unpinned", null, 1, 1), null, SkillDecision.ALLOW),
        )
        store.files["1"] = listOf(script)
        assertEquals(listOf("allowed"), catalog.effective(member).map { it.name })
        assertEquals(listOf("allowed"), catalog.effective(member.copy(serviceScopes = setOf(ServiceScope.HOOKS_READ))).map { it.name })
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.effective(member.copy(serviceScopes = setOf(ServiceScope.SKILLS_READ))) }.code)
        assertEquals("forbidden", assertFailsWith<TeamFailure> { catalog.effective(member, "someone-else") }.code)
        assertEquals(artifact.digest, catalog.download(member, "allowed", "1").hook.digest)
        assertEquals("not_found", assertFailsWith<TeamFailure> { catalog.download(member, "denied", "1") }.code)
        store.files["1"] = listOf(SkillFile("bin/check.sh", SkillFileMode.EXECUTABLE, "#!/bin/sh\necho changed\n".encodeToByteArray()))
        assertEquals("hook_integrity", assertFailsWith<TeamFailure> { catalog.download(member, "allowed", "1") }.code)
    }
}
