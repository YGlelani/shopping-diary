ALTER TABLE shopping_lists ADD COLUMN creation_request_id uuid;
CREATE UNIQUE INDEX idx_list_creation_request
    ON shopping_lists (user_id, creation_request_id) WHERE creation_request_id IS NOT NULL;
