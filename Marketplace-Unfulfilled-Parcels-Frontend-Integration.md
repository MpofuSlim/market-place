# Marketplace Unfulfilled Parcels & Refunds — Frontend Integration

**Service:** `marketplace-service` · **Migration:** V12

What happens when a seller cannot supply goods a buyer has already paid for.
Three surfaces: the **seller's** (merchant app), the **buyer's** (super app),
and the **operator's** (back-office console).

---

## 1. What changed, and why you need this

A seller who was out of stock had **no action available**. The fulfilment queue
offered exactly two buttons — Dispatch and Delivered — so the only honest thing
to do was nothing. And nothing was the worst outcome available:

- the parcel stayed `PREPARING` forever;
- the units stayed off the shelf, so the listing quietly shrank;
- the buyer's money stayed `HELD` in escrow **where no timer could reach it**
  (the release sweeper only looks at parcels a delivery has stamped), so it was
  never released and never refunded;
- and nothing anywhere noticed how long it had been sitting there.

V12 gives that state two exits:

```
  PREPARING ──decline──> UNFULFILLED (terminal)
                │
                ├─> stock goes back on the shelf
                ├─> settlement HELD ──> REFUND_DUE   (operator pays, records it)
                └─> buyer gets an SMS saying so

  and for the ones that got stuck anyway:
  HELD for > 14 days ──> shows up in GET /marketplace/settlements/stale
                         (+ the marketplace.settlements.stale gauge)
```

> **Nothing you already call changed in a breaking way.** One new seller
> endpoint, two new operator endpoints, one new value in each of two existing
> enums, and four new nullable response fields. Every existing field keeps its
> meaning.

**The one thing you must handle even if you build no new screen:**
`FulfilmentStatus` and `SettlementStatus` each gained a value. A client that
switches exhaustively on either will hit a case it does not know. See §7.

---

## 2. Base URL, auth, headers

| | |
|---|---|
| Base URL | the API gateway origin (`/foundry` prefix in staging/prod, stripped by nginx) |
| Route | `/marketplace/**` → `lb://marketplace-service` — **already existed, no gateway change** |
| Auth | `Authorization: Bearer <jwt>` — a normal fleet user JWT |
| Roles | **`MERCHANT_ADMIN`** for §3; **`CUSTOMER`** for §4; **`SUPER_ADMIN`** for §5 |
| `X-Tenant-Id` | **Not required** anywhere in this guide |
| Content-Type | `application/json` on every write |

> Envelope `{ "code": "OK", "message": "…", "data": … }`; errors carry a slug
> `code`. Money is **minor units** (cents, integers). Timestamps are UTC
> ISO-8601 with `Z`.

---

## 3. The seller declines a parcel (`MERCHANT_ADMIN`)

### `POST /marketplace/fulfilments/{fulfilmentId}/unfulfillable`

The third button on the parcel card, beside Dispatch and Delivered. Label it
for what it is — *"I can't supply this"* — not "Cancel": the buyer has already
paid and this is an admission, not a cancellation.

**Request**

```json
{ "reason": "Supplier let us down, no stock until October" }
```

| field | required | notes |
|---|---|---|
| `reason` | **yes** | 1–255 chars. Shown **verbatim to the buyer** and included in their SMS. Sanitized server-side; a value that is nothing but markup is refused. |

Make `reason` a required field in the UI with a short helper — *"the buyer sees
this"*. A refusal with no explanation is what sends a buyer to support instead
of back to the catalogue.

**Response `200`** — the usual `MerchantFulfilmentResponse`, with three fields
that matter here:

```json
{
  "code": "OK",
  "message": "Parcel closed - the buyer has been told",
  "data": {
    "id": "7f3c1a92-64d1-4f0e-9a77-0b1d2e3f4a5b",
    "orderId": "2a9e7c18-3b44-4d2f-8e6a-9c0d1b2a3f4e",
    "orderRef": "MKT-4F2A9C1B77D0",
    "merchantId": "c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e5f",
    "status": "UNFULFILLED",
    "unfulfilledAt": "2026-09-18T09:14:22Z",
    "unfulfilledReason": "Supplier let us down, no stock until October",
    "deliveryMethod": "COLLECTION",
    "items": [
      { "listingId": "9c2e8a4d-…", "titleSnapshot": "Solar Lantern 20W",
        "unitPriceCents": 1550, "quantity": 2, "lineTotalCents": 3100 }
    ],
    "subtotalCents": 3100,
    "currency": "USD",
    "settlementStatus": "REFUND_DUE",
    "settlementNetCents": 3100
  }
}
```

**`settlementStatus: "REFUND_DUE"` is the seller's answer to "so what happened
to my money?"** — render it on the parcel card as *"Refunded to the buyer"*, not
as a pending payout.

### What it does, all in one transaction

1. The parcel closes as `UNFULFILLED` — **terminal**, it cannot be re-opened,
   dispatched or delivered.
2. **The stock goes back**, exactly once. If that takes a listing from 0 to in
   stock, its favouriters get the normal back-in-stock alert.
3. **The money turns around**: a `HELD` settlement becomes `REFUND_DUE`.
4. **The buyer is told by SMS**, after the transaction commits.

### Errors

| status | `code` | when | what to show |
|---|---|---|---|
| `400` | `unfulfilled_reason_required` | `reason` blank, or nothing but markup | inline field error |
| `400` | (bean validation) | `reason` missing or > 255 chars | inline field error |
| `403` | `FORBIDDEN` | caller sells for no organization (a customer, none chosen, or no `marketplace` product) | route to the organization picker |
| `404` | `fulfilment_not_found` | unknown parcel, **or another seller's** | "This parcel is no longer available" |
| `409` | `illegal_fulfilment_state` | parcel is already `DELIVERED` or `UNFULFILLED`, or is a `DISPATCHED` **DELIVERY** parcel | refresh the queue and show the real state |

**`409` on a dispatched DELIVERY parcel is deliberate and permanent.** Once
goods are with a courier, "I cannot fulfil this" has stopped being true — what
happens next is a delivery failure, and the buyer's dispute is the path for it.
Hide or disable the button for a DELIVERY parcel not in `PREPARING` rather than
letting the seller discover this as an error.

### Collections the buyer never came for ("Not collected")

On a **COLLECTION** order the same endpoint is also allowed from `DISPATCHED`
(ready at the counter). It is how a seller ends a collection the buyer never
picked up — the goods never left the counter, so returning them and refunding
the buyer is the truth. It matters more now that a seller **cannot** mark a
collection delivered on their own word (`POST /{id}/delivered` →
`409 collect_code_required`).

- Label it **"Not collected"** on a DISPATCHED collection parcel, and ask for how
  long they waited in `reason` (e.g. *"Not collected within 5 days"*).
- Same effects: parcel `UNFULFILLED`, stock returned, money `REFUND_DUE`.
- The buyer is told it was **not picked up**, not that the seller could not
  supply it (see the SMS below).

**`409` on an already-declined parcel is what a double-tap gets.** Nothing is
re-applied: the stock is not returned a second time. Treat it as "already
done" and refresh.

---

## 4. The buyer's side (`CUSTOMER`)

No new endpoint. `GET /marketplace/orders/{id}` grows two nullable fields on
each entry of `fulfilments[]`:

```json
{
  "id": "2a9e7c18-3b44-4d2f-8e6a-9c0d1b2a3f4e",
  "orderRef": "MKT-4F2A9C1B77D0",
  "status": "PAID",
  "fulfilmentStatus": "UNFULFILLED",
  "fulfilments": [
    {
      "id": "7f3c1a92-64d1-4f0e-9a77-0b1d2e3f4a5b",
      "sellerName": "Chipo Electronics",
      "status": "UNFULFILLED",
      "unfulfilledAt": "2026-09-18T09:14:22Z",
      "unfulfilledReason": "Supplier let us down, no stock until October",
      "items": [ … ]
    }
  ]
}
```

Three things to get right on this screen:

1. **The order stays `PAID`.** Payment state and fulfilment state are different
   questions about the same order; `PAID` is what payment-service and the
   verified-purchase review gate both read. Do **not** render `status: "PAID"`
   as "on its way" — read `fulfilmentStatus` for that.
2. **`fulfilmentStatus` is the least-advanced LIVE parcel, and UNFULFILLED
   parcels are excluded from that roll-up.** A two-seller order where one seller
   declines and the other ships still reports `PREPARING` / `DISPATCHED` /
   `DELIVERED` for the half that is really moving. `fulfilmentStatus` is only
   `UNFULFILLED` when **every** parcel was declined. It is `null` when there are
   no parcels at all.
3. **Show `unfulfilledReason` verbatim.** It is the seller's own words and the
   only explanation the buyer gets.

### The SMS the buyer receives

```
Sorry - a seller cannot supply part of your InnBucks Marketplace order
MKT-4F2A9C1B77D0. Reason - out of stock. A refund of USD 31.00 is being
arranged. Ref MKT-4F2A9C1B77D0
```

For a collection closed as not collected:

```
A collection from your InnBucks Marketplace order MKT-4F2A9C1B77D0 was not
picked up, so the seller has cancelled it. Reason - not collected within 5
days. A refund of USD 15.50 is being arranged. Ref MKT-4F2A9C1B77D0
```

When the parcel's money could **not** be turned around (it was already disputed,
released or paid out), the amount sentence is replaced by *"Our support team
will be in touch."* — the platform never names a refund the ledger has not
queued. Your copy should match: read `settlementStatus` rather than assuming.

### Disputing after a decline

`POST /marketplace/orders/{id}/fulfilments/{fid}/dispute` on a parcel whose
money is already `REFUND_DUE` returns:

```json
{
  "code": "refund_already_due",
  "message": "The seller could not supply this parcel - your refund is already being arranged"
}
```

with status `409`. Prefer to **hide the Dispute action** on a parcel that is
already `UNFULFILLED` — the refund is in motion and a dispute adds nothing.

---

## 5. The operator's side (`SUPER_ADMIN`)

### 5.1 `GET /marketplace/settlements?status=REFUND_DUE` — the refund queue

Existing endpoint, new filter value. These are the parcels the platform owes
money back on and has **not yet sent it**.

### 5.2 `POST /marketplace/settlements/{settlementId}/refund` — record what you sent

**Call this AFTER making the transfer.** This service moves no money; `REFUNDED`
means the money actually left.

```json
{ "refundReference": "RFND-2026-09-18-03" }
```

`200` returns the settlement:

```json
{
  "code": "OK",
  "message": "Refund recorded",
  "data": {
    "id": "5a6b7c8d-…",
    "orderId": "2a9e7c18-…",
    "merchantId": "c1d2e3f4-…",
    "status": "REFUNDED",
    "grossCents": 3100,
    "commissionCents": 0,
    "netCents": 3100,
    "currency": "USD",
    "refundDueAt": "2026-09-18T09:14:22Z",
    "refundedAt": "2026-09-18T11:02:10Z",
    "refundReference": "RFND-2026-09-18-03"
  }
}
```

| status | `code` | when |
|---|---|---|
| `400` | (bean validation) | `refundReference` blank or > 64 chars |
| `403` | `FORBIDDEN` | not a `SUPER_ADMIN` |
| `404` | `settlement_not_found` | unknown settlement id |
| `409` | `illegal_settlement_state` | the row is not `REFUND_DUE` or `DISPUTED` — including a second attempt on one already `REFUNDED` |

**One refund per parcel, not one per payout run.** That asymmetry is the shape
of the money: a payout is a single transfer to one merchant covering everything
cleared, while a refund goes back to the individual buyer of one order — a
batched reference would be proof to none of them.

A dispute resolved as `REFUND` still goes through
`PATCH /marketplace/settlements/disputes/{id}` as before; that path supplies its
own reference and does not pass through `REFUND_DUE`.

### 5.3 `GET /marketplace/settlements/stale` — money nobody is moving

```
GET /marketplace/settlements/stale?size=50
```

Settlements still `HELD` past the staleness threshold
(`marketplace.settlement.stale-after-days`, default **14**), **oldest first**.
Response is the standard settlement page shape.

These are the rows no timer can reach: the release sweeper matches a
`releasableAt` that only exists once a seller closes a parcel as delivered, so a
parcel that was never delivered is invisible to it. **Every row here is a buyer
who paid, a seller who never delivered and never declined, and nobody watching.**

**Nothing is decided for you, deliberately.** Auto-releasing would pay a seller
who never delivered; auto-refunding would punish one who is merely slow. The
two ways out are: chase the seller, or have them decline the parcel so the
refund queues itself.

Ops note for the dashboard: the gauge `marketplace.settlements.stale` carries
the same count, is registered at boot (so it reads `0` rather than being
absent), and is refreshed by a nightly sweep.

---

## 6. New and changed fields, at a glance

**`MerchantFulfilmentResponse`** (seller's queue, `GET /marketplace/fulfilments`)

| field | type | notes |
|---|---|---|
| `unfulfilledReason` | `string \| null` | seller's own words |
| `unfulfilledAt` | `string \| null` | UTC ISO-8601 |

**`FulfilmentResponse`** (inside `GET /marketplace/orders/{id}`)

| field | type | notes |
|---|---|---|
| `unfulfilledReason` | `string \| null` | same value, buyer-facing |
| `unfulfilledAt` | `string \| null` | UTC ISO-8601 |

**`SettlementResponse`**

| field | type | notes |
|---|---|---|
| `refundDueAt` | `string \| null` | when the refund was queued — **not** when it was paid |

All four are `null` on every parcel/settlement that has not been through this
path, which is almost all of them.

---

## 7. Gotchas checklist

- [ ] **`FulfilmentStatus` gained `UNFULFILLED`.** Any exhaustive `switch`
      needs the case. It is **terminal** and reachable only from `PREPARING`.
- [ ] **`SettlementStatus` gained `REFUND_DUE`.** Same. It sits between `HELD`
      and `REFUNDED` and means *decided, not yet transferred*.
- [ ] **Never render an unknown enum value as an error.** Default to showing the
      raw value rather than crashing the screen — this is the second value added
      to these enums and it will not be the last.
- [ ] **The order stays `PAID` after a decline.** Read `fulfilmentStatus`, not
      `status`, for "where are my goods".
- [ ] **`fulfilmentStatus` is only `UNFULFILLED` when EVERY parcel was
      declined** — declined parcels are excluded from the roll-up, not ranked
      into it.
- [ ] **Hide/disable "I can't supply this" outside `PREPARING` on a DELIVERY
      parcel.** A dispatched delivery is a permanent `409`, not a retryable one.
- [ ] **On a DISPATCHED COLLECTION parcel, show "Not collected"** — the same
      endpoint, and the only way to end a no-show.
- [ ] **A double-tap is a `409`, not a second restock.** Refresh, don't retry.
- [ ] **`reason` is mandatory and buyer-visible.** Say so in the field's helper
      text.
- [ ] **Don't promise a refund from the UI on the strength of the decline
      alone** — read `settlementStatus`. It is `REFUND_DUE` only when the money
      actually turned around; a parcel whose money was already disputed or paid
      out closes with the money untouched.
- [ ] **`refundDueAt` ≠ `refundedAt`.** The first is a decision, the second is
      money that left. Only `refundReference` proves a transfer.
- [ ] **Recording a refund is an operator action taken AFTER the transfer.**
      The button must not read as "issue refund" — nothing here moves money.
- [ ] **Money everywhere in this guide is minor units.** `3100` is `USD 31.00`.
- [ ] **All timestamps are UTC with `Z`.** Render in the viewer's locale; do no
      timezone arithmetic of your own on the way back in.
- [ ] **Another seller's parcel is a `404`, not a `403`** — by design, so parcel
      ids cannot be probed. Don't special-case it.
