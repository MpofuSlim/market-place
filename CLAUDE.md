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
* **Notifications** — LIVE (see the "Notifications" section below). The
  clients are FAITHFUL COPIES of the ticketing fleet's proven implementations
  (booking-service / InnRewards), package-renamed with `MKT-` references —
  never invent a new wire contract; the `notify/` package depends on no fleet
  module.

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
* **Listing image GALLERY** (V3 — replaced V2's single-image columns): up to
  **10 images per listing** in the `listing_image` table (BYTEA + content
  type per row), **exactly ONE primary whenever any images exist** — a
  partial unique index (`ON listing_image(listing_id) WHERE is_primary`) is
  the DB backstop; app-side, primary swaps run as ORDERED bulk statements
  (demote THEN mark; delete THEN promote) so the index never sees two
  primaries mid-transaction. The invariant is preserved by construction:
  the first uploaded file of a create becomes primary, adding to an empty
  gallery promotes the sole image, and deleting the primary promotes the
  lowest-position survivor. Metadata reads (list endpoints, response
  assembly) go through a **bytes-free interface projection**
  (`ListingImageRepository.ImageMeta`) — never the entity — and a page of
  listings resolves its galleries with ONE grouped `listing_id IN (...)`
  query (`ListingViewAssembler`), never per-row. Endpoints:
  `PUT /{id}/image` (replace-or-create primary, back-compat V2 contract),
  `DELETE /{id}/image` (delete primary + promotion), `POST /{id}/images`
  (append; 409 `image_limit_reached` at 10), `DELETE /{id}/images/{imageId}`
  (remove one, promotion if primary), `PUT /{id}/images/{imageId}/primary`
  (atomic swap). Multipart create takes parts `listing` + `image` (primary)
  + repeated `images` (max 9 additional → 400 `too_many_images`). Every file
  validates the allow-listed content type AND the magic-byte signature —
  jpeg/png/webp only, GIF deliberately rejected — 10 MB cap enforced twice
  (servlet `spring.servlet.multipart.max-file-size` and in-code;
  `GlobalExceptionHandler` maps the container's rejection to the same 400
  `image_too_large`). Bytes are served ONLY via the public
  `GET /marketplace/catalog/{id}/image` (primary, unchanged contract) and
  `GET /marketplace/catalog/{id}/images/{imageId}` (any image; the
  (listingId, imageId) pair must match), both with the stored Content-Type +
  `X-Content-Type-Options: nosniff` + 1h public cache — status-independent
  by design (DRAFT owners need the preview; UUIDs are unguessable).
  `ListingResponse.imageUrl` (primary, null when none) stays for
  back-compat; `imageUrls` lists the whole gallery primary-first. The JSON
  listing-create contract stays non-multipart (published FE contract).
* **PUBLISH GATE** (owner decision, 2026-08-06): a status transition **TO
  ACTIVE requires a primary image** — 422 `primary_image_required`
  otherwise. Exactly one image is MANDATORY for a live listing; DRAFTs may
  stay imageless. The guard sits on the transition only (from != ACTIVE,
  to == ACTIVE), so listings that were already ACTIVE before V3 keep
  working and deactivation is never blocked.
* **Category taxonomy + condition + location** (V4): `listing.category`
  (free text) was replaced by `category_code` — a FK onto the
  migration-seeded two-level `category` table (10 top-level codes, ~36
  children; extend via a NEW migration, never at runtime — there is no
  admin CRUD). Requests carry `categoryCode` (optional → defaults `other`;
  normalized trim+lowercase; unknown → 400 `unknown_category`); responses
  carry `categoryCode` + resolved `categoryName`. `condition` is an
  enum column (`NEW`/`USED_LIKE_NEW`/`USED_GOOD`/`USED_FAIR`, default NEW,
  CHECK-constrained). `city`/`area` are optional jsoup-sanitized free text
  (blank→null). Public `GET /marketplace/categories` serves the tree
  (permitAll via a DEDICATED SecurityConfig matcher — the path is outside
  `/marketplace/catalog/**`; 1h public cache). Geo/radius search is future
  work — city is an exact lower(city) match only.
* **Catalog browse = conditional Criteria predicates, NEVER a null bind**:
  with four optional filters (q, category — a parent code expands to its
  children, condition, city) the browse uses a JPA `Specification` built
  by APPENDING a predicate per PRESENT filter. An absent filter contributes
  no predicate and therefore no bind — the rule the old
  one-query-per-combination repository methods existed for. Do NOT regress
  to a `(:q is null or lower(...))` nullable-param query: Postgres infers
  `bytea` for an untyped null bind and dies with "function lower(bytea)
  does not exist" at runtime (found by SecuritySurfaceIT in CI; invisible
  to mocked-repo tests). CatalogServiceTest pins the predicate structure;
  SecuritySurfaceIT + CatalogTaxonomyBrowseIT prove it against real SQL.
* **Verified-purchase reviews (V5)**: a review may ONLY be created by a
  CUSTOMER with a **PAID order containing the listing** — the gate queries
  `market_order` ⋈ `market_order_item` (status pinned to PAID in the JPQL,
  never a parameter) and stores the qualifying `order_id` on the review as
  provenance; no paid order → 403 `review_requires_purchase`. One review per
  buyer per listing (`existsBy` + the V5 unique index as race backstop, both
  surfacing 409 `review_already_exists` — the insert is `saveAndFlush`ed
  inside a catch so losing the race is never a 500). ANY listing status is
  reviewable — a delisted product was still bought. **Aggregates discipline
  (same as stock):** `listing.rating_sum`/`rating_count` are denormalized and
  updated ONLY via the atomic bulk UPDATE
  (`ListingRepository.adjustRatingAggregates`) in the same tx as the review
  write (create +rating/+1, edit ±delta/0, delete −rating/−1) — never
  read-modify-write through the entity; `ratingAvg` (1 decimal, null when
  unrated — never 0.0) + `reviewCount` ride every `ListingResponse` with zero
  extra queries. Public reads live on the catalog surface (GET-scoped
  permitAll, no new matcher): `GET /marketplace/catalog/{id}/reviews`
  (newest first) anonymizes the reviewer — constant `"Verified buyer"` plus
  the stable handle `Buyer-<4 hex of sha256(buyer_uuid)>`; the raw buyer uuid
  NEVER leaves the service on that surface. Merchant aggregate:
  `GET /marketplace/catalog/merchants/{id}/rating` (from the review table,
  never 404s). Deletes are author-or-SUPER_ADMIN (moderation removal, audited
  with `adminRemoval`). Audit REVIEW_CREATED/UPDATED/DELETED; metric
  `marketplace.reviews{outcome=created|rejected_unverified|duplicate}`.
* **Favorites (V6)**: CUSTOMER-only wishlist; PUT/DELETE
  `/marketplace/favorites/{listingId}` are IDEMPOTENT (composite-PK
  `INSERT … ON CONFLICT DO NOTHING` / 0-row DELETE — repeat = 200 no-op, and
  a repeat add never bumps the favorited-at ordering). Add requires the
  listing to exist, ANY status; `GET /marketplace/favorites` returns full
  listing summaries via `ListingViewAssembler`, newest-FAVORITED first,
  including each listing's CURRENT status so the FE can render "no longer
  available". **Deliberate choices:** NO audit rows for favorites
  (high-volume, low-sensitivity — auditing them would drown the
  tamper-evident chain in noise) and favorite counts are NOT exposed on any
  surface (merchant envy metric later, maybe).
* **Restock events (V6) + LIVE restock alerts**: when `stock_qty` moves
  **0 → >0** (merchant stock update, or an order cancel/expiry returning the
  last held units) the owning tx publishes the in-process `ListingRestocked`
  event; `favorite/RestockAlertListener` (`AFTER_COMMIT` + `@Async`, never
  fires for a rollback, never throws) now DELIVERS: each favoriter is
  notified via `UserNotifyGateway` (user-service owns contact + channel),
  capped per event (default 200, oldest favorite first; overflow logged +
  metered), gated by `marketplace.notifications.restock-alerts.enabled`
  (default true). Metrics `marketplace.restock_events` +
  `marketplace.notifications{type=restock_alert}`.
* **Reports + moderation queue (V7)**: `POST
  /marketplace/catalog/{listingId}/report` — any AUTHENTICATED user (401
  anonymous — spam control; the catalog permitAll is GET-scoped, pinned by
  SecuritySurfaceIT's `anonymousReportPostIsUnauthorized`); bounded `reason`
  enum + sanitized `detail`; ONE OPEN report per (reporter, listing) — V7
  partial unique index backstops the check, duplicate → 409
  `report_already_open`; audit LISTING_REPORTED (reason only, never free
  text). Moderation is SUPER_ADMIN under `/marketplace/reports` (plain
  `/marketplace/**` gateway route — NOT an internal surface, no deny route
  needed): GET queue (?status= filter, default OPEN, OLDEST first — FIFO so
  the oldest complaint never starves; rows carry a live listing summary
  batch-loaded, no N+1) and `PATCH /{id}` {action RESOLVE|DISMISS,
  resolutionNote, deactivateListing}. Resolving with `deactivateListing=true`
  sets the listing INACTIVE (always allowed — only activation is
  publish-gated) and audits LISTING_STATUS_CHANGED (`via: moderation`)
  alongside LISTING_REPORT_RESOLVED; `deactivateListing` on DISMISS is
  refused (400). Closed reports are terminal (409 `report_not_open`).
  Metrics `marketplace.reports{reason}`, `marketplace.reports.resolved{action}`.
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

## Notifications (fleet copies — do not invent wire contracts)

The `notify/` package is a FAITHFUL COPY of the ticketing fleet's proven
notification stack (booking-service clients, InnRewards' standalone-repo
carry pattern), package-renamed with `MKT-` reference prefixes and "InnBucks
Marketplace" wording. Every client is pinned by a standalone-WireMock
contract test — change the copy or the wire shape ONLY in lock-step with the
fleet originals.

**Channels + env vars (ALL optional — graceful degradation, copied from
booking):** blank creds = that channel disabled with a boot-time WARN
(`NotificationClientConfig.logChannelStatus`) and `outcome=disabled` metrics;
NEVER a crash, and deliberately NEVER boot-required in
`ProductionSecretsGuard` — a cell without notification creds must still take
orders.

* **InnBucks notification API** (`EmailNotificationClient` +
  delegating `SmsNotificationClient`): `POST /auth/third-party` bearer
  (cached until JWT `exp` −30s, ONE forced refresh-and-replay on 401),
  `X-Api-Key` on every call; SMS `{message, reference, destinationMsisdn}`,
  email `{subject, message, reference, destinationEmail}`; GSM-7
  transliteration (`SmsTextSanitizer`) on SMS bodies + email SUBJECTS
  (the gateway 400s on `! : / ? " * ;` and non-ASCII); auto `MKT-SMS-` /
  `MKT-EMAIL-` references, ~46-char reference clamp. Env: `BANK_API_URL/KEY/
  USERNAME/PASSWORD` — the SAME platform creds booking/payment already use.
* **WhatsApp gateway** (`WhatsAppNotificationClient`): `POST
  /api/messages/custom-notification`, lowercase `x-api-key`, 1600-char cap
  (REFUSED, not truncated). Fallback channel for the buyer order-paid SMS.
  Env: `WHATSAPP_GATEWAY_URL` / `WHATSAPP_API_KEY`.
* **Optional own-SMTP email** (`SmtpEmailSender` + local
  `BrandedEmailRenderer`/`EmailSignature` copies): active only when
  `MAIL_ENABLED=true` AND `MAIL_HOST` is set; presents "InnBucks
  Marketplace" as sender name, falls back to the notification API on
  failure. `management.health.mail.enabled=false` is LOAD-BEARING (an unset
  MAIL_HOST would otherwise 503 readiness — took user-service out 2026-07-29).
* **`UserNotifyGateway`** (event-service's OrganizerNotificationGateway
  pattern): `POST /users/internal/{uuid}/notify` on `lb://user-service`
  (`@LoadBalanced` builder — never host:port), `X-Internal-Token` from the
  existing `INTERNAL_API_TOKEN`, body `{subject, message}`; user-service owns
  per-user channel selection/fallback. Strictly best-effort: failures logged
  + metered, NEVER thrown.

**Triggers (all `AFTER_COMMIT` + `@Async` on the bounded
`notificationExecutor`, and NOTHING may escape a listener — an after-commit
exception would make a dead SMS gateway look like a failed payment confirm;
copied from the middleware/ticketing discipline):**

* **Buyer ORDER PAID** — `OrderTransitionService` publishes `OrderPaid` from
  the transition chokepoint (only PAID; a future confirm path cannot forget);
  `notify/OrderPaidNotificationListener` sends the SMS
  (`"Your InnBucks Marketplace order MKT-XXXX (USD 25.99) is confirmed. Ref
  MKT-XXXX"`), WhatsApp fallback when SMS fails and WhatsApp is configured.
  Metric `marketplace.notifications{type=order_paid,outcome=sent|fallback|
  failed|disabled}`.
* **RESTOCK ALERTS** — see the V6 invariant above.
* **MERCHANT NEW-PAID-ORDER** — `notify/MerchantOrderNotifier` groups the
  order's lines by listing `merchant_id` and notifies each merchant's admin
  users via `UserNotifyGateway` with THAT merchant's lines + subtotal.
  **DISABLED BY DEFAULT** (`marketplace.notifications.merchant-orders
  .enabled=false`): user-service has NO internal merchantId→admin-users
  lookup (verified 2026-08-06 — `/users/internal/merchants/assigned` returns
  merchant ids by role, not users), so the shipped `MerchantAdminResolver
  .Unavailable` resolves nobody. Enabling needs a SMALL user-service internal
  endpoint (users/uuids by merchantId, three-files-must-agree) + a real
  contract-tested resolver bean; the marketplace side (grouping, composing,
  fan-out) is complete and unit-tested.

**Copy discipline:** every template lives in `OrderNotificationComposer` and
MUST round-trip `SmsTextSanitizer` unchanged (`Ref MKT-...` and `" - "`,
never `Ref:` or an em-dash) — `OrderNotificationComposerTest` fails the build
otherwise. Money renders in MAJOR units for humans; storage/wire stays cents.
Never log a full MSISDN (`MsisdnMasking`) or a message body.

**Cell provisioning: NOTHING NEW.** `BANK_API_*`, `WHATSAPP_*` and `MAIL_*`
already live in the shared cell ConfigMap/Secret for booking/payment, and the
marketplace k8s Deployment `envFrom`s both — the channels light up on the
existing values. `NotificationFlowIT` is the proof the AFTER_COMMIT + async
wiring fires on a real PAID transition / restock (mock `@Primary` notify
beans, real Postgres + security chain).

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
