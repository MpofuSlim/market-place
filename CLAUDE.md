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
  coordinated deploy. The delivery fee is a flat per-cell
  `marketplace.delivery.fee-cents`, **0 by default** — this service books no
  couriers and has no rate card, so any non-zero number is a deliberate
  commercial decision. **Known gap, deliberately deferred:** a multi-seller
  order ships in several parcels and pays that fee ONCE; a per-merchant or
  per-zone rate card is real work, not something to fake with a number nobody
  can justify.
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
    seller can always close it themselves — a buyer who never opens the app
    must not leave a parcel open forever.
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
    `marketplace.settlement.grace-hours`, default 48) — the
    `SettlementReleaseSweeper` (ShedLock, per-row isolation, re-checks state
    AND clock inside each row's tx so a dispute landing mid-sweep wins)
    promotes it after. Delivery NEVER unfreezes a DISPUTED row.
  * **gross/commission/net are all STORED and CHECK-enforced**
    (`net = gross - commission`); commission is
    `marketplace.settlement.commission-percent`, **0 by default** — charging
    one is a config change, not a migration. The delivery fee is in NO
    merchant settlement: it is per ORDER, sellers ship their own parcels, and
    splitting one fee across N sellers invents an allocation nobody agreed to.
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
    IMMEDIATELY, like a buyer's own confirmation — that instant payout is
    precisely the seller's incentive to ask for a code instead of self-closing
    into the 48h grace window.
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
  * **`FulfilmentStatus.UNFULFILLED` is terminal and reachable ONLY from
    PREPARING.** Once a parcel is DISPATCHED the goods are with a courier and
    "I cannot fulfil this" has stopped being true — that is a delivery failure,
    which the buyer's dispute already covers with an operator looking at it.
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
    a single batched reference would be proof to none of them.
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
  * **The line is drawn at side effects that LEAVE this service**, and that is
    the whole design. Cart, addresses, favorites and the checkout QUOTE are
    here; **order, payment, fulfilment, disputes and collect codes are not, and
    must not be added.** An order carries `buyerMsisdn`, which payment-service
    treats as the payer and which on the EcoCash rail is the handset an
    unsolicited PIN prompt is delivered to — an unauthenticated caller who could
    name that number would have a phishing tool that works on live phones
    whatever cell fired it. Orders also reserve real merchant stock. Nothing on
    this rail sends, reserves or moves money.
  * **The identity is DERIVED, not supplied, and that is what makes it safe.**
    The free-form `handle` in the path is hashed to a **version-5** UUID
    (`PublicTestIdentity`); user-service mints `userUuid` with
    `UUID.randomUUID()`, which is **version 4**. The version nibble differs, so
    a caller here can never address a real customer's cart, address book or
    wishlist — structural, not improbable. It is also what defuses favorites,
    the one endpoint with a downstream notification: a restock alert for a
    derived buyer resolves to nobody in user-service and reaches no real phone.
    `PublicTestIdentityTest` pins the version, and it is not a detail to
    "simplify" later.
  * **The derived caller is `CUSTOMER` with a NULL phone.** Null phone means
    even a mistakenly-added order endpoint could not name a payer. Roles are
    exactly `{CUSTOMER}`, so no seller or operator surface becomes reachable
    through it.
  * Test data on this rail is its own island and does NOT carry over to the
    same person's real account once `/auth/exchange` is live. Correct for a
    test rail, not a shortcoming.
  * `marketplace.public-test.enabled` / `.api-key`
    (`MARKETPLACE_PUBLIC_TEST_*`). Boot says which of off / ungated / gated a
    cell is in, and logs an **ERROR** when it is on under a deployment profile.
    Metrics `marketplace.public_test{operation}` (non-zero in production is an
    incident) and `marketplace.public_test.rejected{reason}`. Needs no gateway
    change — the existing `/marketplace/**` route already carries it, and it is
    deliberately NOT an internal surface, so no deny route.
  * Pinned by `PublicTestIdentityTest`, `PublicTestApiKeyFilterTest`,
    `PublicTestSurfaceIT` and — the one that matters most —
    `PublicTestSurfaceDisabledIT`, which runs on the DEFAULT config and proves
    the surface is absent unless asked for.
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
* **MERCHANT NEW-PAID-ORDER — LIVE.** `notify/MerchantOrderNotifier` groups
  the order's lines by the snapshot `merchant_id` and notifies each merchant's
  admin users via `UserNotifyGateway` with THAT merchant's lines + subtotal.
  **ON by default** since `UserServiceMerchantAdminResolver` landed.
  * **It took THREE repos, because the merchantId→person link is in none of
    the obvious places.** This service stores no user↔merchant link at all
    (`merchantId` is a loyalty id copied off a JWT claim). user-service cannot
    answer it either: `users.loyalty_merchant_id` is stamped on SHOP_ADMIN /
    SHOP_USER rows only, so a **MERCHANT_ADMIN's own row does not name their
    merchant** — which is exactly why `AuthService.resolveMerchantIdClaim`
    resolves the JWT claim by asking loyalty at every login. The binding lives
    in loyalty's `merchants.admin_email`, and until this work it was readable
    only email→merchant. So the chain is `merchantId` →
    (`GET /users/internal/merchants/{id}/admins`) → (`GET /loyalty/internal/
    merchants/{id}/admin-email`) → the user row, and only the first hop is
    visible from here.
  * **Not the shop-staff endpoint** (`/users/internal/shop-staff/by-merchant/
    {id}/contacts`), which already resolves users by `loyalty_merchant_id` and
    would have needed no loyalty hop. It returns shop STAFF, and the fulfilment
    queue is `hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')` — answering with staff
    notifies people who cannot open the screen the notification is about, while
    still not telling the one person who can.
  * **Every miss is the same empty list**, by design at both hops: unknown
    merchant, no admin on file, no account here yet, an inactive account, a
    non-MERCHANT_ADMIN account, and a user-service or loyalty outage. The
    caller's next step is identical for all of them, and a distinguishable
    answer would make an S2S surface an existence oracle for merchants and
    accounts. user-service logs which one it was.
  * **Safe to run ahead of the other two repos.** A user-service too old to
    serve the endpoint 404s, the resolver returns empty and the notifier meters
    `outcome=no_recipients` — the pre-existing behaviour, not a failure. Same
    for a loyalty without its endpoint. So the deploy order is free.
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
