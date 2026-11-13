package me.nathanfallet.teamcode.domain.memory

import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Who can read an entry: every member of the team, or only its owner. No administrator reads a personal entry. */
enum class MemoryScope(val wire: String) {
    TEAM("team"), PERSONAL("personal");
    companion object { fun parse(value: String) = entries.find { it.wire == value } ?: throw MemoryRules.invalid("Use the team or personal scope.") }
}

/** `PROPOSED`, `SUPERSEDED` and `REJECTED` are written by later steps (validation, curation) and never listed or searched. */
enum class MemoryStatus(val wire: String) { ACTIVE("active"), PROPOSED("proposed"), SUPERSEDED("superseded"), REJECTED("rejected") }

enum class MemoryOrigin(val wire: String) { HUMAN("human"), AGENT("agent"), CURATION("curation") }

/** One remembered fact. [ownerId] is the owning member of a personal entry, null for a team entry. */
data class MemoryEntry(
    val id: String, val scope: MemoryScope, val ownerId: String?, val title: String, val summary: String, val content: String,
    val tags: List<String>, val refs: List<String>, val status: MemoryStatus, val origin: MemoryOrigin, val authorId: String, val sessionId: String?,
    val supersedesId: String?, val expiresAt: Long?, val version: Int, val createdAt: Long, val updatedAt: Long, val lastReadAt: Long?, val readCount: Int,
)

/** What an index or a search returns: enough to decide whether to read the entry, never its content. */
data class MemoryIndexEntry(val id: String, val scope: MemoryScope, val title: String, val summary: String, val tags: List<String>, val updatedAt: Long)

/** A position in the index: entries older than [updatedAt], or equally old with a smaller [id]. */
data class MemoryCursor(val updatedAt: Long, val id: String)

data class MemoryPage(val entries: List<MemoryIndexEntry>, val next: MemoryCursor?)

data class NewMemory(
    val scope: MemoryScope, val title: String, val summary: String, val content: String, val tags: List<String> = emptyList(),
    val refs: List<String> = emptyList(), val expiresAt: Long? = null,
)

/** Replaces the fields of an entry; [expectedVersion] must be the one read, so two editors never overwrite each other. */
data class MemoryUpdate(
    val expectedVersion: Int, val title: String, val summary: String, val content: String, val tags: List<String> = emptyList(),
    val refs: List<String> = emptyList(), val expiresAt: Long? = null,
)

object MemoryRules {
    const val TITLE_LENGTH = 160
    const val SUMMARY_LENGTH = 300
    const val CONTENT_LENGTH = 16_000
    const val TAGS_LIMIT = 8
    const val TAG_LENGTH = 40
    const val REFS_LIMIT = 10
    const val REF_LENGTH = 300
    const val PERSONAL_LIMIT = 2_000L
    const val TEAM_LIMIT = 5_000L
    const val PAGE_LIMIT = 50
    const val SEARCH_LIMIT = 20
    private val tag = Regex("[a-z0-9][a-z0-9._-]{0,${TAG_LENGTH - 1}}")

    /**
     * Content that looks like a credential is refused at write time: a memory is read back into agents' contexts and, for the
     * team, by every member, so a secret stored once would be handed out for good. This is a filter of the common shapes
     * (provider and TeamCode token prefixes, private keys, bearer headers, `password=value` assignments), not a guarantee.
     */
    private val secretShapes = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("""\b(sk-ant-|sk-proj-|sk-)[A-Za-z0-9_-]{20,}"""),
        Regex("""\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"""), Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}"""),
        Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}"""), Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        Regex("""\btc(sk|ex|ac|dv)_[A-Za-z0-9_-]{16,}"""),
        Regex("""(?i)\bauthorization:\s*(bearer|basic)\s+\S{16,}"""),
        Regex("""(?i)\b(password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\s*[:=]\s*["']?[^\s"']{12,}"""),
    )

    fun invalid(message: String) = TeamFailure("invalid_memory", 400, message)

    fun validate(title: String, summary: String, content: String, tags: List<String>, refs: List<String>, expiresAt: Long?, now: Long) {
        if (title.isBlank() || title.length > TITLE_LENGTH || title.any(Char::isISOControl)) throw invalid("Use a title of 1–$TITLE_LENGTH characters on one line.")
        if (summary.isBlank() || summary.length > SUMMARY_LENGTH || summary.any(Char::isISOControl)) throw invalid("Use a one-line summary of 1–$SUMMARY_LENGTH characters.")
        if (content.isBlank() || content.length > CONTENT_LENGTH || content.contains('\u0000')) throw invalid("Use content of 1–$CONTENT_LENGTH characters.")
        if (tags.size > TAGS_LIMIT || tags.toSet().size != tags.size || tags.any { !tag.matches(it) })
            throw invalid("Use at most $TAGS_LIMIT distinct tags of lowercase letters, digits, dots, dashes and underscores (at most $TAG_LENGTH characters).")
        if (refs.size > REFS_LIMIT || refs.any { it.isBlank() || it.length > REF_LENGTH || it.any(Char::isISOControl) })
            throw invalid("Use at most $REFS_LIMIT references of 1–$REF_LENGTH characters.")
        if (expiresAt != null && expiresAt <= now) throw invalid("An expiry must be in the future.")
        if (listOf(title, summary, content).plus(refs).any { text -> secretShapes.any { it.containsMatchIn(text) } })
            throw TeamFailure("memory_secret", 422, "This looks like a credential. Memory is read back into agents and shared contexts, so never store secrets: describe where to find them instead.")
    }

    fun validateTag(value: String) { if (!tag.matches(value)) throw invalid("Use a tag of lowercase letters, digits, dots, dashes and underscores.") }

    /** The words of a search query, lowercase, at most 5 of 2–40 characters; an empty result is a refused query. */
    fun terms(query: String): List<String> = query.lowercase().split(Regex("[^\\p{L}\\p{N}._-]+")).filter { it.length in 2..40 }.distinct().take(5)
        .also { if (it.isEmpty()) throw invalid("Search for at least one word of two characters.") }
}
