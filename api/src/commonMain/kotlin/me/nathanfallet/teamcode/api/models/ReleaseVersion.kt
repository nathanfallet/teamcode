package me.nathanfallet.teamcode.api.models

/** Bounded SemVer ordering, including prereleases and numeric components larger than a machine integer. */
class ReleaseVersion private constructor(val value: String) : Comparable<ReleaseVersion> {
    private val core = value.substringBefore('-').split('.')
    private val prerelease = value.substringAfter('-', "").takeIf { it.isNotEmpty() }?.split('.')

    override fun compareTo(other: ReleaseVersion): Int {
        for (index in core.indices) {
            val comparison = numeric(core[index], other.core[index])
            if (comparison != 0) return comparison
        }
        val left = prerelease ?: return if (other.prerelease == null) 0 else 1
        val right = other.prerelease ?: return -1
        for (index in 0 until minOf(left.size, right.size)) {
            val a = left[index]
            val b = right[index]
            val aNumeric = a.all { it in '0'..'9' }
            val bNumeric = b.all { it in '0'..'9' }
            val comparison = when {
                aNumeric && bNumeric -> numeric(a, b)
                aNumeric -> -1
                bNumeric -> 1
                else -> a.compareTo(b)
            }
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    override fun equals(other: Any?) = other is ReleaseVersion && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = value

    companion object {
        /** Rejects invalid/ambiguous versions before comparison or URL construction. */
        fun parse(value: String): ReleaseVersion {
            require(ReleaseFormat.validVersion(value)) { "Invalid release version." }
            return ReleaseVersion(value)
        }

        private fun numeric(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
    }
}
