# Marketplace public test surface — frontend integration

**`/marketplace/public/**` — the buyer journey, keyed on the customer's phone.**
Your customers authenticate at the InnBucks middleware (Veengu), not at this
fleet — so their identity here is their **phone number**, exactly as it is for
loyalty points and vouchers. No login against us, no account step, no linking.

> [!IMPORTANT]
> **Staging only, and off unless a cell turns it on.** A cell that has not set
> `MARKETPLACE_PUBLIC_TEST_ENABLED=true` answers **404** on every path here.
> The **order** endpoints additionally need the cell to have an `x-api-key`
> configured. Ask which cell you are pointed at and what it has set.
>
> **This is not a login.** The x-api-key authenticates your BROKER; the broker
> asserts the phone it authenticated at Veengu — the same posture loyalty's
> public surface has always had, which is why that key must stay in your
> server-side functions and never in the app binary. When `POST /auth/exchange`
> lands, the same journey runs on a real fleet token with identical bodies —
> see `Auth-Exchange-Frontend-Integration.md` and §11 below.

---

## 1. Base URL, identity, envelope

```
https://<cell-edge>/marketplace/public/...
```

- **No `Authorization` header. No `X-Tenant-Id`.** Send nothing but the request.
- **The `handle` in the path is the CUSTOMER'S PHONE NUMBER** — the number your
  broker got from the Veengu session. Send it in whatever form you hold it
  (`0771234567`, `+263771234567`, spaced, punctuated): the server normalises
  it, and **every spelling is the same buyer**. Same phone = same basket,
  addresses and orders, on every device, forever — exactly how loyalty works.
  There is **no account step and no linking step**; do not build one.
- A handle **containing a letter** (`alice`, `demo-3`) still works and keys a
  disposable demo buyer, as before. Digits that are **not a dialable number**
  are refused (`400 invalid_msisdn`) rather than silently becoming an empty
  demo basket. Max 64 characters; blank or longer is `400 invalid_handle`.
- **Optional `x-api-key`.** Where the cell sets one, every call needs it and a
  missing or wrong value is a `401`. Where it is blank — the default — no header
  is needed.

Everything returns the fleet envelope:

```json
{ "code": "OK", "message": "Success", "data": { } }
```

**Money is minor units** (`4998` is `USD 49.98`). **Timestamps are UTC ISO-8601
with `Z`** — render in the viewer's locale, do no arithmetic of your own. Both
conventions are identical to the authenticated surface, because these endpoints
call the same code.

### What a handle actually is

The handle is normalised (phones → E.164) and hashed server-side into an
internal buyer id; the raw value is never stored, and a phone is masked in our
logs. Three consequences worth knowing:

- **A phone-keyed basket is the customer's real, durable basket** for as long
  as the app runs on this rail. It follows the phone across devices and app
  reinstalls with nothing to link.
- **The derived id can never collide with an authenticated account's id**
  (version-5 vs version-4 UUIDs), so nothing on this rail can touch data
  created under a real fleet token, or vice versa.
- **When `POST /auth/exchange` goes live**, the plan is a server-side adoption:
  the first time that customer's real session touches the marketplace, their
  phone-keyed rows are re-keyed to the real account — invisible to the user.
  Until that ships, treat exchange cut-over as starting fresh. Either way,
  **never show the customer an account or linking step**: from their side
  there is only "open the app, shop".

---

## 2. What is here

**The whole buyer journey:** browse → cart → address → quote → **order → pay →
track → confirm receipt → review**, plus favorites and collection codes.

This mirrors what the super app already does for tickets: booking-service's
`POST /bookings` is a guest checkout with the phone number in the body, and
marketplace now matches it. So you can build and demo the complete purchase
flow without a fleet token.

**Two things about the order half specifically:**

- **It needs the cell to have an `x-api-key` configured.** The pre-checkout
  endpoints work on an ungated cell; the order endpoints answer `404` there.
  If your cart works and `POST .../orders` 404s, that is what has happened —
  ask the operator to set `MARKETPLACE_PUBLIC_TEST_API_KEY` and restart.
- **The payer is the phone in the path.** With a phone handle the payer comes
  from the identity itself — no `buyerMsisdn` needed, and a body value is
  ignored, the same rule a real customer token gets. Only a lettered demo
  handle still supplies `buyerMsisdn` in the body.

**Not here:** anything a seller or an operator does — dispatch, marking
delivered, moderation, settlements, payouts. Those need a real
`MERCHANT_ADMIN` or `SUPER_ADMIN` token and always will.

Browsing needs no special endpoint at all — `GET /marketplace/catalog/**` and
`GET /marketplace/categories` have always been public. Use them directly.

---

## 3. Cart

The cart stores **quantities only**. Price, stock and availability resolve live
on every read, so a line can change between one read and the next — that is the
real behaviour, not a test-rail quirk.

| Method | Path |
|---|---|
| `GET` | `/marketplace/public/buyers/{handle}/cart` |
| `POST` | `/marketplace/public/buyers/{handle}/cart/items` |
| `PUT` | `/marketplace/public/buyers/{handle}/cart/items/{listingId}` |
| `DELETE` | `/marketplace/public/buyers/{handle}/cart/items/{listingId}` |
| `DELETE` | `/marketplace/public/buyers/{handle}/cart` |

**Add** (`POST /items`) — accumulates onto whatever is already there, and clamps
at the per-item cap rather than refusing. The `+` button wants the cap.

```json
{ "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93", "quantity": 2 }
```

**Set an exact quantity** (`PUT /items/{listingId}`) — the stepper's write.
Idempotent, so a retried tap can never buy twice. **Quantity `0` is refused** —
use `DELETE`, so a zero can never be a silently swallowed delete.

```json
{ "quantity": 3 }
```

Every one of the five returns **the whole priced cart**:

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "items": [
      {
        "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
        "quantity": 2,
        "unitPriceCents": 2499,
        "lineTotalCents": 4998,
        "issue": null,
        "listing": { "title": "Hand-woven sisal basket", "status": "ACTIVE" }
      }
    ],
    "lineCount": 1,
    "totalQuantity": 2,
    "subtotalCents": 4998,
    "currency": "USD",
    "checkoutReady": true
  }
}
```

- **A line that goes out of stock STAYS visible**, carrying its `issue` and
  contributing `0` to the subtotal. Do not hide it — dropping it silently is how
  a shopper reaches checkout with a total they do not recognise. Render the
  issue next to the line.
- `checkoutReady` is the single flag for "can this basket proceed".
- The cart holds **no stock**. Nothing is reserved until an order is created,
  which this rail cannot do.
- Removing and clearing are **idempotent** — a `200` no-op when there was
  nothing there, so the buttons can retry blindly.

---

## 4. Delivery addresses

| Method | Path |
|---|---|
| `GET` | `/marketplace/public/buyers/{handle}/addresses` |
| `GET` | `/marketplace/public/buyers/{handle}/addresses/{id}` |
| `POST` | `/marketplace/public/buyers/{handle}/addresses` |
| `PUT` | `/marketplace/public/buyers/{handle}/addresses/{id}` |
| `PUT` | `/marketplace/public/buyers/{handle}/addresses/{id}/default` |
| `DELETE` | `/marketplace/public/buyers/{handle}/addresses/{id}` |

```json
{
  "label": "Home",
  "recipientName": "Tariro Moyo",
  "recipientMsisdn": "0771234567",
  "line1": "14 Samora Machel Ave",
  "line2": "Flat 3B",
  "city": "Harare",
  "area": "Avondale",
  "landmark": "Opposite the clinic, blue gate"
}
```

- **`recipientMsisdn` is normalised to E.164 server-side** — send what the user
  typed, get `+263771234567` back. A malformed number is a `400`. Nothing is
  ever sent to it from this rail.
- **The first address saved becomes the default**, whatever you ask for. Exactly
  one entry is the default whenever the book is non-empty; promoting one demotes
  the incumbent, and deleting the default promotes the most recent survivor.
- **Another handle's address is a `404`, not a `403`** — the same owner-masked
  answer the real surface gives, so a missing address and someone else's are
  indistinguishable.
- `POST` returns **`201`**; the others return `200`.

---

## 5. Favorites

| Method | Path |
|---|---|
| `PUT` | `/marketplace/public/buyers/{handle}/favorites/{listingId}` |
| `DELETE` | `/marketplace/public/buyers/{handle}/favorites/{listingId}` |
| `GET` | `/marketplace/public/buyers/{handle}/favorites?page=0&size=20` |

Both writes are **idempotent** — a repeat add is a `200` no-op and never bumps
the ordering. The listing must exist, in **any** status.

The list returns full listing summaries, newest-favourited first, each carrying
the listing's **current** status so you can render "no longer available".

---

## 6. Checkout quote and options

```
POST /marketplace/public/buyers/{handle}/checkout/quote
GET  /marketplace/public/checkout/options
```

`options` takes no handle — it is the same for every buyer.

```json
{ "fromCart": true, "deliveryMethod": "DELIVERY", "deliveryAddressId": "8f2c…" }
```

or, for Buy Now, explicit lines instead of the cart:

```json
{
  "items": [{ "listingId": "b4c2f0a8-…", "quantity": 1 }],
  "deliveryMethod": "COLLECTION"
}
```

- **`fromCart` and `items` are mutually exclusive.**
- **An unstated `deliveryMethod` is `COLLECTION`.** `DELIVERY` needs a
  `deliveryAddressId`.
- **The quote reserves nothing** and creates no order. Compare delivery against
  collection freely.
- **A basket with problems is a `200`**, with `checkoutReady: false` and every
  failing line in `rejections`. Only a malformed request or a missing address is
  an error. Render the basket; do not treat a problem quote as a failure.

`options` lists the delivery methods and payment rails this cell offers, and
names the payment-service call to make once you have an order.

---

## 7. Orders

> Requires the cell to have an `x-api-key`. Ungated cell → every path in this
> section is `404` while §3–§6 keep working.

| Method | Path |
|---|---|
| `POST` | `/marketplace/public/buyers/{handle}/orders` |
| `GET` | `/marketplace/public/buyers/{handle}/orders?page=0&size=20` |
| `GET` | `/marketplace/public/buyers/{handle}/orders/{orderId}` |
| `POST` | `/marketplace/public/buyers/{handle}/orders/{orderId}/cancel` |

**Create.** Same body as the quote. **The `Idempotency-Key` header is
REQUIRED** — a create without one is refused `400 idempotency_key_required`
before anything else is looked at. Mint a fresh key per checkout attempt and
retry the same body under the same key rather than risking a second order.

**The payer is the handle.** When the handle is a phone, **omit
`buyerMsisdn`** — the order is payable by the basket's owner, and a body value
naming a different number is **ignored**, exactly as it is for a real customer
token. Only a lettered demo handle still needs `buyerMsisdn` in the body
(`400 invalid_msisdn` without it).

```http
POST /marketplace/public/buyers/0771234567/orders
x-api-key: <the cell's key>
Idempotency-Key: 9f1c2a77-checkout-attempt-1
Content-Type: application/json

{
  "fromCart": true,
  "deliveryMethod": "DELIVERY",
  "deliveryAddressId": "8f2c…"
}
```

- **The number prompted to pay is the handle's phone** — normalised to
  `+263771234567` server-side, no `buyerMsisdn` needed. A body `buyerMsisdn`
  is ignored for phone handles; only a lettered demo handle needs it. A handle
  that is digits but not dialable is a `400 invalid_msisdn`, and nothing is
  reserved on the way to that refusal.
- **This one DOES reserve stock**, unlike the cart and the quote. An abandoned
  order holds a merchant's inventory until it expires — cancel it if the
  shopper backs out.
- `201` on success. The response is the full order, including a **`payment`
  block** naming what to POST to payment-service next:

```json
{
  "code": "CREATED",
  "message": "Created",
  "data": {
    "id": "b4a8e2d1-…",
    "orderRef": "MKT-4F9A1C22B7D3",
    "status": "PENDING_PAYMENT",
    "subtotalCents": 4998,
    "deliveryFeeCents": 0,
    "totalCents": 4998,
    "currency": "USD",
    "expiresAt": "2026-09-21T11:32:44Z",
    "payment": { "endpoint": "POST /payments", "orderType": "MARKETPLACE", "orderRef": "MKT-4F9A1C22B7D3", "rails": ["INNBUCKS_CODE"] },
    "items": [ … ],
    "fulfilments": []
  }
}
```

- **The order does NOT echo `buyerMsisdn` back.** That is deliberate on every
  order surface, not a gap in this rail. Keep your own copy if you need to
  show it.
- `422` with `data.rejections` names **every** failing line, not just the first.
- **Another handle's order is a `404`**, list and read alike.

**Paying** is unchanged and happens at payment-service, which is already public:
`POST /payments` with `{ "orderType": "MARKETPLACE", "orderRef": "MKT-…",
"paymentRail": "INNBUCKS_CODE" }`. No `x-api-key`, no bearer.

---

## 8. After the sale

| Method | Path |
|---|---|
| `POST` | `…/orders/{orderId}/fulfilments/{fulfilmentId}/received` |
| `POST` | `…/orders/{orderId}/fulfilments/{fulfilmentId}/collect-code` |
| `POST` | `…/orders/{orderId}/fulfilments/{fulfilmentId}/dispute` |
| `POST` | `/marketplace/public/buyers/{handle}/listings/{listingId}/reviews` |

All under `/marketplace/public/buyers/{handle}`, all gated like §7.

- **Parcels are per seller.** A two-seller order has two `fulfilments` and is
  confirmed one at a time — that is how the goods actually arrive.
- **`received`** releases that seller's money immediately. Returns the whole
  order so you can re-render the tracking screen from one response.
- **`collect-code`** is COLLECTION orders only. **The response is the only
  place the code is ever readable** — no seller screen can see it. Calling
  again mints a fresh one and kills the previous, which is the recovery path
  for a lost code. **It sends an SMS**, so it costs money per call.
- **`dispute`** freezes that parcel's money. One per parcel, ever.
- **`reviews`** needs a PAID order of this handle containing the listing, or it
  is a `403 review_requires_purchase`. `{"rating": 5, "comment": "…"}`.

---

## 9. Errors

| Status | When |
|---|---|
| `400 invalid_handle` | Blank handle, or longer than 64 characters |
| `400 VALIDATION_ERROR` | A body field failed validation; `data` names the fields |
| `400 invalid_msisdn` | The handle looked like a phone but is not dialable; or a demo-handle order had no/invalid `buyerMsisdn` |
| `401` | The cell gates this prefix and your `x-api-key` was missing or wrong |
| `403 review_requires_purchase` | Reviewing something this handle has not paid for |
| `404` | **The surface is not enabled**, or **the order half is off because the cell has no api-key**, or the thing does not exist, or belongs to another handle |
| `409` | The cart is full, or an order has moved past the state you asked for |
| `422` | Lines unavailable or short-stocked — `data.rejections` names every one |

The `404` is doing triple duty on purpose: "this endpoint does not exist here",
"ordering is not switched on here" and "that thing is not yours" are the same
answer, and none of them confirms anything to a prober. **If every call 404s,
the surface is off. If only the order calls 404, the cell has no api-key set** —
check those two before assuming a bug.

---

## 10. Gotchas checklist

- [ ] Everything `404`s → the cell has not enabled the surface. Ask an operator.
- [ ] Only the **order** calls `404` → the cell has no `x-api-key` configured.
- [ ] Getting `401`s → the cell has an `x-api-key` and you are not sending it.
- [ ] **The handle is the customer's phone** from the Veengu session — any
      spelling, same basket. Lettered handles remain for demo data only.
- [ ] **No account or linking step, ever** — the phone IS the identity, same
      as loyalty. If you are building a "link your account" screen, stop.
- [ ] Do **not** send `Authorization` or `X-Tenant-Id`.
- [ ] Money is **cents**, always. Never send or expect a decimal.
- [ ] Keep out-of-stock cart lines **visible** with their `issue`.
- [ ] `checkoutReady: false` is a `200` — render it, do not error.
- [ ] Quantity `0` on the stepper is a refusal; call `DELETE` instead.
- [ ] `POST /addresses` returns `201`, not `200`; so does `POST /orders`.
- [ ] **Omit `buyerMsisdn` on phone-handle orders** — the payer is the handle
      and a body value is ignored. Demo (lettered) handles still need it. It
      is never echoed back either way.
- [ ] **`Idempotency-Key` is required on order create** — omitting it is a
      `400 idempotency_key_required`. Fresh key per attempt; retry under the
      same one.
- [ ] An abandoned order **holds stock** — cancel it when the shopper backs out.
- [ ] `collect-code` **sends an SMS** on every call; do not poll it.
- [ ] Pay at **payment-service**, not here: `POST /payments` with
      `{orderType: "MARKETPLACE", orderRef}`. No key, no bearer.
- [ ] Test data will **not** follow the user into their real account.

---

## 11. What changes when `/auth/exchange` goes live

The request and response bodies are identical to the authenticated surface.
Switching over is a change of **URL and header**, not a rewrite:

| Today | After |
|---|---|
| `/marketplace/public/buyers/{phone}/orders` | `/marketplace/orders` |
| `x-api-key: <cell key>` (broker-held) | `Authorization: Bearer <fleet token>` |
| Payer = the phone in the path | Payer = the token's phone claim — same number, same rule |
| Data keyed to `hash(phone)` | Data keyed to the account's `userUuid` |

The last row is the only real gap: the server-side adoption that re-keys a
phone's rows onto the real account is designed (see §1) but not built yet.
Until it ships, cut-over starts the customer fresh; demo (lettered) handles
never carry over, by design.
