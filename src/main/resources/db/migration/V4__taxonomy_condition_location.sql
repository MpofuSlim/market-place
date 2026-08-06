-- =============================================================================
-- V4: category taxonomy (curated two-level tree), item condition, and
-- listing location (city/area).
--
-- The free-text listing.category column becomes a FK onto a seeded category
-- table: browse filters stop being magic strings, and a parent code expands
-- to its children at query time. The taxonomy is migration-owned (no admin
-- CRUD surface) — extend it with a later migration, never at runtime.
-- =============================================================================
CREATE TABLE category (
    code        VARCHAR(40) PRIMARY KEY,
    name        VARCHAR(80) NOT NULL,
    parent_code VARCHAR(40) REFERENCES category (code)
);

CREATE INDEX idx_category_parent ON category (parent_code);

-- Top-level categories.
INSERT INTO category (code, name, parent_code) VALUES
    ('electronics',     'Electronics',        NULL),
    ('fashion',         'Fashion',            NULL),
    ('home-garden',     'Home & Garden',      NULL),
    ('vehicles',        'Vehicles',           NULL),
    ('beauty-health',   'Beauty & Health',    NULL),
    ('sports-outdoors', 'Sports & Outdoors',  NULL),
    ('baby-kids',       'Baby & Kids',        NULL),
    ('groceries',       'Groceries',          NULL),
    ('services',        'Services',           NULL),
    ('other',           'Other',              NULL);

-- Children.
INSERT INTO category (code, name, parent_code) VALUES
    ('phones-tablets',      'Phones & Tablets',      'electronics'),
    ('computers',           'Computers',             'electronics'),
    ('tv-audio',            'TV & Audio',            'electronics'),
    ('appliances',          'Appliances',            'electronics'),
    ('electronics-accessories', 'Accessories',       'electronics'),

    ('mens',                'Men''s Clothing',       'fashion'),
    ('womens',              'Women''s Clothing',     'fashion'),
    ('kids-clothing',       'Kids'' Clothing',       'fashion'),
    ('shoes',               'Shoes',                 'fashion'),
    ('bags-accessories',    'Bags & Accessories',    'fashion'),

    ('furniture',           'Furniture',             'home-garden'),
    ('kitchen-dining',      'Kitchen & Dining',      'home-garden'),
    ('garden-outdoor',      'Garden & Outdoor',      'home-garden'),
    ('home-decor',          'Home Decor',            'home-garden'),
    ('tools-diy',           'Tools & DIY',           'home-garden'),

    ('cars',                'Cars',                  'vehicles'),
    ('motorcycles',         'Motorcycles',           'vehicles'),
    ('vehicle-parts',       'Parts & Accessories',   'vehicles'),
    ('trucks-trailers',     'Trucks & Trailers',     'vehicles'),

    ('skincare',            'Skincare',              'beauty-health'),
    ('haircare',            'Haircare',              'beauty-health'),
    ('fragrances',          'Fragrances',            'beauty-health'),
    ('wellness',            'Wellness & Supplements','beauty-health'),

    ('fitness',             'Fitness & Gym',         'sports-outdoors'),
    ('team-sports',         'Team Sports',           'sports-outdoors'),
    ('camping-hiking',      'Camping & Hiking',      'sports-outdoors'),
    ('cycling',             'Cycling',               'sports-outdoors'),

    ('baby-gear',           'Baby Gear',             'baby-kids'),
    ('toys-games',          'Toys & Games',          'baby-kids'),
    ('kids-furniture',      'Kids'' Furniture',      'baby-kids'),

    ('fresh-produce',       'Fresh Produce',         'groceries'),
    ('pantry',              'Pantry Staples',        'groceries'),
    ('beverages',           'Beverages',             'groceries'),

    ('repairs',             'Repairs & Maintenance', 'services'),
    ('events',              'Events & Catering',     'services'),
    ('lessons',             'Lessons & Tutoring',    'services');

-- ---------------------------------------------------------------------------
-- listing.category (free text) -> listing.category_code (FK). Existing rows
-- whose free-text category happens to equal a taxonomy code (e.g. the
-- pre-taxonomy convention was already "electronics") keep it; everything
-- else lands in 'other'.
-- ---------------------------------------------------------------------------
ALTER TABLE listing ADD COLUMN category_code VARCHAR(40) REFERENCES category (code);

UPDATE listing
   SET category_code = CASE
        WHEN lower(trim(category)) IN (SELECT code FROM category) THEN lower(trim(category))
        ELSE 'other'
   END;

ALTER TABLE listing
    ALTER COLUMN category_code SET NOT NULL,
    ALTER COLUMN category_code SET DEFAULT 'other';

-- Dropping the column also drops V1's idx_listing_status_category.
ALTER TABLE listing DROP COLUMN category;

CREATE INDEX idx_listing_status_category_code ON listing (status, category_code);

-- ---------------------------------------------------------------------------
-- Item condition. Enum-as-varchar with a CHECK, matching the fleet's status
-- column convention.
-- ---------------------------------------------------------------------------
ALTER TABLE listing ADD COLUMN condition VARCHAR(20) NOT NULL DEFAULT 'NEW'
    CHECK (condition IN ('NEW', 'USED_LIKE_NEW', 'USED_GOOD', 'USED_FAIR'));

-- ---------------------------------------------------------------------------
-- Location: optional free-text city/area (jsoup-sanitized app-side). The
-- browse ?city= filter compares lower(city) — index matches that expression.
-- Geo/radius search is future work (would need PostGIS or a geohash column).
-- ---------------------------------------------------------------------------
ALTER TABLE listing
    ADD COLUMN city VARCHAR(80),
    ADD COLUMN area VARCHAR(120);

CREATE INDEX idx_listing_status_city ON listing (status, lower(city));
