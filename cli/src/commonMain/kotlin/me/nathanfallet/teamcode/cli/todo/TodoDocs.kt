package me.nathanfallet.teamcode.cli.todo

/** What the agent is told about the todo tools: the server instructions, the system-prompt sentence and `todo_help`. */
internal object TodoDocs {
    /** The agent-side name of the todo tools; never a team server's label, which always starts with `teamcode-`. */
    const val LABEL = "teamcode_todo"

    /** The sentence added to the agent's instructions while the todo tools are connected. */
    val PROMPT = "For a multi-step task, call `todo_create` of the `$LABEL` MCP server before starting, with the plan reference and every item. " +
        "Once a list exists, you cannot end a turn while items remain, unless a wait is open (`ask_human`, `wait_for`) or a background agent runs."

    val INSTRUCTIONS = """
        teamcode_todo keeps this session's todo: the plan reference and its items.
        The server and a Stop hook hold you to it.
        When: for any task with more than one step, call todo_create before you
        start. A quick question needs no todo.
        Write the todo once:
        - reference: one paragraph that points to the full plan (an artifact link,
          or the person's request copied as is). It is shown to you again at every
          stop, after a compaction and with every wait answer.
        - items: every step of the plan, in order. Each item has
          id (short slug), name (one line), description (complete: what, where,
          why, what is out of scope; enough for a new agent to do the item alone),
          dependsOn, and doneWhen = {text, checks[]}.
        Checks describe what must be observed, never a number you expect:
        - pr_merged {repo}: call todo_start, put the marker it returns in the PR
          body; the PR is found and linked by that marker.
        - release_contains {repo, of: <item id>}: done when a published release
          contains the merge of that item's PR, whatever its version.
        - ci_green {repo, of: <item id>}, command {run} (may cite ${'$'}{pr:<item>.number},
          ${'$'}{pr:<item>.mergeSha}, ${'$'}{release:<item>.tag}), artifact_published {id},
          wait_answered {}, attested {what} (unverified: last resort).
        Most items can be checked. An item of research, writing or analysis ends on
        artifact_published (publish what you found as an artifact); an item that needs
        a person's decision ends on wait_answered (ask with ask_human). Use attested
        only when nothing can be observed.
        While working:
        - todo_start before an item; todo_complete when its checks should pass:
          the checks run, and a refusal says what is missing.
        - todo_add when you find work the plan needs; todo_note for progress;
          todo_link for a PR, release, artifact or wait that already exists.
        - You cannot delete or rewrite an item: todo_drop asks the session's owner.
        Stopping: you cannot end a turn while items remain, unless a wait is open
        (ask_human, wait_for) or a background agent runs. When every item is done,
        stop. Call todo_help for the item schema and examples.
    """.trimIndent()

    /** `todo_help`: the rules, the schema of an item and three examples (a PR, a release, a research that ends on an artifact). */
    val HELP = """
        THE RULES
        One todo per session. You write it once (todo_create), then add items (todo_add); you cannot delete or rewrite one. An item is done only when
        its checks pass: todo_complete runs them and refuses with what is missing. You cannot end a turn while items remain, unless a wait is open
        (ask_human, wait_for) or a background agent runs.

        AN ITEM
        {"id": "backend-endpoint",                 // slug, at most 40 characters, unique
         "name": "Route /vestiaire/listings",      // one line, at most 80 characters
         "description": "...",                     // 80 to 4000 characters: what, where, why, what is out of scope
         "dependsOn": ["other-item-id"],           // optional
         "doneWhen": {"text": "what a person reads", "checks": [ {"type": "...", ...} ]}}
        Check types: pr_merged {repo}, pr_closed {repo, reason}, release_contains {repo, of | commit}, ci_green {repo, of | ref, workflow?},
        command {run, cwd?, expectExit?, timeoutSeconds?}, artifact_published {id?, minVersion?}, wait_answered {action?}, attested {what}.
        Never write a PR number or a version: use pr_merged and the marker todo_start returns.
        Prefer a check that can be observed: research, writing and analysis end on artifact_published, a decision on wait_answered;
        attested is the last resort and is shown as unverified.

        EXAMPLE 1: a fix in one PR
        {"id":"fix-login","name":"Fix the login redirect","dependsOn":[],
         "description":"The login page redirects to / instead of the page asked for. Fix it in web/src/auth/redirect.ts, add a test, and leave the signup flow alone.",
         "doneWhen":{"text":"A PR of acme/web with the fix is merged","checks":[{"type":"pr_merged","repo":"acme/web"},{"type":"ci_green","repo":"acme/web","of":"fix-login"}]}}

        EXAMPLE 2: ship it in a release
        {"id":"release-backend","name":"Publish the backend release with the route","dependsOn":["backend-endpoint"],
         "description":"Once the PR of backend-endpoint is merged, create the release on main with generated notes, then point core at its tag. Do not touch the app.",
         "doneWhen":{"text":"A published release of the backend contains that merge and core points at its tag","checks":[
           {"type":"release_contains","repo":"acme/backend","of":"backend-endpoint"},
           {"type":"command","run":"gh api repos/acme/core/contents/values.yaml -q .content | base64 -d | grep -q 'backend: ${'$'}{release:release-backend.tag}'"}]}}

        EXAMPLE 3: research that ends on an artifact
        {"id":"findings","name":"Publish the findings","dependsOn":[],
         "description":"Compare the three vendors on price, limits and support, with sources, and publish a page for the team. Do not recommend a purchase.",
         "doneWhen":{"text":"A page with the comparison is published as an artifact","checks":[{"type":"artifact_published"}]}}
        After publishing, bind it: todo_link {id: "findings", kind: "artifact", ref: "<artifact id>"}.
    """.trimIndent()
}
