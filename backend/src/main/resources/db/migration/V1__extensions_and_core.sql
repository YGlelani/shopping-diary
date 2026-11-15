CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE users (
    id             BIGSERIAL PRIMARY KEY,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    display_name   VARCHAR(100) NOT NULL,
    role           VARCHAR(20)  NOT NULL DEFAULT 'USER'
                   CHECK (role IN ('GUEST','USER','CONTRIBUTOR','ADMIN')),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ
);
CREATE INDEX idx_users_email ON users (email) WHERE deleted_at IS NULL;

CREATE TABLE user_locations (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label        VARCHAR(50) NOT NULL,
    address      VARCHAR(500),
    location     GEOMETRY(Point, 4326) NOT NULL,
    is_default   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_locations_user ON user_locations (user_id);
CREATE INDEX idx_user_locations_geo ON user_locations USING GIST (location);

CREATE TABLE retailers (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL UNIQUE,
    slug       VARCHAR(100) NOT NULL UNIQUE,
    website    VARCHAR(255),
    logo_url   VARCHAR(500),
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stores (
    id          BIGSERIAL PRIMARY KEY,
    retailer_id BIGINT NOT NULL REFERENCES retailers(id) ON DELETE RESTRICT,
    name        VARCHAR(255) NOT NULL,
    store_code  VARCHAR(50),
    address     VARCHAR(500),
    location    GEOMETRY(Point, 4326) NOT NULL,
    city        VARCHAR(100),
    province    VARCHAR(100),
    postal_code VARCHAR(10),
    store_type  VARCHAR(30) NOT NULL DEFAULT 'SUPERMARKET'
                CHECK (store_type IN ('SUPERMARKET','MALL','INDEPENDENT','SPAZA','SHOPPING_CENTER')),
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (retailer_id, store_code)
);
CREATE INDEX idx_stores_retailer ON stores (retailer_id);
CREATE INDEX idx_stores_geo ON stores USING GIST (location);
CREATE INDEX idx_stores_type ON stores (store_type) WHERE active;