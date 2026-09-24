# Marketplace Escrow (Buyer Protection) — Frontend Integration

**Service:** `marketplace-service` · **Migration:** V10

The seller only gets paid when the buyer gets their goods. This guide covers
the three surfaces that make that true: the **buyer's dispute** (super app),
the **seller's money view** (merchant app/console), and the **operator's
dispute queue + payout run** (Foundry console).

---

## 1. What changed, and why you need this

Until now the money side of a marketplace order ENDED at `PAID`: the platform
collected, and nothing recorded which seller was owed what, or why a seller
should be paid only after delivery. V10 adds the **settlement ledger** — one
row per parcel (a parcel is already "one seller's share of one order") that
tracks the money through:

```
                    buyer confirms receipt ──────────────┐
                                                         v
 order PAID ──> HELD ── seller self-closes + 48h ──> RELEASABLE ──> PAID_OUT
                 │                                       │
                 └──────────── buyer disputes ───────────┘
                                     v
                                 DISPUTED ── operator decides ──> RELEASABLE
                                                                or REFUNDED
```

- **HELD** — the platform holds the money; the parcel is not delivered yet
  (or a seller-closed delivery is waiting out its grace window).
- **RELEASABLE** — cleared for the seller's next payout run. The buyer's own
  receipt confirmation clears it **immediately**; a seller closing the parcel
  themselves starts a **grace window** (default 48h) first.
- **DISPUTED** — a buyer dispute froze it; an operator will resolve.
- **PAID_OUT** — the operator paid the seller, with a payout reference.
- **REFUNDED** — the operator refunded the buyer, with a refund reference.

> **This service never moves money.** Payouts and refunds are executed by the
> operator on the payment rails and **recorded** here with their references.

> **Nothing you already call changed in a breaking way.** All additions are
> new endpoints or new nullable fields on existing responses.

---

## 2. Base URL, auth, headers

| | |
|---|---|
| Base URL | the API gateway origin (`/foundry` prefix in staging/prod, stripped by nginx) |
| Route | `/marketplace/**` → `lb://marketplace-service` — **already existed, no gateway change** |
| Auth | `Authorization: Bearer <jwt>` — a normal fleet user JWT |
| Roles | **`CUSTOMER`** for §3; **`MERCHANT_ADMIN`** for §4; **`SUPER_ADMIN`** for §5 |
| `X-Tenant-Id` | **Not required** anywhere in this guide |
| Content-Type | `application/json` on every write |

> **Envelope.** `{ "code": "OK", "message": "…", "data": … }`. Errors carry a
> slug `code`: `{ "code": "dispute_window_closed", "message": "…" }`.

> **Money is always MINOR UNITS (cents, integer).** `4798` is $47.98. Divide
> by 100 for display only.

> **Timestamps are UTC ISO-8601 with `Z`** (`2026-09-16T14:05:00Z`).

---

## 3. Buyer (super app, `CUSTOMER`)

The buyer never sees a "settlement" — their half is the **dispute**, and it
lives on their order.

### 3.1 The dispute block on the order view

`GET /marketplace/orders/{id}` — each entry in `data.fulfilments[]` now
carries a nullable `dispute` (absent when the parcel was never disputed):

```json
{
  "fulfilments": [
    {
      "id": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
      "status": "DELIVERED",
      "deliveredBy": "MERCHANT",
      "dispute": {
        "id": "5c8d1e2f-9a34-4b67-8c01-2d3e4f5a6b7c",
        "orderId": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
        "fulfilmentId": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
        "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
        "reason": "NOT_RECEIVED",
        "detail": "Paid five days ago, the seller has stopped answering.",
        "status": "OPEN",
        "resolutionNote": null,
        "createdAt": "2026-09-15T10:00:00Z",
        "resolvedAt": null
      }
    }
  ]
}
```

`dispute.status` is `OPEN` ("we're reviewing"), `RELEASED` ("delivery was
confirmed, the seller will be paid") or `REFUNDED` ("your refund was
approved"). `resolutionNote` is the operator's note, present once resolved.

### 3.2 Raising a dispute

```
POST /marketplace/orders/{orderId}/fulfilments/{fulfilmentId}/dispute
```

```json
{
  "reason": "NOT_RECEIVED",
  "detail": "Paid five days ago, the seller has stopped answering."
}
```

- `reason` — **required**, one of `NOT_RECEIVED` · `DAMAGED` ·
  `NOT_AS_DESCRIBED` · `WRONG_ITEM` · `OTHER`.
- `detail` — optional free text, max 1000 chars, sanitized server-side.

**200** → the `DisputeResponse` above (`status: "OPEN"`), message
`"Dispute opened - we will review it and get back to you"`.

**Window rules (render these, don't re-derive them):**

- A parcel that was **never delivered is disputable at any time** — "it never
  arrived" is exactly what disputes are for. No delivery needed.
- A **delivered** parcel is disputable for **7 days** after delivery
  (per-cell config), whoever closed it. Confirming receipt does **not** waive
  the right to dispute.
- Nothing is disputable after the seller has been paid out or the parcel
  refunded.
- **One dispute per parcel, ever.** A resolved dispute cannot be re-opened —
  a second complaint is a support conversation, not this endpoint.

**Errors:**

| Status | `code` | When |
|---|---|---|
| 400 | (Bean Validation) | missing `reason` / oversize `detail` |
| 401 | `UNAUTHORIZED` | no/invalid token |
| 404 | `order_not_found` | not your order (same 404 as nonexistent — deliberate) |
| 404 | `fulfilment_not_found` | parcel isn't on that order |
| 409 | `order_not_paid` | order was never paid |
| 409 | `settlement_missing` | no settlement recorded yet — "contact support" |
| 409 | `dispute_already_raised` | this parcel was already disputed (ever) |
| 409 | `dispute_window_closed` | delivered more than 7 days ago |
| 409 | `settlement_already_paid_out` | seller already paid — "contact support" |
| 409 | `settlement_already_refunded` | already refunded |

The buyer is **notified automatically** when the operator resolves (via the
platform notification bell/SMS — nothing for the app to poll beyond the order
view).

---

## 4. Seller (`MERCHANT_ADMIN`)

Merchant scope comes from the session's ORGANIZATION (`orgId`, when the user
is its OWNER/ADMIN and it holds `marketplace`) — there is no way (and no need)
to pass a merchant id. A session selling for no organization gets
`403 FORBIDDEN`.

### 4.1 Escrow state on the fulfilment queue (additive)

`GET /marketplace/fulfilments` — each item now carries two nullable fields:

```json
{ "settlementStatus": "HELD", "settlementNetCents": 4798 }
```

Render them on the parcel card — they answer "why haven't I been paid for
this one?" without a second screen. Absent on pre-V10 parcels whose order was
never paid.

### 4.2 My settlements (the money ledger)

```
GET /marketplace/settlements?status=RELEASABLE&page=0&size=20
```

- `status` optional (`HELD` · `RELEASABLE` · `DISPUTED` · `PAID_OUT` ·
  `REFUNDED`), newest first, page capped at 50.

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "items": [
      {
        "id": "9d2f7a10-3b64-4c8e-a1f5-6e7b8c9d0a12",
        "orderId": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
        "fulfilmentId": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
        "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
        "status": "RELEASABLE",
        "grossCents": 4798,
        "commissionCents": 0,
        "netCents": 4798,
        "currency": "USD",
        "releasedAt": "2026-09-16T14:05:00Z",
        "createdAt": "2026-09-14T11:20:10Z"
      }
    ],
    "page": 0, "size": 20, "totalItems": 1, "totalPages": 1
  }
}
```

Nullable fields appear only when set (`NON_NULL` serialization):
`releasableAt` (a seller-closed delivery waiting out its grace — render as
"clears on {date}"), `releasedAt`, `paidOutAt` + `payoutReference` (the
transfer reference — the answer to "where is my money?"), `refundedAt` +
`refundReference`.

`netCents = grossCents - commissionCents`, always. Commission is 0 until the
platform decides to charge one; render the split, don't assume 0.

### 4.3 Where is my money (wallet header)

```
GET /marketplace/settlements/summary
```

```json
{
  "data": {
    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
    "totals": [
      { "status": "HELD",       "parcels": 3,  "netCents": 12500 },
      { "status": "RELEASABLE", "parcels": 12, "netCents": 185000 },
      { "status": "PAID_OUT",   "parcels": 113, "netCents": 1730000 }
    ]
  }
}
```

A status with nothing in it is **not listed** — render missing statuses as
zero. Suggested labels: HELD "awaiting delivery", RELEASABLE "clearing /
ready for payout", DISPUTED "on hold — disputed", PAID_OUT "paid",
REFUNDED "refunded to buyer".

---

## 5. Operator (Foundry console, `SUPER_ADMIN`)

### 5.1 Reading any merchant's money

Same two endpoints as §4, plus: `GET /marketplace/settlements?merchantId=…`
narrows to one merchant (omit for fleet-wide); the summary **requires**
`?merchantId=` (400 `merchant_id_required` without — an admin token has no
merchant of its own). For a MERCHANT_ADMIN caller `merchantId` is ignored,
so one screen can serve both roles.

### 5.2 The dispute queue

```
GET /marketplace/settlements/disputes?status=OPEN&page=0&size=20
```

Default `status=OPEN`. **Oldest first (FIFO)** — serve the buyer who has
waited longest. Items are the same `DisputeResponse` as §3.1 (here `detail`,
the buyer's words, matters).

### 5.3 Resolving a dispute

```
PATCH /marketplace/settlements/disputes/{id}
```

```json
{
  "action": "REFUND",
  "resolutionNote": "Courier photo shows the parcel left at the wrong address.",
  "refundReference": "RFND-2026-09-30-07"
}
```

- `action` — **required**: `RELEASE` (seller was right — money returns to
  RELEASABLE and rides the next payout run) or `REFUND` (buyer was right).
- `refundReference` — the transfer reference of the refund the operator
  **executed on the rails first**. The console flow is: make the transfer,
  then record it here.
- **200** → resolved `DisputeResponse`. **404** `dispute_not_found`.
  **409** `dispute_not_open` — already resolved; a decision is made exactly
  once. The buyer is notified automatically either way.

### 5.4 The payout report (what to pay)

```
GET /marketplace/settlements/payout-report        → text/csv attachment
```

`merchantId,displayName,parcels,netCents,currency` — every merchant with
RELEASABLE money, **biggest owed first**. The date is in the **filename**
(`marketplace-payout-report-2026-09-16.csv`), never a preamble row.

### 5.5 Recording a payout run

```
POST /marketplace/settlements/pay-out
```

```json
{ "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
  "payoutReference": "PAYOUT-2026-09-30-01" }
```

Marks **every** RELEASABLE settlement of that merchant `PAID_OUT` under one
reference — one transfer per merchant, covering everything cleared. Call
**after** making the transfer.

**200:**

```json
{
  "data": {
    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
    "parcels": 12,
    "totalNetCents": 185000,
    "currency": "USD",
    "payoutReference": "PAYOUT-2026-09-30-01"
  }
}
```

**409** `nothing_releasable` — nothing to pay (also what a double-submit
gets: the first run already took everything).

---

## 6. Gotchas checklist

- [ ] **Cents everywhere.** Every `*Cents` field is an integer in minor units.
- [ ] **The buyer's surface is the ORDER.** Don't call `/marketplace/settlements`
      from the super app — a CUSTOMER token gets 403.
- [ ] **`dispute` / `settlementStatus` / `settlementNetCents` are nullable** —
      absent keys, not `null` literals, on parcels that predate V10 or orders
      never paid.
- [ ] **Don't re-derive the dispute window client-side.** Offer the button;
      render the 409 codes as friendly copy (`dispute_window_closed` →
      "this order is too old to dispute").
- [ ] **`RELEASE`/`REFUND` execute nothing.** The console records what the
      operator already did on the rails. Order of operations: transfer first,
      record second.
- [ ] **Summary lists only non-empty statuses** — missing = zero.
- [ ] **404 means "not yours" as often as "not there"** (owner-masking) — don't
      debug it as a routing problem.
- [ ] **One dispute per parcel, ever.** Hide the dispute button once
      `dispute` is present on the parcel, whatever its status.
- [ ] **CSV filename carries the date** — save it as served; there is no date
      column.
