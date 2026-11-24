INSERT INTO retailers (name, slug, website) VALUES
    ('Boxer', 'boxer', 'https://www.boxer.co.za/'),
    ('Makro', 'makro', 'https://www.makro.co.za/pages/store-finder'),
    ('OK Foods', 'okfoods', 'https://www.okfoods.co.za/'),
    ('Usave', 'usave', 'https://www.shopriteholdings.co.za/'),
    ('Game', 'game', 'https://www.game.co.za/'),
    ('Kit Kat Cash & Carry', 'kit-kat', 'https://kitkatgroup.com/Shop/Locator'),
    ('President Hyper', 'president-hyper', 'https://www.presidenthyper.co.za/contact-us/'),
    ('Devland Cash & Carry', 'devland', 'https://www.devland.co.za/stores/'),
    ('Oxford Freshmarket', 'oxford-freshmarket', 'https://www.oxfordfreshmarket.co.za/stores'),
    ('Hyperland', 'hyperland', 'https://hyperland.co.za/'),
    ('Big Save', 'big-save', 'https://bigsave.co.za/'),
    ('Walmart', 'walmart', 'https://www.walmart.co.za/store-finder'),
    ('FreshStop', 'freshstop', 'https://freshstop.co.za/'),
    ('Checkmart', 'checkmart', 'https://checkmart.co.za/')
ON CONFLICT DO NOTHING;
