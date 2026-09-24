# Fleet wiring — ticketing-system changes required to onboard marketplace-service

The fleet-side wiring for this service lives in `MpofuSlim/ticketing-system`
(the same way loyalty-service/InnRewards is wired). Apply these on a
`feature/marketplace-fleet-wiring` branch there. Nothing below is needed to
build or review THIS repo — it is needed to deploy into the cell.

## 1. Gateway routes — `api-gateway/src/main/resources/application.yaml`

Order matters: the deny route MUST precede the service route ("three files
must agree" rule). Insert alongside the other service routes:

```yaml
# Internal S2S surface — payments-service only, never internet-reachable.
- id: marketplace-internal-deny
  uri: forward:/__edge_deny__
  predicates:
    - Path=/marketplace/internal/**

- id: marketplace-service-route
  uri: lb://marketplace-service
  predicates:
    - Path=/marketplace/**
  filters:
    - name: RequestRateLimiter
      args:
        redis-rate-limiter.replenishRate: 50
        redis-rate-limiter.burstCapacity: 100
        key-resolver: "#{@gatewayKeyResolver}"
```

Springdoc aggregation (mirror the six existing entries):

```yaml
# In the springdoc proxy routes block:
- id: marketplace-service-api-docs
  uri: lb://marketplace-service
  predicates:
    - Path=/marketplace-service/v3/api-docs/**,/marketplace-service/v3/api-docs
  filters:
    - StripPrefix=1

# In springdoc.swagger-ui.urls:
- name: marketplace-service
  url: /marketplace-service/v3/api-docs
```

## 2. k8s — `deploy/k8s/04-services.yaml`

Copy the loyalty-service Deployment+Service block (lines ~229-280) and adapt:

- name/labels/hostname: `marketplace-service`
- image: `ghcr.io/mpofuslim/marketplace-service:latest`
- port: `8087`
- `DB_URL`: `jdbc:postgresql://postgres:5432/marketplace_service`
- same `envFrom` (cell ConfigMap + Secret), same security context
  (runAsUser 10001, readOnlyRootFilesystem, caps dropped, no SA token),
  `EUREKA_INSTANCE_HOSTNAME=marketplace-service`,
  `EUREKA_PREFER_IP_ADDRESS=false`.

Add `marketplace_service` to the pg-init databases ConfigMap
(`docker/postgres/init-databases.sql`) so the database is created on a fresh
cell.

## 3. Prometheus — `prometheus/prometheus.yml`

Add a scrape job mirroring the loyalty-service job: target
`marketplace-service:8087`, path `/actuator/prometheus`, header
`X-Metrics-Token` = the fleet `METRICS_SCRAPE_TOKEN`.

## 4. Cell env — `deploy/cells/cell.<iso>.env` (+ local secret file)

No NEW secrets are required: marketplace-service consumes the existing
fleet-shared `JWT_SECRET` (verify-only), `INTERNAL_API_TOKEN`,
`REDIS_PASSWORD`, `METRICS_SCRAPE_TOKEN`, plus its `DB_PASSWORD` and its own
`AUDIT_HMAC_SECRET` (generate: `openssl rand -base64 48`; add to the cell
secret).

## 5. Payments integration (when the payments generalization lands)

The payments service will drive marketplace orders through the SAME contract
shape it uses for bookings:

| Purpose            | Booking endpoint (today)                  | Marketplace endpoint (this repo)                          |
|--------------------|-------------------------------------------|-----------------------------------------------------------|
| Read amount/payer  | `GET /bookings/internal/{id}`             | `GET /marketplace/internal/orders/{ref}`                  |
| Keep hold alive    | `PATCH /bookings/internal/{id}/extend-hold` | `PATCH /marketplace/internal/orders/{ref}/extend-expiry` |
| Confirm on paid    | `PATCH /bookings/internal/{id}/confirm`   | `PATCH /marketplace/internal/orders/{ref}/confirm-payment` |

All three require `X-Internal-Token` (constant-time compared) and are
edge-denied by route 1 above. Confirm cross-checks the paid amount against
the order total and refuses on mismatch (the 100x guard).

## 6. The buyer checkout journey (V9) — NO fleet changes required

The cart, address book, checkout quote and fulfilment surfaces added in V9 all
live under paths the existing `marketplace-service-route` already matches:

| Surface            | Path                              | Audience                    |
|--------------------|-----------------------------------|-----------------------------|
| Cart               | `/marketplace/cart/**`            | CUSTOMER                    |
| Delivery addresses | `/marketplace/addresses/**`       | CUSTOMER                    |
| Checkout           | `/marketplace/checkout/**`        | CUSTOMER                    |
| Fulfilment queue   | `/marketplace/fulfilments/**`     | MERCHANT_ADMIN / SUPER_ADMIN |

None is an internal S2S surface, so **no new deny route is needed** and the
"three files must agree" rule does not apply to them — they are ordinary
authenticated endpoints behind the fleet JWT, gated per-endpoint by
`@PreAuthorize`. Pinned by `SecuritySurfaceIT`.

**Payments needs no change either.** Money is still collected by
payment-service through the same three internal endpoints in section 5; the
order's `total_cents` remains the single number it reads, now simply the sum of
the lines plus any delivery fee. What IS new is that marketplace orders now
tell the app how to reach that service — `GET /marketplace/checkout/options`
and each pending order's `payment` block name `POST /payments` with
`{orderType: "MARKETPLACE", orderRef, paymentRail}`.

### The one thing a cell operator must set

`MARKETPLACE_PAYMENT_METHODS` (default `INNBUCKS_CODE`) is what the app offers
at checkout, and **it must mirror the rails payment-service is actually
provisioned with on that cell**. Marketplace-service holds no payment
credentials and cannot ask; advertising `ZIMSWITCH_CARD` or `ECOCASH` on a cell
whose credentials are blank sends the buyer to a 503 from another service —
exactly the half-provisioned failure the ticketing CLAUDE.md records for the ZW
card rail. The default is deliberately the one rail every cell has.

Optional, same file: `MARKETPLACE_DELIVERY_METHODS` (default
`DELIVERY,COLLECTION`). There is no cell-wide delivery fee any more: since V14
each seller sets a fee per town on their listings, and
`MARKETPLACE_DELIVERY_FEE_CENTS` is ignored — remove it from the cell env.

Courier tracking (V14), same file, all optional:
`MARKETPLACE_TRACKING_MIN_PING_INTERVAL_SECONDS` (5),
`MARKETPLACE_TRACKING_MAX_PING_AGE_SECONDS` (600), and the market's bounding
box `MARKETPLACE_TRACKING_{MIN,MAX}_{LATITUDE,LONGITUDE}` — the defaults are
Zimbabwe with a margin, so **a cell in any other market must set all four**
or every courier position there is refused `location_out_of_bounds`. The new
endpoints (`/marketplace/delivery-towns`, `/marketplace/deliveries/**`) ride
the existing `/marketplace/**` route; no gateway change.


## 7. Sellers are ORGANIZATIONS (step 2 of the fleet organizations plan)

Ships in lock-step with two other repos, and they deploy **together**:

| Repo | Change |
|---|---|
| `ticketing-system` (user-service) | stops minting the `merchantId` claim for merchant admins; serves `GET /admin/organizations` |
| `InnRewards` (loyalty, V51) | loyalty merchants owned by organization; `GET /loyalty/internal/merchants/names` removed |
| this repo | seller = the session's `orgId` (OWNER/ADMIN + `marketplace` product) |

**S2S calls this service now makes to user-service** (both behind the existing
`/users/internal/**` permitAll + the gateway's `user-internal-deny`, so no
gateway or SecurityConfig change):

- `GET /users/internal/organizations/names?ids=` — seller names (was loyalty's
  `/loyalty/internal/merchants/names`). `ApiResult` body, ≤ 200 ids per call.
- `GET /users/internal/organizations/{id}/admins` — who to tell about a paid
  order (was `/users/internal/merchants/{id}/admins`, which chained through
  loyalty's `merchants.admin_email`).

**Data: seller ids change meaning.** Every `merchant_id` column here
(`listing`, `listing_review`, `marketplace_seller` (PK), `market_order_item`,
`order_fulfilment`, `merchant_settlement`, `settlement_dispute`) holds a
**loyalty merchant id** on a cell that sold before this change. After it, a
seller's scope is an organization id, so those rows belong to nobody until
they are remapped (loyalty merchant → the organization owning it, per
InnRewards' `merchants.organization_id` once that is stamped) or the cell's
marketplace data is reset. This service cannot do it itself — it never knew
which business owned a loyalty merchant. Watch for two merchants mapping to
ONE organization: `marketplace_seller` is keyed by `merchant_id`, so their
rows must be merged, not both renamed. Production has no marketplace data,
so this is a staging-only decision.
