-- Coordinates come from each official locator's linked place pin.
INSERT INTO stores (retailer_id, store_code, name, address, city, province, postal_code,
                    location, phone, hours_text, source_url)
SELECT retailer.id, entry.code, entry.name, entry.address, entry.city, entry.province, entry.postal_code,
       pin.location, entry.phone, entry.hours_text, entry.source_url
FROM (VALUES
    ('directory-farmyard-polokwane', 'Food Lover''s Market The Farmyard',
     'The Farmyard, Buzworx Avenue', 'Polokwane', 'Limpopo', '0700', 29.5131659, -23.8728077,
     '015 590 5000', 'Mon-Fri 08:00-19:00; Sat-Sun 08:00-17:00',
     'https://foodloversmarket.co.za/stores/limpopo/food-lovers-market-the-farmyard/'),
    ('directory-nelspruit', 'Food Lover''s Market Nelspruit',
     'Andrew Street', 'Mbombela', 'Mpumalanga', '1201', 30.9709158, -25.469684,
     '013 752 3791', 'Mon-Fri 08:00-18:30; Sat-Sun 08:00-17:00',
     'https://foodloversmarket.co.za/stores/mpumalanga/food-lovers-market-nelspruit/'),
    ('directory-kimberley', 'Food Lover''s Market Kimberley',
     'Diamond Pavilion Mall, Cruikshank Street, Monument Heights', 'Kimberley', 'Northern Cape', '8301',
     24.7634277, -28.7658681, '053 832 9965', 'Mon-Fri 09:00-18:00; Sat 09:00-17:00; Sun 09:00-14:00',
     'https://foodloversmarket.co.za/stores/northern-cape/food-lovers-market-kimberley/'),
    ('directory-showgate', 'Food Lover''s Market Showgate',
     'Showgate Centre, Curie Avenue, Generaal De Wet', 'Bloemfontein', 'Free State', '9301',
     26.203552, -29.1377961, '071 849 8137', 'Mon-Fri 09:00-18:00; Sat 09:00-17:00; Sun 09:00-13:30',
     'https://foodloversmarket.co.za/stores/free-state/food-lovers-market-showgate/')
) entry(code, name, address, city, province, postal_code, lon, lat, phone, hours_text, source_url)
JOIN retailers retailer ON retailer.slug = 'flm'
CROSS JOIN LATERAL (SELECT ST_SetSRID(ST_MakePoint(entry.lon, entry.lat), 4326) AS location) pin
WHERE NOT EXISTS (
    SELECT 1 FROM stores existing
    WHERE existing.retailer_id = retailer.id AND upper(existing.store_code) IS DISTINCT FROM 'ONLINE'
      AND (lower(existing.name) = lower(entry.name)
           OR ST_DWithin(existing.location::geography, pin.location::geography, 150))
)
ON CONFLICT (retailer_id, store_code) DO NOTHING;
