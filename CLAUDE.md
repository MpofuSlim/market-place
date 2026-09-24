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
  `CUSTOMER` (buyers), `MERCHANT_ADMIN` (sellers — **derived here from the
  ORGANIZATION claims, never taken from the roles claim**; see "A seller is an
  ORGANIZATION" below), and `SUPER_ADMIN` (fleet oversight: manages ANY
  merchant's listings — update/status/image upload+delete, no ownership
  check, no seller scope needed — reads ALL listings via
  `GET /marketplace/listings/mine` (optional `?merchantId=` filter) and ALL
  orders via `GET /marketplace/orders` (optional `?buyerUuid=`) plus any
  single order by id; **cannot place or cancel orders** — those stay
  CUSTOMER-only). **Merchant-side listing administration is
  MERCHANT_ADMIN-only — an explicit owner decision (2026-08-05); do not
  re-add SHOP_ADMIN without the owner asking.** Merchant scope comes from
  the JWT, NEVER from a request body — with ONE deliberate, owner-approved
  exception: SUPER_ADMIN creates listings ON BEHALF of a seller via the
  optional `merchantId` field on the create request — the seller
  ORGANIZATION's id, picked from user-service's `GET /admin/organizations`
  (400 `merchant_id_required` if omitted, since admin tokens carry no seller
  scope). For MERCHANT_ADMIN callers that field is refused whenever it
  differs from their scope (422 `merchant_scope_mismatch`), so the invariant
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

* **A seller is an ORGANIZATION (step 2 of the fleet organizations plan).**
  user-service no longer mints a `merchantId` claim for merchant admins (it
  was resolved from loyalty's `merchants.admin_email`: one person per
  merchant, none at all for someone running two businesses). `JwtFilter`
  now derives seller authority from the organization claims user-service V39
  mints: the session's `orgId`, when `orgRole` is OWNER or ADMIN and
  `products` holds `marketplace` (`sellingOrganizationOf`). That org id IS the
  seller scope — `AuthenticatedUser.merchantId` — and `MERCHANT_ADMIN` is
  added or dropped to match (`sellerRoles`).
  * **The role alone grants nothing.** A bare `MERCHANT_ADMIN`, a legacy
    `merchantId` claim, STAFF, a loyalty-only business, or a session that
    has not chosen among several organizations is refused at
    `@PreAuthorize` (403 `FORBIDDEN`) before any handler runs. Conversely an
    ADMIN colleague added through `/organizations` with no staff role at all
    IS a seller. Pinned by `JwtFilterSellerScopeTest` and the organization
    cases in `SecuritySurfaceIT`.
  * **The API name `merchantId` was kept on purpose** — every seller column,
    DTO field and query parameter uses it, and renaming would be a breaking
    change for no behavioural gain. Its VALUE is now an organization id.
    `shopId` on a verified principal is always null (a loyalty shop id means
    nothing beside an organization).
  * **Existing rows were minted with loyalty merchant ids**, so a cell
    switching over needs its seller columns remapped (or reset) in the same
    deploy — see `docs/fleet-wiring.md`. Nothing here can do it: this service
    never knew which organization owned a loyalty merchant.
  * `merchant_scope_missing` stays as defence in depth for a principal built
    without a scope; through the real filter it is now unreachable, because
    no scope means no `MERCHANT_ADMIN`.

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
* **The payer is the CALLER**: `OrderService.resolveBuyerMsisdn` takes the
  order's `buyerMsisdn` from the JWT's `phoneNumber` claim whenever the token
  carries one (every real CUSTOMER login does) and reads the body field ONLY
  for a phone-less token. This number is what payment-service treats as the
  payer, and on the EcoCash rail it is the phone that receives the PIN prompt —
  read from the body alone, any authenticated buyer could push a "pay $X"
  prompt to any number they typed. `buyerMsisdn` stays on the DTO (optional,
  back-compat) but is never authoritative for a customer. Same fix, same
  reasoning as payment-service's deprecated `ShopCheckoutRequest.msisdn`.
  `OrderServiceTest` pins all four combinations.
* **A refused order names EVERY failing line.** `OrderService.createOrderTx`
  collects unavailable AND short-stocked lines into `OrderLineRejection`s and
  throws once with all of them in `ApiException.details` → the envelope's
  `data.rejections`. It used to abort at the first bad line, so a cart with two
  problems cost the customer two round-trips and the second problem only
  appeared after they fixed the first. **The top-level status/code/message are
  deliberately byte-identical to the old first-failure behaviour** (`refusalFor`
  reproduces both incidental orderings: availability beat stock whatever the
  positions, and among stock failures the SMALLEST listing id won, because
  `reserveStock` iterates id-sorted and was the thrower) — so this is purely
  additive and no existing client sees a change. The stock pre-check is
  **advisory**: `reserveStock`'s atomic UPDATE is still the authoritative guard,
  and a line that passes the pre-check but loses the race still gets the plain
  409 with no `details`. `ApiException.details` is null everywhere else and
  `ApiResult` is `@JsonInclude(NON_NULL)`, so every other error body is
  unchanged. Reasons are STRINGS with constants on `OrderLineRejection`, never
  an enum — a client meeting an unrecognised one must render it, not choke.
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
  Browse takes a `BrowseQuery` RECORD, not positional arguments, so adding a
  filter cannot silently shift the meaning of an existing call site.
* **Browse sort, price window, in-stock and seller filter**: `sort` is
  `newest` (default) / `price_asc` / `price_desc`; `minPriceCents` +
  `maxPriceCents` are an INCLUSIVE window in minor units; `inStock=true`
  hides listings sitting at `stockQty = 0` (an ACTIVE listing legitimately
  can); `merchantId` is "more from this seller".
  * **Every ordering ends with the same total-order tiebreaker**
    (`createdAt DESC, id`). A sort on a non-unique column is only a PARTIAL
    order, and Postgres may return tied rows in a different sequence per
    query — so paging a catalogue where many items share a price silently
    repeats some listings and skips others. The bug is invisible on page 1
    and reads as "the catalogue is broken". `id` last makes it total.
  * **`inStock=false` means "don't filter", never "show me the sold-out
    ones"** — hence `Boolean` and a `Boolean.TRUE.equals` check, not a
    primitive.
  * **An inverted or negative price window is a 400, not an empty page.**
    An empty page is indistinguishable from "nothing is for sale in your
    budget" and sends the client hunting for a data problem that does not
    exist.
* **An unrecognised query parameter on BROWSE is a 400
  `unknown_parameter`** (`catalog/util/QueryParams.rejectUnknown`), naming
  both the offender and the full supported set. Spring silently ignores an
  unbound parameter, which on a FILTERED endpoint is the worst default: a
  filter the client believes it applied contributes nothing and the response
  is a confidently wrong result set with a 200 on it. Keep
  `CatalogController.BROWSE_PARAMS` in lock-step with the `@RequestParam`
  names. **Deliberately browse-only** — on an unfiltered endpoint a stray
  parameter can get nothing wrong, and refusing there would break callers
  for no benefit. Same reasoning drives the FILTER parsers refusing garbage
  (`invalid_price`, `invalid_boolean`, `invalid_merchant_id`, `invalid_sort`)
  while PAGINATION keeps its forgiving fallback: a wrong page index only
  shows the wrong slice of the same result set.
* **`GET /marketplace/catalog/merchants/{id}` is the public seller header** —
  badge + aggregate rating + ACTIVE listing count in ONE call, where the app
  previously needed three round trips and a client-side join (and so rendered
  a bare UUID). The rating comes from `ReviewService.merchantRating`, not a
  second aggregate query, so this and the sibling `/rating` endpoint can never
  disagree. **It never 404s** — an unknown merchant is a zeroed, nameless
  profile, because a 404 would make the public catalogue an oracle for which
  merchant ids exist. `activeListingCount` uses
  `countByMerchantIdAndStatus(..., ACTIVE)`, NOT `countByMerchantId`, which
  includes DRAFT and ARCHIVED rows the buyer surface hides. **Logo and return
  policy were requested and are deliberately absent**: this service stores
  neither and no other in the fleet does, so a fabricated value would be a
  promise the platform has no basis to make. The requested RESPONSE TIME is
  the one that graduated: V9's fulfilment trail made an honest equivalent
  computable, and the profile now carries it — see the seller-stats bullet
  below.
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
* **The buyer journey is complete end to end (V9)**: browse → cart →
  delivery address → checkout quote → order → pay → fulfilment → confirm
  receipt → review. Four load-bearing decisions hold it together:
  * **One resolver decides "can this be bought, and what does it cost"** —
    `checkout/CheckoutPricer`, shared by the cart read, the checkout quote AND
    order creation. Those are the three screens a shopper sees in a row; a cart
    that says "in stock", a quote that prices it and an order that then refuses
    it is exactly what independent copies of that rule produce as they drift.
    It reuses `OrderLineRejection`'s reason vocabulary rather than inventing
    per-surface ones. Still **advisory** — `reserveStock`'s atomic UPDATE is the
    authoritative guard.
  * **The cart stores QUANTITIES and nothing else**, and holds NO stock. Price,
    stock and status resolve live on every read: a cart sits for weeks, and a
    copied price becomes a promise the catalogue no longer makes. A cart that
    reserved would let anyone freeze a merchant's inventory for free. A line
    that goes out of stock STAYS visible carrying its `issue` and contributing
    0 — dropping it silently is how a shopper reaches checkout with a total
    they do not recognise. Add ACCUMULATES and clamps at the per-item cap (the
    `+` button wants the cap); PUT sets an EXACT quantity and refuses above it
    (the shopper named that number); quantity 0 is refused so a zero can never
    be a silent delete on a retry.
  * **`POST /marketplace/checkout/quote` reserves NOTHING.** Before it, the only
    way to learn an item was out of stock was to CREATE an order — which holds
    stock as a side effect, so "let me just check the total" cost a merchant an
    inventory hold, and comparing delivery against collection meant placing two
    orders. A problem basket is a **200** with `checkoutReady: false` and every
    failing line in `rejections`: the shopper has to SEE the basket to fix it.
    Only a malformed request or a missing address is an error.
  * **An unstated `deliveryMethod` is COLLECTION**, which is precisely what an
    order meant before delivery existed here (V9 backfills every pre-existing
    row the same way) — so an un-updated client keeps behaving identically
    instead of being 400ed for an address it does not know to send. Delivery is
    an explicit choice: it costs money and needs somewhere to go.
* **The order's destination is a SNAPSHOT, never a reference.** `delivery_*`
  columns on `market_order` copy the chosen `delivery_address` row; the
  `delivery_address_id` kept beside them is provenance only and nothing reads
  through it. Same discipline as `title_snapshot` one level up: a buyer who
  edits "Home" after ordering must not silently redirect a parcel already on
  its way, and deleting the book entry must not erase where a delivered order
  went. The DB refuses a `DELIVERY` order with no destination
  (`chk_order_delivery_destination`) rather than trusting every future write
  path to remember. Exactly one default address per buyer — first-saved wins,
  promotion demotes the incumbent FIRST, deleting the default promotes the most
  recent survivor, and `uq_address_default_per_buyer` backstops all three.
* **Money on an order is a SPLIT**: `total_cents = subtotal_cents +
  delivery_fee_cents`, CHECK-enforced. `total_cents` stays the SINGLE number
  payment-service collects, so its internal contract is unchanged and needed no
  coordinated deploy. Since V14 `delivery_fee_cents` is the SUM of each
  seller's fee to the buyer's town — see "Delivery towns, per-seller fees and
  parcel tracking (V14)" below. The old flat per-cell
  `MARKETPLACE_DELIVERY_FEE_CENTS` is gone.
* **Fulfilment is PER SELLER and a SEPARATE lifecycle from payment (V9)**:
  `order_fulfilment`, one row per `(order, merchant)`, opened in the SAME
  transaction as the PAID transition (idempotent via the unique index, because
  payment-service is free to replay a confirm). An order that committed as paid
  with nothing on any seller's queue would be money taken for goods nobody was
  asked to send.
  * **Deliberately NOT extra `OrderStatus` values.** `PENDING_PAYMENT` is what
    payment-service reads to decide an order is payable and `PAID` is what the
    verified-purchase review gate queries — a delivered order that had moved on
    from PAID would silently stop being reviewable. Payment state and
    fulfilment state are different questions about the same order.
  * `PREPARING → DISPATCHED → DELIVERED`, with `PREPARING → DELIVERED` legal
    (goods handed over in person never pass through a dispatch). DELIVERED is
    terminal; an illegal move is refused 409 + counted, never applied. The
    mutation that goes with a move runs only AFTER the legality check —
    structurally, not carefully.
  * **The order-level `fulfilmentStatus` is the LEAST advanced parcel**, so
    DELIVERED always means everything arrived; `FulfilmentStatus`'s ordinal
    order IS the advancement order that roll-up depends on. Null (never
    PREPARING) when there are no parcels.
  * `deliveredBy` keeps BUYER and MERCHANT apart: a buyer's confirmation is
    evidence a dispute can lean on in a way the seller's own say-so is not. The
    seller can close a **DELIVERY** parcel themselves — a buyer who never opens
    the app must not leave a courier delivery open forever — and the buyer is
    SMSed when they do (see "A seller's word" below).
  * **A seller's word never closes a COLLECTION.** `POST /{id}/delivered` on a
    COLLECTION order is `409 collect_code_required`, for EVERY caller,
    SUPER_ADMIN included. A collection happens at the seller's own counter, so
    "the buyer collected" is the one claim a seller could make about goods still
    on their shelf — and it is also the one case where the platform can do
    better than take their word: the buyer's collection code (no seller surface
    ever sees it) or the buyer's own "received". An operator marking a counter
    handover they did not witness is the same unprovable claim, hence no admin
    override. The exit for a buyer who never came is `DISPATCHED → UNFULFILLED`
    ("not collected"), legal on COLLECTION orders only — see the V12 bullet
    below. Without that exit this rule would strand a no-show forever: goods on
    the shelf, money HELD, nobody able to end it. `FulfilmentStateMachine` is
    method-aware (`isLegal(from, to, method)`) so the extra edge cannot leak onto
    a courier delivery. Pinned by `FulfilmentServiceTest`,
    `FulfilmentStateMachineTest`, `EscrowFlowIT.aCollectionCannotBeSelfClosed`
    and `UnfulfilledParcelFlowIT.aCollectionNoShowClosesAsNotCollected`.
  * One vocabulary for both delivery methods: on a COLLECTION order DISPATCHED
    reads "ready to collect" and DELIVERED reads "collected". The app has the
    order's `deliveryMethod` and labels accordingly; two parallel vocabularies
    would double every state machine, query and dashboard for a wording
    difference.
  * The seller's view carries the DESTINATION and only THEIR lines and
    subtotal — never the order total — so a seller in a multi-seller order
    learns nothing about what else the buyer bought. A separate DTO from the
    buyer's view rather than one with fields blanked, because that is how the
    destination eventually leaks onto the wrong surface.
* **`market_order_item.merchant_id` is a SNAPSHOT (V9)** and is what the
  fulfilment queue and `MerchantOrderNotifier` group on. Joining back to the
  live listing is a lie waiting to happen — a listing can be archived or
  transferred, and the seller who must pack and be paid is the one who was
  selling AT ORDER TIME. (The notifier's old join also silently DROPPED any
  line whose listing could not be read.)
* **Seller trust stats are COMPUTED, never asserted** (`fulfilment/
  SellerFulfilmentStatsService`, on the public profile as `fulfilment` and on
  the seller's own `GET /marketplace/fulfilments/stats`). Three figures, all
  off real `order_fulfilment` rows: `completedOrders` (delivered parcels),
  `medianDispatchHours` (median `paid_at → dispatched_at`; MEDIAN so one
  forgotten parcel cannot poison a same-day seller, ROUNDED UP with a floor
  of 1 — the platform understates speed, never flatters), and
  `buyerConfirmedPercent` (share of completed parcels closed by the BUYER —
  strength-of-evidence, not a score). Discipline that holds it together:
  * **Silent below the sample floor** (`marketplace.seller-stats.min-sample`,
    default 5): each figure is null until it rests on enough parcels — "100%
    confirmed" over two orders is noise wearing a percentage — and the whole
    block is ABSENT for a seller with no completed parcel, so a new seller
    renders "new seller", never a zero that reads like a verdict. Mirrors the
    `ratingAvg`-null-when-unrated stance.
  * Parcels closed straight from PREPARING (handed over in person) are
    excluded from the dispatch median — nothing was dispatched — but count as
    completed; an OPEN DISPATCHED parcel counts toward the median (its
    dispatch already happened). Rows whose `dispatched_at` precedes `paid_at`
    (corrected/backfilled clocks) are dropped from the median, never fed in
    as negative durations. All pinned by `SellerStatsIT` against real SQL
    (`percentile_cont` + `FILTER` counts are invisible to mocked repos).
  * The seller's own view wraps the SAME public object plus their private
    queue counts (`awaitingDispatch`, `inTransit`) — one computation, so "why
    does my profile say 2 days?" is answered by the very number the shopper
    sees. A MERCHANT_ADMIN's `merchantId` filter is IGNORED (cannot widen own
    scope); SUPER_ADMIN must name one (`merchant_id_required`).
* **Escrow / Buyer Protection (V10): the settlement ledger is a RECORD of the
  money, never a mover of it.** payment-service collects every marketplace
  order into the shared platform account (MKT tag); `merchant_settlement` is
  what makes "the seller only gets paid when you get your goods" true — one
  row per PARCEL (V9's `order_fulfilment` already IS one seller's share of one
  order), keyed by `fulfilment_id` with a unique index so a replayed payment
  confirm can double neither parcels nor settlements (`openIfAbsent` =
  `INSERT … ON CONFLICT DO NOTHING`). Payouts and refunds are OPERATOR actions
  executed on the rails and RECORDED here with their references — the rails
  have no reversal API, and a ledger that pretended to move the money would be
  lying.
  * **State machine** (`SettlementStateMachine`, same discipline as orders/
    fulfilments — validate BEFORE mutate, illegal moves 409
    `illegal_settlement_state` + counted, terminals immutable): HELD →
    {RELEASABLE, DISPUTED}; RELEASABLE → {PAID_OUT, DISPUTED}; DISPUTED →
    {RELEASABLE, REFUNDED}. RELEASABLE → DISPUTED is deliberate: confirming
    receipt does not sign away the dispute — only PAID_OUT does (the money has
    left).
  * **Release follows the EVIDENCE.** The buyer's own receipt confirmation
    releases IMMEDIATELY (`released_at` stamped, no wait). A seller's
    self-close only starts the grace clock (`releasable_at = now + `
    `marketplace.settlement.grace-hours`, default **168**) — the
    `SettlementReleaseSweeper` (ShedLock, per-row isolation, re-checks state
    AND clock inside each row's tx so a dispute landing mid-sweep wins)
    promotes it after. Delivery NEVER unfreezes a DISPUTED row.
  * **The grace MUST cover the buyer's dispute window, and the service refuses
    to boot otherwise** (`SettlementService.requireGraceCoversDisputeWindow`).
    It was 48h against a 7-day window: a seller-closed parcel became payable on
    day 2, and once PAID_OUT a dispute is refused
    (`settlement_already_paid_out`), so a buyer reporting a parcel that never
    came on day 3 found the money gone — the seller's word, unchallenged for two
    days, was enough to be paid. Equal windows mean the money becomes payable
    exactly when the buyer's right to object ends. Rows already HELD with a
    48h `releasable_at` keep it (nothing rewrites them); only new self-closes
    get the longer clock.
  * **A seller's word is announced to the buyer.** Dispatch and a seller's own
    "delivered" publish `ParcelProgressed` → `ParcelProgressNotificationListener`
    (AFTER_COMMIT + `@Async`, never throws, SMS with WhatsApp fallback,
    `marketplace.notifications.parcel-updates.enabled`, default ON). The
    "delivered" copy names the dispute window in days: a window nobody knows is
    running protects nobody. The buyer's own close and a redeemed code send
    nothing — the person on the other end already knows. The dispatch note is
    deliberately NOT relayed: it is seller free text, and an SMS carries the
    platform's name (a note saying "pay the courier USD 5" would read as our
    instruction). Metric `marketplace.notifications{type=parcel_dispatched|
    parcel_delivered}`.
  * **gross/commission/net are all STORED and CHECK-enforced**
    (`net = gross - commission`); commission is
    `marketplace.settlement.commission-percent`, **0 by default** — charging
    one is a config change, not a migration. **Since V14 the seller's
    delivery fee IS in their settlement** (`gross = goods + delivery`, the
    split kept in `delivery_fee_cents`): the fee is now per SELLER, fixed at
    order time, so there is nothing to allocate — the seller who drove the
    parcel is owed it, and it is held and released with the goods.
    **Commission is charged on the goods only**, never on the delivery fee.
  * **Disputes are the buyer's half** (`POST /marketplace/orders/{id}/
    fulfilments/{fid}/dispute` — buyers think in orders, not settlements):
    owner-masked 404s, PAID orders only, bounded reason enum + sanitized
    detail (free text NEVER in the audit trail — V7 stance). An UNDELIVERED
    parcel is disputable however old ("it never arrived" IS the refund path);
    a delivered one for `dispute-window-days` (default 7) after delivery.
    **One dispute per parcel, EVER** (unique index backstop) — a buyer who
    could re-dispute after a release could freeze a seller's money in a loop.
    The operator resolves exactly once (FIFO queue, oldest first): RELEASE →
    money back to RELEASABLE for the next run; REFUND → recorded with the
    operator's transfer reference. The buyer is told either way via
    `DisputeResolved` → AFTER_COMMIT + `@Async` listener (never throws,
    `UserNotifyGateway`).
  * **Payout is per MERCHANT, per run** (`POST /marketplace/settlements/
    pay-out`): every RELEASABLE row of one merchant under ONE payout
    reference — the shape finance actually pays in. An empty run is refused
    (409 `nothing_releasable`). **ONE audit event per payout run**, not per
    row (a batch of per-row audits would serialise on the chain-head lock);
    the per-parcel trail is each order's journal — settlement transitions ride
    `market_order_event` with `kind = SETTLEMENT` (V10 widened the CHECK).
    Plain deliveries/releases are NOT audited (already evidenced by the
    fulfilment trail); dispute open/resolve and payout runs ARE.
    `GET /marketplace/settlements/payout-report` is the finance CSV (biggest
    owed first, date in the FILENAME — fleet CSV rule).
  * **Scoping is the fulfilment queue's, verbatim**: MERCHANT_ADMIN always
    reads their own claim (`merchantId` param IGNORED — cannot widen scope),
    SUPER_ADMIN reads all and must NAME a merchant for the summary. The
    seller's parcel view carries `settlementStatus` + `settlementNetCents`;
    the buyer's order view carries the parcel's `dispute` — each surface
    answers its own "where is my money / my complaint?" without a second
    call.
  * **V10 backfilled honestly**: existing PAID parcels got settlements —
    buyer-confirmed → RELEASABLE (released at the delivery moment),
    seller-closed → HELD with `releasable_at = delivered_at` (the first sweep
    after deploy promotes what genuinely lapsed — the migration does not
    guess the configured grace), everything else HELD.
* **Gifting + the collection handover code (V11): an order can be FOR someone,
  and a handover can be PROVEN.** The marketplace could only be bought from for
  yourself — a diaspora buyer paying for their mother's groceries had nowhere to
  say whose they were, the recipient was never told anything, and at the counter
  the seller had no way to know who was entitled to collect. `market_order`
  gained `recipient_name` / `recipient_msisdn` / `gift_message` (all nullable —
  **nothing is defaulted from the buyer**, because every surface reads "has a
  recipient" as "is a gift"), and a COLLECTION parcel can carry a code.
  * **The recipient is NOT an account and never becomes one**: a name the goods
    are handed to and a number we can message. Making them a party to the order
    would mean deciding what a stranger may see of someone else's purchase, and
    nothing here needs that. Their number rides the same `Msisdns` as the payer's
    (refusal names `recipient.msisdn`), and it is returned in full ONLY on the
    buyer's own order view — the seller gets `collectorName`, never the number.
  * **`CollectCodes`: 12 Crockford base32 characters = 60 bits of `SecureRandom`,
    stored as a plain SHA-256.** The entropy is what licenses an unkeyed hash
    against the fleet's HMAC rule for low-entropy secrets — an OTP's million-value
    space is a dictionary, 2^60 is not — so **no new boot-required secret and no
    cell provisioning change**. Inbound, confusables are folded (`I`/`L` → `1`,
    `O` → `0`) and grouping/case ignored, so a code read imperfectly off a screen
    still verifies.
  * **The plaintext exists in exactly two places, neither at rest**: the mint
    response to the buyer (`POST /marketplace/orders/{id}/fulfilments/{fid}/
    collect-code`) and the SMS to whoever collects. **No merchant surface has
    ever seen it** — a seller who could read a code could redeem it themselves
    and take the instant payout with the goods still on the shelf; pinned by
    `GiftFlowIT`. Lost code = mint again, which replaces the live one.
  * **A redeemed code is the THIRD kind of delivery evidence**
    (`DeliveryConfirmer.RECIPIENT`, V11 widened the CHECK) and releases escrow
    IMMEDIATELY, like a buyer's own confirmation. It is also the ONLY way a
    seller can close a collection themselves — see "A seller's word never
    closes a COLLECTION" above.
  * **The wrong-code budget is counted in its OWN transaction**
    (`CollectCodeAttempts`, `REQUIRES_NEW` + bulk UPDATE). A counter written on
    the refusing transaction is rolled back by the very exception it counts, so
    the budget never moves however many codes are tried — the middleware's
    failed-PIN lesson, imported rather than rediscovered. The cap matters
    because the only party who can submit a candidate is the seller holding
    that parcel. Exhausting it locks the parcel (the buyer mints a fresh one)
    and writes ONE `COLLECT_CODE_LOCKED` audit row — self-limiting, since the
    lock short-circuits every later attempt before the compare.
  * **Minting is deliberately NOT `@Transactional`**: one row write plus a call
    to an external SMS gateway, and wrapping them together would hold a pooled
    connection open across somebody else's network call. The send is inline and
    best-effort (the buyer is watching the screen and already holds the code in
    the response, so `sentTo` reports what actually happened) — deliberately
    neither the OTP posture (roll back on failure) nor the order-paid one
    (after-commit async).
  * **`buyerConfirmedPercent` now counts BUYER *and* RECIPIENT closes.** The
    figure has always measured "a close the seller did not perform themselves";
    counting only BUYER would have made the stat FALL for every seller who
    verified a handover properly. The published field name is unchanged.
  * **Cost:** a gift order sends one extra SMS on payment (the recipient's
    notice — without it the gift is invisible to the one person it is for), and
    one per code mint. Watch `marketplace.collect_codes{outcome=invalid}`: a
    seller mistyping is ordinary, a climb is somebody working the keyspace.
* **A parcel can end without arriving, and stuck money gets noticed (V12).** V9
  gave a parcel three states and V10 put the buyer's money behind delivery —
  together a silent trap. A seller who was out of stock had NO action available
  (the queue offered dispatch and delivered, nothing else), so the honest answer
  was to do nothing; and doing nothing left the settlement HELD where **no timer
  could reach it**, because `SettlementReleaseSweeper` matches a `releasable_at`
  that only a delivery sets. Money taken, goods never sent, nobody watching.
  * **`FulfilmentStatus.UNFULFILLED` is terminal and reachable from PREPARING —
    and, on a COLLECTION order only, from DISPATCHED.** Once a DELIVERY parcel
    is DISPATCHED the goods are with a courier and "I cannot fulfil this" has
    stopped being true — that is a delivery failure, which the buyer's dispute
    already covers with an operator looking at it. A DISPATCHED collection is
    "ready at the counter": the goods never left, so a buyer who never came is
    genuinely unfulfilled, and it is closed as "not collected" (journal detail
    `Not collected: <reason>`, audit `notCollected: true`,
    `ParcelUnfulfilled.notCollected` so the buyer's SMS says it was not picked
    up rather than that the seller could not supply it).
    **Not `CANCELLED`**: `OrderStatus` spends that word on a buyer abandoning an
    unpaid order, and the two facts have opposite consequences.
  * **`rollUp` EXCLUDES UNFULFILLED rather than ranking it**, so
    `FulfilmentStatus`'s ordinal run stays the three-stage journey and
    UNFULFILLED sits after it. Ranking it least-advanced would pin a two-seller
    order on "unfulfilled" while the other half ships; ranking it most-advanced
    would let DELIVERED claim everything arrived. An order whose parcels are ALL
    declined rolls up to UNFULFILLED; no parcels is still null.
  * **The decline does three things in one transaction**: closes the parcel with
    the seller's reason, hands the stock back (`ParcelStockReturner.returnOnce`,
    guarded by the per-parcel `stock_returned` — the sibling of
    `market_order.stock_released`, which is per ORDER and owned by
    cancel/expiry, so a later expiry cannot restock this parcel's units twice),
    and turns the money around. A 0 → >0 move still publishes `ListingRestocked`,
    so favoriters are alerted exactly as any other restock.
  * **`SettlementStatus.REFUND_DUE` is the mirror of RELEASABLE**, and exists
    for the same reason: this service moves no money, so the ledger needs
    "decided, not yet transferred". Going straight to REFUNDED would record a
    refund the operator has not made — and REFUNDED carries a
    `refund_reference` precisely because it means the money actually left.
    `POST /marketplace/settlements/{id}/refund` (SUPER_ADMIN) closes one row
    against that reference, **per parcel rather than batched like the payout
    run**: a payout is one transfer to one merchant covering everything
    cleared, while a refund goes back to the individual buyer of one order, and
    a single batched reference would be proof to none of them. **It accepts
    REFUND_DUE only**: DISPUTED → REFUNDED is a legal machine edge, but only
    the dispute resolve may take it — refunding a DISPUTED row here would
    orphan the OPEN dispute (both resolve actions then become illegal
    transitions from REFUNDED and the FIFO queue is pinned forever), so it is
    refused 409 `settlement_disputed` pointing at the dispute queue. Dispute
    rows (queue, open, resolve responses) carry `netCents`/`currency` from a
    batched settlement read — the operator decides over a named amount, and
    there are NO partial outcomes: RELEASE or REFUND, always the whole parcel.
  * **Only HELD money turns around.** A parcel whose settlement is already
    DISPUTED, RELEASABLE or PAID_OUT still CLOSES — refusing would leave the
    parcel open, which is the exact state being fixed — but the money is left
    alone, and `ParcelUnfulfilled.refundQueued()` is false so the buyer's SMS
    says "our support team will be in touch" instead of naming an amount the
    ledger never queued. A buyer disputing money already REFUND_DUE gets a
    clean 409 `refund_already_due`, not an illegal-transition error.
  * **`StaleEscrowSweeper` REPORTS and does not DECIDE.** Auto-releasing would
    pay a seller who never delivered; auto-refunding would punish one who is
    merely slow; and either transfer is an operator's to make. So its whole
    output is the gauge `marketplace.settlements.stale` (registered at
    construction, so the series exists and reads 0 from boot — an alert cannot
    fire on a series that is not there yet), a WARN naming the oldest row, and
    `GET /marketplace/settlements/stale` to work the list.
    `marketplace.settlement.stale-after-days` (default 14) is the threshold.
  * **V12 backfills nothing, deliberately** — same call V9 made backfilling
    parcels as PREPARING. Marking an existing row UNFULFILLED or REFUND_DUE
    would assert a seller's intent the migration never observed. What those rows
    get instead is VISIBILITY: the stale sweep surfaces the genuinely overdue
    ones and an operator decides one at a time.

* **A seller has a payout DESTINATION (V13) — the one fact a payment cannot be
  made without.** V10 built the escrow ledger and V12 the refund side, so the
  platform knew precisely who was owed and how much. It never knew where to
  send it: `GET /marketplace/settlements/payout-report` carried merchantId,
  displayName, parcels, netCents, currency, and an operator then had to find
  that seller's bank details somewhere outside this system — an email, a
  spreadsheet, a WhatsApp message.
  * **On `marketplace_seller`, not a table of its own**: a destination is a
    property of the seller, exactly one per seller, and V8 already keyed that
    record by `merchant_id`. Two rails (`MOBILE_MONEY` / `BANK`), each owning
    its own columns.
  * **`payout_account_name` is NOT `display_name`.** The trading name is what
    shoppers see; this is the name the destination account is held in, which is
    what finance checks a transfer against and what a bank rejects a payment for
    not matching. They differ routinely — "Rudo Traders" paying into
    "R. Chikwanha".
  * **Complete or absent, never half** (`chk_seller_payout_destination`). A
    method with no account behind it reads as configured on every screen and
    fails only when a transfer is attempted; the CHECK makes that
    unrepresentable rather than trusting each write path, the same call V9 made
    with `chk_order_delivery_destination`. The API replaces the whole
    destination for the same reason — there is no partial update, and "change
    just my account number" is not a smaller action than "change where my money
    goes".
    **V17 closed a NULL hole in it.** As V13 wrote it, a row with NO method
    but carrying ONE rail's COMPLETE details (`payout_account_name` +
    `payout_msisdn`, or `payout_account_name` + `payout_bank_name` +
    `payout_account_number`) passed. With the method NULL,
    `payout_method = 'MOBILE_MONEY'` / `= 'BANK'` is UNKNOWN; every other
    conjunct in that branch was TRUE, so the whole CHECK was UNKNOWN, and a
    CHECK that evaluates to UNKNOWN passes. Exactly those two shapes: a
    partial set was always refused (`FALSE AND UNKNOWN` is FALSE), confirmed
    over all 48 method × detail combinations on real Postgres. The method
    branches now open with `payout_method IS NOT NULL`, which makes every
    branch definite. The impact was low: no write path produces either shape,
    and every read path gates on the method, so such a row read as "not
    configured". V17 clears any stray details it finds before re-adding the
    CHECK, and logs a WARNING if it touched a row (printed before commit, so
    trust it only beside V17's success row in `flyway_schema_history`); it
    leaves the `payout_updated_*` stamps. **V17's own header comment says "account
    details with no method" generally. That overstates it, and this note is
    the precise statement.** The file cannot be corrected in place: it has
    shipped, and Flyway checksums comments too, so any edit makes every
    database that already ran it refuse to boot. **When you write a CHECK over
    nullable columns, put an explicit `IS NOT NULL` wherever a branch compares
    one** (V16's `chk_fulfilment_unfulfilled_by` was written with the same
    explicit `IS NOT NULL` for the same reason). Pinned by
    `PayoutDestinationFlowIT.detailsWithoutAMethodAreRefused`, whose two
    regression pins are exactly the two hole shapes.
  * **The seller sets their own** (`PUT /marketplace/sellers/me/payout-destination`,
    scoped by SHAPE — no path or query parameter names a merchant, so there is
    nothing to point at someone else's bank details). SUPER_ADMIN has an
    override at `/marketplace/admin/sellers/{merchantId}/payout-destination` for
    a phoned-in detail or a correction; the audit records which it was.
  * **A missing destination does NOT block a payout run, deliberately.**
    `payOutReleasable` RECORDS a transfer the operator already made on the
    rails; refusing to record one because this service holds no address would
    leave the ledger saying RELEASABLE after the money left, and the next run
    would pay those parcels twice. A ledger that cannot record a payment that
    happened is worse than one holding an incomplete address book. The
    destination is surfaced where it changes an outcome instead: on the report,
    read in the moment BEFORE money moves. Such a seller still APPEARS on the
    report with empty destination columns — dropping the row would hide a seller
    who cannot be paid rather than surface them.
  * **Nothing is masked, anywhere it is read.** A masked account number cannot
    be paid into, and masking a seller's own details for the person who typed
    them makes "is this the right account?" unanswerable — the one question the
    screen exists for. Both surfaces are already narrow (the seller's own, and
    SUPER_ADMIN); the public merchant profile builds its DTO field by field and
    cannot pick these up. It is PII, not a secret: it must be read back to pay
    someone, so the fleet's keyed-HMAC rule does not apply and hashing it would
    make the feature impossible.
  * **Redirecting a payout is THE attack on this**, so two controls, neither of
    them a hold policy nobody asked for: the seller's admin users are notified
    on every change (via the same merchantId→admin-user chain the paid-order
    notifier uses — `AFTER_COMMIT` + `@Async`, never throws), and the report
    carries `payoutChangedAt` so finance can see a destination that moved
    yesterday. **The SMS names the RAIL and never the account** — quoting the
    new destination would hand an attacker holding the phone a confirmation
    receipt. A FIRST destination gets no "contact support" line: telling someone
    to check something they just created is how people learn to ignore the line
    that matters.
  * **The audit row carries the method and never the account** (V7's free-text
    stance applied to money): an account number in the audit log is an account
    number in one more place, and the method plus who-and-when is what a
    redirect is investigated with.
  * `payoutDestinationConfigured` rides the seller's own
    `GET /marketplace/settlements/summary` — the screen a seller is on when
    "where is my money" matters to them, and the only place they would learn
    they need to provide one.
  * **V13 backfills nothing** — no destination was ever collected, so every
    existing seller correctly has none.

* **Delivery towns, per-seller fees and parcel tracking (V14).** A seller now
  says WHERE they deliver and what it costs; a buyer can only choose DELIVERY
  when every line reaches their town; and a parcel on the road can be tracked
  by code and on a map.
  * **Towns are a controlled, migration-seeded list** (`delivery_town`,
    `GET /marketplace/delivery-towns`, public, 1h cache), filtered by
    `innbucks.country` and cached for the life of the process by
    `DeliveryTownCatalog` (nothing can write the table, so no invalidation).
    Free text on both sides never matches ("Harare" vs "harare cbd"). Extend
    with a NEW migration, like categories.
  * **A listing's coverage is its own table** (`listing_delivery_town`: town +
    fee). No rows = collection only; there is deliberately NO `deliverable`
    column to drift out of step — `ListingResponse.deliverable` is derived.
    On update, `deliveryTowns` is the ONE field a full replace leaves alone
    when omitted (null keeps, `[]` clears, a list replaces) — clients built
    before V14 would otherwise wipe every seller's towns on their next edit.
    Unknown town → 400 `unknown_town`; a town twice → 400
    `duplicate_delivery_town`; both refused before the listing is written.
  * **Addresses name a town** (`delivery_address.town_code`): `townCode` wins,
    else `city` is matched to a town NAME ignoring case (so an un-updated app
    sending `"city":"Harare"` keeps working), else 400 `unknown_town` — never
    stored as an address nobody can deliver to. V14 backfilled codes from
    city names; an address that matched none keeps a null town and a DELIVERY
    checkout on it is 422 `address_town_required` rather than a guess.
    **The `unknown_town` message on the address path is CUSTOMER copy.** An app
    that still sends free text shows it verbatim to whoever typed "Byo" or
    "Harare CBD", and it once read "Choose the town from the list - GET
    /marketplace/delivery-towns" on a shopper's screen. It is plain words
    now, names no endpoint, and does not echo the typed text back.
    `DeliveryTownCatalogTest.refusalsAreCustomerSafe` pins that. Matching stays
    exact on purpose: guessing that "Byo" means Bulawayo risks the wrong town
    and the wrong fee, and the town picker is the fix. Clients should branch
    on `code`, not on the message.
  * **The fee is per SELLER, and it is the DEAREST of that seller's lines to
    the town, never the sum** — one seller ships one parcel. `CheckoutPricer`
    decides it (so cart, quote and order still cannot disagree), a line the
    seller does not cover becomes `NOT_DELIVERED_TO_TOWN` in the same
    rejection list, and the quote carries `deliveryFees` per seller. At order
    creation each seller's fee is SNAPSHOTTED into
    `market_order_delivery_fee`: a seller repricing a town before the buyer
    pays does not change what the buyer owes. At PAID it is copied onto the
    parcel and into the settlement (see the escrow bullet above).
    `refusalFor` ranks `not_delivered_to_town` AFTER availability and stock,
    so every pre-V14 refusal stays byte-identical.
  * **Tracking code**: every parcel gets `TRK-` + 10 Crockford characters at
    PAID (V14 backfilled existing ones from hex, which is inside the same
    alphabet). `TrackingCodes.normalize` forgives case, spaces, dashes, the
    prefix and I/L/O confusion, and refuses anything outside the alphabet
    before it reaches the database. Portal lookup
    `GET /marketplace/fulfilments/tracking/{code}` is seller-scoped (another
    seller's code is the same 404 as a missing one — the box is not an
    oracle); SUPER_ADMIN finds any. **No public tracking page, by owner's
    decision** — portal and the buyer's own app only.
  * **`TrackingStatus` (RECEIVED / DISPATCHED / DELIVERED / CANCELLED) is
    PRESENTATION ONLY**, derived from `FulfilmentStatus` by `TrackingStatus.of`.
    It is not stored and must never grow its own transitions: CANCELLED is
    exactly UNFULFILLED (the seller could not supply, or a collection was
    never picked up), whose refund path already exists.
  * **`COURIER` is derived, never believed**: `JwtFilter` grants it to ANY
    member (OWNER, ADMIN or STAFF) of an organization holding `marketplace`,
    and strips a token-supplied one. Wider than seller authority on purpose —
    drivers are STAFF — and it buys exactly the courier surface:
    `GET /marketplace/deliveries` (DISPATCHED DELIVERY parcels, **no money on
    them**) and `POST /marketplace/deliveries/{id}/location`. Dispatching and
    closing stay seller actions. `AuthenticatedUser.deliversFor` carries the
    scope.
  * **Latest position ONLY, never a route.** `order_fulfilment.last_*` is
    overwritten on every ping; a trail ending at a buyer's front door is
    personal data nobody asked to keep, and not keeping it is why there is no
    retention job. Written ONLY by a native bulk UPDATE (the columns are
    `insertable=false, updatable=false` on the entity, so an entity save can
    never clobber a position, and a ping can never trip `@Version`). The
    UPDATE's own predicate (`status = 'DISPATCHED'` and newer than the stored
    fix by `min-ping-interval-seconds`) is the throttle AND the out-of-order
    guard — the update count is the answer. Everything that merely ignores a
    ping is a **200 `accepted:false`**, never an error; only a request that can
    never succeed is refused (404 / 409 `parcel_not_in_transit` / 422
    `location_out_of_bounds` — the market's bounding box, which catches a
    phone reporting 0,0). A fix older than `max-ping-age-seconds` is dropped;
    a future timestamp is clamped to now.
  * **The buyer sees the pin only while it means something**: a DELIVERY
    parcel that is DISPATCHED. Delivered or cancelled, `liveLocation` is
    absent — the last point is usually the buyer's own door.
  * **Refunds tell the buyer twice, and the amounts are GROSS.** The seller's
    decline sends "a refund is being arranged" (existing listener); the
    operator's `POST /marketplace/settlements/{id}/refund` now publishes
    `RefundSent` → `RefundSentNotificationListener` (AFTER_COMMIT + `@Async`,
    SMS then WhatsApp, never throws, metric type `refund_sent`) naming the
    amount and the operator's transfer reference. Both name the parcel's
    GROSS — what the buyer paid for it, goods + delivery — not the seller's
    net: commission is the platform's arrangement with the seller and must
    never shrink a buyer's refund.
  * Config `marketplace.tracking.*` (`MARKETPLACE_TRACKING_*`): throttle 5s,
    max age 600s, and a bounding box that defaults to Zimbabwe with a margin
    — **a cell in another market MUST set its own**. Metric
    `marketplace.tracking.pings{outcome}`. No gateway change: everything is
    under `/marketplace/**`, and nothing here is an internal surface.
  * Pinned by `CheckoutPricerTest` (dearest-line fee, per-seller split,
    uncovered lines, one coverage query), `ParcelTrackingServiceTest`,
    `TrackingCodesTest`, `DeliveryTownCatalogTest`, the COURIER cases in
    `JwtFilterSellerScopeTest`, the V14 cases in `OrderServiceTest` /
    `FulfilmentServiceTest` / `SettlementServiceTest` /
    `ListingServiceTest`, and end to end by `DeliveryTrackingFlowIT` plus the
    refund case in `NotificationFlowIT`.
* **The seller portal reads (V15): cards and earnings rows answer the
  seller's actual questions.** Driven by the portal team's list — "which order
  is this row", "why is this one still held", "did the buyer get the SMS", "find
  the buyer at my counter".
  * **Cards and rows are BATCH-assembled** (`MerchantParcelViewAssembler`,
    `SettlementViewAssembler`): one query per related table for a whole page,
    however long. The card used to cost ~3 queries per parcel, and every field
    asked for would have added another. Single-row paths (an action's response,
    a tracking lookup) go through the same method with a list of one, so a card
    cannot differ by screen.
  * **`ParcelCloseMethod` is DERIVED, never stored**: BUYER_CONFIRMED /
    COLLECTION_CODE / SELLER_MARKED from `deliveredBy`; an UNFULFILLED
    collection that had been set aside is NOT_COLLECTED, every other decline
    CANNOT_SUPPLY (only a collection can be declined after dispatch, by the
    state machine). A stored copy would be a second truth. `deliveredBy`
    itself is unchanged: `BUYER` and `RECIPIENT` stay distinct values.
  * **Refund reason comes from whoever decided**: the seller's
    `unfulfilled_reason` for a decline, the operator's `resolution_note` for a
    dispute decided for the buyer — shown on REFUND_DUE/REFUNDED rows only. The
    dispute on a card/row carries status, reason and dates, **never the buyer's
    free-text detail** (that is for the operator).
  * **Whether the buyer was told is now recorded** (V15
    `order_fulfilment.buyer_notice_*`): the parcel-progress and
    unfulfilled listeners write the LAST notice's kind + outcome (SMS /
    WHATSAPP / FAILED / NOT_SENT) after sending. Written by a bulk UPDATE with
    its own transaction — the listener runs after commit on the async pool,
    and going through the entity would let a late notice turn the seller's
    NEXT action into an optimistic-lock failure. The recorder never throws.
    Seller-triggered notices only: the buyer's own collect-code SMS and the
    operator's refund SMS do not overwrite it.
  * **The queue's `q` is read by SHAPE** (`SellerParcelQueryService.Search`):
    `MKT…` → order ref, `TRK…` → tracking code, dialable → phone normalised
    by `Msisdns` (buyer, delivery recipient or gift recipient number), anything
    else → a name (delivery recipient or named collector, LIKE-escaped). A
    phone-shaped query that is not a number is a 400, never a silent name
    search. There is **no name for a buyer collecting for themselves** — the
    platform never stored one — so their phone or reference is the search.
    Built as appended Criteria predicates + one subquery on the order, never a
    nullable bind (the catalogue rule).
  * **Split counts** on `/fulfilments/stats`: `onTheWay` / `readyToCollect` /
    `readyToCollectOverdue` (set aside longer than
    `marketplace.fulfilment.collection-overdue-days`, default 7 — deliberately
    inside the operator's 14-day stale list so the seller sees it first).
    `inTransit` is kept (= onTheWay + readyToCollect) for older clients.
  * **Earnings**: `from`/`to` are calendar days in THIS market (`MarketZone`,
    which fails boot on an unmapped country rather than defaulting to UTC),
    matched on when the buyer paid. The summary adds `nextClearingAt`,
    `clearingNext7DaysCents` and `lastPayout` (grouped by payout reference).
    `GET /marketplace/settlements/statement` is the same rows as CSV, oldest
    first, market-local timestamps in ONE fixed shape, period in the filename,
    refused past 5000 rows (422 `statement_too_large`), and every free-text
    cell neutralised against spreadsheet formula injection.
  * **A mistyped query/path parameter is now a 400 `invalid_parameter`**
    (`MethodArgumentTypeMismatchException` in `GlobalExceptionHandler`), not a
    500 — every enum/UUID filter in the service benefits.
  * Pinned by `PortalParcelsAndEarningsIT` (search shapes, filters, split
    counts, async notice write-back, clearing date, dispute block, code budget,
    earnings rows, summary, statement), plus `MerchantParcelViewAssemblerTest`,
    `SettlementViewAssemblerTest`, `ParcelCloseMethodTest`,
    `SellerParcelSearchTest`, `BuyerNoticeTest`, `MarketZoneTest`,
    `StatementCsvTest` and the listener tests.
* **"Delivers to my town", the seller's bell, and a buyer who can call a
  parcel off (V16).**
  * **`deliversTo=<town code>` on the catalogue browse** is a correlated
    `EXISTS` over `listing_delivery_town`, appended like every other filter —
    **never a join**, which would repeat a listing once per town it covers and
    break paging. The code is normalised through `DeliveryTownCatalog.require`,
    so an unknown town is **400 `unknown_town`**, not an empty page (an empty
    page would tell the shopper nobody delivers to them). `BROWSE_PARAMS`
    carries it, so the unknown-parameter guard stays in step.
  * **Seller alerts go to the portal bell through the existing
    `/users/internal/{uuid}/notify`, now TYPED** (`UserNotice`: `type`,
    `severity`, `subjectKind`/`subjectId`, `deepLink`; optional keys are
    ABSENT from the body when unset, so a plain notice is the pre-V16 body byte
    for byte). `SellerAlertService` sends to every OWNER/ADMIN of the selling
    organization (`MerchantAdminResolver`) and **never throws**. Types:
    `ORDER_PAID` (the existing new-order notice, now typed and linked),
    `PARCEL_DISPUTED`, `COLLECTION_OVERDUE`, `PAYOUT_SENT`,
    `BUYER_NOT_NOTIFIED`, `ORDER_CANCELLED_BY_BUYER` — user-service stores an
    unknown type as-is, so none needed a user-service change. Metric
    `marketplace.notifications{type=seller_*}`.
  * **Deep links are deployment templates**
    (`marketplace.notifications.seller-alerts.parcel-link`, default
    `/marketplace/parcels?q={orderRef}`; `earnings-link`,
    `/marketplace/earnings`), because the server supplies the link and the
    portal keeps no type→route map. Change the config when the portal's routes
    move — not the code.
  * **Each alert rides an event published INSIDE the deciding transaction**
    (`DisputeOpened`, `PayoutRecorded`, `ParcelCancelledByBuyer`) and is sent
    by `SellerAlertListener` AFTER_COMMIT on the notification pool — a
    rolled-back dispute, payout or cancel alerts nobody. **One alert per payout
    RUN**, mirroring the one audit row per run. The dispute alert carries the
    reason label only, never the buyer's free-text detail (that is the
    operator's).
  * **`BUYER_NOT_NOTIFIED` fires from `BuyerNoticeRecorder`** when every
    channel of a seller-triggered buyer SMS FAILED — the seller's action went
    through, the buyer does not know, and only the seller can now tell them.
    `NOT_SENT` (a deployment choice) alerts nobody.
  * **`CollectionOverdueSweeper` alerts ONCE per parcel**: it CLAIMS
    `order_fulfilment.collection_overdue_alerted_at` with a conditional bulk
    UPDATE before sending (at most once — a crash loses an alert rather than
    repeating it daily), and uses the same
    `marketplace.fulfilment.collection-overdue-days` as the seller's stats, so
    the bell and the `readyToCollectOverdue` counter cannot disagree. Daily
    (`MARKETPLACE_COLLECTION_OVERDUE_CRON`), ShedLock-elected, per-row isolated.
    The marker column is read-only on the entity (the V15 reason).
  * **A buyer can cancel a PAID parcel while it is PREPARING**
    (`POST /marketplace/orders/{id}/fulfilments/{fulfilmentId}/cancel`, body
    `{reason?}`, CUSTOMER; public-test mirror under `/buyers/{handle}/…`). Same
    three moves as a seller's decline in one transaction — UNFULFILLED, stock
    back (`returnOnce`), HELD → REFUND_DUE — and the parcel records
    **`unfulfilled_by = BUYER`** (V16; `SELLER` for a decline or a no-show,
    backfilled SELLER on every existing UNFULFILLED row). It is **refused
    before anything moves** whenever the refund could not honestly be queued:
    DISPATCHED or closed → 409 `parcel_not_cancellable`; money DISPUTED → 409
    `parcel_disputed` (the operator owns it); money not HELD, or no settlement
    row → 409 `parcel_not_cancellable`. A seller's decline may close a parcel
    whose money is elsewhere because refusing would leave it open; a buyer's
    cancel has no such need, so it must not promise a refund the ledger then
    does not queue.
  * **The seller is alerted; the buyer is NOT SMSed** (they did it on the screen
    in front of them) — hence its own event, not `ParcelUnfulfilled`. They hear
    again when the operator records the refund (`RefundSent`). Audit
    `FULFILMENT_UNFULFILLED` with `cancelledByBuyer: true`, actor = the buyer,
    no free text.
  * **Every surface says who ended it**: `ParcelCloseMethod.BUYER_CANCELLED`
    (derived from `unfulfilled_by`, still never stored) on the seller's card
    and earnings row, `unfulfilledBy` on the buyer's order, `cancelledBy` on
    tracking, and `refundReason` falls back to "Cancelled by the buyer" when
    they gave none. **`chk_fulfilment_unfulfilled_by` needs its explicit
    `IS NOT NULL`**: `NULL IN (...)` is UNKNOWN and a CHECK that evaluates to
    UNKNOWN passes — the first draft accepted an UNFULFILLED row naming nobody,
    and only the real-Postgres IT caught it.
  * Pinned by `SellerAlertServiceTest`, `BuyerCancelParcelTest`,
    `CollectionOverdueSweeperTest`, the deliversTo cases in
    `CatalogServiceTest`, the typed/plain cases in
    `UserNotifyGatewayContractTest`, `BuyerNoticeTest`, and end to end by
    `SellerAlertsAndBuyerCancelIT` (shares `NotificationFlowIT`'s mocked
    channels, which now include `MerchantAdminResolver`).
* **A seller says WHERE buyers collect (V18): collection points.** Before V18
  a COLLECTION order meant "arrange it with the seller": no address, no hours,
  and nothing on the order to say which counter. Now a seller keeps up to
  **10** points (`seller_collection_point` + weekly `seller_collection_point_hours`),
  managed at `/marketplace/sellers/me/collection-points` (MERCHANT_ADMIN, scoped
  by SHAPE like the payout destination) with a SUPER_ADMIN override at
  `/marketplace/admin/sellers/{merchantId}/collection-points`.
  * **A point belongs to the SELLER, not the listing.** Every listing the
    seller sells can be collected at any of their points; there is no per-listing
    table to drift. So the browse `collectsIn` filter is an EXISTS correlated on
    the listing's `merchantId`, where `deliversTo` correlates on the listing.
  * **Exactly one default whenever a seller has any** (partial unique index
    `uq_collection_point_default`): the first point becomes it, promotion demotes
    FIRST then marks, deleting the default promotes the oldest survivor — the
    address book's and the gallery's discipline. The index stops two defaults;
    only the service can stop zero, so **every write for one seller first takes
    that seller's `marketplace_seller` row lock**
    (`MarketplaceSellerRepository.lockForUpdate`). Without it, two concurrent
    first points both try to become the default and a double-tap is a 500
    (this service maps no constraint violation to a 4xx). `is_default` is
    read-only on the entity and changed only by the bulk statements.
  * **Replace, never merge** — a point is redefined whole, hours included (the
    payout-destination rule). Hard delete; orders keep their snapshot.
  * **Hours are market-local wall-clock times** (`TIME`, never instants), at
    most two periods a day, no overlap, opens before closes (400
    `invalid_opening_hours`, naming the day). The server renders
    `openingHoursSummary` ("Mon-Fri 08:00-17:00, Sat 08:00-13:00") and
    `openNow` on `MarketZone`'s clock — the client prints, never computes. No
    hours given = no summary and no `openNow`: saying nothing beats implying
    "closed". A null element inside `openingHours` is a 400, not a 500 (Bean
    Validation skips null list elements, so `OpeningHours.validate` checks).
  * **The order SNAPSHOTS the point** (`market_order_collection_point`, one row
    per `(order, merchant)`, written in the order's transaction): name, town,
    address, landmark, phone, pin. `collection_point_id` is provenance only (no
    FK) — the delivery-address and `title_snapshot` rule. A seller moving or
    deleting a point never moves a collection already arranged. **Only the
    HOURS are read live**, through the provenance id while the point still
    exists: "is it open now" is about the counter today, not the day of the
    order. A deleted point leaves the address and no hours.
  * **The buyer's choice is optional and never gating.** `collectionPoints:
    [{merchantId, collectionPointId}]` on the quote and the order; unchosen =
    the seller's default; no points = no row, and collection is arranged
    directly exactly as before V18. `CollectionPointResolver` is shared by quote
    and order (the `CheckoutPricer` reason: the point a buyer was quoted is the
    point the order records). A point that is not that seller's = 400
    `unknown_collection_point`; one seller named twice = 400
    `duplicate_collection_point_choice`; a choice for a seller no longer in the
    basket is ignored. The new request field is
    `@JsonInclude(NON_NULL)` so an old body fingerprints the same for
    idempotency.
  * **Where it is shown**: the quote and the buyer's order
    (`collectionPoints`, per seller, COLLECTION only), each parcel
    (`collectionPoint`), the seller's parcel card, the buyer's tracking view and
    the collect-code response — all the snapshot, batch-read by
    `CollectionPointViews` (one query per table per page). The public profile
    carries the seller's live `collectionPoints` (empty list, never absent,
    never a 404); every listing card carries `collectionTowns` (distinct, town
    list order, one query per page).
  * **Browse**: `collectsIn=<town>` and `availableIn=<town>` (delivered there OR
    collectable there, one `OR` of two EXISTS, so a listing doing both appears
    once — the filter a "near me" toggle wants). Both go through
    `DeliveryTownCatalog.require`, so an unknown town is 400 `unknown_town`,
    and both are in `BROWSE_PARAMS`. `city` stays the seller's free-text city.
  * **Deliberately NOT done**: no SMS carries the point (seller free text stays
    out of platform SMS — the dispatch-note rule), no per-listing points, no
    geo search, no collection fee. Audit `COLLECTION_POINT_*` carries the
    merchant, the town and `bySeller` — never the address typed.
  * Pinned by `OpeningHoursTest`, `CollectionPointServiceTest`,
    `CollectionPointResolverTest`, the collection cases in `CatalogServiceTest`,
    and end to end by `CollectionPointFlowIT` (CRUD, default promotion, the cap,
    owner-scoped 404s, the admin override, browse against real SQL, the
    snapshot surviving an edit and a delete) plus the public-test case in
    `PublicTestOrderRailIT`.
* **A seller's NAME comes from the organization registry (user-service) when
  nobody here has set one.** This service stores seller IDS and no NAMES —
  `Listing.merchantId` and `MarketOrderItem.merchantId` are the selling
  ORGANIZATION's id, taken from the seller's JWT — and
  `display_name` is written by exactly ONE path: `approve(...)` with an
  optional `displayName`. So until an operator approved a seller *with a name
  typed in*, the admin queue rendered "Unnamed merchant", the finance payout
  CSV carried an empty name column beside an amount about to be transferred,
  and the public catalogue showed shoppers an unnamed seller. `ensureExists`
  creates the row nameless, which is every seller's starting state.
  * **`MerchantNameResolver` is BATCH by construction** (`namesFor(Collection)`),
    because every caller is a page: a catalogue page, a moderation queue, a
    payout run. A per-id signature would have made an N+1 of all three, and the
    one that matters is the catalogue — a shopper's browse must not cost a
    round trip per listing. Same discipline as `ListingViewAssembler`'s grouped
    image query, and it is the reason the seam is an interface rather than a
    call inside a DTO factory.
  * **The operator-set name WINS and costs no lookup.** `SellerService.displayNames`
    takes the local `display_name` where there is one and asks the registry
    ONLY about the gaps — a name somebody typed while vouching for a seller is
    a deliberate choice the registry must not overrule, and an approved, named
    seller never leaves the box. That scoping is also what keeps the resolver
    off the catalogue's critical path as sellers get approved: the ask shrinks.
  * **Never mutate the entity with a resolved name.** These are JPA entities;
    writing the registry's value onto `MarketplaceSeller.displayName` inside an open
    transaction would let dirty-checking PERSIST it, silently turning our
    column into a stale copy of the registry and defeating operator-wins. The
    resolved name is passed to the DTO factories (`SellerResponse.from(s, name)`,
    `SellerBadge.from(seller, name)`) and never written back.
  * **Best-effort, and it must be**: nothing here DECIDES on a name — ownership
    comes from our own `merchant_id` columns, money from the settlement ledger,
    authorization from the JWT. A 401, a 404 (a user-service without the
    organization surface), a 5xx, a timeout and a blank `INTERNAL_API_TOKEN`
    all yield the same empty map, which every caller already renders as no
    name.
  * **The cache is names ONLY, and that is the whole safety argument.** A
    cached balance or a cached ownership would be a correctness bug no TTL
    makes safe; a cached label is at worst minutes stale (the same narrow
    licence as the middleware's `CustomerNameResolver`). **Successes only** —
    a failed lookup is never cached, so a user-service blip cannot pin every
    merchant as nameless for a whole TTL. The TTL
    (`marketplace.merchant-names.ttl-seconds`, default 300) is a staleness
    budget, and since this service has no name-write path it is the complete
    invalidation story — **if one is ever added, it must evict**. Do NOT widen
    this into a general cache in front of user-service.
  * The registry side is user-service's `GET /users/internal/organizations/
    names?ids=` (`ticketing-system`), `ApiResult` body with `data:
    [{organizationId, name}]`, unknown ids omitted rather than 404ing the
    batch, 400 above 200 ids per call — chunked here to stay under it. No
    gateway change: `/users/internal/**` is edge-denied by
    `user-internal-deny`. (It read loyalty's `merchants/names` while a seller
    id was a loyalty merchant id; that endpoint is gone.)
  * Pinned by `MerchantNameResolutionTest` (local-wins, gap-scoping, batching,
    absent-not-placeholder) and `UserServiceOrganizationNameResolverContractTest`
    (the wire shape, every failure mode, the cache, and `verify(0, ...)` on a
    blank token).
* **An unauthenticated PRE-CHECKOUT surface, for building the app before login
  exists (`/marketplace/public/**`).** Super-app customers authenticate at the
  InnBucks middleware, which does not yet sign the assertion user-service trades
  at `POST /auth/exchange` for a fleet CUSTOMER token — so every
  `hasRole('CUSTOMER')` endpoint here is unreachable from a real ZW session and
  the FE had nothing to build the basket against. This is the marketplace
  sibling of loyalty's `/loyalty/public/**`, carried deliberately: same
  `enabled: false` default, same optional `x-api-key` gate by SHAPE (a filter,
  never a per-method check), same WARN-per-call trail, same
  never-re-implement-a-service-method rule. **Do not enable on production.**
  * **The order journey IS here, and the earlier rule against it was wrong
    about this fleet.** This surface originally stopped at the checkout quote,
    reasoning that an order carries `buyerMsisdn` — which payment-service treats
    as the payer, and which on the EcoCash rail is the handset an unsolicited
    PIN prompt is delivered to — so an unauthenticated caller naming that number
    would hold a phishing tool that works on live phones. The risk is real. The
    conclusion was not, because **booking-service already `permitAll`s
    `POST /bookings`**: a ticket purchase is created with a client-supplied
    `phoneNumber` and that number is exactly what payment-service later hands
    EcoCash. Guest checkout is the fleet's established posture for a super app
    that authenticates elsewhere, and marketplace refusing it left the ZW app
    able to fill a basket and unable to buy anything in it — which is precisely
    the report that came back from the FE. So order / cancel / receipt /
    dispute / collect-code / review are served here, and the risk is accepted
    knowingly rather than reasoned away. **If the fleet ever closes guest
    checkout on booking-service, close this in the same change.**
  * **Two things make it narrower than ticketing's, not wider.** (1) The order
    endpoints additionally require the cell to have an api-key CONFIGURED
    (`requireOrderRail`): the pre-checkout half may run ungated — a leaked cart
    is a nuisance — but anything that reserves stock or names a payer is 404
    on a cell with a blank `MARKETPLACE_PUBLIC_TEST_API_KEY`, which is far more
    often an operator mid-provisioning than a deliberate choice to take orders
    from the open internet. `POST /bookings` has no equivalent gate at all.
    (2) Ownership of every order, parcel, dispute and review keys on the DERIVED
    buyer id, so holding the key buys a caller nothing but their own handle's
    orders. The refusal is **404, matched to the surface-off refusal**, so an
    ungated cell is indistinguishable on the wire from one that never enabled
    the surface; the boot log is where an operator learns which they have.
  * **`buyerMsisdn` is REQUIRED in the order body here**, where the
    authenticated twin ignores it. `resolveBuyerMsisdn` prefers the token's
    phone claim and the derived caller has none, so the body is the only source
    and a missing one is a clean `400 invalid_msisdn` having reserved nothing.
    Do NOT give the derived identity a phone to "fix" that — a server-invented
    payer is the phishing shape, and an explicit refusal is the honest answer.
  * **Nothing here reaches a seller or operator surface**, and not by an
    accident of routing: the derived caller holds `CUSTOMER` and nothing else,
    so dispatch, delivery marking, moderation, settlement and payout are all
    refused by their own `@PreAuthorize`.
  * **The handle is the CUSTOMER'S PHONE (operator's direction, 2026-09-22:
    "customers exist on Veengu, not my DB — same as loyalty").**
    `PublicBuyerResolver` splits handles by SHAPE: digits-and-phone-punctuation
    → normalised to E.164 by the same `Msisdns` every payer goes through, THEN
    hashed — so every spelling of a number is ONE buyer, stable across devices,
    with no account or linking step (the FE had started building one; it must
    not exist). Anything containing a letter stays the original opaque demo
    handle. Digits that don't normalise are REFUSED (`400 invalid_msisdn`),
    never silently hashed — a typo'd phone forking into its own empty basket
    presents as "my cart disappeared". A handle already in canonical E.164
    derives the id it always did, so no existing basket was orphaned.
  * **A phone-keyed buyer CARRIES its phone; the payer is the identity.**
    `resolveBuyerMsisdn` prefers the principal's phone and ignores the body —
    the same rule a real CUSTOMER token gets — so an order under a phone handle
    is payable by the basket's owner and a body `buyerMsisdn` naming a third
    number does nothing (pinned at rest in `PublicTestOrderRailIT`). The
    trade accepted with eyes open: whoever holds the cell's api-key can act as
    any phone's basket. That is loyalty's long-standing public-surface posture
    — the key authenticates the BROKER (held server-side in the FE's Firebase
    functions, never the binary), the broker asserts the phone it authenticated
    at Veengu — and `/auth/exchange` is what retires it. Opaque demo buyers
    keep a NULL phone and must name a payer in the body, validated as ever.
  * **Both flavours stay version-5** — user-service mints `userUuid` v4, so a
    derived id can never address AUTHENTICATED-surface data, and a restock
    alert for a derived buyer still resolves to nobody in user-service.
    `PublicTestIdentityTest` + `PublicBuyerResolverTest` pin all of it.
  * **Carry-over to `/auth/exchange` is DESIGNED, not built**: the derivation
    is deterministic, so adoption is one re-key per table
    (`buyer_uuid = v5(phone) → userUuid`) the first time an authenticated
    session with that phone claim touches this service. That is the follow-up
    that makes cut-over seamless; do not fake it by handing this rail v4 ids.
    Demo-handle data never carries over, by design.
  * `marketplace.public-test.enabled` / `.api-key`
    (`MARKETPLACE_PUBLIC_TEST_*`). Boot says which of off / ungated / gated a
    cell is in, and logs an **ERROR** when it is on under a deployment profile.
    Metrics `marketplace.public_test{operation}` (non-zero in production is an
    incident) and `marketplace.public_test.rejected{reason}`. Needs no gateway
    change — the existing `/marketplace/**` route already carries it, and it is
    deliberately NOT an internal surface, so no deny route.
  * Pinned by `PublicTestIdentityTest`, `PublicTestApiKeyFilterTest`,
    `PublicTestSurfaceIT` (enabled but UNGATED — proves the cart works and the
    order rail is off), `PublicTestOrderRailIT` (enabled AND gated — proves the
    order journey works, the payer is validated, and one handle cannot touch
    another's order) and — the one that matters most —
    `PublicTestSurfaceDisabledIT`, which runs on the DEFAULT config and proves
    the surface is absent unless asked for. The first two are deliberately
    SEPARATE classes: they prove opposite halves of the same rule and cannot
    share a configuration, and a single class flipping the property mid-run is
    how a gate stops being tested.
* **An unknown path is a 404, not a 500.** `GlobalExceptionHandler` now maps
  `NoResourceFoundException`. Without it that exception fell through to the
  `Exception` catch-all, so **every typo'd URL in the whole service answered
  `500 INTERNAL_ERROR`** — telling a client its request was fine and our server
  broke, and logging somebody else's typo at ERROR (a path scanner could fill
  the error log on its own). Found while proving the public test surface has no
  order endpoint: the assertion that an unmapped path 404s failed at 500.
* **Marketplace-service still collects no money, but it now says WHERE to.**
  A `PENDING_PAYMENT` order carries a `payment` block, and
  `GET /marketplace/checkout/options` lists the rails, naming `POST /payments`
  with `{orderType: "MARKETPLACE", orderRef, paymentRail}`. That was
  cross-service knowledge hardcoded in a mobile binary, which is wrong the
  moment a cell enables a rail the app was not built with.
  **`marketplace.checkout.payment-methods` MUST mirror what payment-service is
  provisioned with on that cell** — this service holds no payment credentials
  and cannot ask, and advertising a rail whose credentials are blank sends the
  buyer to another service's 503 (the ZimSwitch half-provisioned lesson).
  Hence the fail-safe default of `INNBUCKS_CODE` alone. Keep `PaymentRail`'s
  names byte-identical to payment-service's enum — the value is passed straight
  through.
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
* **PARCEL UPDATES (buyer)** — dispatched / ready to collect / seller-marked
  delivered; see "A seller's word is announced to the buyer" in the escrow
  section.
* **MERCHANT NEW-PAID-ORDER — LIVE.** `notify/MerchantOrderNotifier` groups
  the order's lines by the snapshot `merchant_id` and notifies each merchant's
  admin users via `UserNotifyGateway` with THAT merchant's lines + subtotal.
  **ON by default** since `UserServiceMerchantAdminResolver` landed.
  * **The recipients are the selling organization's OWNERs and ADMINs**, from
    user-service's `GET /users/internal/organizations/{id}/admins` (active
    accounts only) — the snapshot `merchant_id` IS that organization's id. So
    a colleague added to the business gets the next paid-order notification,
    and one removed does not. It used to be a three-repo chain through
    loyalty's `merchants.admin_email` that reached one person at most; that
    chain and its endpoints are gone.
  * **Not the shop-staff endpoint** (`/users/internal/shop-staff/by-merchant/
    {id}/contacts`): it returns loyalty shop STAFF, keyed by a loyalty merchant
    id no seller row holds any more — and the fulfilment queue is
    `hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')`, so staff could not open the
    screen the notification is about anyway.
  * **Every miss is the same empty list**: an unknown organization, one whose
    runners are all inactive, a user-service outage. The caller's next step is
    identical, and a distinguishable answer would make an S2S surface an
    existence oracle. The notifier meters `outcome=no_recipients`.
  * **`MerchantAdminResolver.Unavailable` and its `@ConditionalOnMissingBean`
    fallback are GONE.** That conditional is only reliable in auto-configuration;
    against a component-scanned bean it can evaluate before the component is
    registered and leave TWO `MerchantAdminResolver` beans, which is a
    `NoUniqueBeanDefinitionException` at boot. The interface survives as the
    seam the notifier's grouping/composition/fan-out are unit-tested over.

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
