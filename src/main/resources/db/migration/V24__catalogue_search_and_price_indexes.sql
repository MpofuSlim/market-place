-- Catalogue indexes from the optimization-checklist audit; EXPLAIN-checked on
-- Postgres 16 against 100k listings.

-- CatalogService.browse searches lower(title) LIKE '%q%', which no B-tree can
-- serve, so every search scanned the whole listing table. A trigram GIN index
-- on exactly LOWER(title) — the expression Hibernate renders for
-- cb.lower(root.get("title")) — turns it into a bitmap index scan. Not partial
-- on status: the Criteria query binds ACTIVE as a parameter, which a partial
-- index cannot be proved against. Queries shorter than three characters still
-- scan; a trigram index has nothing to match on.
--
-- pg_trgm ships with the postgres:16 image and is a trusted extension, so the
-- database owner can create it without superuser.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_listing_title_trgm
    ON listing USING gin (LOWER(title) gin_trgm_ops);

-- ListingSort.PRICE_ASC / PRICE_DESC on the ACTIVE catalogue: rows come back
-- in index order (id is the tie-breaker the sort adds), so a price-sorted page
-- no longer sorts every active listing first.
CREATE INDEX IF NOT EXISTS idx_listing_status_price
    ON listing (status, price_cents, id);
