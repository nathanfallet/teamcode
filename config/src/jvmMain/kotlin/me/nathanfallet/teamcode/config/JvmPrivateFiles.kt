package me.nathanfallet.teamcode.config

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.security.SecureRandom
import java.util.EnumSet
import java.util.HexFormat

/**
 * [PrivateFiles] on the JVM (the desktop app; the CLI's JVM build uses it for its tests). POSIX: 0700 directory, 0600
 * file. Windows: the directory and the file are owned by the signed-in user and carry one ACL entry granting that user
 * full control, like the native CLI's protected DACL; a reparse point (symbolic link, junction) or a path owned by
 * someone else is refused.
 *
 * Unlike the native CLI, the JVM cannot mark the Windows DACL protected: a later inheritance change on a parent folder
 * (an administrator propagating permissions) can add inherited entries. Windows resolves the user from
 * `com.sun.security.auth.module.NTSystem`, so a jlink runtime must include the `jdk.security.auth` module.
 */
object JvmPrivateFiles : PrivateFiles {
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    private val random = SecureRandom()

    override fun write(path: String, content: String) { writePrivate(Path.of(path), content.encodeToByteArray(), replace = true) }
    override fun create(path: String, content: String): Boolean = writePrivate(Path.of(path), content.encodeToByteArray(), replace = false)

    /** Create [directory] (and missing parents) when needed, refuse a link or a foreign owner, and restrict it to the user. */
    fun ensurePrivateDirectory(directory: Path) {
        val absolute = directory.toAbsolutePath()
        var created = false
        if (attributesOrNull(absolute) == null) {
            absolute.parent?.let { Files.createDirectories(it) }
            try {
                if (posix) Files.createDirectory(absolute, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                else Files.createDirectory(absolute, ownerOnlyAcl(directory = true))
                created = true
            } catch (_: FileAlreadyExistsException) { /* Created concurrently: validated below. */ }
        }
        val attributes = attributesOrNull(absolute) ?: throw FileSystemException(absolute.toString(), null, "The configuration directory disappeared.")
        require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) { "Configuration directory cannot be a symbolic link or a reparse point." }
        if (posix) Files.setPosixFilePermissions(absolute, PosixFilePermissions.fromString("rwx------"))
        else restrictWindows(absolute, directory = true, created = created)
    }

    private fun writePrivate(destination: Path, content: ByteArray, replace: Boolean): Boolean {
        require(destination.isAbsolute) { "Private files need an absolute path." }
        require(content.size <= 1024 * 1024) { "Private files are at most 1 MiB." }
        val parent = requireNotNull(destination.parent)
        ensurePrivateDirectory(parent)
        attributesOrNull(destination)?.let { existing ->
            require(existing.isRegularFile && !existing.isSymbolicLink && !existing.isOther) { "Configuration file cannot be a symbolic link." }
            if (!posix && Files.getOwner(destination, LinkOption.NOFOLLOW_LINKS) != windowsUser)
                throw FileSystemException(destination.toString(), null, "The configuration file belongs to another user.")
        }
        val suffix = ByteArray(16).also(random::nextBytes).let { HexFormat.of().formatHex(it) }
        val temporary = parent.resolve("${destination.fileName}.$suffix.tmp")
        try {
            if (posix) Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            else {
                Files.createFile(temporary, ownerOnlyAcl(directory = false))
                restrictWindows(temporary, directory = false, created = true)
            }
            FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(content)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            if (replace) {
                moveRetrying(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return true
            }
            if (posix) {
                // link(2) publishes the complete file and fails if the destination exists, like the native CLI.
                try { Files.createLink(destination, temporary) } catch (_: FileAlreadyExistsException) { return false }
            } else {
                // Without REPLACE_EXISTING, Windows renames with MoveFileEx(0), which refuses an existing destination.
                try { moveRetrying(temporary, destination) } catch (_: FileAlreadyExistsException) { return false }
            }
            return true
        } finally { Files.deleteIfExists(temporary) }
    }

    /** A concurrent reader on Windows may briefly hold a handle without delete sharing: retry, as the native CLI does. */
    private fun moveRetrying(source: Path, target: Path, vararg options: CopyOption) {
        var attempt = 0
        while (true) {
            try { Files.move(source, target, *options); return }
            catch (error: FileAlreadyExistsException) { throw error }
            catch (error: AccessDeniedException) { if (posix || ++attempt >= 20) throw error }
            catch (error: FileSystemException) { if (posix || ++attempt >= 20 || error.reason?.contains("used by another process") != true) throw error }
            Thread.sleep(25)
        }
    }

    private fun attributesOrNull(path: Path): BasicFileAttributes? =
        try { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) } catch (_: NoSuchFileException) { null }

    /** The token user (not the elevated default owner, which may be Administrators), as the native CLI resolves it. */
    private val windowsUser: UserPrincipal by lazy {
        val system = try { Class.forName("com.sun.security.auth.module.NTSystem").getConstructor().newInstance() }
            catch (error: ReflectiveOperationException) { throw IllegalStateException("The Java runtime lacks the jdk.security.auth module.", error) }
        val name = system.javaClass.getMethod("getName").invoke(system) as String
        val domain = system.javaClass.getMethod("getDomain").invoke(system) as String?
        val lookup = FileSystems.getDefault().userPrincipalLookupService
        if (domain.isNullOrEmpty()) lookup.lookupPrincipalByName(name) else lookup.lookupPrincipalByName("$domain\\$name")
    }

    private fun ownerOnlyEntry(directory: Boolean): AclEntry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(windowsUser)
        .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
        .setFlags(if (directory) EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) else EnumSet.noneOf(AclEntryFlag::class.java))
        .build()

    private fun ownerOnlyAcl(directory: Boolean): FileAttribute<List<AclEntry>> = object : FileAttribute<List<AclEntry>> {
        override fun name() = "acl:acl"
        override fun value() = listOf(ownerOnlyEntry(directory))
    }

    /**
     * Replace the DACL with the single owner entry. A path this call just created is given to the token user (an elevated
     * process would otherwise leave it to Administrators); an existing one owned by someone else is refused.
     */
    private fun restrictWindows(path: Path, directory: Boolean, created: Boolean) {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: throw UnsupportedOperationException("This file system has no access control lists.")
        if (view.owner != windowsUser) {
            if (!created) throw FileSystemException(path.toString(), null, "The configuration path belongs to another user.")
            view.owner = windowsUser
        }
        view.acl = listOf(ownerOnlyEntry(directory))
    }
}
