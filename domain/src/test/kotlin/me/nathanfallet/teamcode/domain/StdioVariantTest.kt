package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.variants.*
import kotlin.test.*

/** MCP variants served by a hosted command (`transport: stdio`): what an administrator may declare, and what stays fixed. */
class StdioVariantTest {
    private val launchers = setOf(StdioLauncher.NPM, StdioLauncher.PYPI)
    private val everything = StdioCommand(StdioLauncher.NPM, "@modelcontextprotocol/server-everything@2025.9.25")
    private fun stdio(
        command: StdioCommand? = everything, binding: VariantBinding = VariantBinding.SHARED,
        config: Map<String, String> = emptyMap(), secrets: Map<String, String>? = null, endpoint: String? = null,
    ) = NewVariant(VariantKind.MCP, "everything", "team", binding, endpoint, config, secrets?.let(StdioEnvironment::encode), McpTransport.STDIO, command)
    private fun declared(new: NewVariant) = Variant("v", new.kind, new.target, new.name, new.binding, 1, new.endpoint, new.config,
        new.sharedSecret != null, 0, 0, new.transport, new.command)
    private fun code(block: () -> Unit) = assertFailsWith<TeamFailure> { block() }.code
    private fun invalid(variant: NewVariant) = assertEquals("invalid_variant", code { VariantService.validate(variant, launchers) }, variant.toString())

    @Test fun anAdministratorDeclaresAPinnedPackageWithBoundedArgumentsAndEncryptedVariables() {
        VariantService.validate(stdio(), launchers)
        VariantService.validate(stdio(StdioCommand(StdioLauncher.NPM, "server-memory@0.6.2", listOf("--port", "0"))), launchers)
        VariantService.validate(stdio(StdioCommand(StdioLauncher.PYPI, "mcp-server-fetch==2025.4.7")), launchers)
        VariantService.validate(stdio(StdioCommand(StdioLauncher.PYPI, "mcp-server-git==0.6")), launchers)
        VariantService.validate(stdio(StdioCommand(StdioLauncher.NPM, "tracker-mcp@1.0.0-rc.1", listOf("--token", "\${TRACKER_TOKEN}")),
            config = mapOf("TRACKER_URL" to "https://tracker.example.test"), secrets = mapOf("TRACKER_TOKEN" to "synthetic-tracker-token")), launchers)
        VariantService.validate(stdio(binding = VariantBinding.PER_MEMBER), launchers)
    }

    @Test fun onlyAnExactVersionFromNpmOrPypiIsAccepted() {
        for (pkg in listOf("server-everything", "server-everything@latest", "server-everything@^1.2.3", "server-everything@~1.2.3", "server-everything@1.2",
            "server-everything@1.2.x", "server-everything@>=1.0.0", "server-everything@01.2.3", "Server@1.2.3", "@scope@1.2.3", "-rf@1.2.3",
            "server@1.2.3 --registry=https://evil.test", "git+https://evil.test/x.git", "file:../x@1.0.0", "server@1.2.3\n", "x".repeat(260) + "@1.0.0"))
            invalid(stdio(StdioCommand(StdioLauncher.NPM, pkg)))
        for (pkg in listOf("mcp-server-fetch", "mcp-server-fetch>=1.0", "mcp-server-fetch==1.*", "mcp-server-fetch~=1.0", "mcp-server-fetch[cli]==1.0",
            "mcp-server-fetch==1.0;python_version>'3'", "mcp-server-fetch @ https://evil.test/x.whl", "-e==1.0", "mcp-server-fetch@1.0.0"))
            invalid(stdio(StdioCommand(StdioLauncher.PYPI, pkg)))
        // A package of one registry is not accepted under the other launcher.
        invalid(stdio(StdioCommand(StdioLauncher.PYPI, "server-everything@1.2.3")))
        invalid(stdio(StdioCommand(StdioLauncher.NPM, "mcp-server-fetch==1.0")))
    }

    @Test fun argumentsAreBoundedAndReferenceOnlyDeclaredSecrets() {
        fun args(vararg values: String) = StdioCommand(StdioLauncher.NPM, "server-memory@0.6.2", values.toList())
        invalid(stdio(args(*Array(VariantService.STDIO_ARGS_LIMIT + 1) { "a" })))
        VariantService.validate(stdio(args(*Array(VariantService.STDIO_ARGS_LIMIT) { "a" })), launchers)
        invalid(stdio(args("x".repeat(VariantService.STDIO_ARG_LENGTH + 1))))
        invalid(stdio(args("line\nbreak")))
        invalid(stdio(args("nul\u0000")))
        invalid(stdio(args("")))
        invalid(stdio(args("\${MISSING}")))
        invalid(stdio(args("\${lower}"), secrets = mapOf("lower" to "synthetic-value")))
        invalid(stdio(args("\${TOKEN}"), config = mapOf("TOKEN" to "visible")))
        VariantService.validate(stdio(args("--header=Bearer \${TOKEN}", "\$HOME stays literal"), secrets = mapOf("TOKEN" to "synthetic-token")), launchers)
        // A per-member command may reference variables that each member's value will hold.
        VariantService.validate(stdio(args("\${TOKEN}"), binding = VariantBinding.PER_MEMBER), launchers)
    }

    @Test fun environmentNamesFollowTheReservedListAndTheLauncherSettingsStayOutOfReach() {
        for (name in listOf("PATH", "HOME", "LD_PRELOAD", "NODE_OPTIONS", "TEAMCODE_MCP_TOKEN", "NPM_CONFIG_REGISTRY", "NPM_CONFIG_IGNORE_SCRIPTS",
            "UV_INDEX_URL", "UV_EXCLUDE_NEWER", "PIP_INDEX_URL", "lower", "WITH SPACE", "HTTPS_PROXY")) {
            invalid(stdio(config = mapOf(name to "value")))
            invalid(stdio(secrets = mapOf(name to "synthetic-value")))
        }
        invalid(stdio(config = (1..33).associate { "VAR_$it" to "v" }))
        invalid(stdio(config = mapOf("API_URL" to "x".repeat(1025))))
        invalid(stdio(config = mapOf("API_URL" to "a\nb")))
        invalid(stdio(config = mapOf("TOKEN" to "visible"), secrets = mapOf("TOKEN" to "synthetic-secret")))
    }

    @Test fun secretVariablesAreAnObjectOfAtMostSixteenValuesOfEightCharactersOrMore() {
        invalid(stdio(secrets = (1..VariantService.STDIO_SECRET_LIMIT + 1).associate { "TOKEN_$it" to "synthetic-$it" }))
        VariantService.validate(stdio(secrets = (1..VariantService.STDIO_SECRET_LIMIT).associate { "TOKEN_$it" to "synthetic-$it" }), launchers)
        invalid(stdio(secrets = mapOf("TOKEN" to "short")))
        invalid(stdio(secrets = emptyMap()))
        invalid(stdio(secrets = mapOf("TOKEN" to "x".repeat(VariantService.SECRET_LIMIT))))
        // A hosted command takes its secrets as variables, never as one bare credential.
        invalid(stdio().copy(sharedSecret = SecretValue("synthetic-bare-credential")))
        invalid(stdio().copy(sharedSecret = SecretValue("""{"TOKEN":1234567890}""")))
        invalid(stdio().copy(sharedSecret = SecretValue("""["TOKEN"]""")))
        invalid(stdio(binding = VariantBinding.PER_MEMBER, secrets = mapOf("TOKEN" to "synthetic-value")))
        val encoded = StdioEnvironment.encode(mapOf("B_TOKEN" to "synthetic-b", "A_TOKEN" to "synthetic-a"))
        assertEquals(mapOf("A_TOKEN" to "synthetic-a", "B_TOKEN" to "synthetic-b"), StdioEnvironment.decode(encoded))
        assertEquals("SecretValue([REDACTED])", encoded.toString())
    }

    @Test fun transportsDoNotMix() {
        invalid(stdio(endpoint = "https://mcp.example.test"))
        invalid(stdio(command = null))
        invalid(NewVariant(VariantKind.MCP, "tracker", "team", VariantBinding.SHARED, "https://mcp.example.test", command = everything))
        invalid(NewVariant(VariantKind.ENV, "TOKEN", "org", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic"), transport = McpTransport.STDIO, command = everything))
        invalid(NewVariant(VariantKind.ENV, "TOKEN", "org", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic"), command = everything))
        VariantService.validate(NewVariant(VariantKind.MCP, "tracker", "team", VariantBinding.SHARED, "https://mcp.example.test"), emptySet())
        assertEquals(McpTransport.STDIO, McpTransport.parse("stdio"))
        assertEquals(StdioLauncher.PYPI, StdioLauncher.parse("pypi"))
        assertEquals("invalid_variant", code { McpTransport.parse("sse") })
        assertEquals("invalid_variant", code { StdioLauncher.parse("cargo") })
    }

    @Test fun withoutAnMcpHostTheServerRefusesToDeclareAHostedCommand() {
        val failure = assertFailsWith<TeamFailure> { VariantService.validate(stdio(), emptySet()) }
        assertEquals("mcp_disabled" to 409, failure.code to failure.status)
        assertTrue(failure.message!!.contains("mcp.enabled"))
        // A launcher the host does not offer is a mistake in the request, not a missing host.
        assertEquals("invalid_variant", code { VariantService.validate(stdio(StdioCommand(StdioLauncher.PYPI, "mcp-server-fetch==2025.4.7")), setOf(StdioLauncher.NPM)) })
    }

    @Test fun theCommandAndItsVisibleVariablesAreImmutableWhileSecretsRotate() {
        val tokenArgs = StdioCommand(StdioLauncher.NPM, "tracker-mcp@1.0.0", listOf("\${TOKEN}"))
        val current = declared(stdio(tokenArgs, config = mapOf("API_URL" to "https://tracker.example.test"), secrets = mapOf("TOKEN" to "synthetic-token")))
        VariantService.validate(current, VariantUpdate(1, "renamed", current.config))
        VariantService.validate(current, VariantUpdate(1, "team", current.config, StdioEnvironment.encode(mapOf("TOKEN" to "synthetic-rotated"))))
        // Visible variables decide where the secret goes (API_URL), so they cannot change after creation.
        invalid2(current, VariantUpdate(1, "team", mapOf("API_URL" to "https://attacker.test")))
        invalid2(current, VariantUpdate(1, "team", emptyMap()))
        invalid2(current, VariantUpdate(1, "team", current.config, clearSharedSecret = true))
        invalid2(current, VariantUpdate(1, "team", current.config, StdioEnvironment.encode(mapOf("OTHER" to "synthetic-other"))))
        invalid2(current, VariantUpdate(1, "team", current.config, SecretValue("synthetic-bare")))
        val open = declared(stdio())
        VariantService.validate(open, VariantUpdate(1, "team", clearSharedSecret = true))
    }

    @Test fun aMemberValueForAHostedCommandIsAnObjectHoldingEveryReferencedVariable() {
        val perMember = declared(stdio(StdioCommand(StdioLauncher.NPM, "tracker-mcp@1.0.0", listOf("\${TOKEN}")), VariantBinding.PER_MEMBER, mapOf("API_URL" to "https://tracker.example.test")))
        VariantService.validateMemberSecret(perMember, StdioEnvironment.encode(mapOf("TOKEN" to "synthetic-member")))
        for (secret in listOf(null, SecretValue("synthetic-bare-member"), StdioEnvironment.encode(mapOf("OTHER" to "synthetic-member")),
            StdioEnvironment.encode(mapOf("TOKEN" to "short")), StdioEnvironment.encode(mapOf("API_URL" to "synthetic-member", "TOKEN" to "synthetic-member"))))
            assertEquals("invalid_variant", code { VariantService.validateMemberSecret(perMember, secret) }, secret?.let(StdioEnvironment::decodeOrNull).toString())
        val http = Variant("h", VariantKind.MCP, "tracker", "h", VariantBinding.PER_MEMBER, 1, "https://mcp.example.test", emptyMap(), false, 0, 0)
        VariantService.validateMemberSecret(http, SecretValue("plain-delegated-credential"))
    }

    @Test fun aHostedCommandIsNotServedYetSoItNeverReachesTheMemberServerList() = runTest {
        val hosted = declared(stdio(secrets = mapOf("TOKEN" to "synthetic-token")))
        val remote = Variant("r", VariantKind.MCP, "tracker", "r", VariantBinding.SHARED, 1, "https://mcp.example.test", emptyMap(), true, 0, 0)
        val resolve = ResolveEffectiveVariantsUseCase(object : EffectiveVariantSource {
            override suspend fun state(teamId: String, memberId: String) = MemberVariantState(true, listOf(
                AssignmentCandidate(hosted.slot, AssignmentSource.DIRECT, hosted, StdioEnvironment.encode(mapOf("TOKEN" to "synthetic-token")), null),
                AssignmentCandidate(remote.slot, AssignmentSource.DIRECT, remote, SecretValue("synthetic-remote"), null),
            ), emptySet())
        })
        val effective = resolve("team", "member")
        assertEquals(listOf("tracker"), effective.mcpServers.map { it.name })
        assertEquals(listOf(hosted.slot), effective.unavailable)
    }

    private fun invalid2(current: Variant, update: VariantUpdate) = assertEquals("invalid_variant", code { VariantService.validate(current, update) }, update.toString())
}
