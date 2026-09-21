# Marketplace: answers to the FE's blocking questions

Every answer below is read out of merged code on `master` / `main`, not from
memory, and each concrete claim was re-verified by a second pass. Where
something is **not built**, it says so plainly rather than describing an
intention.

---

## 0. First — your 401s are a missing header, not a bug

```
GET /marketplace/public/buyers/<handle>/cart
401 {"code":"UNAUTHORIZED","message":"Invalid or missing API key"}
```

That body is our `PublicTestApiKeyFilter` refusing the call. The staging cell
has an `x-api-key` provisioned in front of `/marketplace/public/**`, and the app
is not sending it.

**Send `x-api-key: <key>` on every `/marketplace/public/**` call.** The operator
has the value; it is not in any repo. Missing key and wrong key are the same
401 on purpose.

It also answers a question you did not ask: your base URL and edge path are
already correct — the request reached Cloudflare → gateway → marketplace-service
and was refused by our own filter, so nothing about routing needs changing.

---

## 1. `POST /auth/exchange` — the contract, and who should call it

### The contract already exists

Two documents are merged on `ticketing-system@master`, both attached:

- **`Auth-Exchange-Frontend-Integration.md`** — your contract.
- **`Auth-Exchange-Assertion-Signing-Spec.md`** — what the broker/middleware
  side must implement before it can be switched on.

Short version of the FE half:

| | |
|---|---|
| Request | `POST /auth/exchange`, body `{"assertion": "<compact JWS>"}` — **that one field only** |
| Header | `X-Device-Id` (optional but send it — see below). No `X-Tenant-Id` anywhere on this path |
| Response | **Identical shape to `POST /auth/login`**: `token`, `refreshToken`, `roles:["CUSTOMER"]`, `tier`, `verified` |
| Access token | 15 min (`JWT_EXPIRATION_MS=900000`) |
| Refresh token | 7 days (`JWT_REFRESH_EXPIRATION_MS=604800000`), rotating |
| Refresh | `POST /auth/refresh` with the refresh token in the **`Authorization` header**, not the body |
| Errors | `400` missing assertion · `401 "Assertion rejected"` (opaque — every cause) · `409 wrong_cell` · `404` feature off (**today's answer on ZW**) · `503` half-provisioned or replay-guard down |

There is deliberately **no msisdn field** — the phone comes from the assertion's
signed `sub`, so no caller can pair their own number with someone else's valid
assertion.

### "Could the broker mint the fleet token at login?"

Mechanically yes. I'd still have the **app call `/auth/exchange` itself**, for
three reasons that are facts about the code rather than preference:

1. **Device binding would land on the wrong party.** `X-Device-Id` sent at
   exchange is SHA-256'd onto the refresh-token row, and `RefreshTokenService.rotate`
   requires every later `/auth/refresh` to present the *same* value — a mismatch,
   **including an absent header**, revokes the entire family. If the broker
   exchanges, either it sends its own device id (which the app then cannot match,
   so the first refresh kills the session) or it sends none (so the family has no
   device binding at all). Neither is what you want.
2. **One rate-limit bucket for all customers.** `auth-exchange-route` is keyed by
   `preAuthIpKeyResolver` — IP only, never bearer — at 5/s replenish, burst 20.
   Every customer exchanging through one broker shares that single bucket, and it
   is the fail-*safe* limiter, so a Redis outage does not lift it. Same trap one
   level down: `/auth/refresh` has no gateway limiter but user-service's own
   `LoginRateLimiter` caps **60 refreshes per IP per minute**, so a broker
   proxying refreshes collapses every customer into one bucket.
3. **The premise doesn't quite hold.** Exchange returns *both* tokens, so
   broker-minting still ends with the app holding a fleet token alongside the
   Veengu bearer. It does not avoid two credentials; it moves where the short-lived
   assertion is handled.

So: **the broker signs the assertion at login and hands it to the app; the app
trades it.** The assertion is single-use (its `jti` is SETNX-burned in Redis
before any account work) and lives ≤ 5 minutes, so it is a smaller thing for the
app to hold than the token would be.

One fact if you *do* go broker-side anyway: user-service reads the **left-most**
`X-Forwarded-For` entry for audit rows and its per-IP buckets, while the gateway
appends on the right — so a server-side caller can preserve the real customer IP
by setting `X-Forwarded-For`. Nothing in user-service distinguishes a
server-originated call.

---

## 2. Paying — what actually happens after `POST /payments`

### Request

```jsonc
POST /payments
Content-Type: application/json
{ "orderType": "MARKETPLACE", "orderRef": "MKT-4F9A1C22B7D3", "paymentRail": "INNBUCKS_CODE" }
```

The DTO has exactly four fields: `bookingId`, `orderType`, `orderRef`,
`paymentRail`. **No msisdn, no amount, no currency** — all three are read
server-side from the order. `paymentRail` is optional and defaults to
`INNBUCKS_CODE`. `bookingId` must be absent.

### Credential — this will surprise you

**`POST /payments` is public.** `SecurityConfig` permitAlls the exact path (it
predates marketplace; guest ticket checkout needs it), and there are zero
`@PreAuthorize` annotations anywhere in payment-service. **No `X-Tenant-Id`** is
read on this path — not by the service, not by the gateway route.

You still need the fleet token for everything *around* it: creating the order
and polling `GET /marketplace/orders/{id}` are both `CUSTOMER`-gated. The payment
call itself is the odd one out.

Gateway limit: `payment-service-write-route`, **1/s replenish, burst 5**, keyed
by bearer token when present else IP. That matters — see polling below.

### Response (INNBUCKS_CODE)

Fleet envelope; `data` is `PaymentResponse`. On a fresh mint:

```jsonc
{
  "transactionId": "…",          // stable across replays
  "bookingId": null,             // always null for MARKETPLACE
  "orderType": "MARKETPLACE",
  "orderRef": "MKT-4F9A1C22B7D3",
  "status": "PROCESSING",        // SUCCESS | PROCESSING | FAILED
  "stage": "AWAITING_PAYMENT",   // branch on THIS, never on `message`
  "fundsCaptured": false,        // true | false | null (null = unknown)
  "paymentCode": "…",            // render it
  "paymentQrCode": "<base64>",   // data:image/png;base64,<this>
  "paymentCodeExpiresAt": "…",   // countdown
  "paymentRail": "INNBUCKS_CODE",
  "amountPaid": …, "currency": "USD", "processedAt": "…"
}
```

`stage` values: `AWAITING_PAYMENT`, `IN_PROGRESS`, `INSTRUMENT_EXPIRED`,
`PAYMENT_UNAVAILABLE`, `PAYMENT_RECEIVED`, `COMPLETED`, `VERIFYING`.
**Branch on `stage`, not on `message`.** `FAILED` never appears in a 200 body —
declines are HTTP 400 with `data: null`.

A deep link also exists: `com.innbucks.customer://purchase?paymentToken=<code>`.

### How the money actually moves

payment-service **does not debit a wallet**. It mints a 2D PAYMENT code via the
InnBucks Merchant API (`POST /api/code/generate`), and the customer approves that
code **inside their own InnBucks app** — which is you. So: render the code + QR,
or deep-link straight into your existing pay-code flow.

The payer msisdn is the **order's** `buyerMsisdn` (read over the internal S2S
surface), which marketplace took from the JWT's `phoneNumber` claim at order
creation. It is never in the payment request, and is not actually sent to
InnBucks at all — the customer self-identifies by approving the code.

Learning it was paid: **no webhooks on this rail.** A scheduled poller
(`PT20S`) queries `POST /api/code/inquiry`; on `Paid` it calls
`PATCH /marketplace/internal/orders/{ref}/confirm-payment`. A re-POST of
`/payments` also triggers an *instant* inquiry, so "I've paid" resolves in ~1s
rather than waiting for the next sweep.

The hold is extended **once, before the code is minted** — to code TTL (10 min)
+ 3 min safety = 13 min — not repeatedly while it is live.

### Polling — the part with a trap

There is **no customer-facing GET on payment-service**. Two signals exist:

- **`GET /marketplace/orders/{id}`** — this is your poll loop. Read side,
  50/s. Serves live `status` and `fulfilmentStatus`, nothing cached.
- **Re-POST `/payments`** — replay-safe, returns the same live code, and forces
  an upstream check. **Reserve this for the user's "I've paid" tap and screen
  refreshes.** The write route allows ~1/s; a poll loop here gets 429 from the
  gateway.

Poll the order until `status: "PAID"`.

### Failure and abandonment

**One active payment per order, across all rails**, enforced by a partial unique
index. A second POST while a code is live is **not an error** — it replays the
same code, QR and expiry with `stage: AWAITING_PAYMENT`.

- Code expires unpaid → poller marks the row `EXPIRED`, slot freed. Between the
  local deadline and the poller's verdict, a re-POST reads
  `stage: INSTRUMENT_EXPIRED`, `fundsCaptured: null`.
- Once `EXPIRED`/`FAILED`, the next POST mints a **fresh** code.
- Orphan self-heal: a `PENDING` row with no code older than 30s is closed and
  replaced inside the same re-POST — a crash mid-mint recovers by tapping Pay
  again. Younger than 30s replays as `stage: IN_PROGRESS` ("retry shortly") — a
  **double-tap state your UI must handle**.
- Abandoned order → `OrderExpirySweeper` (every minute) flips it to `EXPIRED`
  and releases stock. Order TTL is 30 min from creation, or 13 min past the last
  payment attempt. Paying an expired order is a clean 409.

Refunds on this rail do not exist programmatically — operator procedure.

### Currency

`INNBUCKS_CURRENCY=USD` on the ZW cell. See §5.

---

## 3. Order create details

### `Idempotency-Key`

**Required** — absent or blank is `400 idempotency_key_required`. No length or
charset limit; it is trimmed, then SHA-256'd together with your buyer UUID, so
two customers may safely use the same key. A UUIDv4 per checkout attempt is fine.

| Case | Result |
|---|---|
| Same key, same body | **201 again** with the stored body (replay) |
| Same key, different body | `422 idempotency_key_reuse` |
| Fresh claim still running | `409 request_in_flight` |
| Claim older than 60s | Taken over and re-executed |

### Every refusal slug on the order endpoints

Domain slugs are **lowercase snake**; framework-level ones are **UPPERCASE** —
branch case-sensitively on both families.

**`POST /marketplace/orders`**

| Slug | Status | When |
|---|---|---|
| `idempotency_key_required` | 400 | Header absent/blank |
| `request_in_flight` | 409 | Concurrent same-key claim |
| `idempotency_key_reuse` | 422 | Same key, different body |
| `ambiguous_basket` | 400 | `fromCart` **and** `items` |
| `cart_empty` | 400 | `fromCart` with an empty cart |
| `invalid_items` | 400 | Neither supplied, or >20 lines |
| `invalid_quantity` | 400 | Outside 1..25 per line |
| `duplicate_listing` | 400 | Same listing twice |
| `invalid_msisdn` | 400 | Names `buyerMsisdn` or `recipient.msisdn` |
| `recipient_name_required` | 400 | Gift with a name that sanitises to nothing |
| `delivery_unavailable` | 422 | Cell offers no delivery method |
| `delivery_method_unavailable` | 422 | Method not offered here |
| `delivery_address_required` | 400 | DELIVERY, no id given, no default saved |
| `address_not_found` | 404 | Named address isn't the buyer's |
| `listing_unavailable` | 422 | Line missing / not ACTIVE / foreign currency — **carries `data.rejections`** |
| `insufficient_stock` | 409 | Short stock — **usually** carries `data.rejections` |
| `order_total_overflow` | 422 | Total exceeds representable amount |
| `VALIDATION_ERROR` | 400 | Bean validation; `data` maps field → message |
| `MALFORMED_REQUEST` | 400 | Unparseable JSON |
| `UNAUTHORIZED` | 401 | Missing/expired token |
| `FORBIDDEN` | 403 | Not a CUSTOMER |

**`POST /marketplace/orders/{id}/cancel`** — CUSTOMER-only, owner-scoped,
`PENDING_PAYMENT` only: `invalid_order_id` 400 · `order_not_found` 404
(also when it's someone else's) · `illegal_order_state` 409 ·
`CONCURRENT_UPDATE` 409 (lost race with the expiry sweep — retry).

**`GET /marketplace/orders/{id}`** — `invalid_order_id` 400 · `order_not_found` 404.

### `data.rejections`

```jsonc
{ "code": "insufficient_stock", "message": "…",
  "data": { "rejections": [ {
      "listingId": "…",
      "reason": "LISTING_UNAVAILABLE" | "INSUFFICIENT_STOCK",
      "message": "Only 2 left of Solar Lantern 20W",
      "requestedQty": 5,
      "availableQty": 2,        // INSUFFICIENT_STOCK only; 0 = sold out
      "unitPriceCents": 1550    // may be absent
  } ] } }
```

`reason` is a **string, not an enum** — render an unrecognised one, don't choke.
`rejections` is in request order and lists only failing lines.

**Two traps:**
- `insufficient_stock` can arrive **without** `data.rejections` — a line that
  passes the advisory pre-check but loses the atomic reserve race gets the plain
  409. Don't assume the key is present.
- An unsupported `Content-Type` is a **500**, not a 415. Always send
  `application/json`.

---

## 4. Test data on staging

### The recipe — three calls, one real SMS

There is **no backdoor**. The OTP is never in a response, never logged, and
stored only as a keyed HMAC — so every test customer costs a real SMS to a phone
someone holds. Use a real `+263` number.

```jsonc
// 1 — register (SENDS A REAL SMS)
POST /auth/customer/register
{ "phoneNumber": "+263771234567", "password": "S3cur3Pass!" }
// 201, no token. Pending row, 30-min TTL.
// Note: the tier-1 body does NOT contain userId — our Swagger example is stale.

// 2 — read the code off the phone, verify
POST /auth/otp/verify
{ "phoneNumber": "+263771234567", "code": "123456" }
// 200. Creates the CUSTOMER account. Returns a loyalty-scoped token — IGNORE it,
// it carries no roles and every marketplace endpoint will reject it.

// 3 — login for the fleet token (no SMS)
POST /auth/login
{ "identifier": "+263771234567", "password": "S3cur3Pass!" }
// 200. data.token carries roles:["CUSTOMER"] and the phoneNumber claim.
```

`identifier` accepts `0771234567`, `771234567` or `+263771234567` — all
normalise to the same account. An `@` routes it as an email.

**Use one msisdn per tester or tool.** Every successful login bumps
`tokenVersion` and revokes all prior refresh families — last login wins. Two
testers on one number will silently log each other out. Same for
forgot-password.

Also: `POST /auth/login` is rate-limited at **5 attempts per identifier / 20 per
IP per minute** — an automated script will trip that long before the 7-attempt
lockout. And if your harness sends `X-Device-Id`, send the **same value** on
every refresh or the family is revoked.

There is **no admin path to create a customer**. `/admin/users` has no
create endpoint at all.

### Funded wallet + merchants with stock

Not something I can provision from here — those are operator actions on the
staging cell:

- **Funded wallet:** the InnBucks code rail debits through the InnBucks merchant
  account, so "funding" means whatever your InnBucks staging tenant needs. That
  is the same team that owns the assertion signing.
- **Merchants with stock:** a `MERCHANT_ADMIN` token creates listings; note the
  publish gate — a listing cannot go `ACTIVE` without a primary image.
- **Seller names:** see §5.1 — someone must run the approve call.

Tell me which of these you want and I'll write the exact call sequence, or a
seed script.

---

## 5. The smaller things

### 5.1 `seller.displayName` is null everywhere — expected, and fixable today

`marketplace_seller` rows are created `PENDING` with **no name**, by design:
marketplace-service holds merchant *ids*, not names. The **only** way a name is
ever set is:

```
PUT /marketplace/admin/sellers/{merchantId}/approve   (SUPER_ADMIN)
{ "displayName": "Sunrise Electronics" }
```

Nobody has run it on staging, hence null on every listing. Not a bug — an
operational step.

In production: yes, merchants should carry trading names, and that's the same
call. Two things worth knowing:

- **There is no merchant self-service for this.** A seller cannot set or dispute
  their own trading name.
- **`verified: true` with `displayName: null` is reachable** — approve's name is
  optional. Don't treat the verified badge as implying a name.
- Our own Swagger examples show `"sellerName": "Sunrise Electronics"`, which is
  more optimistic than staging. **Handle null** — on the listing badge, the
  public merchant header, and each parcel's `sellerName`.

Loyalty already holds the real trading name (`merchants.name`, same UUID key), so
an automatic backfill is a clean follow-up — it needs a new internal endpoint on
loyalty plus a client here. Not built.

### 5.2 Delivery fee

`marketplace.delivery.fee-cents`, **0 by default**, flat per cell
(`MARKETPLACE_DELIVERY_FEE_CENTS`). It is 0 on staging because nobody set one.

**What production charges is your commercial call, not a technical default.**
This service books no couriers and has no rate card, so any number is a decision
someone has to own. Flag: a multi-seller order ships in several parcels and pays
that one flat fee **once** — a per-merchant or per-zone rate card is real work
we deliberately deferred.

### 5.3 Listing images — resized variants **do** exist

Add `?w=` to either image endpoint:

```
GET /marketplace/catalog/{id}/image?w=240
GET /marketplace/catalog/{id}/images/{imageId}?w=480
```

- Allowed widths: **120, 240, 480, 960**. Anything else is
  `400 unsupported_image_width`. Omit `w` for the original.
- Resize happens **on read**, cached 1h (`Cache-Control: public, max-age=3600`).
- Response carries **`X-Image-Resized: true|false`** so you can tell whether you
  actually got a smaller copy.
- **WebP passes through unresized** — the JDK ships no WebP decoder. An image
  already narrower than the requested width also comes back untouched. Both
  report `X-Image-Resized: false`.

So: use `?w=240` for grid thumbnails and `?w=960` for detail, keep your device
cache, and don't pull originals for lists. Uploads are capped at 10 MB.

### 5.4 Currency — single per cell

USD only on the ZW cell, end to end. A merchant **cannot** set a listing's
currency; it is stamped from the cell config at create and is immutable
afterwards. A cart, quote and order each carry exactly one `currency`, always
the cell's. There is no FX anywhere.

Mixed-currency carts are **unrepresentable**: a listing whose currency differs
from the cell's is rejected as `LISTING_UNAVAILABLE` — the *same* reason as a
delisted or missing listing, deliberately, because the client remedy is
identical. So don't build a "wrong currency" message; there's no signal for it.

ZiG listings are not planned as things stand. If the cell currency were ever
flipped, every existing listing becomes unbuyable rather than converting — worth
knowing before anyone suggests it.

### 5.5 The public test rail

Staging only, and it stays that way. `MARKETPLACE_PUBLIC_TEST_ENABLED` is
`false` by default; enabling it on a deployment profile logs a permanent boot
**ERROR** naming the risk. Your client-side hostname gate is a good second
belt — keep it.

It carries cart, addresses, favorites, checkout quote, and
`GET /marketplace/public/checkout/options`. It will **never** carry orders,
payment, fulfilment, disputes or collect codes — an order names the phone that
receives a payment PIN prompt, which is not something an unauthenticated caller
may choose.

Also: a public-rail handle hashes to a **v5** UUID and real customers are **v4**,
so test data lives on its own island and does **not** carry over to a real
account once `/auth/exchange` is live. Plan demo data accordingly.

### 5.6 Push notifications — none. Polling is the only signal.

**There is no FCM, APNs or web push anywhere** in marketplace-service or
user-service. (user-service stores a `pushToken` at tier-3 registration and
nothing ever reads it — the DTO's description overpromises.)

Worse for your purposes, the buyer's order lifecycle is **not in the in-app
bell** either. Marketplace's buyer-facing notices go **direct to SMS/WhatsApp**
and bypass the bell entirely:

| Event | Recipient | Channel | In `GET /notifications`? |
|---|---|---|---|
| Order PAID | buyer | SMS → WhatsApp | **No** |
| Gift recipient notice | recipient | SMS → WhatsApp | **No** |
| Parcel UNFULFILLED | buyer | SMS → WhatsApp | **No** |
| Collect-code | collector | SMS → WhatsApp | **No** |
| Dispute resolved | buyer | user-service | Yes |
| New paid order | merchant admins | user-service | Yes |
| Restock alert | favoriters | user-service | Yes |
| Payout destination changed | merchant admins | user-service | Yes |

And these have **no notification at all** — polling only: fulfilment
`DISPATCHED`, `DELIVERED`, escrow release, payout executed, refund executed,
collect code redeemed, order `CANCELLED` or `EXPIRED`.

**So the order screen must poll `GET /marketplace/orders/{id}`.** A buyer
notifications screen built on `GET /notifications` will miss almost the entire
order lifecycle.

If you do want the bell for the parts that are there: `GET /notifications`
(paged, max size 100) and `GET /notifications/unread-count`, which is **ETagged**
— validator is `"<count>-<latestCreatedAtEpochMillis>"`, a matching
`If-None-Match` returns **304 with no body but with the ETag re-sent**. Store and
replay that header **byte for byte**; any intermediary that rewrites it to a weak
validator (`W/"…"`) silently defeats the 304 path. Any authenticated token works,
including CUSTOMER — it needs only the `userUuid` claim.

---

## What is actually blocking you, ranked

1. **The `x-api-key`** — one header, unblocks everything you have already built.
2. **The broker signing the assertion** — the only real blocker for orders,
   payment and tracking. Send them
   `Auth-Exchange-Assertion-Signing-Spec.md`; it's a keypair and about ten lines
   of jjwt.
3. **Operator steps on staging** — approve sellers with display names, seed
   merchants with stock, fund the InnBucks staging tenant.

Everything in §2, §3, §5 is answerable today and needs no backend change.
