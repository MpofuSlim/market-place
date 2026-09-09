# Marketplace Seller Approval — Frontend Integration

**Service:** `marketplace-service` · **Merged:** PR #27 (`b2f585e`) · **Migration:** V8

Answers §3.1 of *Foundry Console — Backend Requests*: the seller trust record,
the SUPER_ADMIN approval queue, and the buyer-facing verified badge.

---

## 1. What changed

A "seller" used to be nothing but a `merchantId` UUID stamped on a listing from
the caller's JWT. There was no record to hang a decision on, so a buyer could not
tell a vetted merchant from one that signed up an hour ago — the gap you said you
couldn't fake a badge for.

`marketplace_seller` is that record, keyed by **`merchantId`** rather than a
surrogate id: a merchant *is* the seller here, so "one trust record per merchant"
is true by construction.

---

## 2. Base URL, auth, headers

| | |
|---|---|
| Base URL | the API gateway origin (`/foundry` prefix in staging/prod, stripped by nginx) |
| Route | `/marketplace/**` → `lb://marketplace-service` — **already existed, no gateway change** |
| Auth | `Authorization: Bearer <jwt>` — a normal fleet user JWT, not the S2S surface |
| Role | **`SUPER_ADMIN`**, class-level on the whole controller |
| `X-Tenant-Id` | **Not required** anywhere in this guide |
| Content-Type | `application/json` on reject/suspend (required body) |

> **Envelope differs from user-service.** marketplace's `ApiResult` uses a short
> `code` (`"OK"`), not `"200 OK"`, and **errors carry a slug code with no `data`
> key**: `{ "code": "seller_not_found", "message": "…" }`. Don't reuse the
> ticketing error parser here.

---

## 3. Admin endpoints

### 3.1 `GET /marketplace/admin/sellers`

| Query param | Default | Notes |
|---|---|---|
| `status` | *(all)* | `PENDING` \| `APPROVED` \| `REJECTED` \| `SUSPENDED` |
| `page` | `0` | |
| `size` | `20` | |

**Oldest first (FIFO).**

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "items": [
      {
        "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
        "status": "PENDING",
        "displayName": null,
        "verified": false,
        "canPublish": true,
        "decidedBy": null,
        "decisionNote": null,
        "createdAt": "2026-04-01T09:15:00Z",
        "decidedAt": null
      }
    ],
    "page": 0, "size": 20, "totalElements": 37, "totalPages": 2
  }
}
```

**`verified` and `canPublish` are two different questions**, and this is the part
worth reading twice:

| status | `verified` (badge) | `canPublish` |
|---|---|---|
| `PENDING` | ❌ | ✅ |
| `APPROVED` | ✅ | ✅ |
| `REJECTED` | ❌ | ❌ |
| `SUSPENDED` | ❌ | ❌ |

Both are computed server-side and returned, so **don't derive them from `status`
in the client** — if the policy changes, the flags change with it and your UI
follows for free.

### 3.2 `PUT /marketplace/admin/sellers/{merchantId}/approve`

Body **optional**:

```json
{ "note": "Verified against CR14.", "displayName": "Rudo Traders" }
```

- `displayName` (≤120 chars) is read on **approve only** — naming a seller is
  part of vouching for them, and it's the trading name the badge shows. Omit it
  to leave the existing name unchanged.
- Re-approving an already-`APPROVED` seller is **allowed**, so a name can be
  corrected without a suspend/reinstate dance.

### 3.3 `PUT /marketplace/admin/sellers/{merchantId}/reject`

Body **required**, `note` **required** (≤500 chars).

Keeps their DRAFT work but stops them publishing.

### 3.4 `PUT /marketplace/admin/sellers/{merchantId}/suspend`

Body **required**, `note` **required**.

> **Suspend also deactivates every one of that seller's `ACTIVE` listings**, in
> the same transaction and in one statement. A suspension that left goods on sale
> would mean nothing. Warn the admin in the confirm dialog — this is destructive
> to the seller's storefront, and your listing views will change underneath you.

### 3.5 `PUT /marketplace/admin/sellers/{merchantId}/reinstate`

Body optional. Returns the seller to `APPROVED`.

> **Reinstate deliberately does NOT re-publish the listings the suspension took
> down.** The seller chooses what goes back on sale. Say so in the UI, or an
> admin will reinstate and assume the storefront is restored.

Only a `SUSPENDED` or `REJECTED` seller can be reinstated — anything else is a
409.

---

## 4. The buyer-facing badge

`ListingResponse` now carries a **`seller`** object, on both the merchant surface
and the public catalogue. **Always present**, never null:

```json
"seller": {
  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
  "displayName": "Rudo Traders",
  "verified": true,
  "since": "2026-04-01T09:15:00Z"
}
```

| Field | Notes |
|---|---|
| `merchantId` | same value as the listing's own `merchantId` |
| `displayName` | **nullable** — render the listing without a seller name rather than inventing one |
| `verified` | `APPROVED` only. Never inferred from a merchant merely existing. |
| `since` | first listing for a backfilled merchant; **nullable** when there's no trust record |

**Why `displayName` can be null:** this service holds ids, not names — there is no
merchant-name lookup in it. An admin sets it on approve. Resolving it
automatically from InnRewards is a clean follow-up that needs an S2S client this
repo doesn't have yet.

**Performance:** badges resolve with **one batched query per page**, keyed on
distinct merchant ids. A page of listings costs the same regardless of page size —
no N+1, so paginate freely.

---

## 5. The publish gate

`canPublish` is enforced server-side on the transition **to `ACTIVE`**, alongside
the existing primary-image requirement:

```json
{ "code": "seller_not_permitted", "message": "This seller may not publish listings" }
```

**403.** Render it against the publish action, not as a generic error.

Two deliberate choices behind that:

- **The gate is on the transition to ACTIVE, not on drafting.** A `DRAFT` is
  private, so an unvetted seller drafting costs nobody anything; `ACTIVE` is the
  moment goods reach buyers.
- **A merchant with no trust record yet is allowed.** The record is written on
  their first listing. Treating an absence as a refusal would fail closed on
  *"new"* rather than on *"barred"*.

---

## 6. ⚠️ Two things that will shape your first screen

### 6.1 Every existing merchant is `PENDING`, and your queue will look busy

V8 backfilled every merchant that has already listed something as **`PENDING`,
not `APPROVED`**. None of them was ever vetted, so marking them approved would
have put a verified badge on the entire existing catalogue on day one — the
platform asserting something it has not done.

So expect a **real backlog on first load**. That backlog is the point. Nobody is
blocked by it, because `PENDING` can still publish.

`createdAt` is the merchant's **first listing**, so the FIFO order reflects how
long they have actually been trading here rather than when the migration ran.

### 6.2 `PENDING` sellers can still trade, unbadged — is that what you want?

Only `REJECTED` and `SUSPENDED` stop a seller publishing. Making `PENDING` a hard
gate would turn this into an **approval-queued marketplace**, which is a product
decision about onboarding friction rather than a consequence of adding a trust
record — so it wasn't made as a side effect.

It is a one-line change to `SellerStatus.canPublish` if you want it. **Tell us
which way**, and note your UI shouldn't hard-code the assumption either way —
read `canPublish`.

---

## 7. Error handling

All top-level; there are no per-field/per-row errors on this surface.

| Status | `code` | When |
|---|---|---|
| **400** | `note_required` | reject/suspend without a `note` |
| **403** | *(standard)* | caller is not SUPER_ADMIN |
| **403** | `seller_not_permitted` | publish attempt by a REJECTED/SUSPENDED seller |
| **404** | `seller_not_found` | no trust record for that merchant |
| **409** | `seller_already_rejected` / `seller_already_suspended` | no-op transition |
| **409** | `seller_not_suspended` | reinstate on a seller that is neither SUSPENDED nor REJECTED |

Bean-validation failures (`note` > 500, `displayName` > 120) return 400.

The 409s are safe to treat as *"someone got there first"* — re-fetch the queue.

---

## 8. Gotchas checklist

- [ ] Error envelope is `{ code, message }` with a **slug** code — not ticketing's `"400 BAD_REQUEST"` shape, and no `data` key.
- [ ] Read **`canPublish`** and **`verified`** from the response; don't re-derive them from `status`.
- [ ] `note` is required on **reject** and **suspend**, optional on approve/reinstate.
- [ ] `displayName` is read on **approve only** — sending it to reject/suspend/reinstate does nothing.
- [ ] `seller.displayName` and `seller.since` are **nullable** on the badge; `seller` itself never is.
- [ ] **Suspend deactivates live listings**; **reinstate does not restore them.** Say both in the UI.
- [ ] The queue's initial content is a genuine backfill backlog, all `PENDING`.
- [ ] Endpoints are keyed by **`merchantId`**, not a seller id — and it's the **loyalty** merchant id (`GET /loyalty/merchants`), not a user-service `userUuid`.
- [ ] No `X-Tenant-Id` on this surface.
- [ ] Timestamps are `Instant`, always UTC with the `Z` suffix.
- [ ] Paginate freely — badges are batched, not N+1.

---

## 9. Related

- **`merchantId` identity** — see §3.2 of the backend response: `GET /loyalty/merchants`
  is authoritative. MERCHANT_ADMIN tokens now carry a `merchantId` claim
  (ticketing PR #560), which is what makes merchant self-service listing work at
  all.
- **Seller-applied / seller-approved notifications** (§1.4 of your list) are not
  built — there is no notifications resource yet. These decisions currently notify
  nobody.
