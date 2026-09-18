# InnBucks Marketplace — Super App (Customer) Integration Guide

**Service:** `marketplace-service` · **Audience:** the customer-facing super app · **Schema:** V13

Everything a buyer does: browse → cart → address → quote → order → pay → track → confirm → review. This is the complete customer surface; the merchant and operator surfaces are in the **Admin Portal** guide.

---

## 1. Base URL, auth, envelope

| | |
|---|---|
| Base URL | the API gateway origin (`/foundry` prefix in staging/prod, stripped by nginx) |
| Route | `/marketplace/**` → `marketplace-service` |
| Auth | `Authorization: Bearer <jwt>` — a fleet user JWT minted by user-service |
| Role | **`CUSTOMER`** for everything except §2, which is anonymous |
| `X-Tenant-Id` | **Not required anywhere in this guide** |
| Content-Type | `application/json` on every write |

**Every response is the fleet envelope:**

```json
{ "code": "OK", "message": "…", "data": { … } }
```

Errors carry a slug in `code` — **branch on `code`, never on `message`**, which is human copy and may be reworded.

```json
{ "code": "insufficient_stock", "message": "Only 3 left of Solar Lantern 20W" }
```

**Two conventions that hold everywhere:**

- **Money is always minor units** (cents), as an integer. `4998` is `USD 49.98`. Never send or expect a decimal.
- **Timestamps are UTC ISO-8601 with `Z`.** Render in the viewer's locale; do no arithmetic of your own.

### Where the customer's token comes from

Super-app customers authenticate at the **InnBucks middleware**, then trade that for a fleet token at `POST /auth/exchange` on user-service. Everything below is gated on `hasRole('CUSTOMER')` and works with that token unchanged — this service never sees how the customer proved themselves.

---

## 2. Browsing — no login required

These are the only endpoints in this guide that work anonymously. **Do not force a login to browse.**

### `GET /marketplace/catalog` — the catalogue

ACTIVE listings only. Every parameter is optional and they all combine.

| param | values | notes |
|---|---|---|
| `q` | free text | case-insensitive, title + description |
| `category` | a category code | a **parent** code expands to its children |
| `condition` | `NEW` `USED_LIKE_NEW` `USED_GOOD` `USED_FAIR` | |
| `city` | free text | exact, case-insensitive match. No radius search yet |
| `merchantId` | uuid | "more from this seller" |
| `minPriceCents` / `maxPriceCents` | integer cents | **inclusive** window |
| `inStock` | `true` | `false` means *don't filter*, not *show sold out* |
| `sort` | `newest` (default) `price_asc` `price_desc` | |
| `page` / `size` | integers | `size` is capped server-side |

> **An unrecognised parameter is a `400 unknown_parameter`, naming the full supported set.** This is deliberate and browse-only: on a filtered endpoint, a typo'd filter that gets silently ignored returns a confidently wrong result set with a 200 on it. Fix the typo — do not retry.

**Response:**

```json
{
  "code": "OK",
  "data": {
    "items": [ { …ListingResponse… } ],
    "page": 0,
    "size": 20,
    "totalItems": 137
  }
}
```

### The `ListingResponse` shape — used on every listing surface

```json
{
  "id": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
  "title": "Solar Lantern 20W",
  "description": "Portable solar lantern with 12h battery",
  "categoryCode": "electronics",
  "categoryName": "Electronics",
  "condition": "NEW",
  "city": "Harare",
  "area": "Avondale",
  "priceCents": 1550,
  "currency": "USD",
  "stockQty": 8,
  "status": "ACTIVE",
  "ratingAvg": 4.5,
  "reviewCount": 12,
  "createdAt": "2026-09-01T09:15:00Z",
  "updatedAt": "2026-09-14T11:02:44Z",
  "imageUrl": "/marketplace/catalog/9c2e8a4d-…/image",
  "imageUrls": [ "/marketplace/catalog/9c2e8a4d-…/image", "…/images/{imageId}" ],
  "seller": {
    "merchantId": "7e2a9c41-…",
    "displayName": "Rudo Traders",
    "verified": true,
    "since": "2026-04-01T09:15:00Z"
  }
}
```

- **`ratingAvg` is `null` when unrated — never `0.0`.** Render "no reviews yet", not zero stars.
- **`seller.displayName` can be `null`** (the platform does not know every merchant's trading name). Render the listing without a seller name rather than showing a raw UUID.
- **`imageUrls` is the whole gallery, primary first.** `imageUrl` is just the primary, kept for older clients.

### `GET /marketplace/catalog/{id}` — one listing

Same shape. `404 listing_not_found` for an unknown id.

### `GET /marketplace/catalog/{id}/image` and `…/images/{imageId}`

Raw image bytes with the stored `Content-Type`, `X-Content-Type-Options: nosniff` and a 1-hour public cache. Use them directly as `<img src>`. jpeg / png / webp only.

### `GET /marketplace/categories` — the category tree

Two levels, seeded server-side. There is no runtime category admin, so it is safe to cache for the session (1h public cache header).

```json
{ "data": [ { "code": "electronics", "name": "Electronics",
              "children": [ { "code": "phones", "name": "Phones" } ] } ] }
```

### `GET /marketplace/catalog/merchants/{merchantId}` — the seller header

Badge, rating and live listing count in **one** call, so the seller page needs no client-side join.

```json
{
  "data": {
    "merchantId": "7e2a9c41-…",
    "displayName": "Rudo Traders",
    "verified": true,
    "since": "2026-04-01T09:15:00Z",
    "ratingAvg": 4.4,
    "reviewCount": 37,
    "activeListingCount": 18,
    "fulfilment": {
      "completedOrders": 42,
      "medianDispatchHours": 18,
      "buyerConfirmedPercent": 86
    }
  }
}
```

- **It never 404s.** An unknown merchant is a zeroed, nameless profile — the catalogue must not become an oracle for which merchant ids exist.
- **The whole `fulfilment` block is absent for a new seller**, and each figure inside it is `null` until it rests on enough completed parcels. Render "new seller" — never a zero, which reads as a verdict the platform has not earned.

### `GET /marketplace/catalog/{id}/reviews` — public reviews

Newest first. **The reviewer is anonymised**: a constant `"Verified buyer"` plus a stable handle (`Buyer-4f9a`) so a repeat reviewer is recognisable without being identifiable.

```json
{ "data": { "items": [ {
  "id": "…", "rating": 5, "comment": "Battery really does last all day.",
  "createdAt": "2026-09-10T08:00:00Z",
  "reviewerName": "Verified buyer", "reviewerHandle": "Buyer-4f9a" } ] } }
```

### `GET /marketplace/catalog/merchants/{merchantId}/rating`

The merchant's aggregate alone, when you don't need the whole profile. Never 404s.

---

## 3. The cart (`CUSTOMER`)

**The cart stores quantities and nothing else.** Price, stock and availability resolve **live on every read** — a cart can sit for weeks, and a copied price would be a promise the catalogue no longer makes. **It holds no stock**; nothing is reserved until an order is created.

### `GET /marketplace/cart`

```json
{
  "data": {
    "items": [
      {
        "listingId": "9c2e8a4d-…",
        "listing": { …ListingResponse… },
        "quantity": 2,
        "lineTotalCents": 3100,
        "issue": null,
        "addedAt": "2026-09-14T11:02:44Z"
      },
      {
        "listingId": "1a2b3c4d-…",
        "listing": { …ListingResponse… },
        "quantity": 1,
        "lineTotalCents": 0,
        "issue": {
          "listingId": "1a2b3c4d-…",
          "reason": "INSUFFICIENT_STOCK",
          "message": "Only 0 left of Garden Hose",
          "requestedQty": 1,
          "availableQty": 0
        }
      }
    ],
    "lineCount": 2,
    "totalQuantity": 3,
    "subtotalCents": 3100,
    "currency": "USD"
  }
}
```

> **A line that goes out of stock STAYS in the cart**, carrying its `issue` and contributing **0** to the subtotal. Show it struck through with the reason. Dropping it silently is how a shopper reaches checkout with a total they don't recognise.

### `POST /marketplace/cart/items` — add

```json
{ "listingId": "9c2e8a4d-…", "quantity": 1 }
```

**Add ACCUMULATES** and clamps at the per-item cap rather than erroring — someone tapping `+` past the cap wants the cap. `quantity` defaults to 1.

### `PUT /marketplace/cart/items/{listingId}` — set an exact quantity

```json
{ "quantity": 3 }
```

**PUT sets exactly** and **refuses** above the cap (the shopper named that number). **Quantity `0` is refused** — so a zero can never become a silent delete on a retry. Use DELETE.

### `DELETE /marketplace/cart/items/{listingId}` · `DELETE /marketplace/cart`

Remove one line; empty the cart.

---

## 4. Delivery addresses (`CUSTOMER`)

### `GET /marketplace/addresses` · `GET /marketplace/addresses/{id}`

```json
{
  "data": [ {
    "id": "…", "label": "Home",
    "recipientName": "Tariro Moyo", "recipientMsisdn": "+263771234567",
    "line1": "12 Josiah Tongogara Ave", "line2": null,
    "city": "Harare", "area": "Avondale",
    "landmark": "Opposite the clinic, blue gate",
    "defaultAddress": true,
    "createdAt": "…", "updatedAt": "…"
  } ]
}
```

### `POST /marketplace/addresses` · `PUT /marketplace/addresses/{id}`

```json
{
  "label": "Home",
  "recipientName": "Tariro Moyo",
  "recipientMsisdn": "0771234567",
  "line1": "12 Josiah Tongogara Ave",
  "city": "Harare",
  "area": "Avondale",
  "landmark": "Opposite the clinic, blue gate",
  "makeDefault": true
}
```

`recipientMsisdn` is normalised to E.164 server-side — render what comes back.

### `PUT /marketplace/addresses/{id}/default` · `DELETE /marketplace/addresses/{id}`

- **Exactly one default per buyer.** The first address saved becomes the default whatever `makeDefault` says; promoting one demotes the incumbent in the same transaction; deleting the default promotes the most recent survivor.
- **An order's destination is a SNAPSHOT.** Editing or deleting an address never changes where an order already placed is going.

---

## 5. Checkout (`CUSTOMER`)

### `GET /marketplace/checkout/options` — what this cell supports

Call it before rendering the checkout screen. **Do not hardcode payment rails or the delivery fee in the app** — a cell advertises only what it is provisioned for, and a rail the app was built with may not be enabled here.

```json
{
  "data": {
    "deliveryMethods": ["DELIVERY", "COLLECTION"],
    "deliveryFeeCents": 200,
    "currency": "USD",
    "paymentMethods": [ { "rail": "INNBUCKS_CODE", "label": "InnBucks code" } ],
    "paymentEndpoint": "POST /payments",
    "paymentOrderType": "MARKETPLACE"
  }
}
```

### `POST /marketplace/checkout/quote` — price it without committing

**This reserves NOTHING.** Before it existed, the only way to learn something was out of stock was to *create* an order — which holds stock as a side effect, so "let me just check the total" cost a merchant an inventory hold, and comparing delivery against collection meant placing two orders.

```json
{ "fromCart": true, "deliveryMethod": "DELIVERY", "deliveryAddressId": "…" }
```

or explicit items:

```json
{ "items": [ { "listingId": "9c2e8a4d-…", "quantity": 2 } ],
  "deliveryMethod": "COLLECTION" }
```

**Response — note that a problem basket is a `200`:**

```json
{
  "data": {
    "items": [ { …PricedLine, possibly with `issue`… } ],
    "lineCount": 2, "totalQuantity": 3,
    "subtotalCents": 3100,
    "deliveryFeeCents": 200,
    "totalCents": 3300,
    "currency": "USD",
    "deliveryMethod": "DELIVERY",
    "deliveryMethods": ["DELIVERY", "COLLECTION"],
    "deliveryAddress": { …AddressResponse… },
    "rejections": [ { "listingId": "…", "reason": "INSUFFICIENT_STOCK", … } ],
    "checkoutReady": false
  }
}
```

> **`checkoutReady: false` with `rejections` is a `200`, not an error.** The shopper has to *see* the basket to fix it. Only a malformed request or a missing address is a 4xx. Gate your "Place order" button on `checkoutReady`.

**`totalCents = subtotalCents + deliveryFeeCents`**, always. COLLECTION never pays the fee.

---

## 6. Orders (`CUSTOMER`)

### `POST /marketplace/orders` — place it

**`Idempotency-Key` header is required.** Reuse the same key on a retry; a replay returns the original result rather than creating a second order.

```json
{
  "fromCart": true,
  "deliveryMethod": "DELIVERY",
  "deliveryAddressId": "…",
  "recipient": {
    "name": "Gogo Chipo Moyo",
    "msisdn": "0772345678",
    "message": "Happy birthday Gogo, love from Tari"
  }
}
```

| field | required | notes |
|---|---|---|
| `fromCart` **or** `items` | yes, one of | `items` is `[{listingId, quantity}]` |
| `deliveryMethod` | no | **defaults to `COLLECTION`** |
| `deliveryAddressId` | for `DELIVERY` | ignored for COLLECTION |
| `recipient` | no | present = **this is a gift**. See §8 |
| `buyerMsisdn` | no | **ignored for a real customer** — the payer is taken from your token's `phoneNumber` claim |

> **Do not rely on `buyerMsisdn`.** It stays on the DTO for back-compat but is never authoritative for a logged-in customer: read from the body alone, any buyer could push a "pay $X" prompt to any number they typed.

**Response `201`** — an `OrderResponse` carrying a `payment` block:

```json
{
  "data": {
    "id": "2a9e7c18-…",
    "orderRef": "MKT-4F2A9C1B77D0",
    "status": "PENDING_PAYMENT",
    "subtotalCents": 4798,
    "deliveryFeeCents": 200,
    "totalCents": 4998,
    "currency": "USD",
    "deliveryMethod": "DELIVERY",
    "deliveryAddress": { …snapshot… },
    "expiresAt": "2026-09-14T11:32:44Z",
    "createdAt": "2026-09-14T11:02:44Z",
    "paidAt": null,
    "items": [ { "listingId": "…", "titleSnapshot": "Solar Lantern 20W",
                 "unitPriceCents": 2399, "quantity": 2, "lineTotalCents": 4798 } ],
    "payment": {
      "endpoint": "POST /payments",
      "orderType": "MARKETPLACE",
      "orderRef": "MKT-4F2A9C1B77D0",
      "amountCents": 4998,
      "currency": "USD",
      "payBefore": "2026-09-14T11:32:44Z",
      "paymentMethods": [ { "rail": "INNBUCKS_CODE", "label": "InnBucks code" } ]
    },
    "fulfilmentStatus": null,
    "fulfilments": []
  }
}
```

### Refusals name EVERY failing line

A cart with two problems used to cost two round-trips. Now:

```json
{
  "code": "insufficient_stock",
  "message": "Only 3 left of Solar Lantern 20W",
  "data": {
    "rejections": [
      { "listingId": "…", "reason": "INSUFFICIENT_STOCK",
        "message": "Only 3 left of Solar Lantern 20W",
        "requestedQty": 5, "availableQty": 3 },
      { "listingId": "…", "reason": "LISTING_UNAVAILABLE",
        "message": "Garden Hose is no longer available" }
    ]
  }
}
```

**`reason` is a string, not an enum on the wire.** A client meeting one it does not recognise must render `message` and drop the line — never choke. Today's values are `LISTING_UNAVAILABLE` and `INSUFFICIENT_STOCK`.

> `data.rejections` is **absent** when a line loses a stock race after passing the pre-check — you still get the plain 409. Handle both.

### Paying

Marketplace-service **collects no money**. Take the `payment` block and call **payment-service**:

```
POST /payments
{ "orderType": "MARKETPLACE", "orderRef": "MKT-4F2A9C1B77D0", "paymentRail": "INNBUCKS_CODE" }
```

Then poll `GET /marketplace/orders/{id}` until `status` becomes `PAID`. The order expires at `expiresAt` if unpaid — payment-service extends that window to outlive whatever instrument it mints, so a payment started just before the deadline still completes.

### `GET /marketplace/orders/mine` — order history

Newest first. Paged with Spring's conventions — `?page=`, `?size=` (default 20), `?sort=`.

> **There is no `status` filter.** Fetch the page and filter client-side if you want tabs — `OrderStatus` is `PENDING_PAYMENT` `PAID` `CANCELLED` `EXPIRED`.

### `GET /marketplace/orders/{id}` — one order

The full `OrderResponse`, including live fulfilment state. **404 for an order that is not yours** — indistinguishable from one that does not exist, deliberately.

### `POST /marketplace/orders/{id}/cancel`

Only while `PENDING_PAYMENT`. Returns the stock. A `PAID` order is not cancellable — that is a dispute (§9).

---

## 7. Tracking a delivery

An order carries `fulfilmentStatus` and a `fulfilments[]` array — **one parcel per seller**, because a multi-seller order ships in several parcels.

```json
{
  "fulfilmentStatus": "DISPATCHED",
  "fulfilments": [ {
    "id": "7f3c1a92-…",
    "merchantId": "…",
    "sellerName": "Rudo Traders",
    "status": "DISPATCHED",
    "dispatchNote": "Swift Couriers, waybill 88213",
    "dispatchedAt": "2026-09-15T08:00:00Z",
    "deliveredAt": null,
    "deliveredBy": null,
    "items": [ … ],
    "unfulfilledReason": null,
    "unfulfilledAt": null,
    "dispute": null
  } ]
}
```

**Four things that trip clients up here:**

1. **`status` stays `PAID` forever.** Payment state and fulfilment state are different questions about the same order. Read `fulfilmentStatus` for "where are my goods" — never `status`.
2. **`fulfilmentStatus` is the LEAST advanced *live* parcel**, so `DELIVERED` always means everything arrived. It is `null` when there are no parcels.
3. **On a COLLECTION order the vocabulary shifts.** `DISPATCHED` reads *"ready to collect"* and `DELIVERED` reads *"collected"*. You have the order's `deliveryMethod` — label accordingly. There is deliberately only one state machine.
4. **`UNFULFILLED` means the seller could not supply it** (§10). Declined parcels are **excluded** from the roll-up, so a two-seller order where one declines still reports the state of the half that is moving; `fulfilmentStatus` is only `UNFULFILLED` when *every* parcel was declined.

### `POST /marketplace/orders/{id}/fulfilments/{fulfilmentId}/received`

The buyer confirms receipt. **This releases the seller's money immediately** — it is the strongest evidence the platform has, so it waits for nothing. A seller's own close instead starts a 48-hour grace window.

Prompt for it. It is the single most useful thing a buyer can do, and it is what makes fast payouts possible for good sellers.

---

## 8. Gifting and collection codes

### Ordering for someone else

Send the `recipient` block on order create (§6). Then:

- **The recipient gets an SMS when the order is paid.** Without it, the gift is invisible to the one person it is for.
- **They are not given an account** and cannot see the order. They are a name the goods are handed to and a number the platform can message.
- The recipient's number is returned in full **only on the buyer's own order view** — the seller sees a `collectorName`, never the number.

### `POST /marketplace/orders/{id}/fulfilments/{fulfilmentId}/collect-code`

For a **COLLECTION** parcel: mints the code that proves a handover at the counter.

```json
{
  "data": {
    "fulfilmentId": "…",
    "code": "K7Q29XMF3TRW",
    "groupedCode": "K7Q2-9XMF-3TRW",
    "issuedAt": "2026-09-15T08:00:00Z",
    "sentTo": "****5678"
  }
}
```

- **Show `groupedCode`** (and a QR of `code`) — it is the form a human reads aloud.
- **The plaintext exists only here and in the SMS.** No merchant surface has ever seen it: a seller who could read a code could redeem it themselves and take the instant payout with the goods still on the shelf.
- **Lost code → mint again**, which replaces the live one.
- Redeeming it closes the parcel as `deliveredBy: RECIPIENT` and **releases the seller's money immediately** — that instant payout is exactly the seller's incentive to ask for a code rather than self-closing into the grace window.
- `sentTo` is masked and reports what actually happened. It is absent when no message channel is configured on the cell.

---

## 9. Disputes — the buyer's half of Buyer Protection

Money for a marketplace order is held in escrow and only released to the seller when the goods arrive.

### `POST /marketplace/orders/{id}/fulfilments/{fulfilmentId}/dispute`

```json
{ "reason": "NOT_RECEIVED", "detail": "Paid five days ago, the seller has stopped answering." }
```

`reason` ∈ `NOT_RECEIVED` `DAMAGED` `NOT_AS_DESCRIBED` `WRONG_ITEM` `OTHER`.

**Response `200`** — a `DisputeResponse` with `status: "OPEN"`, which then rides the parcel on the buyer's order view.

**The windows:**

| parcel state | disputable? |
|---|---|
| never delivered | **yes, however old** — "it never arrived" IS the refund path |
| delivered | for `dispute-window-days` (default **7**) after delivery |
| already disputed | `409 dispute_already_raised` — **one per parcel, ever** |
| money already `REFUND_DUE` | `409 refund_already_due` — a refund is already being arranged |

> **One dispute per parcel, permanently.** A buyer who could re-dispute after a release could freeze a seller's money in a loop. Hide the action once `dispute` is present on the parcel — and once it is `UNFULFILLED`, because the refund is already in motion.

The operator resolves it exactly once, either way, and the buyer is notified.

---

## 10. When a seller cannot supply

A parcel can end `UNFULFILLED` — the seller declaring they cannot supply goods already paid for.

```json
{
  "status": "UNFULFILLED",
  "unfulfilledAt": "2026-09-18T09:14:22Z",
  "unfulfilledReason": "Supplier let us down, no stock until October"
}
```

- **Show `unfulfilledReason` verbatim** — the seller's own words, and the only explanation the buyer gets.
- The stock goes back on sale and the buyer's money is queued for refund automatically.
- The buyer also gets an SMS. When the money could not be turned around (already disputed or paid out), the copy says support will be in touch instead of naming an amount.
- **Hide the Dispute action** on an `UNFULFILLED` parcel.

---

## 11. Favorites

| | |
|---|---|
| `PUT /marketplace/favorites/{listingId}` | add — **idempotent**, a repeat never bumps the ordering |
| `DELETE /marketplace/favorites/{listingId}` | remove — idempotent |
| `GET /marketplace/favorites` | full listing summaries, **newest-favorited first** |

The list carries each listing's **current status**, so render "no longer available" for anything not `ACTIVE` rather than hiding it.

**Favoriting also subscribes the buyer to a back-in-stock alert** — when a favorited listing moves from 0 to in stock, they are notified. Worth saying on the button.

---

## 12. Reviews

**Only a verified purchase can review**: the buyer must have a `PAID` order containing that listing.

| | |
|---|---|
| `POST /marketplace/listings/{listingId}/reviews` | `{ "rating": 5, "comment": "…" }` — rating 1–5, comment ≤ 1000 chars, optional |
| `PUT /marketplace/listings/{listingId}/reviews/mine` | edit your own |
| `DELETE /marketplace/listings/{listingId}/reviews/{reviewId}` | delete your own |

| status | `code` | meaning |
|---|---|---|
| `403` | `review_requires_purchase` | no paid order containing this listing |
| `409` | `review_already_exists` | one review per buyer per listing — offer *edit* instead |

**Any listing status is reviewable** — a delisted product was still bought.

---

## 13. Reporting a listing

`POST /marketplace/catalog/{listingId}/report` — **any authenticated user** (401 anonymous, as spam control).

```json
{ "reason": "COUNTERFEIT", "detail": "Logo is fake — the brand does not make this model." }
```

`reason` ∈ `PROHIBITED_ITEM` `COUNTERFEIT` `MISLEADING` `OFFENSIVE` `SCAM` `OTHER`. `detail` ≤ 500 chars, optional.

`409 report_already_open` — one open report per person per listing. Tell them it is already with the team.

---

## 14. Gotchas checklist

- [ ] **Branch on `code`, never on `message`.** Messages are copy and get reworded.
- [ ] **Money is minor units everywhere.** `4998` = `USD 49.98`.
- [ ] **Timestamps are UTC `Z`.** Render in the viewer's locale; never re-interpret or append a `Z` yourself.
- [ ] **Browse anonymously.** Don't gate the catalogue behind a login.
- [ ] **An unknown browse parameter is a 400** naming the supported set — it means a typo in your query, not a retryable failure.
- [ ] **`ratingAvg: null` ≠ 0.** "No reviews yet", not zero stars. Same for the whole `fulfilment` block on a new seller.
- [ ] **`seller.displayName` can be null** — render without a name, never a raw UUID.
- [ ] **Cart prices resolve live.** Never cache a line total; re-read the cart when the screen opens.
- [ ] **An unbuyable cart line stays visible, contributing 0.** Show it with its `issue`.
- [ ] **Cart `PUT` with quantity 0 is refused** — use DELETE.
- [ ] **`checkoutReady: false` is a 200.** Gate the button on that flag, not on HTTP status.
- [ ] **`Idempotency-Key` is required on order create** and must be reused on retry.
- [ ] **A refused order can name several lines** in `data.rejections` — show them all.
- [ ] **An unrecognised rejection `reason` must render, not crash.**
- [ ] **Order `status` stays `PAID`** — read `fulfilmentStatus` for delivery progress.
- [ ] **COLLECTION relabels `DISPATCHED`/`DELIVERED`** as ready-to-collect / collected.
- [ ] **`fulfilmentStatus` excludes declined parcels** — only all-declined reads `UNFULFILLED`.
- [ ] **Never show a collection code to a seller surface**, and expect it only in the mint response and the SMS.
- [ ] **One dispute per parcel, ever.** Hide the action once one exists or the parcel is `UNFULFILLED`.
- [ ] **Read payment rails from `/checkout/options`**, never from a hardcoded list.
- [ ] **Prompt for "I received it"** — it is what releases a good seller's money immediately.
