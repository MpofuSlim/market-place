# Marketplace Seller Trust Stats — Frontend Integration

**Service:** `marketplace-service` · **Builds on:** the checkout journey (V9, PR #46)

The seller profile finally answers "how do I know this seller ships?" — with
figures **computed from their real order history**, not seller-entered claims.

---

## 1. What changed

You previously asked for a response time on the seller profile and were told
no, because the platform had nothing to base one on. V9 changed that: every
paid order now leaves a fulfilment trail (`paid → dispatched → delivered`, and
*who* confirmed delivery). These endpoints serve figures computed from that
trail. Logo and return policy remain absent — still nothing to compute them
from.

Everything here is **additive**. No existing field changed.

---

## 2. Public: the seller profile grew a `fulfilment` block

`GET /marketplace/catalog/merchants/{merchantId}` — public, no auth, unchanged
otherwise.

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
    "displayName": "Rudo Traders",
    "verified": true,
    "since": "2026-04-01T09:15:00Z",
    "ratingAvg": 5.0,
    "reviewCount": 1,
    "activeListingCount": 12,
    "fulfilment": {
      "completedOrders": 128,
      "medianDispatchHours": 20,
      "buyerConfirmedPercent": 96
    }
  }
}
```

| Field | Meaning | Render as |
|---|---|---|
| `completedOrders` | Parcels this seller has delivered, all time | "128 orders completed" |
| `medianDispatchHours` | Median hours from the buyer **paying** to the seller **dispatching**, rounded UP (never 0 — a same-hour dispatch reads `1`) | "Usually dispatches within 20 hours" / bucket it ("same day", "within 2 days") |
| `buyerConfirmedPercent` | Share of completed parcels the **buyer** confirmed receiving — evidence strength, not a score | "96% of deliveries confirmed by buyers" |

### The nulls are deliberate — render them, don't fill them

- **`fulfilment` absent entirely** → the seller has never completed a parcel.
  Render **"New seller"**, not zeroes — a zero reads like a verdict.
- **`medianDispatchHours` null** inside the block → not enough dispatched
  parcels yet, **or** a seller whose goods are always handed over in person
  (nothing is ever "dispatched"). Just omit the line.
- **`buyerConfirmedPercent` null** → fewer than the minimum sample (5) of
  completed parcels. Omit the line; `completedOrders` still shows.

Never compute your own version of these client-side, and never default a null
to 0 or 100 — the whole point of this surface is that every number on it is
one the platform can stand behind.

---

## 3. Seller-side: `GET /marketplace/fulfilments/stats`

`MERCHANT_ADMIN` (own merchant, always — a `merchantId` param is **ignored**
for them) or `SUPER_ADMIN` (must pass `?merchantId=`, else
`400 merchant_id_required`).

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "publicStats": {
      "completedOrders": 128,
      "medianDispatchHours": 20,
      "buyerConfirmedPercent": 96
    },
    "awaitingDispatch": 3,
    "inTransit": 5,
    "completedOrders": 128
  }
}
```

- `publicStats` is **byte-identical to what shoppers see** on the profile —
  same query, same rounding — so the seller dashboard can say "this is your
  public track record" truthfully. Null while they have no completed parcel.
- `awaitingDispatch` is the number to surface loudly: paid orders the seller
  has not moved. Badge it on the seller home screen.
- `inTransit` = dispatched, not yet delivered.

Dispatching faster and getting buyers to confirm receipt are now the two
levers a seller has to improve their own public profile — worth a line in the
seller onboarding copy.

---

## 4. Gotchas checklist

- [ ] Absent `fulfilment` block = **"New seller"**, never zeroes.
- [ ] Null figures inside the block = omit the line, never default.
- [ ] `medianDispatchHours` is payment→dispatch, **not** payment→delivery —
      don't label it "delivery time".
- [ ] `buyerConfirmedPercent` is evidence strength, not a star rating — avoid
      red/green thresholds that punish sellers whose buyers just don't tap
      "confirm".
- [ ] The seller-side endpoint is not a buyer surface: CUSTOMER tokens get 403.
