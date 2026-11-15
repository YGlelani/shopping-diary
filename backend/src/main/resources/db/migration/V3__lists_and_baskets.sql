CREATE TABLE promotions (
    id           BIGSERIAL PRIMARY KEY,
    product_id   BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    store_id     BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    promo_type   VARCHAR(20) NOT NULL
                 CHECK (promo_type IN ('MULTIBUY','DISCOUNT','MEMBER','BOGOF')),
    description  VARCHAR(500),
    promo_price  NUMERIC(10,2),
    min_quantity INT DEFAULT 1,
    start_date   DATE NOT NULL,
    end_date     DATE NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED'
                 CHECK (status IN ('SCHEDULED','ACTIVE','EXPIRED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_promotions_active ON promotions (store_id, status, end_date);
CREATE INDEX idx_promotions_product ON promotions (product_id);

CREATE TABLE shopping_lists (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name          VARCHAR(200) NOT NULL,
    budget_amount NUMERIC(10,2),
    status        VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT','ACTIVE','COMPLETED','ARCHIVED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lists_user ON shopping_lists (user_id, status);

CREATE TABLE shopping_list_items (
    id              BIGSERIAL PRIMARY KEY,
    list_id         BIGINT NOT NULL REFERENCES shopping_lists(id) ON DELETE CASCADE,
    product_id      BIGINT REFERENCES products(id) ON DELETE SET NULL,
    free_text       VARCHAR(500),
    quantity        NUMERIC(10,3) NOT NULL DEFAULT 1,
    unit            VARCHAR(10),
    preferred_brand VARCHAR(100),
    flexible_brand  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (product_id IS NOT NULL OR free_text IS NOT NULL)
);
CREATE INDEX idx_list_items_list ON shopping_list_items (list_id);

CREATE TABLE baskets (
    id              BIGSERIAL PRIMARY KEY,
    list_id         BIGINT NOT NULL REFERENCES shopping_lists(id) ON DELETE CASCADE,
    store_id        BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    subtotal        NUMERIC(10,2) NOT NULL DEFAULT 0,
    promo_savings   NUMERIC(10,2) NOT NULL DEFAULT 0,
    missing_items   INT NOT NULL DEFAULT 0,
    item_breakdown  JSONB,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_baskets_list ON baskets (list_id, computed_at DESC);

CREATE TABLE basket_optimizations (
    id                    BIGSERIAL PRIMARY KEY,
    list_id               BIGINT NOT NULL REFERENCES shopping_lists(id) ON DELETE CASCADE,
    user_location_id      BIGINT REFERENCES user_locations(id) ON DELETE SET NULL,
    strategy              VARCHAR(20) NOT NULL CHECK (strategy IN ('SINGLE','MULTI')),
    total_product_cost    NUMERIC(10,2) NOT NULL,
    total_transport_cost  NUMERIC(10,2) NOT NULL DEFAULT 0,
    grand_total           NUMERIC(10,2) NOT NULL,
    savings_vs_baseline   NUMERIC(10,2) NOT NULL DEFAULT 0,
    recommendation_json   JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_opt_list ON basket_optimizations (list_id, created_at DESC);

CREATE TABLE basket_optimization_stores (
    id               BIGSERIAL PRIMARY KEY,
    optimization_id  BIGINT NOT NULL REFERENCES basket_optimizations(id) ON DELETE CASCADE,
    store_id         BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    round_trip_km    NUMERIC(8,2),
    transport_cost   NUMERIC(10,2) NOT NULL DEFAULT 0,
    store_subtotal   NUMERIC(10,2) NOT NULL DEFAULT 0
);
CREATE INDEX idx_opt_stores_opt ON basket_optimization_stores (optimization_id);