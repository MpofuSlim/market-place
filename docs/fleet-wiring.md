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
