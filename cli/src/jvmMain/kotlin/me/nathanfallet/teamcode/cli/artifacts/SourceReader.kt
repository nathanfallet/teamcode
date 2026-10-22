package me.nathanfallet.teamcode.cli.artifacts

import java.nio.ByteBuffer
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** The JVM build runs tests only; it checks every level without following links, like the native readers. */
internal actual fun readWithoutLinks(root: String, components: List<String>, maxBytes: Long): ByteArray {
    var path = Paths.get(root)
    if (!Files.isDirectory(path)) throw SourceReadFailure(SourceReadFailure.Kind.ROOT, -1)
    components.forEachIndexed { index, name ->
        path = path.resolve(name)
        val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
            catch (_: NoSuchFileException) { throw SourceReadFailure(SourceReadFailure.Kind.MISSING, index) }
            catch (_: AccessDeniedException) { throw SourceReadFailure(SourceReadFailure.Kind.DENIED, index) }
        val last = index == components.size - 1
        if (attributes.isSymbolicLink || attributes.isOther && !last || (!last && !attributes.isDirectory)) throw SourceReadFailure(SourceReadFailure.Kind.LINK, index)
        if (last && !attributes.isRegularFile) throw SourceReadFailure(SourceReadFailure.Kind.NOT_REGULAR, index)
        if (last && attributes.size() > maxBytes) throw SourceReadFailure(SourceReadFailure.Kind.TOO_LARGE, index)
    }
    Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
        val buffer = ByteBuffer.allocate((maxBytes + 1).toInt().coerceAtMost(channel.size().toInt() + 1))
        while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
        if (buffer.position() > maxBytes) throw SourceReadFailure(SourceReadFailure.Kind.TOO_LARGE, components.size - 1)
        return buffer.array().copyOf(buffer.position())
    }
}
