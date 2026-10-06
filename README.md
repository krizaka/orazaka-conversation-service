# Orazaka Conversation Service

> Interactive ingress of the Orazaka engine: chat (SSE streaming), intents, models, pipeline, MCP and job APIs — translates transport into Intentions, never business logic.

**Layer:** Orazaka AI engine · **Version:** `1.0.0-SNAPSHOT` · **License:** Apache-2.0 ·
part of the [Orazaka platform](https://github.com/krizaka/orazaka) by [Krizaka](https://krizaka.com)

## What it provides

The interactive ingress (port `8080`, behind the edge): `/api/v1/chat` (SSE streaming),
`/api/v1/intent`, `/api/v1/models`, `/api/v1/pipeline`, `/api/v1/mcp`, `/api/v1/jobs`,
`/api/v1/features`, `/api/v1/media`, `/api/v1/assets`, `/api/v1/status`.
It translates transport into an `Intention` and enforces security — **no business logic**.
Owns `infra/initdb/20-conversation.sql`.

## Position in the platform

| | |
|:---|:---|
| Depends on | [`orazaka-build`](https://github.com/krizaka/orazaka-build) · [`orazaka-contracts`](https://github.com/krizaka/orazaka-contracts) · [`orazaka-users`](https://github.com/krizaka/orazaka-users) · [`orazaka-billing`](https://github.com/krizaka/orazaka-billing) · [`orazaka-ai-engine`](https://github.com/krizaka/orazaka-ai-engine) |
| Used by | _no other Orazaka repository._ |
| Workspace path | `orazaka-apps/services/orazaka-conversation-service` |

## Build

**Inside the Orazaka workspace** (recommended — every dependency is built from source):

```bash
git clone https://github.com/krizaka/orazaka.git && cd orazaka
node scripts/workspace.mjs clone          # clones every repository at its workspace path
./mvnw -f orazaka-apps/services/orazaka-conversation-service/pom.xml verify
```

**Standalone** — upstream artifacts must be in `~/.m2` (built by the workspace) or resolvable from
GitHub Packages (`https://maven.pkg.github.com/krizaka/<repository>`, see the
[workspace README](https://github.com/krizaka/orazaka#consuming-packages)):

```bash
./mvnw verify
```

Requirements: JDK 21, Docker (Testcontainers integration tests).

## Governance

This repository follows the Orazaka governance contract — [AGENTS.md](https://github.com/krizaka/orazaka/blob/main/AGENTS.md)
in the workspace is normative; the local [AGENTS.md](AGENTS.md) only scopes it to this repository.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
