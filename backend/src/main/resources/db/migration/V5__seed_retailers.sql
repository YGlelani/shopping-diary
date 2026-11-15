INSERT INTO retailers (name, slug, website) VALUES
    ('Checkers',             'checkers',   'https://www.checkers.co.za'),
    ('Pick n Pay',           'picknpay',   'https://www.picknpay.co.za'),
    ('Shoprite',             'shoprite',   'https://www.shoprite.co.za'),
    ('Woolworths',           'woolworths', 'https://www.woolworths.co.za'),
    ('SPAR',                 'spar',       'https://www.spar.co.za'),
    ('Food Lover''s Market', 'flm',        'https://www.foodloversmarket.co.za')
ON CONFLICT (slug) DO NOTHING;