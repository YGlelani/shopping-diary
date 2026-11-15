CREATE TABLE categories (
    id        BIGSERIAL PRIMARY KEY,
    parent_id BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    name      VARCHAR(100) NOT NULL,
    slug      VARCHAR(100) NOT NULL UNIQUE,
    depth     INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_categories_parent ON categories (parent_id);

CREATE TABLE products (
    id             BIGSERIAL PRIMARY KEY,
    canonical_name VARCHAR(255) NOT NULL,
    brand          VARCHAR(100),
    barcode        VARCHAR(20) UNIQUE,
    category_id    BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    base_unit      VARCHAR(10) CHECK (base_unit IN ('g','ml','count','kg','l')),
    base_quantity  NUMERIC(12,4),
    size_text      VARCHAR(50),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_products_barcode ON products (barcode) WHERE barcode IS NOT NULL;
CREATE INDEX idx_products_category ON products (category_id);
CREATE INDEX idx_products_name_trgm ON products USING GIN (canonical_name gin_trgm_ops);

CREATE TABLE product_aliases (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    retailer_id BIGINT NOT NULL REFERENCES retailers(id) ON DELETE CASCADE,
    raw_name    VARCHAR(500) NOT NULL,
    confidence  NUMERIC(3,2) NOT NULL DEFAULT 1.00 CHECK (confidence BETWEEN 0 AND 1),
    matched_by  VARCHAR(20) NOT NULL CHECK (matched_by IN ('BARCODE','FUZZY','MANUAL')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (retailer_id, raw_name)
);
CREATE INDEX idx_aliases_product ON product_aliases (product_id);
CREATE INDEX idx_aliases_raw_trgm ON product_aliases USING GIN (raw_name gin_trgm_ops);

CREATE TABLE price_observations (
    id                  BIGSERIAL PRIMARY KEY,
    product_id          BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    store_id            BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    scrape_job_id       BIGINT,
    source_type         VARCHAR(20) NOT NULL
                        CHECK (source_type IN ('SCRAPER','API','CSV','BROCHURE','RECEIPT','COMMUNITY','MANUAL')),
    source_reference    VARCHAR(500),
    observed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    shelf_price         NUMERIC(10,2),
    promo_price         NUMERIC(10,2),
    member_price        NUMERIC(10,2),
    unit_price          NUMERIC(12,4),
    stock_status        VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
                        CHECK (stock_status IN ('IN_STOCK','OUT_OF_STOCK','UNKNOWN')),
    verification_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
                        CHECK (verification_status IN ('UNVERIFIED','VERIFIED','DISPUTED','STALE','EXPIRED')),
    raw_payload         JSONB,
    content_hash        VARCHAR(64) NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_po_product_store_time ON price_observations (product_id, store_id, observed_at DESC);
CREATE INDEX idx_po_store_time ON price_observations (store_id, observed_at DESC);
CREATE INDEX idx_po_observed_at ON price_observations (observed_at DESC);
CREATE INDEX idx_po_status ON price_observations (verification_status);