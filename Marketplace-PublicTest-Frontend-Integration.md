# Marketplace public test surface — frontend integration

**`/marketplace/public/**` — the pre-checkout journey with no login.** Build the
basket, address book, wishlist and checkout-quote screens now, while the super
app's login is still being wired to the fleet.

> [!IMPORTANT]
> **Staging only, and off unless a cell turns it on.** A cell that has not set
> `MARKETPLACE_PUBLIC_TEST_ENABLED=true` answers **404** on every path here.
> Ask which cell you are pointed at and whether it needs an `x-api-key`.
>
> **This is not a login and it is not a shortcut to shipping.** What you ship
> against is the authenticated surface plus `POST /auth/exchange` — see
> `Auth-Exchange-Frontend-Integration.md` and the main
> `Marketplace-SuperApp-Frontend-Integration.md`. This rail exists so the
> screens can be built and demoed before that lands.

---

## 1. Base URL, identity, envelope

```
https://<cell-edge>/marketplace/public/...
```

- **No `Authorization` header. No `X-Tenant-Id`.** Send nothing but the request.
- **The `handle` in the path is the identity.** Any string you choose — `alice`,
  a device id, whatever your test harness generates. Keep using the same one and
  you get the same basket back. Max 64 characters; blank or longer is a
  `400 invalid_handle`.
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

Your handle is hashed server-side into an internal buyer id. Two consequences
worth knowing before you plan around it:

- **It can never collide with a real customer.** The derived id is a version-5
  UUID; real customers carry version-4 ids. You cannot reach anyone's real data
  from here, and nobody can reach yours.
- **The data does not carry over.** When the app switches to a real fleet token,
  that customer starts with an empty cart and an empty address book. Test data
  lives on this rail only. That is deliberate — plan your demo data accordingly.

---

## 2. What is here, and what is deliberately not

**Here:** cart, delivery addresses, favorites, checkout quote, checkout options.

**Not here, and not coming:** creating an order, paying, fulfilment, tracking,
disputes, collect codes, reviews.

The line is side effects that leave the service. An order names the phone number
that receives a payment PIN prompt and reserves a merchant's real stock; neither
belongs behind a path with no authentication. So the journey you can build here
runs **browse → cart → address → quote**, and stops there.

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

`options` lists the delivery methods and payment rails this cell offers. The
rails are informational here — paying happens at payment-service against a real
order, which this rail cannot create.

---

## 7. Errors

| Status | When |
|---|---|
| `400 invalid_handle` | Blank handle, or longer than 64 characters |
| `400 VALIDATION_ERROR` | A body field failed validation; `data` names the fields |
| `401` | The cell gates this prefix and your `x-api-key` was missing or wrong |
| `404` | **The surface is not enabled on this cell**, or the listing/address does not exist, or belongs to another handle |
| `409` | The cart is full (max distinct listings per order) |

The `404` is doing double duty on purpose: "this endpoint does not exist here"
and "that thing is not yours" are the same answer, and neither confirms anything
to a prober. **If every call 404s, the surface is off** — that is the first thing
to check, before assuming a bug.

---

## 8. Gotchas checklist

- [ ] Everything `404`s → the cell has not enabled the surface. Ask an operator.
- [ ] Getting `401`s → the cell has an `x-api-key` and you are not sending it.
- [ ] Use **one stable handle** per test user; a new handle is a new empty basket.
- [ ] Do **not** send `Authorization` or `X-Tenant-Id`.
- [ ] Money is **cents**, always. Never send or expect a decimal.
- [ ] Keep out-of-stock cart lines **visible** with their `issue`.
- [ ] `checkoutReady: false` is a `200` — render it, do not error.
- [ ] Quantity `0` on the stepper is a refusal; call `DELETE` instead.
- [ ] `POST /addresses` returns `201`, not `200`.
- [ ] There is **no order endpoint here** — do not build a checkout submit
      against this rail; it ships against `POST /auth/exchange`.
- [ ] Test data will **not** follow the user into their real account.
