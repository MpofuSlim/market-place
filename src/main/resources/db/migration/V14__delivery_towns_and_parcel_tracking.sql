-- V14: sellers say WHERE they deliver and what it costs; parcels can be tracked.
--
-- Until now DELIVERY was a per-cell switch with one flat fee (zero by default):
-- nothing said whether a seller would actually take a sofa from Harare to
-- Beitbridge, and a multi-seller order paid one fee for several parcels. A
-- listing now carries the towns it delivers to, each with its own fee, and a
-- DELIVERY order is only accepted when every line delivers to the buyer's town.
--
-- Parcels gain a human tracking code and the courier's LAST known position —
-- latest only, never a route history (see order_fulfilment below).

-- ---------------------------------------------------------------------------
-- Delivery towns: reference data, seeded here and extended ONLY by a new
-- migration (the category table's discipline). A controlled list rather than
-- free text because coverage is a MATCH: a seller covering "Harare" and a buyer
-- living in "harare cbd" must meet, and free text on both sides never does.
-- `country` because each cell seeds its own market; the service filters by
-- innbucks.country.
-- ---------------------------------------------------------------------------
CREATE TABLE delivery_town (
    code       VARCHAR(40) PRIMARY KEY,
    name       VARCHAR(80) NOT NULL,
    province   VARCHAR(80) NOT NULL,
    country    VARCHAR(2)  NOT NULL,
    sort_order INTEGER     NOT NULL
);

CREATE UNIQUE INDEX uq_delivery_town_country_name ON delivery_town (country, lower(name));

INSERT INTO delivery_town (code, name, province, country, sort_order) VALUES
    ('harare',          'Harare',          'Harare',              'ZW',  10),
    ('chitungwiza',     'Chitungwiza',     'Harare',              'ZW',  20),
    ('epworth',         'Epworth',         'Harare',              'ZW',  30),
    ('bulawayo',        'Bulawayo',        'Bulawayo',            'ZW',  40),
    ('mutare',          'Mutare',          'Manicaland',          'ZW',  50),
    ('rusape',          'Rusape',          'Manicaland',          'ZW',  60),
    ('chipinge',        'Chipinge',        'Manicaland',          'ZW',  70),
    ('nyanga',          'Nyanga',          'Manicaland',          'ZW',  80),
    ('bindura',         'Bindura',         'Mashonaland Central', 'ZW',  90),
    ('mount-darwin',    'Mount Darwin',    'Mashonaland Central', 'ZW', 100),
    ('shamva',          'Shamva',          'Mashonaland Central', 'ZW', 110),
    ('mvurwi',          'Mvurwi',          'Mashonaland Central', 'ZW', 120),
    ('marondera',       'Marondera',       'Mashonaland East',    'ZW', 130),
    ('ruwa',            'Ruwa',            'Mashonaland East',    'ZW', 140),
    ('mutoko',          'Mutoko',          'Mashonaland East',    'ZW', 150),
    ('murehwa',         'Murehwa',         'Mashonaland East',    'ZW', 160),
    ('chinhoyi',        'Chinhoyi',        'Mashonaland West',    'ZW', 170),
    ('norton',          'Norton',          'Mashonaland West',    'ZW', 180),
    ('chegutu',         'Chegutu',         'Mashonaland West',    'ZW', 190),
    ('kadoma',          'Kadoma',          'Mashonaland West',    'ZW', 200),
    ('karoi',           'Karoi',           'Mashonaland West',    'ZW', 210),
    ('kariba',          'Kariba',          'Mashonaland West',    'ZW', 220),
    ('banket',          'Banket',          'Mashonaland West',    'ZW', 230),
    ('masvingo',        'Masvingo',        'Masvingo',            'ZW', 240),
    ('chiredzi',        'Chiredzi',        'Masvingo',            'ZW', 250),
    ('triangle',        'Triangle',        'Masvingo',            'ZW', 260),
    ('victoria-falls',  'Victoria Falls',  'Matabeleland North',  'ZW', 270),
    ('hwange',          'Hwange',          'Matabeleland North',  'ZW', 280),
    ('lupane',          'Lupane',          'Matabeleland North',  'ZW', 290),
    ('gwanda',          'Gwanda',          'Matabeleland South',  'ZW', 300),
    ('beitbridge',      'Beitbridge',      'Matabeleland South',  'ZW', 310),
    ('plumtree',        'Plumtree',        'Matabeleland South',  'ZW', 320),
    ('gweru',           'Gweru',           'Midlands',            'ZW', 330),
    ('kwekwe',          'Kwekwe',          'Midlands',            'ZW', 340),
    ('redcliff',        'Redcliff',        'Midlands',            'ZW', 350),
    ('zvishavane',      'Zvishavane',      'Midlands',            'ZW', 360),
    ('shurugwi',        'Shurugwi',        'Midlands',            'ZW', 370),
    ('gokwe',           'Gokwe',           'Midlands',            'ZW', 380),
    ('chivhu',          'Chivhu',          'Mashonaland East',    'ZW', 390);

-- ---------------------------------------------------------------------------
-- A listing's delivery coverage: the towns it delivers to, each with the fee
-- the seller charges to get it there. No rows = collection only. There is no
-- separate "deliverable" flag to drift out of step with this table: a listing
-- is deliverable exactly when it has a row here.
-- ---------------------------------------------------------------------------
CREATE TABLE listing_delivery_town (
    listing_id UUID        NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    town_code  VARCHAR(40) NOT NULL REFERENCES delivery_town (code),
    fee_cents  BIGINT      NOT NULL,
    PRIMARY KEY (listing_id, town_code),
    CONSTRAINT chk_listing_delivery_fee CHECK (fee_cents >= 0)
);

CREATE INDEX idx_listing_delivery_town_town ON listing_delivery_town (town_code);

-- ---------------------------------------------------------------------------
-- Addresses and order destinations name a TOWN, so coverage can be checked.
-- Existing addresses are matched on their free-text city; one that matches no
-- town keeps a null town_code and must be edited before it can take a
-- DELIVERY order (checkout says so, it does not guess).
-- ---------------------------------------------------------------------------
ALTER TABLE delivery_address ADD COLUMN town_code VARCHAR(40) REFERENCES delivery_town (code);

UPDATE delivery_address a
   SET town_code = t.code
  FROM delivery_town t
 WHERE lower(trim(a.city)) = lower(t.name);

-- A snapshot like the rest of delivery_*, so no foreign key: the order keeps
-- saying where it went even if the reference list is ever reorganised.
ALTER TABLE market_order ADD COLUMN delivery_town_code VARCHAR(40);

-- Each seller's delivery fee on a DELIVERY order, fixed at ORDER time. Per
-- seller because each seller ships their own parcel; fixed at order time
-- because the fee a seller charges can change between the order and payment,
-- and the buyer pays the fee they were quoted.
CREATE TABLE market_order_delivery_fee (
    order_id    UUID   NOT NULL REFERENCES market_order (id) ON DELETE CASCADE,
    merchant_id UUID   NOT NULL,
    fee_cents   BIGINT NOT NULL,
    PRIMARY KEY (order_id, merchant_id),
    CONSTRAINT chk_order_delivery_fee CHECK (fee_cents >= 0)
);

-- ---------------------------------------------------------------------------
-- Parcels: the seller's delivery share, a tracking code, and the LAST known
-- courier position.
--
-- Latest position ONLY, overwritten on every ping — never a route history.
-- "Where is my parcel now" needs one point; a trail of a courier's movements
-- (and, at the end of it, the buyer's front door) is personal data nobody asked
-- to keep, and not storing it removes the need for a retention job.
-- ---------------------------------------------------------------------------
ALTER TABLE order_fulfilment
    ADD COLUMN delivery_fee_cents BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN tracking_code      VARCHAR(20),
    ADD COLUMN last_latitude      NUMERIC(9, 6),
    ADD COLUMN last_longitude     NUMERIC(9, 6),
    ADD COLUMN last_accuracy_m    INTEGER,
    ADD COLUMN last_location_at   TIMESTAMPTZ,
    ADD COLUMN last_location_by   VARCHAR(64);

UPDATE order_fulfilment
   SET tracking_code = 'TRK-' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 10))
 WHERE tracking_code IS NULL;

ALTER TABLE order_fulfilment ALTER COLUMN tracking_code SET NOT NULL;

CREATE UNIQUE INDEX uq_fulfilment_tracking_code ON order_fulfilment (tracking_code);

ALTER TABLE order_fulfilment
    ADD CONSTRAINT chk_fulfilment_delivery_fee CHECK (delivery_fee_cents >= 0),
    -- A position is all three or nothing: a latitude with no time is a point
    -- nobody can say is current.
    ADD CONSTRAINT chk_fulfilment_location_complete CHECK (
        (last_latitude IS NULL AND last_longitude IS NULL AND last_location_at IS NULL)
        OR (last_latitude IS NOT NULL AND last_longitude IS NOT NULL
            AND last_location_at IS NOT NULL));

-- The seller's delivery share rides their settlement: they did the delivering,
-- so the escrow holds it with the goods and releases it the same way.
-- gross_cents INCLUDES it; commission is charged on the goods only.
ALTER TABLE merchant_settlement
    ADD COLUMN delivery_fee_cents BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_settlement_delivery_fee CHECK (delivery_fee_cents >= 0);
