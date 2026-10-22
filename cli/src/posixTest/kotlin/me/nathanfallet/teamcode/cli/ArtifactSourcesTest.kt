package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.artifacts.ArtifactSources
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.*

/** The native reader: every component below the root opened without following links, through the real system calls. */
class ArtifactSourcesTest {
    private val fs = FileSystem.SYSTEM
    private val base = fs.canonicalize(FileSystem.SYSTEM_TEMPORARY_DIRECTORY) / "tc-artifacts-${Random.nextInt().toUInt().toString(16)}"
    private val root = base / "workspace"
    private val outside = base / "outside"
    @AfterTest fun cleanup() { fs.deleteRecursively(base, mustExist = false) }

    private fun refusal(sources: ArtifactSources, path: String) = assertFailsWith<CliUserException> { sources.read(path, "x.html") }.message.orEmpty()

    @Test fun regularFilesBelowTheRootAreReadAndLinksNeverFollowed() {
        fs.createDirectories(root / "site" / "img"); fs.createDirectories(outside)
        fs.write(root / "site" / "index.html") { writeUtf8("<p>hello</p>") }
        fs.write(root / "site" / "img" / "a.png") { write(ByteArray(3) { 1 }) }
        fs.write(outside / "secret.txt") { writeUtf8("outside the root") }
        fs.createSymlink(root / "leak.txt", outside / "secret.txt")
        fs.createSymlink(root / "linked", outside)
        fs.createSymlink(root / "site" / "self.html", "index.html".toPath())
        val sources = ArtifactSources(listOf(root.toString()))

        val page = sources.read("site/index.html", "index.html")
        assertEquals("<p>hello</p>", page.bytes.decodeToString()); assertEquals(64, page.sha256.length)
        assertEquals(3, sources.read("./site/img/a.png", "img/a.png").bytes.size)
        assertEquals(3, sources.read((root / "site" / "img" / "a.png").toString(), "img/a.png").bytes.size, "An absolute path inside the root is accepted")

        assertTrue("symbolic link" in refusal(sources, "leak.txt")!!, "A link as the file")
        assertTrue("symbolic link" in refusal(sources, "linked/secret.txt")!!, "A link in the path")
        assertTrue("symbolic link" in refusal(sources, "site/self.html")!!, "Even a link that stays inside the root")
        assertTrue("contains .." in refusal(sources, "site/../../outside/secret.txt"))
        assertTrue("outside" in refusal(sources, (outside / "secret.txt").toString()))
        assertTrue("does not exist" in refusal(sources, "site/missing.css"))
        assertTrue("not a regular file" in refusal(sources, "site/img"))
        assertTrue("symbolic link or not a directory" in refusal(sources, "site/index.html/child"))
    }

    @Test fun credentialsOversizedFilesAndRefusedPrefixesAreNeverRead() {
        fs.createDirectories(root / ".git"); fs.createDirectories(root / ".home")
        fs.write(root / ".git" / "config") { writeUtf8("[core]") }
        fs.write(root / ".env.local") { writeUtf8("TOKEN=1") }
        fs.write(root / ".home" / "notes.txt") { writeUtf8("runner home") }
        fs.write(root / "huge.bin") { write(ByteArray(16 * 1024 * 1024 + 1)) }
        val sources = ArtifactSources(listOf(root.toString()), refusedPrefixes = listOf((root / ".home").toString()))
        assertTrue(".git" in refusal(sources, ".git/config"))
        assertTrue(".env" in refusal(sources, ".env.local"))
        assertTrue("never published" in refusal(sources, ".home/notes.txt"))
        assertTrue("16 MiB" in refusal(sources, "huge.bin"))
        assertFalse(refusal(sources, ".env.local").contains("TOKEN"), "A refusal names the path and the rule, never the content")

        // The configuration directory is refused, except the scratch directory inside it.
        fs.createDirectories(root / "config" / "artifacts" / "run")
        fs.write(root / "config" / "config.json") { writeUtf8("{}") }
        fs.write(root / "config" / "artifacts" / "run" / "page.html") { writeUtf8("<html>merged</html>") }
        fs.createDirectories(root / "config" / "artifacts" / "run" / "kept")
        fs.write(root / "config" / "artifacts" / "run" / "kept" / "notes.txt") { writeUtf8("refused by the caller") }
        val configured = ArtifactSources(listOf(root.toString()), refusedPrefixes = listOf((root / "config" / "artifacts" / "run" / "kept").toString()),
            refusedExcept = listOf((root / "config").toString() to (root / "config" / "artifacts" / "run").toString()))
        assertTrue("never published" in refusal(configured, "config/config.json"))
        assertEquals("<html>merged</html>", configured.read("config/artifacts/run/page.html", "page.html").bytes.decodeToString())
        assertTrue("never published" in refusal(configured, "config/artifacts/run/kept/notes.txt"), "The exception never lifts another refusal")

        // A refused directory named through a link is refused all the same.
        fs.createSymlink(base / "home-link", root / ".home")
        val linked = ArtifactSources(listOf(root.toString()), refusedPrefixes = listOf((base / "home-link").toString()))
        assertTrue("never published" in refusal(linked, ".home/notes.txt"))
    }

    @Test fun privateDirectoriesAreCreatedOrAlreadyPrivate() {
        fs.createDirectories(base)
        val fresh = base / "fresh"
        assertTrue(me.nathanfallet.teamcode.cli.artifacts.ensurePrivateDirectory(fresh.toString()))
        assertTrue(me.nathanfallet.teamcode.cli.artifacts.ensurePrivateDirectory(fresh.toString()), "An existing 0700 directory of this user")
        val shared = base / "shared"
        fs.createDirectories(shared)
        platform.posix.chmod(shared.toString(), 493u)
        assertFalse(me.nathanfallet.teamcode.cli.artifacts.ensurePrivateDirectory(shared.toString()), "A shared directory keeps its permissions and is refused")
        fs.createSymlink(base / "link", fresh)
        assertFalse(me.nathanfallet.teamcode.cli.artifacts.ensurePrivateDirectory((base / "link").toString()), "Never a link")
    }
}
