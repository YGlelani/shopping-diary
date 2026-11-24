CREATE TABLE grocery_import_runs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source varchar(32) NOT NULL,
    dry_run boolean NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('SUCCEEDED','FAILED','SKIPPED')),
    examined integer NOT NULL DEFAULT 0,
    imported integer NOT NULL DEFAULT 0,
    skipped integer NOT NULL DEFAULT 0,
    review integer NOT NULL DEFAULT 0,
    checkpoint varchar(500),
    detail varchar(600),
    started_at timestamptz NOT NULL,
    finished_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX grocery_import_runs_latest ON grocery_import_runs(source,id DESC);

CREATE TABLE grocery_import_review (
    source varchar(32) NOT NULL,
    source_key varchar(180) NOT NULL,
    reason varchar(100) NOT NULL,
    label varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source,source_key)
);

ALTER TABLE stores
    ADD COLUMN osm_type varchar(8) CHECK (osm_type IN ('node','way','relation')),
    ADD COLUMN osm_id bigint CHECK (osm_id > 0),
    ADD COLUMN osm_checked_at timestamptz,
    ADD CONSTRAINT stores_osm_identity UNIQUE (osm_type,osm_id),
    ADD CONSTRAINT stores_osm_complete CHECK ((osm_type IS NULL)=(osm_id IS NULL));

CREATE TABLE grocery_scoped_prices (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source varchar(32) NOT NULL,
    source_key varchar(180) NOT NULL,
    product_id bigint REFERENCES products(id) ON DELETE SET NULL,
    retailer_id bigint REFERENCES retailers(id) ON DELETE SET NULL,
    barcode varchar(14),
    retailer_sku varchar(100),
    product_name varchar(255),
    brand varchar(100),
    package_size varchar(100),
    retailer_key varchar(100),
    scope varchar(20) NOT NULL CHECK (scope IN ('ONLINE','REGIONAL','CATALOGUE','BRANCH','UNKNOWN')),
    scope_label varchar(100),
    branch_id bigint REFERENCES stores(id) ON DELETE SET NULL,
    amount numeric(12,2) NOT NULL CHECK(amount>0),
    regular_amount numeric(12,2) CHECK(regular_amount>0),
    promotional_amount numeric(12,2) CHECK(promotional_amount>0),
    membership_amount numeric(12,2) CHECK(membership_amount>0),
    currency char(3) NOT NULL CHECK(currency='ZAR'),
    observed_at timestamptz,
    observed_on date,
    collected_at timestamptz NOT NULL DEFAULT now(),
    source_url varchar(1000),
    promotion_terms varchar(500),
    loyalty_required boolean,
    in_stock boolean,
    UNIQUE(source,source_key),
    CHECK(scope='BRANCH' OR branch_id IS NULL)
);
CREATE INDEX grocery_scoped_product ON grocery_scoped_prices(product_id,source,observed_at DESC);
CREATE INDEX grocery_scoped_retention ON grocery_scoped_prices(source,collected_at);

CREATE TABLE loyalty_hub_free_requests (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    requested_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX loyalty_hub_free_requests_time ON loyalty_hub_free_requests(requested_at);
CREATE TABLE loyalty_hub_free_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    retry_after timestamptz,
    remaining integer,
    rows_remaining integer,
    quota_reset timestamptz,
    last_checked_at timestamptz
);
INSERT INTO loyalty_hub_free_state(singleton) VALUES (true);
CREATE TABLE grocery_price_checkpoints (
    source varchar(32) NOT NULL,
    search_key varchar(180) NOT NULL,
    next_offset integer NOT NULL DEFAULT 0,
    watermark timestamptz,
    scan_watermark timestamptz,
    PRIMARY KEY(source,search_key)
);
