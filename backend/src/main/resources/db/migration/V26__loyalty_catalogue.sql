ALTER TABLE loyalty_hub_free_requests ADD COLUMN rows_used integer NOT NULL DEFAULT 10 CHECK(rows_used >= 0);

CREATE TABLE loyalty_generic_types (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name varchar(120) NOT NULL UNIQUE,
    category varchar(100) NOT NULL,
    keywords varchar(120) NOT NULL,
    priority integer NOT NULL CHECK(priority BETWEEN 1 AND 3),
    target integer NOT NULL DEFAULT 5 CHECK(target BETWEEN 1 AND 100),
    next_run timestamptz NOT NULL DEFAULT now(),
    last_run timestamptz,
    term_index integer NOT NULL DEFAULT 0,
    failures integer NOT NULL DEFAULT 0
);

CREATE TABLE loyalty_catalogue_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    paused boolean NOT NULL DEFAULT false
);
INSERT INTO loyalty_catalogue_state DEFAULT VALUES;

CREATE TABLE loyalty_product_metadata (
    product_id bigint PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
    provider_updated_at timestamptz NOT NULL,
    attributes jsonb NOT NULL DEFAULT '{}'
);

CREATE TABLE loyalty_product_identity (
    retailer_id bigint NOT NULL REFERENCES retailers(id) ON DELETE CASCADE,
    sku varchar(100) NOT NULL,
    product_id bigint NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    PRIMARY KEY(retailer_id,sku)
);

CREATE TABLE loyalty_generic_variants (
    generic_id bigint REFERENCES loyalty_generic_types(id) ON DELETE CASCADE,
    product_id bigint REFERENCES products(id) ON DELETE CASCADE,
    PRIMARY KEY(generic_id,product_id)
);

CREATE TABLE loyalty_latest_prices (
    product_id bigint REFERENCES products(id) ON DELETE CASCADE,
    retailer_id bigint REFERENCES retailers(id) ON DELETE CASCADE,
    scope varchar(20) NOT NULL CHECK(scope IN ('REGIONAL','ONLINE','CATALOGUE')),
    scope_label varchar(100) NOT NULL DEFAULT '',
    amount numeric(12,2) NOT NULL CHECK(amount > 0),
    regular_amount numeric(12,2),
    promotional_amount numeric(12,2),
    membership_amount numeric(12,2),
    in_stock boolean,
    observed_at timestamptz NOT NULL,
    collected_at timestamptz NOT NULL DEFAULT now(),
    source_url varchar(1000),
    promotion_terms varchar(500),
    loyalty_required boolean,
    promotion_expires_at timestamptz,
    PRIMARY KEY(product_id,retailer_id,scope,scope_label)
);
CREATE INDEX loyalty_latest_observed ON loyalty_latest_prices(observed_at);

INSERT INTO loyalty_latest_prices(product_id,retailer_id,scope,scope_label,amount,regular_amount,promotional_amount,
    membership_amount,in_stock,observed_at,collected_at,source_url,promotion_terms,loyalty_required)
SELECT DISTINCT ON (product_id,retailer_id,scope,lower(coalesce(scope_label,'')))
    product_id,retailer_id,scope,lower(coalesce(scope_label,'')),amount,regular_amount,promotional_amount,
    membership_amount,in_stock,observed_at,collected_at,source_url,promotion_terms,loyalty_required
FROM grocery_scoped_prices
WHERE source='LOYALTYHUB' AND product_id IS NOT NULL AND retailer_id IS NOT NULL
    AND scope IN ('REGIONAL','ONLINE','CATALOGUE') AND observed_at IS NOT NULL
ORDER BY product_id,retailer_id,scope,lower(coalesce(scope_label,'')),observed_at DESC,id DESC;


INSERT INTO loyalty_generic_types(name,category,keywords,priority) VALUES
    ('Maize meal', 'Staple foods', 'maize meal|mealie meal', 1),
    ('White rice', 'Staple foods', 'white rice', 1),
    ('Samp', 'Staple foods', 'samp', 1),
    ('Cake flour', 'Staple foods', 'cake flour', 1),
    ('White bread', 'Staple foods', 'white bread', 1),
    ('Brown bread', 'Staple foods', 'brown bread', 1),
    ('Macaroni', 'Staple foods', 'macaroni', 1),
    ('Spaghetti', 'Staple foods', 'spaghetti', 1),
    ('Instant noodles', 'Staple foods', 'instant noodles', 1),
    ('Oats', 'Staple foods', 'oats', 1),
    ('Sunflower cooking oil', 'Cooking essentials', 'sunflower cooking oil', 1),
    ('White sugar', 'Cooking essentials', 'white sugar', 1),
    ('Brown sugar', 'Cooking essentials', 'brown sugar', 1),
    ('Salt', 'Cooking essentials', 'salt', 1),
    ('Curry powder', 'Cooking essentials', 'curry powder', 1),
    ('Stock cubes', 'Cooking essentials', 'stock cubes', 1),
    ('Soup powder', 'Cooking essentials', 'soup powder', 1),
    ('Cooking spices', 'Cooking essentials', 'mixed spice|paprika|turmeric', 1),
    ('Frozen mixed chicken portions', 'Meat and proteins', 'frozen chicken portions|mixed chicken portions', 1),
    ('Whole chicken', 'Meat and proteins', 'whole chicken', 1),
    ('Chicken breasts', 'Meat and proteins', 'chicken breasts', 1),
    ('Chicken drumsticks', 'Meat and proteins', 'chicken drumsticks', 1),
    ('Chicken livers', 'Meat and proteins', 'chicken livers', 1),
    ('Beef mince', 'Meat and proteins', 'beef mince', 1),
    ('Beef stew meat', 'Meat and proteins', 'beef stew meat', 1),
    ('Boerewors', 'Meat and proteins', 'boerewors', 1),
    ('Polony', 'Meat and proteins', 'polony', 1),
    ('Viennas', 'Meat and proteins', 'viennas', 1),
    ('Pilchards', 'Meat and proteins', 'pilchards', 1),
    ('Canned tuna', 'Meat and proteins', 'canned tuna', 1),
    ('Sugar beans', 'Meat and proteins', 'sugar beans', 1),
    ('Baked beans', 'Meat and proteins', 'baked beans', 1),
    ('Full cream milk', 'Dairy and eggs', 'full cream milk', 1),
    ('Long-life milk', 'Dairy and eggs', 'long life milk|uht milk', 1),
    ('Low-fat milk', 'Dairy and eggs', 'low-fat milk', 1),
    ('Eggs', 'Dairy and eggs', 'eggs', 1),
    ('Amasi', 'Dairy and eggs', 'amasi', 1),
    ('Margarine', 'Dairy and eggs', 'margarine', 1),
    ('Butter', 'Dairy and eggs', 'butter', 1),
    ('Cheese', 'Dairy and eggs', 'cheese', 1),
    ('Yoghurt', 'Dairy and eggs', 'yoghurt', 1),
    ('Potatoes', 'Vegetables and fruits', 'potatoes', 1),
    ('Onions', 'Vegetables and fruits', 'onions', 1),
    ('Tomatoes', 'Vegetables and fruits', 'tomatoes', 1),
    ('Cabbage', 'Vegetables and fruits', 'cabbage', 1),
    ('Spinach', 'Vegetables and fruits', 'spinach', 1),
    ('Carrots', 'Vegetables and fruits', 'carrots', 1),
    ('Butternut', 'Vegetables and fruits', 'butternut', 1),
    ('Frozen mixed vegetables', 'Vegetables and fruits', 'frozen mixed vegetables', 1),
    ('Apples', 'Vegetables and fruits', 'apples', 1),
    ('Bananas', 'Vegetables and fruits', 'bananas', 1),
    ('Oranges', 'Vegetables and fruits', 'oranges', 1),
    ('Tea', 'Beverages', 'tea', 1),
    ('Rooibos tea', 'Beverages', 'rooibos tea', 1),
    ('Coffee', 'Beverages', 'coffee', 1),
    ('Fruit juice', 'Beverages', 'fruit juice', 1),
    ('Soft drinks', 'Beverages', 'soft drinks', 1),
    ('Washing powder', 'Household essentials', 'washing powder', 1),
    ('Dishwashing liquid', 'Household essentials', 'dishwashing liquid', 1),
    ('Toilet paper', 'Household essentials', 'toilet paper', 1),
    ('Laundry soap', 'Household essentials', 'laundry soap', 1),
    ('Bleach', 'Household essentials', 'bleach', 1),
    ('Multipurpose cleaner', 'Household essentials', 'multipurpose cleaner|multi purpose cleaner', 1),
    ('Fabric softener', 'Household essentials', 'fabric softener', 1),
    ('Refuse bags', 'Household essentials', 'refuse bags', 1),
    ('Bath soap', 'Personal care', 'bath soap|bar soap', 1),
    ('Toothpaste', 'Personal care', 'toothpaste', 1),
    ('Toothbrushes', 'Personal care', 'toothbrushes', 1),
    ('Roll-on deodorant', 'Personal care', 'roll-on deodorant', 1),
    ('Body lotion', 'Personal care', 'body lotion', 1),
    ('Petroleum jelly', 'Personal care', 'petroleum jelly', 1),
    ('Sanitary pads', 'Personal care', 'sanitary pads', 1);

INSERT INTO retailers(name,slug,website) VALUES
    ('Clicks','clicks','https://clicks.co.za/'),
    ('Dis-Chem','dischem','https://www.dischem.co.za/')
ON CONFLICT(slug) DO NOTHING;

CREATE TABLE loyalty_provider_references (
    kind varchar(20) NOT NULL,
    identifier varchar(100) NOT NULL,
    label varchar(120) NOT NULL,
    PRIMARY KEY(kind,identifier)
);

CREATE TABLE loyalty_reference_sync (
    kind varchar(20) PRIMARY KEY,
    next_run timestamptz NOT NULL
);
