# CLAUDE.md

Project context for Claude / Claude Code. Read this first on any new session.

## What this is

**marketplace-service** — the InnBucks Marketplace: merchant product listings,
a public catalog, and buyer orders. A standalone repo joining the InnBucks
fleet exactly the way `MpofuSlim/InnRewards` (loyalty-service) does: it
registers on the cell's Eureka registry as **`marketplace-service`** and the
fleet api-gateway (in `MpofuSlim/ticketing-system`) routes `/marketplace/**`
to it by service name. The service name + the image name
(`ghcr.io/mpofuslim/marketplace-service`) are the stable extraction contract —
never change either casually.

* Stack: Spring Boot 4.x (import-BOM pattern, NOT starter-parent — the CVE
  overrides rely on first-wins resolution), Java 21, JPA + Postgres 16,
  Flyway (`ddl-auto: validate`), shared fleet Redis (denylist read side),
  Eureka client, Micrometer + optional OTel, Springdoc.
* App port `8087`. Database `marketplace_service` on the cell Postgres.
* Money is ALWAYS minor units (cents, `BIGINT`/`long`). Timestamps are ALWAYS
  UTC `Instant` → `TIMESTAMPTZ`. Containers pin `-Duser.timezone=UTC`.

> [!IMPORTANT]
> **Branch naming: ALWAYS `feature/<short-kebab-description>`, cut from the
> default branch — NO exceptions.** Claude Code / web sessions frequently
> start on an auto-assigned `claude/<random-words>` branch. That is a harness
> artifact, NOT this project's convention — NEVER push it and NEVER open a PR
> from it. Before the FIRST push of any session, rename:
> `git branch -m feature/<name>` (or create `feature/<name>` from the current
> HEAD), push that, and delete any `claude/*` branch that reached the remote.
> The operator has stated this preference explicitly and permanently — do not
> ask again, just comply. One feature per branch; push with
> `git push -u origin <branch>` and open a **draft** PR.

## What this service deliberately does NOT own

* **Identity** — user-service mints the fleet JWTs; this service only
  VERIFIES them (HS256 shared secret + RS256 dual-verify selected by the
  token's own `alg` header when `JWT_PUBLIC_KEY` is set). Roles used here:
  `CUSTOMER` (buyers), `MERCHANT_ADMIN` (sellers, scoped by the JWT's
  `merchantId` claim), and `SUPER_ADMIN` (fleet oversight: manages ANY
  merchant's listings — update/status/image upload+delete, no ownership
  check, no `merchantId` claim needed — reads ALL listings via
  `GET /marketplace/listings/mine` (optional `?merchantId=` filter) and ALL
  orders via `GET /marketplace/orders` (optional `?buyerUuid=`) plus any
  single order by id; **cannot place or cancel orders** — those stay
  CUSTOMER-only). **Merchant-side listing administration is
  MERCHANT_ADMIN-only — an explicit owner decision (2026-08-05); do not
  re-add SHOP_ADMIN without the owner asking.** Merchant scope comes from
  the JWT, NEVER from a request body — with ONE deliberate, owner-approved
  exception: SUPER_ADMIN creates listings ON BEHALF of a merchant via the
  optional `merchantId` field on the create request (400
  `merchant_id_required` if omitted, since admin tokens carry no merchant
  claim). For MERCHANT_ADMIN callers that field is refused whenever it
  differs from their claim (422 `merchant_scope_mismatch`), so the invariant
  stays intact for merchants. The principal uuid prefers the `userUuid`
  claim (fleet tokens carry the login identifier, not the uuid, in `sub`).
* **Payments** — there is NO InnBucks Merchant API client in this repo. The
  platform payments service drives orders through the internal S2S surface
  (`/marketplace/internal/orders/*`: read, extend-expiry, confirm-payment),
  mirroring the proven booking-service contract. Payment confirmation
  cross-checks the paid amount against the order total (the 100x guard) —
  a mismatch parks with an audit event, it NEVER confirms.
* **Loyalty** — points ride InnRewards' generic S2S surfaces.
* **Notifications** — deferred; when added, copy the fleet's gateway client
  (contract-tested with WireMock) rather than inventing a new one.

## Security invariants (do not weaken without a called-out reason)

* **Fail-closed secrets guard** (`config/ProductionSecretsGuard`):
  "deployment" = an active-profile set with NO `dev`/`test`/`it`/`local`
  profile, **including the empty set**. Boot-required, ≥32 chars, no
  placeholder markers, all distinct: `JWT_SECRET`, `INTERNAL_API_TOKEN`,
  `AUDIT_HMAC_SECRET`; plus non-blank `DB_PASSWORD` and `REDIS_PASSWORD`.
  Generate each: `openssl rand -base64 48`.
* **Internal endpoints — three files must agree** (fleet rule, now
  cross-repo): controller enforces `X-Internal-Token` with a constant-time
  compare + this repo's `SecurityConfig` permitAlls the exact path + the
  fleet gateway carries a `marketplace-internal-deny` route BEFORE the
  `/marketplace/**` route. Adding an internal endpoint here without the
  gateway deny route makes it internet-reachable — update
  `ticketing-system/api-gateway` in lock-step (see `docs/fleet-wiring.md`).
  Test assertions use specific codes (`.isUnauthorized()`), never
  `.is4xxClientError()`.
* **Tamper-evident audit chain**: `audit_events.row_hmac` (content seal) +
  `chain_hmac = HMAC(key, prev ‖ row_hmac)` (deletion/reorder evidence),
  serialised via single-row `audit_chain_head` `SELECT … FOR UPDATE` in a
  REQUIRES_NEW tx. Nightly `AuditIntegrityVerifier`: content tamper →
  `marketplace.audit.integrity.broken` (page), chain break →
  `marketplace.audit.chain.broken` (ticket).
* **Idempotency claim-row** on order creation: key claimed with
  `INSERT … ON CONFLICT DO NOTHING` (status 0 sentinel) BEFORE work runs;
  replays return the ORIGINAL stored status/body; same key + different body
  → 422; fresh claim in flight → 409; stale claim (>60s) taken over. DB
  backstop: partial unique index on `market_order.idempotency_key`.
* **Stock is reserved atomically** (`UPDATE listing SET stock_qty = stock_qty
  - :q WHERE id = :id AND stock_qty >= :q` — check the update count), and
  released exactly once (`market_order.stock_released` double-release guard).
  Prices/totals are computed SERVER-SIDE from listing rows — a client can
  never supply a price.
* **Order state machine**: legal-transitions map (PENDING_PAYMENT →
  PAID/CANCELLED/EXPIRED; terminals immutable); illegal requests are refused
  and counted, never applied; every transition writes a same-tx
  `market_order_event` journal row + an audit event.
* **Input hygiene**: Bean Validation on every DTO; jsoup-sanitized free text
  on listing write paths (stored-XSS defense); pagination hard-capped;
  MSISDNs normalised to E.164 (libphonenumber) before storage.
* **Listing images** (event-service banner design, V2): BYTEA bytes +
  content-type columns, `@Basic(fetch = LAZY)` so list endpoints never pull
  bytes. Upload (`PUT /marketplace/listings/{id}/image`, multipart part
  `image`) validates the allow-listed content type AND the magic-byte
  signature — jpeg/png/webp only, GIF deliberately rejected — with a 10 MB
  cap enforced twice (servlet `spring.servlet.multipart.max-file-size` and
  in-code; `GlobalExceptionHandler` maps the container's rejection to the
  same 400 `image_too_large`). Bytes are served ONLY via the public
  `GET /marketplace/catalog/{id}/image` with the stored Content-Type +
  `X-Content-Type-Options: nosniff` + 1h public cache — status-independent
  by design (DRAFT owners need the preview; UUIDs are unguessable). The
  JSON listing-create contract stays non-multipart (published FE contract) —
  a deliberate deviation from event-service's multipart create.
* **Error shape**: everything renders as the fleet `ApiResult` envelope via
  `GlobalExceptionHandler`; server.error includes nothing; unhandled → generic
  500, internals stay in logs.
* **CI supply chain**: every third-party GitHub Action pinned to an immutable
  commit SHA + `# vX.Y.Z` comment; least-privilege `permissions:` per
  workflow. Release: full test suite gates the image build, Trivy scans
  CRITICAL/HIGH against the governed `.trivyignore` BEFORE any push, then
  pushes `ghcr.io/mpofuslim/marketplace-service:{latest,sha-<commit>}` with
  SLSA provenance + SBOM. Deploys pull a pinned `sha-<commit>`.

## Fleet integration (cross-repo contracts — keep in lock-step)

All fleet-side wiring lives in `MpofuSlim/ticketing-system` and is documented
with exact diffs in **`docs/fleet-wiring.md`**:

* Gateway routes: `marketplace-internal-deny` (`/marketplace/internal/**` →
  `forward:/__edge_deny__`) BEFORE `marketplace-service-route`
  (`/marketplace/**` → `lb://marketplace-service`), + the api-docs proxy and
  Swagger aggregation entries.
* k8s: Deployment + Service in `deploy/k8s/` (namespace `ticketing`), envFrom
  the shared cell ConfigMap/Secret; DB `marketplace_service` added to the
  pg-init ConfigMap.
* Prometheus: scrape job with the fleet `METRICS_SCRAPE_TOKEN`.
* Shared secrets (`JWT_SECRET`, `INTERNAL_API_TOKEN`, `REDIS_PASSWORD`,
  `METRICS_SCRAPE_TOKEN`) are provisioned from the cell's secret — rotation
  is a cross-repo operation.

## Persistence gotchas

* Schema is Flyway-owned (`V<N>__*.sql`); Hibernate validates only. New
  sensitive columns must follow the fleet A02 rules (keyed HMAC for
  low-entropy secrets, never bare hashes).
* JPA entities use manually-assigned UUID ids + `@Version` optimistic
  locking; stock movements bypass the entity (bulk `@Modifying` update) —
  never read-modify-write stock through the entity.

## Tests

* Unit tests (`*Test`) run with Surefire, no Docker.
* Integration tests (`*IT`) run with Failsafe during `verify` and use the
  shared Postgres Testcontainer — they need Docker (CI has it).
* Every future external-HTTP client MUST get a standalone-WireMock contract
  test per the fleet convention.

## Swagger

Every controller: `@Tag` + `@Operation` + `@ApiResponses` with
`@ExampleObject` bodies in the `ApiResult` envelope; realistic failure shapes
(400/401/403/404/409/422), real messages from the code, cross-endpoint
consistency. Internal endpoints are excluded from the spec
(`springdoc.paths-to-exclude`). UI off under `prod` (gateway hosts the
aggregated UI).
