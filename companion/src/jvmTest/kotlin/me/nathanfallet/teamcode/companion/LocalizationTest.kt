package me.nathanfallet.teamcode.companion

import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.InvalidInput
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException
import me.nathanfallet.teamcode.companion.presentation.viewmodels.userMessage
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.transcript_actions
import me.nathanfallet.teamcode.companion.resources.transcript_steps
import me.nathanfallet.teamcode.companion.resources.session_problem
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * The translations of the app: every language has every text of the default (English) one, with the same placeholders,
 * and each language is declared to the operating systems (Android's per-app language picker, iOS's bundle languages).
 * Gradle runs the tests of a module from its folder.
 */
class LocalizationTest {
    private val resources = File("src/commonMain/composeResources")

    /** The text of each resource: its name (`plurals:` in front for a plural) to its texts (one per quantity). */
    private fun load(folder: String): Map<String, List<String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resources, "$folder/strings.xml"))
        val entries = document.documentElement.childNodes
        val loaded = linkedMapOf<String, List<String>>()
        for (index in 0 until entries.length) {
            val node = entries.item(index) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> loaded["string:$name"] = listOf(node.textContent)
                "plurals" -> loaded["plurals:$name"] = (0 until node.getElementsByTagName("item").length).map { node.getElementsByTagName("item").item(it).textContent }
                else -> error("Unexpected <${node.tagName}> in $folder/strings.xml")
            }
        }
        return loaded
    }

    /** The languages translated: the default one (English) and each `values-<language>` folder. */
    private fun languages(): Set<String> =
        setOf("en") + resources.listFiles().orEmpty().map { it.name }.filter { it.startsWith("values-") }.map { it.removePrefix("values-") }

    private fun placeholders(text: String) = Regex("%(\\d+)\\$[sdf]").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyLanguageHasEveryDefaultText() {
        val default = load("values")
        for (language in languages() - "en") {
            val translated = load("values-$language")
            assertEquals(default.keys.toList().sorted(), translated.keys.toList().sorted(), "values-$language and values differ in their keys")
        }
    }

    @Test
    fun placeholdersMatchTheDefaultText() {
        val default = load("values")
        for (language in languages() - "en") {
            val translated = load("values-$language")
            for ((key, texts) in default) {
                val other = translated.getValue(key)
                // A plural has one text per quantity: the languages do not share the same quantities, but each text of a
                // language carries the placeholders of the default text (the number shows in every form).
                val expected = placeholders(texts.last())
                other.forEach { assertEquals(expected, placeholders(it), "$key in values-$language: placeholders differ from the default text") }
            }
        }
    }

    @Test
    fun noTextIsEmptyOrEscapesAnApostrophe() {
        for (language in languages()) {
            val folder = if (language == "en") "values" else "values-$language"
            for ((key, texts) in load(folder)) texts.forEach {
                assertTrue(it.isNotBlank(), "$key in $folder is empty")
                // Compose does not unescape \', so the apostrophe stays bare.
                assertFalse(it.contains("\\'"), "$key in $folder escapes an apostrophe")
            }
        }
    }

    @Test
    fun everyPluralHasItsOtherForm() {
        for (language in languages()) {
            val folder = if (language == "en") "values" else "values-$language"
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resources, "$folder/strings.xml"))
            val plurals = document.getElementsByTagName("plurals")
            for (index in 0 until plurals.length) {
                val node = plurals.item(index) as Element
                val quantities = (0 until node.getElementsByTagName("item").length).map { (node.getElementsByTagName("item").item(it) as Element).getAttribute("quantity") }
                assertTrue("other" in quantities, "${node.getAttribute("name")} in $folder has no 'other' form")
            }
        }
    }

    @Test
    fun everyLanguageIsDeclaredToTheSystems() {
        val languages = languages()
        val android = File("../companion-android/src/main/res/xml/locales_config.xml").readText()
        assertEquals(languages, Regex("<locale android:name=\"([^\"]+)\"").findAll(android).map { it.groupValues[1] }.toSet(), "locales_config.xml")
        assertTrue("android:localeConfig=\"@xml/locales_config\"" in File("../companion-android/src/main/AndroidManifest.xml").readText(), "the manifest does not reference locales_config.xml")

        val plist = File("../companion-ios/TeamCode/Info.plist").readText()
        val bundle = Regex("<key>CFBundleLocalizations</key>\\s*<array>(.*?)</array>", RegexOption.DOT_MATCHES_ALL).find(plist)?.groupValues?.get(1).orEmpty()
        assertEquals(languages, Regex("<string>([^<]+)</string>").findAll(bundle).map { it.groupValues[1] }.toSet(), "CFBundleLocalizations in Info.plist")

        val project = File("../companion-ios/TeamCode.xcodeproj/project.pbxproj").readText()
        val regions = Regex("knownRegions = \\((.*?)\\);", RegexOption.DOT_MATCHES_ALL).find(project)?.groupValues?.get(1).orEmpty()
        assertEquals(languages + "Base", Regex("[A-Za-z_-]+").findAll(regions).map { it.value }.toSet(), "knownRegions in project.pbxproj")
    }

    /** The desktop picks the language from the JVM's default locale; any other language than English or French shows English. */
    private fun <T> inLocale(locale: Locale, block: suspend () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try { return runBlocking { block() } } finally { Locale.setDefault(previous) }
    }

    @Test
    fun theLanguageFollowsTheLocale() {
        assertEquals("Retry", inLocale(Locale.ENGLISH) { getString(Res.string.action_retry) })
        assertEquals("Réessayer", inLocale(Locale.FRENCH) { getString(Res.string.action_retry) })
        assertEquals("Réessayer", inLocale(Locale.CANADA_FRENCH) { getString(Res.string.action_retry) })
        assertEquals("Retry", inLocale(Locale.GERMAN) { getString(Res.string.action_retry) })
        assertEquals("Problème : x", inLocale(Locale.FRANCE) { getString(Res.string.session_problem, "x") })
    }

    @Test
    fun pluralsFollowTheLanguage() {
        assertEquals("1 action", inLocale(Locale.FRENCH) { getPluralString(Res.plurals.transcript_actions, 1, 1) })
        assertEquals("3 actions", inLocale(Locale.FRENCH) { getPluralString(Res.plurals.transcript_actions, 3, 3) })
        assertEquals("1 step", inLocale(Locale.ENGLISH) { getPluralString(Res.plurals.transcript_steps, 1, 1) })
    }

    /** The sentence of a failure in [locale]. */
    private fun worded(locale: Locale, failure: Throwable): String = inLocale(locale) {
        val message = failure.userMessage()
        message.resource?.let { getString(it, *message.args.toTypedArray()) } ?: message.text
    }

    @Test
    fun failuresAreWordedInTheLanguage() {
        assertEquals("Nom d'utilisateur ou mot de passe incorrect.", worded(Locale.FRENCH, CompanionException.InvalidCredentials()))
        assertEquals("Wrong username or password.", worded(Locale.ENGLISH, CompanionException.InvalidCredentials()))
        assertEquals("Utilise au moins 14 caractères.", worded(Locale.FRENCH, InvalidInput(InvalidInput.Problem.PASSWORD_SHORT, 14, "x")))
        assertEquals("Trop de tentatives. Réessaie dans 30 s.", worded(Locale.FRENCH, CompanionException.RateLimited(30)))
        assertEquals("Le serveur a répondu HTTP 502 (bad_gateway).", worded(Locale.FRENCH, CompanionException.Server(502, "bad_gateway")))
        // The CLI's explanation is shown as the CLI wrote it.
        assertEquals("folder refused", worded(Locale.FRENCH, LocalHostException("folder refused")))
    }

    @Test
    fun everyFailureHasItsEnglishTextInThePlaceOfItsSentence() {
        for (failure in listOf<Throwable>(
            CompanionException.InvalidOrigin(), CompanionException.Unreachable(), CompanionException.NotFound(), CompanionException.Server(500, null),
            CompanionException.DeviceRefused("device_offline"), CompanionException.GitHubRefused("github_app_not_configured"),
            CompanionException.EnrollmentRefused("account_in_use"), InvalidInput(InvalidInput.Problem.CHOOSE_PROFILE, 0, "Choose a profile."),
        )) {
            val message = failure.userMessage()
            val sentence = worded(Locale.ENGLISH, failure)
            assertEquals(message.text, sentence, "${failure::class.simpleName}: the English text and the English resource differ")
        }
    }
}
