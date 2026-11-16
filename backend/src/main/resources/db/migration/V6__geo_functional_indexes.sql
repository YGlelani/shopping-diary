CREATE INDEX IF NOT EXISTS idx_stores_geog
    ON stores USING GIST ((location::geography));

CREATE INDEX IF NOT EXISTS idx_user_locations_geog
    ON user_locations USING GIST ((location::geography));