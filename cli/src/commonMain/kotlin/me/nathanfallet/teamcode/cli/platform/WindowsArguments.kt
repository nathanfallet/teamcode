package me.nathanfallet.teamcode.cli.platform

/** Windows CRT argv encoding. No cmd.exe, shell expansion or interpretation of metacharacters. */
internal fun quoteWindowsArgument(value: String): String {
    require('\u0000' !in value)
    return buildString {
        append('"')
        var slashes = 0
        for (character in value) when (character) {
            '\\' -> slashes++
            '"' -> { repeat(slashes * 2 + 1) { append('\\') }; append('"'); slashes = 0 }
            else -> { repeat(slashes) { append('\\') }; slashes = 0; append(character) }
        }
        repeat(slashes * 2) { append('\\') }
        append('"')
    }
}

/**
 * msiexec.exe reads its own command line instead of the CRT's argv: a quoted switch (`"/i"`) or property
 * (`"MSIINSTALLPERUSER=1"`) is not recognized, and it answers with its usage window, which waits for a click that no
 * installation by `teamcode ui` can give. Switches and properties go through as they are, a property value is quoted
 * only when it holds a space, and everything else (the package path) is quoted as usual.
 */
internal fun msiexecCommandLine(application: String, arguments: List<String>): String =
    (listOf(quoteWindowsArgument(application)) + arguments.map { argument ->
        val property = Regex("^([A-Za-z0-9_]+)=(.*)$", RegexOption.DOT_MATCHES_ALL).matchEntire(argument)
        when {
            argument.isNotEmpty() && argument[0] == '/' && argument.none { it.isWhitespace() || it == '"' } -> argument
            property != null && property.groupValues[2].none { it == '"' || it == '\u0000' } ->
                property.groupValues[1] + "=" + property.groupValues[2].let { if (it.isEmpty() || it.any(Char::isWhitespace)) "\"$it\"" else it }
            else -> quoteWindowsArgument(argument)
        }
    }).joinToString(" ")
