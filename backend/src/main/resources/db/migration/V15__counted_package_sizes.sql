CREATE OR REPLACE FUNCTION catalogue_package_size(value text)
RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT trim_scale(replace(parts[1], ',', '.')::numeric *
        CASE WHEN parts[2] IN ('kg', 'l') THEN 1000 ELSE 1 END)::text ||
        CASE WHEN parts[2] IN ('g', 'kg') THEN 'g'
             WHEN parts[2] IN ('ml', 'l') THEN 'ml'
             ELSE 'count' END
    FROM regexp_match(lower(value), '^\s*([0-9]+(?:[.,][0-9]+)?)\s*(kg|g|ml|l|count|pack|pk)\s*$') AS raw(parts)
$$;
