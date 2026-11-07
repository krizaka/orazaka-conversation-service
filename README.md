<!-- krizaka-header -->
<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/sovkora-dark.svg">
  <img src="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/sovkora-light.svg" alt="Sovkora" width="96">
</picture>

# Sovkora Conversation Service

**The AI that never leaves home.**

Interactive ingress of the Sovkora engine: chat (SSE streaming), intents, models, pipeline, MCP and job APIs — translates transport into Intentions, never business logic.

[![CI](https://github.com/krizaka/sovkora-conversation-service/actions/workflows/ci.yml/badge.svg)](https://github.com/krizaka/sovkora-conversation-service/actions/workflows/ci.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/krizaka/sovkora-conversation-service/badge)](https://scorecard.dev/viewer/?uri=github.com/krizaka/sovkora-conversation-service)
[![License: FSL-1.1-ALv2](https://img.shields.io/badge/license-FSL--1.1--ALv2-blue.svg)](LICENSE.md)
[![Sovkora](https://img.shields.io/badge/part%20of-Sovkora-f67e23)](https://github.com/krizaka/sovkora#repositories)
[![Docs](https://img.shields.io/badge/docs-krizaka.com-f67e23)](https://www.krizaka.com/en/products/sovkora)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-conversation-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-conversation-service) [![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-conversation-service&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-conversation-service) [![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-conversation-service&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-conversation-service) [![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-conversation-service&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-conversation-service) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-conversation-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-conversation-service)

[Documentation](https://www.krizaka.com/en/products/sovkora) · [Website](https://www.krizaka.com) · [Krizaka on GitHub](https://github.com/krizaka)

</div>
<!-- /krizaka-header -->

**Layer:** Sovkora AI engine · **Version:** `1.0.0-SNAPSHOT` · **License:** FSL-1.1-ALv2 ·
part of the [Sovkora platform](https://github.com/krizaka/sovkora) by [Krizaka](https://krizaka.com)

## What it provides

The interactive ingress (port `8080`, behind the edge): `/api/v1/chat` (SSE streaming),
`/api/v1/intent`, `/api/v1/models`, `/api/v1/pipeline`, `/api/v1/mcp`, `/api/v1/checks` (court records and credit for products, sovkora-engine#105), `/api/v1/jobs`,
`/api/v1/features`, `/api/v1/media`, `/api/v1/assets`, `/api/v1/status`.
Service-only: `POST /internal/v1/agent-turns` (one model turn of a client-run agent for the Studio service, ADR-093 §4: the actor is named in the body, hydrated and refused when unknown or disabled, the engine returns the tool calls instead of running them).
It translates transport into an `Intention` and enforces security — **no business logic**.
Owns its schema: `src/main/resources/db/migration/V1__conversation_baseline.sql`, applied by Flyway at startup (history table `flyway_history_conversation`; `infra/initdb/20-conversation.sql` is a comment).

### The law review queue (ADR-089 §8): `/api/v1/admin/law/**`

The routes behind the console's **Law → Proposals** screen. They are for **administrators only**: a session without
`ROLE_ADMIN` gets 403. **Every write leaves an `operator_audit` line** (ADR-085), refused ones included. Every rule
belongs to the sovkora-engine use cases; these controllers only translate HTTP.

| Method and path | Use case |
|:---|:---|
| `GET /proposals?status=&kindId=` · `GET /proposals/{id}` | `RuleProposalService.list` / `find` |
| `PUT /proposals/{id}` (a rule) | `RuleProposalReviewService.edit`: the next revision, vetted again |
| `POST /proposals/{id}/approve {note}` · `POST /proposals/{id}/reject {reason}` | `RuleProposalService.approve` / `reject` |
| `GET /kinds/{kindId}/golden` | `RulesetPromotionService.goldenCases` |
| `POST /kinds/{kindId}/promote {proposalIds, effectiveFrom, revisionNote?}` → `{prUrl, …}` | `RulesetPromotionService.promote`: opens the sovkora-packs pull request, or answers 409 with its `code` |
| `POST /derivations {jurisdiction, kindId, asOf, language?}` → `{runId, …}` | `KindDerivationService.start` (503 `law-derivation-off` when it is not wired) |
| `GET /demand` | `KindDemandService.list` |
| `GET /instruments` · `GET /articles?jurisdiction&instrument&ref&language&asOf` · `GET /imports?limit=` | `LawCorpusCatalogueService`: metadata only, never an article's text |
| `POST /imports` (multipart: `file`, `jurisdiction`, `instrument`, `language`, `datasetUrl`, `sha256?`, `consolidatedTo?`) | `LawManualImportService.importFile` (503 `law-corpus-disabled` while the corpus is off) |

Refusals are Problem Details with a `code` slug. The 409 codes are:
- `proposal-not-reviewable`, `proposal-not-editable`, `proposal-not-approved`;
- `already-in-review`, `kind-mismatch`, `duplicate-rule`;
- `golden-minimum-not-met`, `law-version-mismatch`, `quote-too-long`.

The other refusals are 404 `proposal-not-found` / `kind-not-found`, 503 `law-promotion-off`, and 400 `invalid-request`.

Promotion is off by default. To turn it on, set these variables (read in `META-INF/sovkora/conversation-service-defaults.yml`):

| Variable | Purpose |
|:---|:---|
| `AI_LAW_PROMOTION_ENABLED` | `true` turns promotion on. |
| `APP_GITHUB_AGENTS_APP_ID` | The id of the GitHub App `krizaka-agents`. |
| `APP_GITHUB_AGENTS_INSTALLATION_ID` | The App's installation. |
| `APP_GITHUB_AGENTS_PRIVATE_KEY` | The App's private key, in PEM. |

Promotion never uses a personal token.

**Rule `[AUDIT-001]`** (`AdminWritesAreAuditedTest`): every POST, PUT, PATCH or DELETE mapping under `/api/v1/admin` is on the
audit filter's operator surface (`OperatorAuditPaths`). The rule is proven on a planted controller. The audit line names a
mapped write by its template (`POST /api/v1/admin/law/kinds/{id}/promote`), and its target is the template's last variable.

## Position in the platform

| | |
|:---|:---|
| Depends on | [`sovkora-build`](https://github.com/krizaka/sovkora-build) · [`sovkora-contracts`](https://github.com/krizaka/sovkora-contracts) · [`krizaka-users`](https://github.com/krizaka/krizaka-build/tree/main/krizaka-users) · [`krizaka-billing`](https://github.com/krizaka/krizaka-build/tree/main/krizaka-billing) · [`sovkora-engine`](https://github.com/krizaka/sovkora-engine) |
| Used by | _no other Sovkora repository._ |
| Workspace path | `orazaka-apps/services/orazaka-conversation-service` |

## Build

**Inside the Sovkora workspace** (recommended — every dependency is built from source):

```bash
git clone https://github.com/krizaka/sovkora.git && cd sovkora
node scripts/workspace.mjs clone          # clones every repository at its workspace path
./mvnw -f orazaka-apps/services/orazaka-conversation-service/pom.xml verify
```

**Standalone** — upstream artifacts must be in `~/.m2` (built by the workspace) or resolvable from
GitHub Packages (`https://maven.pkg.github.com/krizaka/<repository>`, see the
[workspace README](https://github.com/krizaka/sovkora#consuming-packages)):

```bash
./mvnw verify
```

Requirements: JDK 21, Docker (Testcontainers integration tests).

## Governance

This repository follows the Sovkora governance contract — [AGENTS.md](https://github.com/krizaka/sovkora/blob/main/AGENTS.md)
in the workspace is normative; the local [AGENTS.md](AGENTS.md) only scopes it to this repository.

<!-- krizaka:workflows:start -->
## Workflows

What runs in the Actions tab of this repository.

- **CI** (`ci.yml`): Lint, tests and build of the repository. Runs on push, on every pull request, on demand.
- **Dependabot auto-merge** (`dependabot-automerge.yml`): Auto-merges safe Dependabot updates. Runs on every pull request.
- **Merge resolution** (`merge-resolution.yml`): Fails a PR that drops what main added without a deletion-explained line. Runs on every pull request.
- **Release notes** (`release-notes.yml`): Writes the release notes. Runs on push.
- **Scorecard** (`scorecard.yml`): OpenSSF Scorecard of the repository. Runs when branch rules change, on a schedule, on demand.
- **Security scan** (`security.yml`): Secret scan (gitleaks) and workflow scan (zizmor); both are required checks. Runs on every pull request, in the merge queue, on push.
<!-- krizaka:workflows:end -->

## License

[FSL-1.1-ALv2](LICENSE.md) (Functional Source License, Apache-2.0 future licence) © Technologies Krizaka Inc. — read,
run, modify and self-host it for any purpose except offering a competing service; each version becomes Apache-2.0 two
years after its release. Commits before the licence change remain [Apache-2.0](LICENSE-APACHE) — see [NOTICE](NOTICE)
and [ADR-012](https://github.com/krizaka/.github/blob/main/docs/adr/ADR-012-licensing-libraries-open-products-fair-source.md).
The Krizaka names and birds are not licensed: [trademark policy](https://github.com/krizaka/.github/blob/main/TRADEMARKS.md).
