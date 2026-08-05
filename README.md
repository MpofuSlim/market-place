# marketplace-service

InnBucks Marketplace — merchant product listings, a public catalog, and
buyer orders. A standalone service in the InnBucks fleet: it registers on the
cell's Eureka registry as `marketplace-service` and is routed by the fleet
api-gateway at `/marketplace/**` (same extraction/join pattern as
`MpofuSlim/InnRewards`).

## What it owns

- **Listings** — merchants (JWT `merchantId`/`shopId` scoping from
  user-service) create and manage product listings. Prices in **minor units**
  (cents), cell currency.
- **Catalog** — public, paginated browse/search over ACTIVE listings.
- **Orders** — buyers place orders; stock is reserved atomically; orders are
  `PENDING_PAYMENT` until the platform payments service confirms payment via
  the internal S2S surface, or they lapse and restock via the expiry sweeper.

## What it deliberately does NOT own

- **Identity** — user-service mints the fleet JWTs; this service only verifies
  (HS256 shared secret + RS256 dual-verify via `JWT_PUBLIC_KEY`).
- **Payments** — no InnBucks Merchant API client here. The payments service
  drives orders through `/marketplace/internal/orders/*` (read, extend-expiry,
  confirm-payment) exactly like it drives bookings in ticketing.
- **Loyalty** — points ride InnRewards' generic surfaces.

## Run locally

```sh
cp .env.example .env   # fill in the dev values
set -a; . ./.env; set +a
./mvnw spring-boot:run
```

Swagger UI (dev only): http://localhost:8087/swagger-ui/index.html

## Build & test

```sh
./mvnw test     # unit tests (no Docker needed)
./mvnw verify   # + Testcontainers integration tests (needs Docker)
```

## Deploy

The Release workflow builds, Trivy-scans, then pushes
`ghcr.io/mpofuslim/marketplace-service` (`:latest` + `:sha-<commit>`) with
provenance + SBOM. The k8s Deployment lives in the fleet repo
(`ticketing-system/deploy/k8s/`) — see `docs/fleet-wiring.md` for the exact
fleet-side wiring (gateway routes, manifests, Prometheus).

See `CLAUDE.md` for the full conventions and security invariants.
