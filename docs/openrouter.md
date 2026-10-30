# OpenRouter models

An administrator can give the team one OpenRouter API key. Any model OpenRouter serves (Gemini, DeepSeek, Llama, Mistral, GPT and more) then becomes reachable through TeamCode with the same member authentication, model policy, budgets, ledger and audit as a subscription account. Claude Code is the supported client: it speaks the Messages protocol, which OpenRouter accepts natively, so the request is forwarded without translation. Codex speaks the Responses protocol and would need [experimental cross-provider translation](routing.md#experimental-conversion-setting), which stays off by default.

## Add the key

Create a key at [openrouter.ai/keys](https://openrouter.ai/keys). **Give it a credit limit there**: that limit is the hard cap on what the team can spend, whatever TeamCode's own budgets say. Then either:

- **Dashboard → Subscription accounts → Add an OpenRouter API key**, or
- `teamcode accounts add --provider openrouter` (the key is read without echo).

TeamCode calls `GET /api/v1/key` with the key before storing anything. A rejected key, or a *management* key (which cannot run inference), is refused without echoing either. The key is stored encrypted like any other credential, serves requests at once (nothing else refreshes it, so there is no handover) and is never shown again. The account is named by a digest of the key: adding the same key again updates the account, another key is another account. Who may add accounts follows the usual [enrollment policy](capacity.md#who-can-add-accounts).

## Send a model to it

Without a matching route, requests keep going to their own subscription provider, so nothing changes until a route says otherwise. In **Dashboard → Routes** (or `/api/v1/routes`), `openrouter` is a provider next to `anthropic` and `codex`:

| Requested model | Targets | Effect |
| --- | --- | --- |
| `google/*` | `openrouter` | Every `google/...` slug is sent as typed. |
| `gemini` | `openrouter:google/gemini-3-pro` | A short alias for a slug. |
| `deepseek/*` | `openrouter, anthropic:claude-haiku-4-5` | Fall back to Claude only if you write it; see below. |

In Claude Code, `/model google/gemini-3-pro` (or `--model`) selects it. Slugs contain `/` and sometimes `:` (`:free`); route patterns, targets and the request's model accept both. A target is written `provider:model`, split at the first colon, so `openrouter:meta-llama/llama-3.3-70b-instruct:free` is one target.

## Which models are allowed

The existing [model policy](capacity.md#models-and-effort) applies unchanged to the requested model and to the resolved one: **Deny takes precedence**, then the model must match an allow pattern. The default allows `claude-*` and `gpt-*` only, so an OpenRouter model is refused (`model_not_allowed`) until an administrator opens it:

- everything: allow `*`;
- a family: allow `google/*`, `deepseek/*`;
- everything but some: allow `*` and deny `*/*:free`, `openai/*`, and so on.

## Limits and cost

- **The key's credit limit** is enforced by OpenRouter itself and is the only hard dollar cap. TeamCode reads it (`limit`, `limit_remaining`) and shows the key as a `credits` quota window; a key without a limit shows no window.
- **Member, group, pool and run budgets** count credits (every input token once, output five times), the unit admission already uses, so they apply to OpenRouter traffic the same way. They are token budgets, not dollar budgets.
- **Dollars in the ledger**: tokens enter the usage ledger under provider `openrouter` and the served model. Enter a price row for that provider and model in the API price table ([API prices and units](capacity.md#api-prices-and-units)) (micro-dollars per million tokens, from the model's OpenRouter page) to see them as dollars. Without a row the tokens are counted as unpriced, never as free. Input and output prices must be above zero, so a `:free` model needs a token price too if you want it counted.
- Per-member dollar budgets (as opposed to token budgets) are not part of this change.

## Failures

There is no fallback unless the route lists one. OpenRouter errors reach the caller as they do for any provider, with TeamCode's own text and never the provider's body:

| OpenRouter | Caller sees | Effect on the account |
| --- | --- | --- |
| 402 (out of credit) | 402 `billing_error`: "The provider account has no credit left…" | none |
| 400, 413 | the same status | none |
| 401, 403 | 429 `pool_unavailable` | cooled down; a 401 marks the account reconnect-required |
| 429 | 429 | cooled down, or counted as exhausted when the key's cap is spent |
| 5xx | 502 | cooled down |

Claude Code does not retry a `billing_error` and prints its message. A 403 from OpenRouter can also mean one model's provider refused the prompt; it cools the whole key for the retry delay, like any 403.

## What is and is not sent

The Messages body is forwarded with `metadata` removed: Claude Code puts a device and session identity there, and it is not sent to a third party. `anthropic-version` and `anthropic-beta` are forwarded. The key goes in the `Authorization` header only and is never logged. Prompts and responses pass through OpenRouter and the model's provider, under their terms and data policies, unlike the subscription providers: administrators should decide per model what may be sent (OpenRouter's key settings can restrict providers and data collection).

## Known limits

- Claude Code is tuned for Anthropic models; a non-Anthropic model can misbehave on tool use, thinking or long context. OpenRouter says it guarantees Claude Code only with its Anthropic provider.
- Request-level reasoning fields and fast mode are passed as sent; whether a given model honors them is up to that model.
- Only the Messages protocol is wired. Codex through OpenRouter is not supported.
- No live OpenRouter call is made by CI or by the tests: the transport is covered with a mock engine, as for the other providers. A real-key acceptance run is still to be done.
