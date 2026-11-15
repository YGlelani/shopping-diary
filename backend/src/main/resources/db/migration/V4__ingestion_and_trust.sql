CREATE TABLE scrape_sources (
    id                      BIGSERIAL PRIMARY KEY,
    retailer_id             BIGINT NOT NULL REFERENCES retailers(id) ON DELETE CASCADE,
    source_type             VARCHAR(20) NOT NULL CHECK (source_type IN ('HTML','JSON_API','CATALOGUE')),
    url                     VARCHAR(1000) NOT NULL,
    scraper_class           VARCHAR(200) NOT NULL,
    requires_residential_ip BOOLEAN NOT NULL DEFAULT FALSE,
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sources_retailer ON scrape_sources (retailer_id) WHERE active;

CREATE TABLE scrape_jobs (
    id             BIGSERIAL PRIMARY KEY,
    retailer_id    BIGINT NOT NULL REFERENCES retailers(id) ON DELETE CASCADE,
    source_id      BIGINT REFERENCES scrape_sources(id) ON DELETE SET NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
                   CHECK (status IN ('QUEUED','RUNNING','SUCCESS','PARTIAL','FAILED')),
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    items_scraped  INT NOT NULL DEFAULT 0,
    items_failed   INT NOT NULL DEFAULT 0,
    error_message  TEXT,
    github_run_id  VARCHAR(100),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_jobs_retailer_time ON scrape_jobs (retailer_id, created_at DESC);
CREATE INDEX idx_jobs_status ON scrape_jobs (status);

ALTER TABLE price_observations
    ADD CONSTRAINT fk_po_scrape_job
    FOREIGN KEY (scrape_job_id) REFERENCES scrape_jobs(id) ON DELETE SET NULL;

CREATE TABLE receipts (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    store_id       BIGINT REFERENCES stores(id) ON DELETE SET NULL,
    purchase_date  DATE,
    total_amount   NUMERIC(10,2),
    image_url      VARCHAR(1000),
    ocr_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                   CHECK (ocr_status IN ('PENDING','DONE','FAILED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_receipts_user ON receipts (user_id, created_at DESC);

CREATE TABLE community_submissions (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id       BIGINT REFERENCES products(id) ON DELETE SET NULL,
    store_id         BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    submitted_price  NUMERIC(10,2) NOT NULL,
    photo_url        VARCHAR(1000),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    submitted_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_community_status ON community_submissions (status, submitted_at);

CREATE TABLE price_alerts (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id        BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    target_price      NUMERIC(10,2) NOT NULL,
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    last_notified_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, product_id)
);
CREATE INDEX idx_alerts_active ON price_alerts (active, product_id) WHERE active;

CREATE TABLE verification_events (
    id             BIGSERIAL PRIMARY KEY,
    observation_id BIGINT NOT NULL REFERENCES price_observations(id) ON DELETE CASCADE,
    user_id        BIGINT REFERENCES users(id) ON DELETE SET NULL,
    action         VARCHAR(20) NOT NULL
                   CHECK (action IN ('VERIFY','DISPUTE','APPROVE','REJECT')),
    note           TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_verif_obs ON verification_events (observation_id, created_at DESC);