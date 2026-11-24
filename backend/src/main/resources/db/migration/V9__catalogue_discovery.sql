CREATE FUNCTION catalogue_package_size(value text)
RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT trim_scale(normalized.parts[1]::numeric *
        CASE WHEN normalized.parts[2] IN ('kg', 'l') THEN 1000 ELSE 1 END)::text ||
        CASE WHEN normalized.parts[2] IN ('g', 'kg') THEN 'g' ELSE 'ml' END
    FROM regexp_match(lower(value), '^\s*([0-9]+(?:[.,][0-9]+)?)\s*(kg|g|ml|l)\s*$')
        AS raw(parts)
    CROSS JOIN LATERAL (SELECT ARRAY[replace(raw.parts[1], ',', '.'), raw.parts[2]] AS parts) normalized
$$;

ALTER TABLE products ADD COLUMN catalogue_source_url VARCHAR(1000);
ALTER TABLE products ADD COLUMN image_source_url VARCHAR(1000);

INSERT INTO categories (name, slug)
SELECT department.name, department.slug
FROM (VALUES
    ('Fruit and vegetables', 'fruit-and-vegetables'),
    ('Meat, poultry and fish', 'meat-poultry-and-fish'),
    ('Dairy and eggs', 'dairy-and-eggs'),
    ('Bakery', 'bakery'),
    ('Pantry essentials', 'pantry-essentials'),
    ('Beverages', 'beverages'),
    ('Snacks and biscuits', 'snacks-and-biscuits'),
    ('Frozen foods', 'frozen-foods'),
    ('Household and cleaning', 'household-and-cleaning'),
    ('Personal care', 'personal-care')
) AS department(name, slug)
WHERE NOT EXISTS (
    SELECT 1 FROM categories WHERE lower(name) = lower(department.name)
)
ON CONFLICT (slug) DO NOTHING;

-- Existing provider-hosted images already carry attributable URLs.
UPDATE products
SET image_source_url = 'https://world.openfoodfacts.org/product/' || barcode
WHERE barcode ~ '^[0-9]{8,14}$'
  AND image_url LIKE 'https://images.openfoodfacts.org/%';
