# Marketplace Checkout Journey — Frontend Integration

**Service:** `marketplace-service` · **PR:** #46 · **Migration:** V9

The super-app's buyer journey, end to end: **browse → cart → delivery address →
checkout quote → order → pay → track fulfilment → confirm receipt → review.**

---

## 1. What changed, and why you need this

The marketplace had both ends of the journey and not the middle or the tail.

There was **no cart** (every client held one in local storage, so it died with
the device), **no way to see what an order would cost** before creating one —
and creating one reserves stock, so "let me just check the total" cost a
merchant an inventory hold — and **no way to say where the goods should go**.
After payment, `PAID` was the last thing that ever happened: nothing to track,
and no queue for the seller to work.

Paying already worked, and is unchanged. What's new is that the order now
**tells you how to pay** instead of you hardcoding it.

> **Nothing you already call has changed shape in a breaking way.** Every new
> field on `OrderResponse` is additive, and an order created the old way (no
> `deliveryMethod`) still behaves exactly as before — see §9.

---

## 2. Base URL, auth, headers

| | |
|---|---|
| Base URL | the API gateway origin (`/foundry` prefix in staging/prod, stripped by nginx) |
| Route | `/marketplace/**` → `lb://marketplace-service` — **already existed, no gateway change** |
| Auth | `Authorization: Bearer <jwt>` — a normal fleet user JWT |
| Role | **`CUSTOMER`** for everything in §3–§7; **`MERCHANT_ADMIN`** for §8 |
| `X-Tenant-Id` | **Not required** anywhere in this guide |
| `Idempotency-Key` | **Required on `POST /marketplace/orders` only** (§6) |
| Content-Type | `application/json` on every write |

> **Envelope.** `{ "code": "OK", "message": "Success", "data": … }`. Errors carry
> a slug `code` and usually no `data`: `{ "code": "cart_full", "message": "…" }`.
> Two errors DO carry `data` — see §10.

> **Money is always MINOR UNITS (cents, integer).** `3750` is $37.50. Nothing on
> the wire is ever a decimal. Divide by 100 for display only.

> **Everything is scoped to the caller by SHAPE.** No endpoint here takes a user
> id — your cart, your addresses and your orders come from the token. Another
> buyer's resource is the same `404` as a nonexistent one, deliberately.

---

## 3. Cart

Stores **quantities only**. Price, stock, images and availability are resolved
**live from the catalogue on every read**, so the cart never quotes a price the
catalogue has moved past. **The cart holds no stock** — units are reserved once,
at order creation.

**Every call, mutations included, returns the whole priced cart.** Never re-read
after a change, and never keep a client-side sum.

### 3.1 `GET /marketplace/cart`

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "items": [
      {
        "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
        "listing": {
          "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
          "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
          "title": "Wireless Bluetooth Speaker",
          "priceCents": 2399,
          "currency": "USD",
          "stockQty": 150,
          "status": "ACTIVE",
          "ratingAvg": 5.0,
          "reviewCount": 1,
          "imageUrl": "/marketplace/catalog/b4c2f0a8-.../image",
          "imageUrls": ["/marketplace/catalog/b4c2f0a8-.../images/5f0d8c2a-..."],
          "seller": { "merchantId": "7e2a9c41-...", "displayName": "Sunrise Electronics", "verified": true }
        },
        "quantity": 2,
        "lineTotalCents": 4798,
        "addedAt": "2026-09-14T11:02:44Z"
      }
    ],
    "lineCount": 1,
    "totalQuantity": 2,
    "subtotalCents": 4798,
    "currency": "USD",
    "checkoutReady": true
  }
}
```

- `listing` is the **full catalogue summary** — render the row from this, never
  re-fetch per line.
- `totalQuantity` is your cart badge. `lineCount` is distinct listings.
- `subtotalCents` counts only the lines that can actually be bought.
- **Gate your Checkout button on `checkoutReady`.** Don't re-derive it.
- **Delivery is NOT in the subtotal** — it isn't known until a method is chosen.

### 3.2 A line that went out of stock stays visible

```json
{
  "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
  "listing": { "title": "Solar Lantern 20W", "priceCents": 1550, "stockQty": 3, "...": "..." },
  "quantity": 5,
  "lineTotalCents": 0,
  "issue": {
    "reason": "INSUFFICIENT_STOCK",
    "message": "Only 3 left of Solar Lantern 20W",
    "requestedQty": 5,
    "availableQty": 3,
    "unitPriceCents": 1550
  }
}
```

We deliberately **do not drop it** — a vanishing line is how a shopper reaches
checkout with a total they don't recognise. Render the `issue.message` on the
row and let them fix it. `lineTotalCents` is `0` and the line is excluded from
`subtotalCents`, so `checkoutReady` is `false`.

`reason` is a **string, not an enum** — render an unrecognised one, don't choke.
Today: `LISTING_UNAVAILABLE` (gone, off sale, or another currency — drop the
line) and `INSUFFICIENT_STOCK` (`availableQty` says how many; `0` = sold out).

### 3.3 `POST /marketplace/cart/items` — add

```json
{ "listingId": "b4c2f0a8-...", "quantity": 1 }
```

`quantity` defaults to `1`. **This ADDS to what's already there** and is
**clamped** at the per-item cap (25) rather than refused — someone tapping `+`
past the cap wants the cap, not an error.

The listing must exist but need **not** be in stock or on sale: the cart carries
the issue, and checkout is where it's finally refused.

### 3.4 `PUT /marketplace/cart/items/{listingId}` — set an exact quantity

```json
{ "quantity": 3 }
```

Your stepper control's write. **A different endpoint from add on purpose:** "make
it 3" and "add 3 more" are different intentions, and collapsing them is how a
retried request silently buys six. Idempotent — safe to retry.

- Above the cap → `400 invalid_quantity` (**refused, not clamped** — you named
  the number).
- `quantity: 0` → `400`. **Use DELETE to remove**, so a zero can never become a
  silent delete on a retry.

### 3.5 `DELETE /marketplace/cart/items/{listingId}` · `DELETE /marketplace/cart`

Both **idempotent** — removing what isn't there, or clearing an empty cart, is a
`200` no-op. Your remove button can retry blindly.

### 3.6 Cart errors

| Code | HTTP | When |
|---|---|---|
| `listing_not_found` | 404 | No such listing |
| `cart_full` | 409 | 20 distinct listings — the most an order may carry |
| `invalid_quantity` | 400 | `PUT` above the per-item cap, or `< 1` |

---

## 4. Delivery addresses

The buyer-side equivalent of "my existing payment methods": saved once, picked
at checkout, never retyped on a phone keyboard.

### 4.1 `GET /marketplace/addresses`

Default first, then newest. An empty list is normal for a new shopper.

```json
{
  "code": "OK",
  "message": "Success",
  "data": [
    {
      "id": "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45",
      "label": "Home",
      "recipientName": "Tariro Moyo",
      "recipientMsisdn": "+263771234567",
      "line1": "14 Samora Machel Ave",
      "line2": "Flat 3B",
      "city": "Harare",
      "area": "Avondale",
      "landmark": "Opposite the clinic, blue gate",
      "defaultAddress": true,
      "createdAt": "2026-09-12T08:10:22Z",
      "updatedAt": "2026-09-12T08:10:22Z"
    }
  ]
}
```

### 4.2 `POST /marketplace/addresses` · `PUT /{id}` · `DELETE /{id}` · `PUT /{id}/default`

```json
{
  "label": "Home",
  "recipientName": "Tariro Moyo",
  "recipientMsisdn": "0771234567",
  "line1": "14 Samora Machel Ave",
  "line2": "Flat 3B",
  "city": "Harare",
  "area": "Avondale",
  "landmark": "Opposite the clinic, blue gate",
  "makeDefault": true
}
```

Required: `recipientName`, `recipientMsisdn`, `line1`, `city`. Everything else
optional.

- **`recipientName`/`recipientMsisdn` are NOT the buyer's.** Ordering something
  delivered to a relative is the common case, and the number a courier rings is
  whoever is at the door. Default the fields to the buyer's own details in your
  UI, but let them be changed.
- **Send what the shopper typed.** We normalise the msisdn to E.164 server-side
  (`0771234567` → `+263771234567`) and sanitize the free text. Don't pre-format.
- **The FIRST address saved is the default** whatever `makeDefault` says.
- `PUT /{id}` with `makeDefault: true` promotes it. There is deliberately **no
  way to clear** the default — a buyer with addresses and no default would be
  asked a question at every checkout. Move it by promoting the other one.
- **Deleting the default promotes the most recent survivor.**

| Code | HTTP | When |
|---|---|---|
| `invalid_msisdn` | 400 | Not a dialable number. We **reject**, never strip — these numbers exist to be rung |
| `invalid_address` | 400 | A required field was empty (or was nothing but markup) |
| `address_not_found` | 404 | Not yours, or doesn't exist — indistinguishable on purpose |
| `address_limit_reached` | 409 | 25 saved addresses |

---

## 5. Checkout

### 5.1 `GET /marketplace/checkout/options` — fetch once, cache for the session

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "deliveryMethods": ["DELIVERY", "COLLECTION"],
    "deliveryFeeCents": 0,
    "currency": "USD",
    "paymentMethods": [
      {
        "rail": "INNBUCKS_CODE",
        "label": "InnBucks app",
        "description": "Approve the payment code in your InnBucks app.",
        "completion": "APPROVE_IN_APP"
      }
    ],
    "paymentEndpoint": "POST /payments",
    "paymentOrderType": "MARKETPLACE"
  }
}
```

> **Read the payment rails from here. Do not hardcode them.** Which rails a cell
> can collect on is a deployment fact — an app that assumed all three would
> offer a buyer a method that dead-ends in a `503` from the payments service.
> Likewise `deliveryMethods`: a market with no courier arrangement drops
> `DELIVERY` and you must stop offering it.

`completion` tells you which screen to route to after `POST /payments` returns:

| `completion` | Rail | What to do |
|---|---|---|
| `APPROVE_IN_APP` | `INNBUCKS_CODE` | Render the returned code + QR, then poll |
| `CARD_WIDGET` | `ZIMSWITCH_CARD` | Render the returned COPYandPAY widget |
| `PHONE_PROMPT` | `ECOCASH` | "Approve the prompt on your phone", then poll |

An unrecognised value → poll and show a generic "complete your payment" screen
rather than failing.

### 5.2 `POST /marketplace/checkout/quote` — the checkout screen

**Reserves nothing. Changes nothing. Call it as often as the shopper changes
method or address.**

```json
{
  "fromCart": true,
  "deliveryMethod": "DELIVERY",
  "deliveryAddressId": "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45"
}
```

Or, for Buy Now, `items` instead of `fromCart`:

```json
{
  "items": [{ "listingId": "b4c2f0a8-...", "quantity": 2 }],
  "deliveryMethod": "COLLECTION"
}
```

> **Send exactly ONE of `fromCart` and `items`.** Both → `400 ambiguous_basket`.
> This is deliberate: a client that believes it sent a Buy Now and gets the whole
> cart has bought things the shopper never confirmed.

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "items": [ /* same shape as a cart line, §3.1 */ ],
    "lineCount": 1,
    "totalQuantity": 2,
    "subtotalCents": 4798,
    "deliveryFeeCents": 200,
    "totalCents": 4998,
    "currency": "USD",
    "deliveryMethod": "DELIVERY",
    "deliveryMethods": ["DELIVERY", "COLLECTION"],
    "deliveryAddress": { "id": "6f1c9d20-...", "city": "Harare", "...": "..." },
    "checkoutReady": true,
    "paymentMethods": [ /* §5.1 */ ]
  }
}
```

- **`totalCents = subtotalCents + deliveryFeeCents`.** That's what will be
  charged.
- `deliveryAddress` is absent for `COLLECTION`.
- **The same body works on `POST /marketplace/orders`** — the quote is a dry run.
- Totals are what the order will produce **as long as nothing sells out in
  between**. They're not a held price; the order recomputes from the listings.

> **A basket with problems is a `200`, not an error.** `checkoutReady: false`
> plus a `rejections` array (and `issue` on each bad line) — the shopper has to
> *see* the basket to fix it. Only a malformed request or a missing address is
> an error.

| Code | HTTP | When |
|---|---|---|
| `ambiguous_basket` | 400 | Both `fromCart` and `items` |
| `invalid_items` | 400 | Neither |
| `cart_empty` | 400 | `fromCart: true` with an empty cart |
| `delivery_address_required` | 400 | `DELIVERY` and the buyer has no saved address |
| `delivery_method_unavailable` | 422 | This cell doesn't offer that method |

---

## 6. Creating the order

`POST /marketplace/orders` — **`Idempotency-Key` header required.**

```json
{
  "fromCart": true,
  "deliveryMethod": "DELIVERY",
  "deliveryAddressId": "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45"
}
```

Same body as the quote. `deliveryAddressId` may be omitted on a `DELIVERY` order
to use the buyer's default.

```json
{
  "code": "CREATED",
  "message": "Created",
  "data": {
    "id": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
    "orderRef": "MKT-4F9A1C22B7D3",
    "status": "PENDING_PAYMENT",
    "subtotalCents": 4798,
    "deliveryFeeCents": 200,
    "totalCents": 4998,
    "currency": "USD",
    "deliveryMethod": "DELIVERY",
    "deliveryAddress": {
      "recipientName": "Tariro Moyo",
      "recipientMsisdn": "+263771234567",
      "line1": "14 Samora Machel Ave",
      "line2": "Flat 3B",
      "city": "Harare",
      "area": "Avondale",
      "landmark": "Opposite the clinic, blue gate"
    },
    "expiresAt": "2026-09-14T11:32:44Z",
    "createdAt": "2026-09-14T11:02:44Z",
    "items": [ { "listingId": "…", "titleSnapshot": "…", "unitPriceCents": 2399, "quantity": 2, "lineTotalCents": 4798 } ],
    "payment": {
      "endpoint": "POST /payments",
      "orderType": "MARKETPLACE",
      "orderRef": "MKT-4F9A1C22B7D3",
      "amountCents": 4998,
      "currency": "USD",
      "payBefore": "2026-09-14T11:32:44Z",
      "methods": [ /* §5.1 */ ]
    },
    "fulfilments": []
  }
}
```

- **Stock is reserved now** and held until `expiresAt`, the buyer cancels, or
  payment confirms.
- **Ordering `fromCart` removes the ordered lines from the cart** once it
  commits. Anything else stays. A *failed* order never clears the cart.
- `deliveryAddress` here is a **snapshot** — no `id`. Editing or deleting the
  book entry afterwards will never change it.
- `fulfilments` is empty until paid. There's nothing to pack until money moves.

---

## 7. Paying

**Marketplace-service does not collect money.** Use the order's `payment` block:

```
POST /payments
Authorization: Bearer <same customer jwt>
Content-Type: application/json

{
  "orderType": "MARKETPLACE",
  "orderRef": "MKT-4F9A1C22B7D3",
  "paymentRail": "INNBUCKS_CODE"
}
```

> **Send `orderRef`, NOT the order's `id`.** The payments service addresses
> marketplace orders by ref.
>
> **Never send an amount.** It reads the amount from the order.

Then poll the payments service as you already do for bookings. When it confirms,
the marketplace order moves to `PAID` on its own — re-read
`GET /marketplace/orders/{id}` and you'll see `status: "PAID"`, `paidAt` set, the
`payment` block **gone** (a paid order isn't asking to be paid for) and
`fulfilments` populated.

**One active payment per order across all rails.** While an attempt is open on
one rail, posting with another returns the open attempt's receipt unchanged;
switch rails only after it lapses.

---

## 8. Tracking fulfilment

Work is tracked **per SELLER**. A cart spanning two sellers becomes two parcels,
each moving on its own clock.

```json
"fulfilmentStatus": "DISPATCHED",
"fulfilments": [
  {
    "id": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
    "sellerName": "Sunrise Electronics",
    "status": "DISPATCHED",
    "dispatchNote": "Swift Couriers, waybill 88213",
    "dispatchedAt": "2026-09-15T09:20:00Z",
    "items": [ /* only this seller's lines */ ]
  }
]
```

- **`fulfilmentStatus` is the LEAST advanced parcel**, so `DELIVERED` always
  means *everything* arrived. Use it for the one-line status on the orders list;
  render `fulfilments[]` on the detail screen.
- **Absent** until the order is paid, and on a cancelled/expired order.
- `dispatchNote` is the only thing the buyer learns about how the goods are
  coming — show it prominently.

### 8.1 Label by delivery method

`PREPARING → DISPATCHED → DELIVERED` is one vocabulary for both methods. **Read
the order's `deliveryMethod` and word it accordingly:**

| Status | `DELIVERY` | `COLLECTION` |
|---|---|---|
| `PREPARING` | "Seller is preparing your order" | "Seller is preparing your order" |
| `DISPATCHED` | "On its way" | "Ready to collect" |
| `DELIVERED` | "Delivered" | "Collected" |

### 8.2 `POST /marketplace/orders/{id}/fulfilments/{fulfilmentId}/received`

The buyer confirming receipt — closes **one** parcel and returns the whole order.
A multi-seller order is confirmed one seller at a time, because that's how the
goods actually arrive.

`409 illegal_fulfilment_state` if the parcel is already closed. That's normal:
**the seller can also close it themselves** (you'll see `deliveredBy: "MERCHANT"`
rather than `"BUYER"`), so hide the button when `status` is already `DELIVERED`.

### 8.3 Seller side (`MERCHANT_ADMIN`)

| Endpoint | Purpose |
|---|---|
| `GET /marketplace/fulfilments?status=PREPARING&page=&size=` | The queue — **oldest first**, so the longest-waiting order never starves |
| `POST /marketplace/fulfilments/{id}/dispatch` with `{ "note": "Swift Couriers, waybill 88213" }` | Sent, or ready at the counter |
| `POST /marketplace/fulfilments/{id}/delivered` | Handed over |

Each row carries the **destination** and only **that seller's** lines and
subtotal — never the order total. `merchantId` is ignored for a `MERCHANT_ADMIN`
(always their own scope); `SUPER_ADMIN` may use it to narrow.

---

## 9. Migrating an existing client

**Nothing breaks.** In order of least to most work:

1. **Change nothing.** An order with no `deliveryMethod` defaults to
   `COLLECTION` — exactly what an order meant before delivery existed here — so
   no address is demanded and no fee is added. Totals are unchanged.
2. **Read the new fields.** `subtotalCents` / `deliveryFeeCents` on orders you
   already render; `payment.methods` instead of a hardcoded rail list.
3. **Adopt the cart and the quote**, then delivery and fulfilment tracking.

One thing to check now: **stop hardcoding payment rails** and read them from
`GET /marketplace/checkout/options` or the order's `payment.methods`.

---

## 10. The two errors that carry `data`

Everything else is `{ code, message }`. These two carry per-line detail so a
cart with two problems is corrected in one pass, not two round-trips:

**`422 listing_unavailable`** / **`409 insufficient_stock`** on
`POST /marketplace/orders`:

```json
{
  "code": "insufficient_stock",
  "message": "Insufficient stock for listing 9c2e8a4d-…",
  "data": {
    "rejections": [
      { "listingId": "9c2e8a4d-…", "reason": "INSUFFICIENT_STOCK", "message": "Only 3 left of Solar Lantern 20W", "requestedQty": 5, "availableQty": 3, "unitPriceCents": 1550 },
      { "listingId": "5e7a9b1c-…", "reason": "LISTING_UNAVAILABLE", "message": "Listing 5e7a9b1c-… is not available", "requestedQty": 1 }
    ]
  }
}
```

Render **every** entry. The top-level `code`/`message` names only the first
offender (kept that way for existing clients) — don't show just that one.

The order is refused **as a whole**; a partially-fulfilled order is never
created.

---

## 11. Gotchas checklist

- [ ] **Money is cents, always.** Never send or expect a decimal.
- [ ] **Send `orderRef`, not `id`, to `POST /payments`.** And never an amount.
- [ ] **Read payment rails from the API**, not a hardcoded list — a cell
      advertises only what it can collect on.
- [ ] **Gate Checkout on `checkoutReady`**, don't re-derive it.
- [ ] **Send exactly one of `fromCart` / `items`.**
- [ ] **Add vs set are different endpoints.** `POST /cart/items` accumulates;
      `PUT /cart/items/{id}` sets exactly. Use PUT for the stepper.
- [ ] **`DELETE` removes a line. Quantity `0` is rejected.**
- [ ] **Don't drop cart lines with an `issue`** — show them so they can be fixed.
- [ ] **Don't pre-format the msisdn.** Send what was typed; we normalise.
- [ ] **The recipient isn't the buyer** — default the fields, allow changes.
- [ ] **Label `DISPATCHED`/`DELIVERED` by the order's `deliveryMethod`** —
      "Ready to collect" vs "On its way".
- [ ] **`fulfilmentStatus` is the least advanced parcel.** Don't report an order
      delivered off one parcel.
- [ ] **Hide "Confirm receipt" once the parcel is `DELIVERED`** — the seller may
      have closed it.
- [ ] **A `422`/`409` on order creation may carry `data.rejections`** — render
      every entry.
- [ ] **`Idempotency-Key` is required on order creation only**, and is per
      buyer. Reuse it on retry; a new attempt needs a new key.

---

## 12. Not built, deliberately

- **Refunds.** A seller who can't fulfil a paid order is still an operator
  problem — neither this service nor payment-service models a refund yet.
- **Per-merchant or per-zone delivery rates.** The fee is flat per cell and
  **`0` by default**; a multi-seller order ships in several parcels and pays it
  once. A real rate card is separate work, not a number we'd invent.
- **Order cancellation after payment.** `PAID` is terminal; cancel only works
  while `PENDING_PAYMENT`.
