# Marketplace Gifting & Collection Codes — Frontend Integration

**Service:** `marketplace-service` · **Migration:** V11

Buying for someone else — the diaspora case — and proving a handover at the
counter. Two surfaces: the **buyer's** (super app) and the **seller's**
(merchant app).

---

## 1. What changed, and why you need this

The marketplace could only be bought from **for yourself**. An order carried
the payer's name and number; there was nowhere to say the goods were for
somebody else, the recipient was never told anything, and when they turned up
to collect, the seller had no way to know who was entitled to the parcel.

V11 adds two things that only work together:

1. **An order can name a RECIPIENT** — `{name, msisdn, message}`. They get an
   SMS when the order is paid. They are **not** given an account and cannot see
   the order.
2. **A COLLECTION parcel can carry a handover code** — the buyer mints it, the
   person collecting presents it, the seller verifies it. That closes the
   parcel as `deliveredBy: RECIPIENT`, which **releases the seller's money
   immediately** instead of after the 48-hour self-close grace window.

```
  buyer orders for Gogo ──> pays ──> Gogo gets an SMS
           │
           └─> buyer taps "Get collection code" ──> code (+ QR) ──> forwards it
                                                          │
  Gogo at the counter reads it out ──> seller verifies ──> parcel DELIVERED
                                                          └─> seller paid now
```

> **Nothing you already call changed in a breaking way.** `recipient` is a new
> optional field on order create; everything else is new endpoints and new
> nullable response fields.

---

## 2. Base URL, auth, headers

| | |
|---|---|
| Base URL | the API gateway origin (`/foundry` prefix in staging/prod, stripped by nginx) |
| Route | `/marketplace/**` → `lb://marketplace-service` — **already existed, no gateway change** |
| Auth | `Authorization: Bearer <jwt>` — a normal fleet user JWT |
| Roles | **`CUSTOMER`** for §3–§4; **`MERCHANT_ADMIN`** for §5 |
| `X-Tenant-Id` | **Not required** anywhere in this guide |
| Content-Type | `application/json` on every write |

> Envelope `{ "code": "OK", "message": "…", "data": … }`; errors carry a slug
> `code`. Money is minor units. Timestamps are UTC ISO-8601 with `Z`.

---

## 3. Ordering for someone else (`CUSTOMER`)

`POST /marketplace/orders` takes an optional `recipient` block:

```json
{
  "items": [{ "listingId": "9c2e8a4d-…", "quantity": 1 }],
  "deliveryMethod": "COLLECTION",
  "recipient": {
    "name": "Gogo Chipo Moyo",
    "msisdn": "0772345678",
    "message": "Happy birthday Gogo, love from Tari"
  }
}
```

- `name` — **required whenever `recipient` is sent**, max 120 chars.
- `msisdn` — optional, max 32 chars, normalised to E.164 server-side (default
  region = the cell's country). Omit it and **nobody is messaged**: the buyer
  passes the collection code on themselves. A number that cannot be dialled is
  `400 invalid_msisdn` naming `recipient.msisdn`.
- `message` — optional, max 200 chars, sanitized server-side. It rides the
  recipient's SMS, so keep it short.

Omit `recipient` entirely and the order is yours, exactly as before — **nothing
is copied from the buyer**. The response (and every later read of the order)
carries it back:

```json
"recipient": {
  "name": "Gogo Chipo Moyo",
  "msisdn": "+263772345678",
  "message": "Happy birthday Gogo, love from Tari"
}
```

Absent when the order is not a gift. This is the **only** surface that returns
the recipient's number — the seller sees the name alone.

**What the recipient gets**, once the order is paid, by SMS:

> You have a gift coming on InnBucks Marketplace. Order MKT-4F2A9C1B77D0.
> Note - Happy birthday Gogo, love from Tari

Deliberately no price and no contents. Nothing for the app to trigger — it
fires off the payment confirmation.

---

## 4. The collection code (`CUSTOMER`)

### 4.1 Minting one

```
POST /marketplace/orders/{orderId}/fulfilments/{fulfilmentId}/collect-code
```

No body. `fulfilmentId` comes from the order's `fulfilments[]`.

```json
{
  "code": "OK",
  "message": "Collection code ready - show it when you collect",
  "data": {
    "fulfilmentId": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
    "code": "K7Q29XMF3TRW",
    "groupedCode": "K7Q2-9XMF-3TRW",
    "issuedAt": "2026-09-16T14:05:00Z",
    "sentTo": "****5678"
  }
}
```

- **`code` is the QR payload.** Encode it verbatim; the seller's scanner
  submits it unchanged.
- **`groupedCode` is what you print on screen** and what someone reads down a
  phone line. Both forms verify.
- `sentTo` is the masked number the code was also SMS'd to — the recipient's if
  the order named one with a number, else the buyer's. **`null` means it was
  not sent** (no channel configured on the cell, or the gateway refused); the
  code in this response is still valid, so tell the user to forward it.

> **This response is the only place the code is ever readable.** It is stored
> hashed, so no later read returns it — not the order view, and never any
> seller surface. **Do not cache it anywhere you would not cache a password.**
>
> Lost it? Call the endpoint again. That mints a **fresh** code, **kills the
> previous one**, and resets the parcel's wrong-code budget. There is no
> "show me the existing code" call, deliberately.

**Errors:**

| Status | `code` | When |
|---|---|---|
| 401 | `UNAUTHORIZED` | no/invalid token |
| 404 | `order_not_found` / `fulfilment_not_found` | not your order, or the parcel isn't on it |
| 409 | `collect_code_not_applicable` | a DELIVERY order — nothing is collected in person |
| 409 | `illegal_fulfilment_state` | the parcel was already handed over |

### 4.2 On the order view

Each entry in `fulfilments[]` gained two nullable stamps:

```json
{ "collectCodeIssuedAt": "2026-09-16T14:05:00Z", "collectCodeRedeemedAt": null }
```

- `collectCodeIssuedAt` present → a live code is out there (render "code
  active", and offer re-mint rather than "get code").
- `collectCodeRedeemedAt` present → it was used; the parcel is `DELIVERED` with
  `deliveredBy: "RECIPIENT"`.

Both absent on parcels that never had a code, including every pre-V11 parcel.

---

## 5. Redeeming a code (`MERCHANT_ADMIN`)

```
POST /marketplace/fulfilments/{fulfilmentId}/collect
```

```json
{ "code": "K7Q2-9XMF-3TRW" }
```

Scan the QR, or let the seller type what the collector read out. **Dashes,
spaces and letter case are ignored, and the confusable characters are folded**
(`I`/`l` → `1`, `O` → `0`), so an imperfect reading still verifies. Don't
pre-validate the shape client-side — send what was entered.

**200** returns the seller's parcel view: `status: "DELIVERED"`,
`deliveredBy: "RECIPIENT"`, `collectCodeRedeemedAt` set, and
`settlementStatus: "RELEASABLE"` — **the money is released on the spot.** Lead
with that in the UI: it is the reason to ask for a code rather than using
"Mark delivered", which starts a 48-hour grace window instead.

**Errors:**

| Status | `code` | When |
|---|---|---|
| 404 | `fulfilment_not_found` | not your parcel (same 404 as nonexistent) |
| 409 | `collect_code_unavailable` | the buyer has not minted a code yet |
| 409 | `collect_code_not_applicable` | a DELIVERY order |
| 409 | `illegal_fulfilment_state` | already handed over (a double-tap) |
| 409 | `collect_code_locked` | too many wrong codes — the buyer must mint a new one |
| 422 | `collect_code_invalid` | wrong code; **this one is counted** |

> **Wrong codes are budgeted per parcel** (10 in production). The tenth locks
> the parcel until the buyer mints a fresh code. Show the seller the remaining
> path plainly: "ask the buyer for a new code". A `collect_code_locked` is not
> a dead end and not a support ticket.

### 5.1 On the seller's queue

`GET /marketplace/fulfilments` items gained three fields:

```json
{
  "collectorName": "Gogo Chipo Moyo",
  "collectCodeIssued": true,
  "collectCodeRedeemedAt": null
}
```

- `collectorName` — who is coming, on COLLECTION orders bought for someone
  else. **Name only** — the platform does not hand a seller a third party's
  phone number, and the code is what proves entitlement. Absent when the buyer
  collects themselves, and on DELIVERY parcels (there the `destination` block
  already names who the courier hands to).
- `collectCodeIssued` — a live code is waiting to be redeemed. Use it to show
  "ask for the code" on the parcel card. **The code itself is never here**: a
  seller verifies a code, they never read one.

---

## 6. Gotchas checklist

- [ ] **The mint response is the only place the code exists.** No read returns
      it. Treat it like a password: don't log it, don't cache it, don't put it
      in analytics.
- [ ] **Re-minting invalidates the old code.** Don't mint speculatively on
      screen load — mint when the user asks.
- [ ] **Send the typed code as entered.** Server-side normalisation handles
      dashes, case and I/L/O confusion; client-side "cleaning" can only break it.
- [ ] **`sentTo: null` is not a failure of the mint** — the code is valid, it
      just wasn't SMS'd. Prompt the user to forward it.
- [ ] **Codes are COLLECTION-only.** On a DELIVERY order both endpoints answer
      `409 collect_code_not_applicable`; don't render the button there.
- [ ] **`recipient.msisdn` is optional** — a gift with no number is legitimate,
      it just means nobody is messaged.
- [ ] **A recipient is not a user.** They have no login, no order access, and
      no way to confirm receipt in-app; the code is their entire interaction.
- [ ] **`collectorName` on the seller side has no phone number and never will.**
- [ ] `deliveredBy` now has three values — `BUYER`, `MERCHANT`, `RECIPIENT`.
      Render the third rather than falling through to a default.
