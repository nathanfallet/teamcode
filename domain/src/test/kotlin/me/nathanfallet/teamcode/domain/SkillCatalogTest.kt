package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.domain.skills.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class SkillCatalogTest {
    private val admin = Principal(Member("admin", "admin", Role.ADMIN, false, false), "admin-token", "team")
    private val member = Principal(Member("member", "member", Role.MEMBER, false, false), "member-token", "team")
    private fun file(path: String, content: String = path, mode: SkillFileMode = SkillFileMode.FILE) = SkillFile(path, mode, content.encodeToByteArray())

    /** Minimal in-memory store: one candidate list per member plus stored files per version. */
    private class FakeStore : SkillStore {
        val candidates = mutableMapOf<String, List<SkillCandidate>>()
        val files = mutableMapOf<String, List<SkillFile>>()
        val published = mutableListOf<SkillArtifact>()
        val pins = mutableListOf<Pair<String, String>>()
        val reports = mutableListOf<SkillInstallationReport>()
        override suspend fun candidates(actor: Principal, memberId: String) = candidates[memberId].orEmpty()
        override suspend fun files(actor: Principal, versionId: String) = files.getValue(versionId)
        val audiences = mutableListOf<Pair<String, Boolean>>()
        override suspend fun publish(actor: Principal, name: String, version: String, artifact: SkillArtifact, pin: Boolean, allMembers: Boolean): SkillPublication {
            published += artifact
            if (pin) pins += name to version
            if (allMembers) audiences += name to true
            val skill = Skill("s", name, if (pin) version else null, false, 1, 1, allMembers)
            return SkillPublication(skill, SkillVersion("v", "s", version, artifact.digest, artifact.sizeBytes, artifact.entries.size, actor.member.id, 1, false), true)
        }
        override suspend fun reportInstallations(actor: Principal, reports: List<SkillInstallationReport>) { this.reports += reports }
        override suspend fun catalog(actor: Principal) = error("unused")
        override suspend fun versions(actor: Principal, name: String) = error("unused")
        override suspend fun pin(actor: Principal, name: String, version: String): Skill { pins += name to version; return Skill("s", name, version, false, 1, 1) }
        override suspend fun unpin(actor: Principal, name: String) = error("unused")
        override suspend fun retire(actor: Principal, name: String) = error("unused")
        override suspend fun restore(actor: Principal, name: String) = error("unused")
        override suspend fun retireVersion(actor: Principal, name: String, version: String) = error("unused")
        override suspend fun assignments(actor: Principal, name: String) = error("unused")
        override suspend fun setAudience(actor: Principal, name: String, allMembers: Boolean): Skill { audiences += name to allMembers; return Skill("s", name, null, false, 1, 1, allMembers) }
        override suspend fun assign(actor: Principal, name: String, memberId: String, decision: SkillDecision?) = error("unused")
        override suspend fun installations(actor: Principal, memberId: String) = error("unused")
    }

    @Test fun pathsAreRelativePortableAndCannotEscape() {
        for (path in listOf("SKILL.md", "scripts/run.sh", "references/api-v2.md", "a/b/c/d/e/f/g/h.md", "_private/notes+1.txt"))
            SkillArtifacts.validatePath(path)
        for (path in listOf("", "..", "../SKILL.md", "a/../b", "./SKILL.md", "/etc/passwd", "a//b", "a/", ".hidden", "a/.git/config",
            "a\\b", "C:/x", "C:x", "CON", "con.txt", "lpt1.md", "x.", "with space.md", "café.md", "a\u0000b", "a/b/c/d/e/f/g/h/i.md", "x".repeat(256)))
            assertEquals("invalid_skill_path", assertFailsWith<TeamFailure>(path) { SkillArtifacts.validatePath(path) }.code, path)
    }

    @Test fun artifactsAreBoundedUniqueAndRequireTheEntryFile() {
        assertFailsWith<TeamFailure> { SkillArtifacts.validate(emptyList()) }
        assertFailsWith<TeamFailure> { SkillArtifacts.validate(listOf(file("README.md"))) }
        assertFailsWith<TeamFailure> { SkillArtifacts.validate(listOf(file("SKILL.md"), file("Notes.md"), file("notes.md"))) }
        assertFailsWith<TeamFailure> { SkillArtifacts.validate(listOf(file("SKILL.md"), file("docs"), file("docs/a.md"))) }
        assertFailsWith<TeamFailure> { SkillArtifacts.validate(listOf(file("SKILL.md")) + (1..SkillArtifacts.MAX_FILES).map { file("f$it.md") }) }
        val large = SkillFile("big.bin", SkillFileMode.FILE, ByteArray(SkillArtifacts.MAX_ARTIFACT_BYTES))
        assertEquals(413, assertFailsWith<TeamFailure> { SkillArtifacts.validate(listOf(file("SKILL.md"), large)) }.status)
        val exact = SkillFile("SKILL.md", SkillFileMode.FILE, ByteArray(SkillArtifacts.MAX_ARTIFACT_BYTES))
        assertEquals(SkillArtifacts.MAX_ARTIFACT_BYTES.toLong(), SkillArtifacts.validate(listOf(exact)).sizeBytes)
        for (name in listOf("", "Upper", "-lead", "trail-", "double--hyphen", "x".repeat(65), "under_score")) assertFailsWith<TeamFailure>(name) { SkillArtifacts.validateName(name) }
        for (version in listOf("", "-1", "1/2", "../1", "v".repeat(65), "1 0")) assertFailsWith<TeamFailure>(version) { SkillArtifacts.validateVersion(version) }
        SkillArtifacts.validateName("code-review"); SkillArtifacts.validateVersion("1.2.0+build.5")
    }

    @Test fun digestIsCanonicalAndCoversPathsModesAndContent() {
        val a = SkillArtifacts.validate(listOf(file("SKILL.md", "body"), file("scripts/run.sh", "echo", SkillFileMode.EXECUTABLE)))
        val b = SkillArtifacts.validate(listOf(file("scripts/run.sh", "echo", SkillFileMode.EXECUTABLE), file("SKILL.md", "body")))
        assertEquals(a.digest, b.digest)
        assertEquals(listOf("SKILL.md", "scripts/run.sh"), a.entries.map { it.path })
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(a.digest))
        assertNotEquals(a.digest, SkillArtifacts.validate(listOf(file("SKILL.md", "body"), file("scripts/run.sh", "echo"))).digest)
        assertNotEquals(a.digest, SkillArtifacts.validate(listOf(file("SKILL.md", "body!"), file("scripts/run.sh", "echo", SkillFileMode.EXECUTABLE))).digest)
        assertNotEquals(a.digest, SkillArtifacts.validate(listOf(file("SKILL.md", "body"), file("scripts/go.sh", "echo", SkillFileMode.EXECUTABLE))).digest)
    }

    @Test fun memberDecisionsAreYesNoAndPrepareGroupPrecedence() {
        assertFalse(SkillAccessPolicy.allowed(null))
        assertTrue(SkillAccessPolicy.allowed(SkillDecision.ALLOW))
        assertFalse(SkillAccessPolicy.allowed(SkillDecision.DENY))
        // Planned group semantics, exercised without any group implementation.
        assertTrue(SkillAccessPolicy.allowed(null, listOf(SkillDecision.ALLOW)))
        assertFalse(SkillAccessPolicy.allowed(null, listOf(SkillDecision.ALLOW, SkillDecision.DENY)))
        assertTrue(SkillAccessPolicy.allowed(SkillDecision.ALLOW, listOf(SkillDecision.DENY)))
        assertFalse(SkillAccessPolicy.allowed(SkillDecision.DENY, listOf(SkillDecision.ALLOW)))
    }

    @Test fun allMembersIsTheWeakestRuleAndAnExplicitNoStillExcludes() {
        assertTrue(SkillAccessPolicy.allowed(null, allMembers = true))
        assertTrue(SkillAccessPolicy.allowed(SkillDecision.ALLOW, allMembers = true))
        assertFalse(SkillAccessPolicy.allowed(SkillDecision.DENY, allMembers = true))
        assertFalse(SkillAccessPolicy.allowed(null, listOf(SkillDecision.DENY), allMembers = true))
        assertTrue(SkillAccessPolicy.allowed(null, listOf(SkillDecision.ALLOW), allMembers = false))
    }

    @Test fun aSkillForAllMembersReachesMembersWithoutADecisionIncludingNewOnes() = runTest {
        val store = FakeStore()
        val everyone = skill("everyone", "1").copy(allMembers = true)
        // A member who joined after the publication has no decision row at all.
        store.candidates["member"] = listOf(SkillCandidate(everyone, version("a", "1"), null), SkillCandidate(skill("assigned", "1"), version("b", "1"), null))
        store.candidates["admin"] = listOf(SkillCandidate(everyone, version("a", "1"), SkillDecision.DENY))
        val catalog = SkillCatalog(store)
        assertEquals(listOf("everyone"), catalog.effective(member).map { it.name })
        assertEquals(emptyList(), catalog.effective(admin), "An explicit no wins over all members")
        // Unpinned or retired still deliver nothing, whatever the audience.
        store.candidates["member"] = listOf(SkillCandidate(everyone.copy(pinnedVersion = null), null, null), SkillCandidate(everyone.copy(retired = true), version("a", "1"), null))
        assertEquals(emptyList(), catalog.effective(member))
    }

    @Test fun deliveringToAllMembersAtPublicationNeedsTheAssignScopeOnAKey() = runTest {
        val store = FakeStore()
        val catalog = SkillCatalog(store)
        val files = listOf(file("SKILL.md"))
        val publisher = admin.copy(serviceScopes = setOf(ServiceScope.SKILLS_PUBLISH))
        val assigner = admin.copy(serviceScopes = setOf(ServiceScope.SKILLS_PUBLISH, ServiceScope.SKILLS_ASSIGN))
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.publish(publisher, "review", "1", files, pin = true, allMembers = true) }.code)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.publish(admin.copy(serviceScopes = setOf(ServiceScope.SKILLS_ASSIGN)), "review", "1", files, allMembers = true) }.code)
        assertEquals("forbidden", assertFailsWith<TeamFailure> { catalog.publish(member.copy(serviceScopes = setOf(ServiceScope.SKILLS_PUBLISH, ServiceScope.SKILLS_ASSIGN)), "review", "1", files, allMembers = true) }.code)
        assertEquals("forbidden", assertFailsWith<TeamFailure> { catalog.publish(member, "review", "1", files, allMembers = true) }.code)
        assertTrue(store.published.isEmpty(), "Refused before the store is reached")
        assertTrue(catalog.publish(assigner, "review", "1", files, pin = true, allMembers = true).skill.allMembers)
        assertTrue(catalog.publish(admin, "review", "2", files, allMembers = true).skill.allMembers)
        // Without the flag, a publishing key keeps working and leaves the audience alone.
        assertFalse(catalog.publish(publisher, "review", "3", files).skill.allMembers)
        assertEquals(listOf("review" to true, "review" to true), store.audiences)
    }

    @Test fun onlyAnInteractiveAdministratorChangesTheAudienceAfterwards() = runTest {
        val store = FakeStore()
        val catalog = SkillCatalog(store)
        for ((actor, code) in listOf(
            member to "forbidden",
            admin.copy(serviceScopes = setOf(ServiceScope.SKILLS_PUBLISH, ServiceScope.SKILLS_ASSIGN)) to "credential_scope",
        )) assertEquals(code, assertFailsWith<TeamFailure> { catalog.manage.setAudience(actor, "review", true) }.code)
        assertEquals("invalid_skill_name", assertFailsWith<TeamFailure> { catalog.manage.setAudience(admin, "Bad", true) }.code)
        assertTrue(catalog.manage.setAudience(admin, "review", true).allMembers)
        assertFalse(catalog.manage.setAudience(admin, "review", false).allMembers)
        assertEquals(listOf("review" to true, "review" to false), store.audiences)
    }

    private fun version(id: String, label: String, digest: String = "sha256:" + "0".repeat(64), retired: Boolean = false) =
        SkillVersion(id, "skill-$id", label, digest, 1, 1, "admin", 1, retired)
    private fun skill(name: String, pinned: String?, retired: Boolean = false) = Skill("id-$name", name, pinned, retired, 1, 1)

    @Test fun onlyPinnedAllowedNonRetiredVersionsAreEffective() = runTest {
        val store = FakeStore()
        store.candidates["member"] = listOf(
            SkillCandidate(skill("served", "2.0"), version("a", "2.0"), SkillDecision.ALLOW),
            SkillCandidate(skill("unpinned", null), null, SkillDecision.ALLOW),
            SkillCandidate(skill("denied", "1"), version("b", "1"), SkillDecision.DENY),
            SkillCandidate(skill("unassigned", "1"), version("c", "1"), null),
            SkillCandidate(skill("retired", "1", retired = true), version("d", "1"), SkillDecision.ALLOW),
            SkillCandidate(skill("yanked", "1"), version("e", "1", retired = true), SkillDecision.ALLOW),
        )
        val catalog = SkillCatalog(store)
        assertEquals(listOf("served" to "2.0"), catalog.effective(member).map { it.name to it.version })
        // Rollback: pinning an earlier immutable version changes what is served, reproducibly.
        store.candidates["member"] = listOf(SkillCandidate(skill("served", "1.0"), version("old", "1.0"), SkillDecision.ALLOW))
        assertEquals(listOf("served" to "1.0"), catalog.effective(member).map { it.name to it.version })
        // A removed assignment stops delivery.
        store.candidates["member"] = listOf(SkillCandidate(skill("served", "1.0"), version("old", "1.0"), null))
        assertEquals(emptyList(), catalog.effective(member))
    }

    @Test fun readersArePerMemberAndServiceKeysNeedTheReadScope() = runTest {
        val catalog = SkillCatalog(FakeStore())
        assertEquals("forbidden", assertFailsWith<TeamFailure> { catalog.effective(member, "admin") }.code)
        catalog.effective(admin, "member")
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.effective(member.copy(serviceScopes = setOf(ServiceScope.INFERENCE))) }.code)
        catalog.effective(member.copy(serviceScopes = setOf(ServiceScope.SKILLS_READ)))
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.effective(admin.copy(serviceScopes = setOf(ServiceScope.SKILLS_READ)), "member") }.code)
    }

    @Test fun publicationRequiresAnAdministratorOrAnAdministratorKeyWithThePublishScope() = runTest {
        val store = FakeStore()
        val catalog = SkillCatalog(store)
        val files = listOf(file("SKILL.md"))
        assertEquals("forbidden", assertFailsWith<TeamFailure> { catalog.publish(member, "review", "1", files) }.code)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.publish(admin.copy(serviceScopes = setOf(ServiceScope.INFERENCE)), "review", "1", files) }.code)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.publish(admin.copy(serviceScopes = setOf(ServiceScope.SKILLS_READ)), "review", "1", files) }.code)
        assertEquals("forbidden", assertFailsWith<TeamFailure> { catalog.publish(member.copy(serviceScopes = setOf(ServiceScope.SKILLS_PUBLISH)), "review", "1", files) }.code)
        assertTrue(store.published.isEmpty())
        assertTrue(catalog.publish(admin.copy(serviceScopes = setOf(ServiceScope.SKILLS_PUBLISH)), "review", "1", files).created)
        catalog.publish(admin, "review", "2", files)
        assertEquals(2, store.published.size)
        assertFailsWith<TeamFailure> { catalog.publish(admin, "Review", "3", files) }
    }

    @Test fun pinningSharesThePublicationRightAndNothingElse() = runTest {
        val store = FakeStore()
        val catalog = SkillCatalog(store)
        val files = listOf(file("SKILL.md"))
        val publisher = admin.copy(serviceScopes = setOf(ServiceScope.SKILLS_PUBLISH))
        // Publication pins only when asked.
        assertNull(catalog.publish(publisher, "review", "1", files).skill.pinnedVersion)
        assertEquals("1", catalog.publish(publisher, "review", "1", files, pin = true).skill.pinnedVersion)
        catalog.manage.pin(publisher, "review", "1")
        catalog.manage.pin(admin, "review", "1")
        assertEquals(listOf("review" to "1", "review" to "1", "review" to "1"), store.pins)
        // The same refusals as publication, before the store is reached.
        for ((actor, code) in listOf(
            member to "forbidden",
            member.copy(serviceScopes = setOf(ServiceScope.SKILLS_PUBLISH)) to "forbidden",
            admin.copy(serviceScopes = setOf(ServiceScope.SKILLS_READ)) to "credential_scope",
            admin.copy(serviceScopes = setOf(ServiceScope.INFERENCE)) to "credential_scope",
        )) {
            assertEquals(code, assertFailsWith<TeamFailure> { catalog.manage.pin(actor, "review", "1") }.code)
            assertEquals(code, assertFailsWith<TeamFailure> { catalog.publish(actor, "review", "2", files, pin = true) }.code)
        }
        assertEquals("invalid_skill_version", assertFailsWith<TeamFailure> { catalog.manage.pin(publisher, "review", "../1") }.code)
        assertEquals(3, store.pins.size)
        // A publishing key still cannot unpin, retire or read the catalog.
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.manage.unpin(publisher, "review") }.code)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.manage.retire(publisher, "review") }.code)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.manage.catalog(publisher) }.code)
    }

    @Test fun downloadsServeOnlyTheEffectiveVersionAndVerifyStoredContent() = runTest {
        val store = FakeStore()
        val artifact = SkillArtifacts.validate(listOf(file("SKILL.md", "body")))
        store.candidates["member"] = listOf(SkillCandidate(skill("served", "1"), version("v1", "1", artifact.digest), SkillDecision.ALLOW))
        store.files["v1"] = artifact.files
        val catalog = SkillCatalog(store)
        assertEquals(artifact.digest, catalog.download(member, "served", "1").skill.digest)
        assertEquals("not_found", assertFailsWith<TeamFailure> { catalog.download(member, "served", "0") }.code)
        assertEquals("not_found", assertFailsWith<TeamFailure> { catalog.download(member, "other", "1") }.code)
        store.files["v1"] = listOf(file("SKILL.md", "tampered"))
        assertEquals("skill_integrity", assertFailsWith<TeamFailure> { catalog.download(member, "served", "1") }.code)
    }

    @Test fun installationReportsAreInteractiveBoundedAndValidated() = runTest {
        val store = FakeStore()
        val catalog = SkillCatalog(store)
        val ok = SkillInstallationReport("served", "1", "sha256:" + "a".repeat(64), SkillInstallationStatus.INSTALLED)
        catalog.report(member, listOf(ok, SkillInstallationReport("other", null, null, SkillInstallationStatus.CONFLICT)))
        assertEquals(2, store.reports.size)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { catalog.report(member.copy(serviceScopes = setOf(ServiceScope.SKILLS_READ)), listOf(ok)) }.code)
        assertFailsWith<TeamFailure> { catalog.report(member, listOf(ok, ok)) }
        assertFailsWith<TeamFailure> { catalog.report(member, listOf(ok.copy(digest = "md5:x"))) }
        assertFailsWith<TeamFailure> { catalog.report(member, (0..SkillStore.SKILL_LIMIT).map { ok.copy(name = "s$it") }) }
    }
}
