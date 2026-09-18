# Marketplace Seller Payout Destination — Frontend Integration

**Service:** `marketplace-service` · **Migration:** V13

Where a seller's money actually goes. Two surfaces: the **seller's** (merchant app) and the **operator's** (back-office console).

---

## 1. What changed, and why you need this

The escrow ledger already knew, precisely, **who** was owed money and **how much**. It had never known **where to send it**.

The payout report — the sheet finance pays from — carried `merchantId, displayName, parcels, netCents, currency`, and then an operator had to go and find that seller's bank details somewhere outside the system entirely: an email, a spreadsheet, a WhatsApp message. The single fact a payment cannot be made without was the one fact the platform did not hold.

V13 puts it on the seller record:

```
  seller sets destination ──> marketplace_seller
                                     │
                                     ├─> payout report carries it (finance pays from this)
                                     ├─> seller's money summary says "you can be paid"
                                     └─> every change notifies the seller
```

> **Nothing you already call changed in a breaking way.** Two new seller endpoints, two new operator endpoints, one new field on an existing response, and six new columns on a CSV.

**The one existing contract that moved:** `GET /marketplace/settlements/payout-report` gained six columns. If you parse that CSV by column *index*, see §6.

---

## 2. Base URL, auth, headers

| | |
|---|---|
| Base URL | the API gateway origin (`/foundry` prefix in staging/prod, stripped by nginx) |
| Route | `/marketplace/**` → `lb://marketplace-service` — **already existed, no gateway change** |
| Auth | `Authorization: Bearer <jwt>` — a normal fleet user JWT |
| Roles | **`MERCHANT_ADMIN`** for §3; **`SUPER_ADMIN`** for §5 |
| `X-Tenant-Id` | **Not required** anywhere in this guide |
| Content-Type | `application/json` on every write |

> Envelope `{ "code": "OK", "message": "…", "data": … }`; errors carry a slug `code`. Timestamps are UTC ISO-8601 with `Z`.

---

## 3. The seller's own destination (`MERCHANT_ADMIN`)

### `GET /marketplace/sellers/me/payout-destination`

**No merchant id anywhere in the request** — the subject is always the caller's own `merchantId` claim. There is deliberately no way to name another seller on this surface.

**Response `200` — none on file yet:**

```json
{
  "code": "OK",
  "message": "Payout destination",
  "data": {
    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
    "configured": false
  }
}
```

**Response `200` — configured:**

```json
{
  "code": "OK",
  "message": "Payout destination",
  "data": {
    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
    "configured": true,
    "method": "MOBILE_MONEY",
    "accountName": "Rudo Chikwanha",
    "msisdn": "+263771234567",
    "updatedAt": "2026-09-18T09:15:00Z",
    "updatedBy": "3f1c9d24-a77e-4e21-9c60-11ab22cd33ef"
  }
}
```

Two things to build against:

1. **`configured: false` is a normal `200`, not a `404`.** The seller exists; they simply have nowhere to be paid yet. Render the "add your payout details" state, not an error.
2. **Nothing is masked.** They typed it, and checking it is the point — a masked account number makes "is this the right account?" impossible to answer. Show it in full.

### `PUT /marketplace/sellers/me/payout-destination`

**It REPLACES the whole destination.** There is no partial update — send a complete one for the method you choose. A half-changed destination is exactly the shape that reads as configured on every screen and fails when a transfer is attempted.

**Mobile money:**

```json
{
  "method": "MOBILE_MONEY",
  "accountName": "Rudo Chikwanha",
  "msisdn": "0771234567"
}
```

**Bank:**

```json
{
  "method": "BANK",
  "accountName": "Rudo Chikwanha",
  "bankName": "CBZ Bank",
  "accountNumber": "01123456789012"
}
```

| field | required | notes |
|---|---|---|
| `method` | **yes** | `MOBILE_MONEY` or `BANK` |
| `accountName` | **yes, both methods** | The name the **account** is held in — **not** the trading name. See below. |
| `msisdn` | `MOBILE_MONEY` only | Any spelling the cell's country accepts; stored normalised to E.164 |
| `bankName` | `BANK` only | |
| `accountNumber` | `BANK` only | Not format-checked — formats vary per bank |

> **`accountName` is the field users get wrong.** It is what the bank or wallet has on file, and a transfer is rejected when it does not match. It is routinely *different* from the shop's trading name — "Rudo Traders" paying into "R. Chikwanha". Label it explicitly ("the name on the account"), don't prefill it from the shop name.

**Response `200`** — the same shape as the GET, with the saved values.

### Errors

| status | `code` | when | what to show |
|---|---|---|---|
| `400` | `payout_field_required` | a field the chosen method needs is missing or blank — **the message names it** | inline error on that field |
| `400` | `invalid_msisdn` | the number is not valid for this cell's country | inline error on `msisdn` |
| `400` | (bean validation) | `method` or `accountName` missing, or a field over its length cap | inline field error |
| `403` | `FORBIDDEN` | caller is not a `MERCHANT_ADMIN` | not reachable from the merchant app |
| `403` | `merchant_scope_missing` | token carries no `merchantId` claim | re-login |

**Switching rails clears the other one's fields.** Going `BANK` → `MOBILE_MONEY` wipes `bankName`/`accountNumber`. Don't try to preserve them client-side — the database refuses a row carrying both.

---

## 4. Telling the seller they can't be paid

`GET /marketplace/settlements/summary` — the seller's existing "where is my money" read — gains one field:

```json
{
  "code": "OK",
  "data": {
    "merchantId": "7e2a9c41-…",
    "payoutDestinationConfigured": false,
    "totals": [
      { "status": "RELEASABLE", "parcels": 3, "netCents": 46500 }
    ]
  }
}
```

**This is the screen to surface the prompt on.** It is where a seller is standing when the answer matters to them, and the only place they would otherwise learn they need to provide one. `payoutDestinationConfigured: false` alongside a non-zero `RELEASABLE` total means *money is cleared for you and cannot be sent anywhere* — worth a banner, not a footnote.

---

## 5. The operator's side (`SUPER_ADMIN`)

### 5.1 `GET /marketplace/admin/sellers/{merchantId}/payout-destination`

Same response shape as §3, for any seller. Unmasked — a masked account number cannot be paid into.

### 5.2 `PUT /marketplace/admin/sellers/{merchantId}/payout-destination`

The override, for a seller who cannot or will not use the self-service screen — a phoned-in detail, or a correction after a failed transfer. Identical request shape and rules to §3.

**The seller is notified either way**, and the audit records that it was the operator rather than them. Both matter: a seller who did not ask for this change is the first person who should hear about it.

### 5.3 The payout report

`GET /marketplace/settlements/payout-report` now carries the destination. **New header:**

```csv
merchantId,displayName,parcels,netCents,currency,payoutMethod,payoutAccountName,payoutMsisdn,payoutBankName,payoutAccountNumber,payoutChangedAt
```

- Bank and wallet columns are **mutually exclusive** — exactly one set is populated per row.
- A seller with **no destination on file** still appears, with all six columns empty. That row is a seller who is genuinely owed money and cannot be paid; dropping it would hide them instead of surfacing them.
- **`payoutChangedAt` is a fraud control, not bookkeeping.** This report is read in the moment *before* money moves — the last point at which a human can notice a destination that moved yesterday.

**A missing destination does NOT block `POST /marketplace/settlements/pay-out`**, deliberately. That endpoint *records* a transfer the operator has already made; refusing to record one because the platform holds no address would leave the ledger saying `RELEASABLE` after the money left — and the next run would pay those parcels a second time.

---

## 6. Gotchas checklist

- [ ] **`configured: false` is a `200`.** Render "add your details", never an error state.
- [ ] **Don't mask anything.** Both surfaces are already narrow (the seller's own, and SUPER_ADMIN), and a masked value defeats both screens' purpose.
- [ ] **`PUT` replaces, it does not merge.** Always send a complete destination. There is no "just update the account number".
- [ ] **`accountName` ≠ shop name.** Label it as the name on the account; do not prefill from `displayName`. A mismatch is what gets a transfer rejected.
- [ ] **Switching method clears the other rail's fields** — expected, not data loss.
- [ ] **`msisdn` comes back normalised to E.164** (`0771234567` → `+263771234567`). Render what the server returns rather than what was typed.
- [ ] **`payout_field_required` names the offending field in `message`** — attach it to that input rather than showing a generic form error.
- [ ] **Changing this sends the seller a notification.** Expected behaviour, worth telling them on the form so the message is not alarming.
- [ ] **The CSV gained six columns at the END.** Index-based parsers keep working for columns 0–4; anything appending its own columns after `currency` needs updating. Parse by header name if you can.
- [ ] **Never render these on a public/buyer surface.** The public merchant profile does not carry them and must not start to.
