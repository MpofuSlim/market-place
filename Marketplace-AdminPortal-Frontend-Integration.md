# InnBucks Marketplace — Admin Portal Integration Guide

**Service:** `marketplace-service` · **Audience:** the admin portal · **Schema:** V13

Two distinct users share this app and the guide is split accordingly:

- **§3–§7 — the MERCHANT (`MERCHANT_ADMIN`)**: their own listings, their parcel queue, their money.
- **§8–§12 — the OPERATOR (`SUPER_ADMIN`)**: every merchant's listings, seller approval, moderation, disputes, payouts.

The buyer-facing surface is in the **Super App** guide.

> **Build these as two different apps inside one shell.** A merchant's every read is silently scoped to their own `merchantId` claim; an operator's reads span the platform and usually *require* naming a merchant. Mixing the two produces screens that look right for one role and lie to the other.

---

## 1. Base URL, auth, envelope

| | |
|---|---|
| Base URL | the API gateway origin (`/foundry` prefix in staging/prod, stripped by nginx) |
| Route | `/marketplace/**` → `marketplace-service` |
| Auth | `Authorization: Bearer <jwt>` — a fleet user JWT from user-service |
| Roles | `MERCHANT_ADMIN` (seller) · `SUPER_ADMIN` (operator) |
| `X-Tenant-Id` | **Not required anywhere in this guide** |

Envelope `{ "code": "…", "message": "…", "data": … }`; errors carry a slug `code` — **branch on `code`, never on `message`**. Money is **minor units** (cents, integer). Timestamps are **UTC ISO-8601 with `Z`**.

### Merchant scope comes from the token, never from a request

A `MERCHANT_ADMIN`'s JWT carries a `merchantId` claim, and **every merchant-facing endpoint reads it from there**. Consequences you must design around:

- **A `merchantId` query parameter is IGNORED for a merchant** on the queues and money views. They cannot widen their own scope, and sending one does nothing — it is not an error, it just has no effect.
- **A token with no `merchantId` claim is `403 merchant_scope_missing`.** That is not a bug to work around. It happens when the signed-in user administers **more than one merchant**, because a claim is singular and guessing one would silently attribute listings — and therefore commission — to an arbitrary merchant. Render "this account manages several merchants; sign in against one" and route to support.

---

## 2. Roles at a glance

| Surface | `MERCHANT_ADMIN` | `SUPER_ADMIN` |
|---|---|---|
| Listings CRUD + images | own only | **any merchant** |
| `GET /listings/mine` | own | all (`?merchantId=` filters) |
| Fulfilment queue + stats | own | all (`?merchantId=` filters) |
| Dispatch / delivered / decline / redeem code | own parcels | any |
| Settlements list + summary | own | all (summary **requires** `?merchantId=`) |
| Payout destination | own (`/sellers/me/…`) | any (`/admin/sellers/{id}/…`) |
| Orders list / any order | ✗ | ✓ |
| Seller approval queue | ✗ | ✓ |
| Moderation queue | ✗ | ✓ |
| Disputes, payout runs, refunds, payout report | ✗ | ✓ |
| Place / cancel an order | ✗ | ✗ — **CUSTOMER only, by design** |

> **`SUPER_ADMIN` cannot place or cancel orders.** Those stay buyer-only. An operator acting on an order does it through the dispute and settlement surfaces.

---

# PART ONE — THE MERCHANT

## 3. Listings

Base: `/marketplace/listings` · `hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')`

### `GET /marketplace/listings/mine`

The seller's own listings, **all statuses**. Params: `page`, `size`, and — **SUPER_ADMIN only** — `merchantId` to filter.

> **There is no `status` filter here.** Fetch the page and group by `status` client-side for DRAFT / ACTIVE / INACTIVE / ARCHIVED tabs.

Returns the standard page shape: `{ items: [ListingResponse], page, size, totalItems }`. `ListingResponse` is documented in the Super App guide §2.

### `POST /marketplace/listings` — create (JSON)

```json
{
  "title": "Solar Lantern 20W",
  "description": "Portable solar lantern with 12h battery",
  "categoryCode": "electronics",
  "condition": "NEW",
  "city": "Harare",
  "area": "Avondale",
  "priceCents": 1550,
  "stockQty": 10
}
```

| field | required | notes |
|---|---|---|
| `title`, `priceCents`, `stockQty` | yes | price in **cents** |
| `categoryCode` | no | defaults to `other`; unknown → `400 unknown_category` |
| `condition` | no | defaults `NEW`. `NEW` `USED_LIKE_NEW` `USED_GOOD` `USED_FAIR` |
| `city`, `area`, `description` | no | free text, sanitized server-side |
| `merchantId` | **SUPER_ADMIN only** | create on behalf of a merchant |

**On `merchantId`:** admin tokens carry no merchant claim, so a SUPER_ADMIN **must** send it — `400 merchant_id_required` otherwise. For a MERCHANT_ADMIN it is refused whenever it differs from their own claim (`422 merchant_scope_mismatch`).

Created listings are **`DRAFT`**.

### `POST /marketplace/listings` — create with images (multipart)

Same endpoint, `Content-Type: multipart/form-data`:

| part | notes |
|---|---|
| `listing` | the JSON body above |
| `image` | the primary image |
| `images` | repeated, **max 9 more** → `400 too_many_images` |

### `PUT /marketplace/listings/{id}` — update

Same fields as create, **plus `stockQty` — which is REQUIRED on every update**. It is not optional: omitting it is a `400`, so send the current value even when you are only changing the price. Load the listing, edit, send the whole thing back.

**This is also how stock is changed**, and a `0 → >0` move fires back-in-stock alerts to everyone who favorited the listing. Worth surfacing on the form: restocking is a marketing event, not just a number.

### `PATCH /marketplace/listings/{id}/status`

```json
{ "status": "ACTIVE" }
```

`DRAFT` `ACTIVE` `INACTIVE` `ARCHIVED`.

> **Going ACTIVE requires a primary image — `422 primary_image_required`.** Exactly one image is mandatory for a live listing; DRAFTs may stay imageless. Deactivation is never blocked. Disable the "Publish" control until an image is uploaded and say why.

### Images

| | |
|---|---|
| `PUT /{id}/image` | multipart `image` — replace-or-create the **primary** |
| `DELETE /{id}/image` | delete the primary; the lowest-position survivor is promoted |
| `POST /{id}/images` | multipart, append — `409 image_limit_reached` at **10** |
| `DELETE /{id}/images/{imageId}` | remove one; promotion if it was primary |
| `PUT /{id}/images/{imageId}/primary` | atomic swap |

**Rules the UI should enforce before upload:**

- **jpeg / png / webp only** — **GIF is deliberately rejected**. Both the content type *and* the magic bytes are checked.
- **10 MB cap**, enforced twice. Oversize is `400 image_too_large` either way.
- **Max 10 per listing.**
- **Exactly one primary whenever any image exists** — guaranteed server-side, so your UI never has to reconcile two.

---

## 4. The parcel queue

Base: `/marketplace/fulfilments`

**A "parcel" is one seller's share of one order.** A multi-seller order produces several, and each seller sees only their own.

### `GET /marketplace/fulfilments`

Params: `status` (`PREPARING` `DISPATCHED` `DELIVERED` `UNFULFILLED`), `page`, `size`. **`merchantId` is IGNORED for a merchant**; SUPER_ADMIN can filter with it.

```json
{
  "data": { "items": [ {
    "id": "7f3c1a92-…",
    "orderId": "2a9e7c18-…",
    "orderRef": "MKT-4F2A9C1B77D0",
    "merchantId": "…",
    "status": "PREPARING",
    "deliveryMethod": "DELIVERY",
    "destination": {
      "recipientName": "Tariro Moyo", "recipientMsisdn": "+263771234567",
      "line1": "12 Josiah Tongogara Ave", "line2": null,
      "city": "Harare", "area": "Avondale"
    },
    "items": [ { "listingId": "…", "titleSnapshot": "Solar Lantern 20W",
                 "unitPriceCents": 2399, "quantity": 2, "lineTotalCents": 4798 } ],
    "subtotalCents": 4798,
    "currency": "USD",
    "paidAt": "2026-09-14T11:20:10Z",
    "dispatchNote": null,
    "dispatchedAt": null,
    "deliveredAt": null,
    "deliveredBy": null,
    "settlementStatus": "HELD",
    "settlementNetCents": 4798,
    "collectorName": null,
    "collectCodeIssued": false,
    "collectCodeRedeemedAt": null,
    "unfulfilledReason": null,
    "unfulfilledAt": null
  } ], "page": 0, "size": 20, "totalItems": 3 }
}
```

> **The seller sees their OWN lines and subtotal — never the order total.** In a multi-seller order they learn nothing about what else the buyer bought. Don't try to display an order-level total here; it isn't sent.

**`settlementStatus` on the card answers "when do I get paid for this?"** without a second call. Render it.

### The three moves

| | |
|---|---|
| `POST /{id}/dispatch` | body `{ "note": "Swift Couriers, waybill 88213" }` (optional) |
| `POST /{id}/delivered` | the seller's own close |
| `POST /{id}/unfulfillable` | `{ "reason": "…" }` — **required, buyer-visible** |

**The state machine:** `PREPARING → DISPATCHED → DELIVERED`, with `PREPARING → DELIVERED` legal (goods handed over in person never pass through a dispatch), and `PREPARING → UNFULFILLED`. `DELIVERED` and `UNFULFILLED` are terminal.

An illegal move is `409 illegal_fulfilment_state` — **refused, never applied**. Hide or disable a control that isn't legal from the parcel's current status rather than letting the seller discover it as an error. A double-tap gets the same 409; treat it as "already done" and refresh.

### On a COLLECTION order, relabel

`DISPATCHED` reads **"ready to collect"** and `DELIVERED` reads **"collected"**. You have `deliveryMethod` on the parcel. There is deliberately one state machine, not two vocabularies.

### Declining a parcel (`/unfulfillable`)

The third button, beside Dispatch and Delivered. Label it for what it is — *"I can't supply this"* — **not "Cancel"**: the buyer has already paid, and this is an admission.

```json
{ "reason": "Supplier let us down, no stock until October" }
```

In one transaction it closes the parcel, **returns the stock to the catalogue**, **turns the buyer's money around**, and (after commit) SMSs the buyer with the seller's own words.

- `reason` is **mandatory and shown verbatim to the buyer** — say so in the field's helper text.
- `400 unfulfilled_reason_required` for a blank one or one that is nothing but markup.
- **`409` on a DISPATCHED parcel is permanent, not retryable.** Once goods are with a courier, "I cannot fulfil this" has stopped being true — what follows is a delivery failure, and the buyer's dispute covers it. **Only show the button on `PREPARING`.**
- The response's `settlementStatus` becomes `REFUND_DUE` — render it as *"Refunded to the buyer"*, not a pending payout. It stays unchanged when the money was already disputed or paid out; read it rather than assuming.

### `POST /{id}/collect` — redeem a collection code

For COLLECTION parcels where the buyer minted a code.

```json
{ "code": "K7Q2-9XMF-3TRW" }
```

Dashes, spaces and letter case are ignored, and confusable characters are folded (`I`/`L` → `1`, `O` → `0`) — so a code read imperfectly off a screen still verifies. **Accept free-form input; don't build a strict masked input.**

| status | `code` | meaning |
|---|---|---|
| `422` | `collect_code_invalid` | wrong code — **spends one attempt** |
| `409` | `collect_code_locked` | too many wrong tries; the buyer must mint a new one |
| `409` | `collect_code_unavailable` | the buyer never issued one |
| `409` | `collect_code_not_applicable` | this is a DELIVERY order |
| `409` | `illegal_fulfilment_state` | already handed over |

**Redeeming releases the seller's money immediately**, rather than after the 48-hour self-close grace window. That instant payout is the whole reason to ask for a code — worth surfacing on the screen as an incentive.

> **No merchant surface ever shows the code itself.** `collectCodeIssued` tells you one exists; the plaintext is only ever in the buyer's app and the collector's SMS.

### `GET /marketplace/fulfilments/stats`

```json
{
  "data": {
    "publicStats": { "completedOrders": 42, "medianDispatchHours": 18, "buyerConfirmedPercent": 86 },
    "awaitingDispatch": 3,
    "inTransit": 5,
    "completedOrders": 42
  }
}
```

`publicStats` is **byte-identical to what shoppers see** on the seller profile — so "why does my profile say 2 days?" is answered by the very number displayed. Each figure is `null` below the sample floor (default 5 parcels), and the whole `publicStats` block is absent for a seller with no completed parcel. Render "not enough orders yet", never a zero.

---

## 5. The seller's money

Base: `/marketplace/settlements`

**Escrow, in one sentence:** the platform holds a buyer's money and releases it to the seller only once the goods arrive. This service is a **ledger** — it records money, it never moves it. Payouts and refunds are transfers an operator makes on the rails and records here.

### `GET /marketplace/settlements` — one row per parcel

Params: `status`, `page`, `size`. `merchantId` **ignored** for a merchant.

```json
{
  "data": { "items": [ {
    "id": "5a6b7c8d-…",
    "orderId": "…", "fulfilmentId": "…", "merchantId": "…",
    "status": "HELD",
    "grossCents": 4798, "commissionCents": 0, "netCents": 4798,
    "currency": "USD",
    "releasableAt": "2026-09-17T08:00:00Z",
    "releasedAt": null, "paidOutAt": null, "payoutReference": null,
    "refundDueAt": null, "refundedAt": null, "refundReference": null
  } ] }
}
```

### What each status means to the seller

| status | render as |
|---|---|
| `HELD` | *"Waiting on delivery"* — plus `releasableAt` when a grace clock is running |
| `RELEASABLE` | *"Cleared — due in the next payout"* |
| `PAID_OUT` | *"Paid"* + `payoutReference`, which is what answers "where is my money?" |
| `DISPUTED` | *"On hold — the buyer raised an issue"* |
| `REFUND_DUE` | *"Refunded to the buyer"* (queued, not yet sent) |
| `REFUNDED` | *"Refunded to the buyer"* |

**How money clears — the two paths, worth explaining in the UI:**

- **The buyer confirms receipt → released immediately.** Their word is the strongest evidence the platform has.
- **The seller closes the parcel themselves → a 48-hour grace window** (`releasableAt`), then released automatically. A seller's own say-so buys them their money two days later, not never.
- **A redeemed collection code → released immediately**, like the buyer's own confirmation.

### `GET /marketplace/settlements/summary`

```json
{
  "data": {
    "merchantId": "…",
    "payoutDestinationConfigured": false,
    "totals": [
      { "status": "HELD", "parcels": 4, "netCents": 18200 },
      { "status": "RELEASABLE", "parcels": 3, "netCents": 46500 }
    ]
  }
}
```

A status with nothing in it is simply not listed. **SUPER_ADMIN must pass `?merchantId=`** here (`400 merchant_id_required`) — an admin token has no scope to default to.

> **`payoutDestinationConfigured: false` alongside a non-zero `RELEASABLE` total is the banner to build.** It means money is cleared and cannot be sent anywhere. This is the only screen where a seller would learn they need to provide payout details.

---

## 6. Payout details (`MERCHANT_ADMIN`)

Base: `/marketplace/sellers/me/payout-destination` — **no merchant id anywhere in the request**; the subject is always the caller's own claim.

### `GET` — read it back

```json
{ "data": { "merchantId": "…", "configured": false } }
```

```json
{
  "data": {
    "merchantId": "…", "configured": true,
    "method": "MOBILE_MONEY",
    "accountName": "Rudo Chikwanha",
    "msisdn": "+263771234567",
    "updatedAt": "2026-09-18T09:15:00Z",
    "updatedBy": "…"
  }
}
```

**`configured: false` is a normal `200`, not a 404.** Render the "add your payout details" state. **Nothing is masked** — they typed it, and checking it is the point.

### `PUT` — set or replace

```json
{ "method": "MOBILE_MONEY", "accountName": "Rudo Chikwanha", "msisdn": "0771234567" }
```

```json
{ "method": "BANK", "accountName": "Rudo Chikwanha",
  "bankName": "CBZ Bank", "accountNumber": "01123456789012" }
```

- **It REPLACES the whole destination.** There is no partial update — a half-changed destination reads as configured everywhere and fails only when a transfer is attempted.
- **`accountName` is the name on the ACCOUNT, not the shop name.** It is what the bank has on file, and a transfer is rejected when it does not match. It is routinely different — "Rudo Traders" paying into "R. Chikwanha". **Label it explicitly; do not prefill from the trading name.**
- **Switching rails clears the other's fields.** Expected, not data loss.
- `msisdn` comes back normalised to E.164 — render what the server returns.

| status | `code` | when |
|---|---|---|
| `400` | `payout_field_required` | a field the method needs is missing — **the message names it** |
| `400` | `invalid_msisdn` | not a valid number for this cell's country |
| `403` | `merchant_scope_missing` | token carries no merchant claim |

**Changing this notifies the seller's account**, because re-pointing a payout is what a compromised account is used for. Mention it on the form so the message is not alarming when it arrives.

---

## 7. What the merchant is notified about

Handled server-side; useful for setting expectations in the UI.

| event | who | channel |
|---|---|---|
| A new paid order containing their listings | the merchant's admin users | user-service picks the channel |
| Their payout destination changed | same | names the **rail**, never the account |

---

# PART TWO — THE OPERATOR (`SUPER_ADMIN`)

## 8. Orders oversight

| | |
|---|---|
| `GET /marketplace/orders` | every order, newest first. `?buyerUuid=` filters; paged with `?page=`, `?size=` (default 20), `?sort=` |
| `GET /marketplace/orders/{id}` | any single order |

**No `status` filter** — filter client-side if you want status tabs.

**An operator cannot place or cancel an order.** Those are CUSTOMER-only; there is no admin override, by design.

---

## 9. Seller approval

Base: `/marketplace/admin/sellers`

### `GET /marketplace/admin/sellers`

Params: `status` (`PENDING` `APPROVED` `REJECTED` `SUSPENDED`), `page`, `size`. Default view is **oldest first** — FIFO, so the longest-waiting seller never starves.

```json
{
  "data": { "items": [ {
    "merchantId": "7e2a9c41-…",
    "status": "PENDING",
    "displayName": "Rudo Traders",
    "verified": false,
    "canPublish": true,
    "decidedBy": null, "decisionNote": null,
    "createdAt": "2026-04-01T09:15:00Z",
    "decidedAt": null
  } ] }
}
```

### Two different lines, and the UI must not conflate them

- **`verified`** — `APPROVED` only. This is what earns the buyer-facing badge.
- **`canPublish`** — false for `REJECTED` and `SUSPENDED` **only**.

> **A `PENDING` seller can trade.** Making approval a hard gate would turn this into an approval-queued marketplace, which is a product decision about onboarding friction, not a consequence of having a trust record. Don't render PENDING as "blocked".

Every pre-existing merchant was backfilled as `PENDING` — that backlog is the point of the queue, not a bug.

### The four decisions

| | body | notes |
|---|---|---|
| `PUT /{merchantId}/approve` | `{ "note": "…" }` optional | re-approving is allowed (updates the display name) |
| `PUT /{merchantId}/reject` | `{ "note": "…" }` **required** | |
| `PUT /{merchantId}/suspend` | `{ "note": "…" }` **required** | **takes their listings down** — the count comes back in the audit |
| `PUT /{merchantId}/reinstate` | `{ "note": "…" }` optional | only from `SUSPENDED`/`REJECTED` |

- **`400 note_required`** on reject/suspend without one. A seller told only "no" cannot fix anything, and a second admin cannot see what a colleague already decided. Make the field required in the form.
- **`409 seller_already_<status>`** on a repeated reject/suspend.
- **Reinstate does NOT re-publish** the listings a suspension took down — the seller chooses what goes back on sale. Say so in the confirmation.

### Payout destination on behalf of a seller

`GET` / `PUT /marketplace/admin/sellers/{merchantId}/payout-destination` — identical shape and rules to §6, for a seller who cannot use the self-service screen.

**The seller is notified either way**, and the audit records that it was the operator rather than them.

---

## 10. Moderation

Base: `/marketplace/reports`

### `GET /marketplace/reports`

Params: `status` (default `OPEN`), `page`, `size`. **Oldest first** — FIFO so the oldest complaint never starves.

```json
{
  "data": { "items": [ {
    "id": "…",
    "listingId": "…", "listingTitle": "Solar Lantern 20W",
    "merchantId": "…", "listingStatus": "ACTIVE",
    "reporterUuid": "…",
    "reason": "COUNTERFEIT",
    "detail": "Logo is fake — the brand does not make this model.",
    "status": "OPEN",
    "resolvedBy": null, "resolutionNote": null,
    "createdAt": "2026-09-16T10:00:00Z"
  } ] }
}
```

Rows carry a live listing summary, batch-loaded — no second call per row.

### `PATCH /marketplace/reports/{id}`

```json
{ "action": "RESOLVE", "resolutionNote": "Confirmed counterfeit.", "deactivateListing": true }
```

- `action` ∈ `RESOLVE` `DISMISS`.
- **`deactivateListing: true` on a DISMISS is `400`** — dismissing means there was nothing wrong. Disable the checkbox when DISMISS is selected.
- Deactivation is **always allowed** (only *activation* is publish-gated).
- **`409 report_not_open`** — closed reports are terminal. One decision each.

---

## 11. Disputes

### `GET /marketplace/settlements/disputes`

Params: `status` (default `OPEN`), `page`, `size`. **Oldest first.**

```json
{
  "data": { "items": [ {
    "id": "…", "orderId": "…", "fulfilmentId": "…", "merchantId": "…",
    "reason": "NOT_RECEIVED",
    "detail": "Paid five days ago, the seller has stopped answering.",
    "status": "OPEN",
    "resolutionNote": null,
    "createdAt": "2026-09-16T10:00:00Z", "resolvedAt": null
  } ] }
}
```

### `PATCH /marketplace/settlements/disputes/{id}`

```json
{ "action": "RELEASE", "resolutionNote": "Courier photo shows intact parcel." }
```

```json
{ "action": "REFUND", "resolutionNote": "Seller unreachable for a week.",
  "refundReference": "RFND-2026-09-30-07" }
```

- `RELEASE` → the money returns to `RELEASABLE` and the next payout run covers it.
- `REFUND` → **make the transfer on the rails first**, then record it here with your reference. This service moves no money.
- **`409 dispute_not_open`** — decided exactly once.
- The buyer is notified either way.

---

## 12. Paying sellers

### `GET /marketplace/settlements/payout-report` — the sheet finance pays from

`text/csv`, biggest owed first, date in the **filename** (not a preamble row — that breaks every parser that treats line 1 as the header).

```csv
merchantId,displayName,parcels,netCents,currency,payoutMethod,payoutAccountName,payoutMsisdn,payoutBankName,payoutAccountNumber,payoutChangedAt
```

- Bank and wallet columns are **mutually exclusive** — exactly one set is populated per row.
- **A seller with no destination on file still appears**, with all six destination columns empty. That row is money genuinely owed to someone who cannot be paid; dropping it would hide them.
- **`payoutChangedAt` is a fraud control**, not bookkeeping. This report is read in the moment *before* money moves — the last point a human can notice a destination that moved yesterday. Highlight recent ones.
- CORS note: the response sets `Content-Disposition`; expose that header if the browser needs the filename.

### `POST /marketplace/settlements/pay-out` — record a payout run

```json
{ "merchantId": "7e2a9c41-…", "payoutReference": "PAYOUT-2026-09-30-01" }
```

**Every `RELEASABLE` row of that merchant, under one reference** — the shape finance actually pays in, one transfer per merchant rather than a click per parcel.

```json
{ "data": { "merchantId": "…", "parcels": 3, "totalNetCents": 46500,
            "currency": "USD", "payoutReference": "PAYOUT-2026-09-30-01" } }
```

`409 nothing_releasable` for an empty run — a bank reference over no money describes nothing.

> **Make the transfer first, then record it.** And note a missing payout destination does **not** block this: the endpoint records a transfer you already made, and refusing would leave the ledger saying `RELEASABLE` after the money left — so the next run would pay those parcels twice.

### `POST /marketplace/settlements/{id}/refund` — record a refund

```json
{ "refundReference": "RFND-2026-09-18-03" }
```

Closes one `REFUND_DUE` settlement as `REFUNDED`. **Per parcel, not batched like a payout run**: a refund goes back to the individual buyer of one order, and a single batched reference would be proof to none of them.

| status | `code` |
|---|---|
| `404` | `settlement_not_found` |
| `409` | `illegal_settlement_state` — not `REFUND_DUE`/`DISPUTED`, including a second attempt |

### `GET /marketplace/settlements/stale` — money nobody is moving

Settlements still `HELD` past the threshold (default **14 days**), oldest first. `?size=` (default 50).

**These are the rows no timer can reach**: the release sweeper matches a clock that only a delivery sets, so a parcel never delivered is invisible to it. Every row is a buyer who paid, a seller who never delivered and never declined, and nobody watching.

> **Nothing is decided for you, deliberately.** Auto-releasing would pay a seller who never delivered; auto-refunding would punish one who is merely slow. The two ways out are: chase the seller, or have them decline the parcel so the refund queues itself.

**Expect rows on the first look after go-live** — anything paid before today and never delivered has been sitting unnoticed. That is the backlog this list exists to surface, not a bug.

---

## 13. Gotchas checklist

**Both roles**

- [ ] **Branch on `code`, never `message`.**
- [ ] **Money is minor units.** `4998` = `USD 49.98`.
- [ ] **Timestamps are UTC `Z`** — render in the viewer's locale, no arithmetic.
- [ ] **`403 merchant_scope_missing` usually means a multi-merchant account**, not a broken token. Route to support with that wording.

**Merchant**

- [ ] **`merchantId` params are silently ignored** for a merchant — never build a scope switcher out of one.
- [ ] **"Publish" needs a primary image** (`422 primary_image_required`). Disable until one exists.
- [ ] **`stockQty` is REQUIRED on every listing update** — send the current value even when changing only the price, or it's a 400.
- [ ] **jpeg/png/webp only, GIF rejected, 10 MB, 10 images max.** Validate before upload.
- [ ] **Hide illegal parcel moves** rather than letting a 409 explain them.
- [ ] **"I can't supply this" only on `PREPARING`** — the 409 on a dispatched parcel is permanent.
- [ ] **Its `reason` is mandatory and buyer-visible.** Say so in the helper text.
- [ ] **COLLECTION relabels** `DISPATCHED`/`DELIVERED` as ready-to-collect / collected.
- [ ] **Never display a collection code on a merchant screen** — `collectCodeIssued` is all you get, deliberately.
- [ ] **`publicStats` is null/absent below the sample floor.** "Not enough orders yet", never a zero.
- [ ] **Seller sees own subtotal, never the order total.**
- [ ] **`accountName` ≠ shop name.** Don't prefill it.
- [ ] **Payout `PUT` replaces, never merges.**
- [ ] **`payoutDestinationConfigured: false` + cleared money = the banner that matters.**

**Operator**

- [ ] **`PENDING` sellers can trade.** Don't render them as blocked; `canPublish` is the field that matters.
- [ ] **Notes are required on reject and suspend** (`400 note_required`).
- [ ] **Suspend takes listings down; reinstate does NOT put them back.**
- [ ] **`deactivateListing` on a DISMISS is a 400.**
- [ ] **Queues are FIFO (oldest first)** — reports, disputes, sellers. Don't re-sort newest-first.
- [ ] **Moderation and dispute decisions are one-shot** (`409 …_not_open`).
- [ ] **Make transfers on the rails BEFORE recording them.** This service moves no money; a recorded payout means the money already left.
- [ ] **A missing payout destination never blocks a payout run** — by design.
- [ ] **`GET /settlements/summary` requires `?merchantId=`** for an admin.
- [ ] **Expect a real backlog on `/settlements/stale` at first** — that's the point.
- [ ] **Admins cannot place or cancel orders.** Don't build the button.
