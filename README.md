# Shopping Diary

**A Smarter Way to Plan, Track and Save on Groceries.**

Shopping Diary is a South African grocery budgeting and price-intelligence platform designed to help consumers make smarter shopping decisions, maximise savings and manage their grocery expenses.

The platform helps users plan their shopping around a fixed budget, compare product prices across different retailers, discover specials, track price changes and identify the most cost-effective shopping options.

## About the Project

Grocery prices in South Africa vary between retailers, making it difficult for consumers to know where to get the best value for their money.

Shopping Diary aims to address this challenge by bringing grocery budgeting, price comparison and shopping planning into one platform.

The goal is simple: **help South African consumers get more value from every rand they spend.**

## Core Features

- **Budget Management:** Set a shopping budget and monitor planned and actual spending.
- **Shopping Lists:** Create, manage and organise grocery shopping lists.
- **Price Comparison:** Compare equivalent products across different retailers.
- **Specials and Deals:** Discover available grocery promotions and discounts.
- **Price Tracking:** Monitor product price changes and identify price drops.
- **Smart Shopping Recommendations:** Identify cost-effective shopping options based on the user's budget.
- **Shopping History:** Keep a record of previous purchases and spending patterns.
- **Store Discovery:** Find relevant retailers and shopping opportunities.

## Technology Stack

The technology stack is documented and maintained alongside the project's implementation.

## Development

To set up the project locally:

1. Clone the repository.
2. Navigate to the project directory.
3. Install or configure the required dependencies.
4. Configure the necessary environment variables.
5. Set up the database.
6. Run the application using the appropriate development commands.

Refer to the project's documentation for detailed installation, configuration, architecture and deployment instructions.

## Project Vision

Shopping Diary aims to become a practical grocery intelligence platform for South African consumers, helping households make informed purchasing decisions, reduce unnecessary spending and manage their grocery budgets more effectively.

---

**Project:** Shopping Diary  
**Focus:** Grocery Budgeting & Price Intelligence  
**Target Market:** South African Consumers
### Grocery product discovery

Discover searches the PostgreSQL catalogue by name, brand, barcode, category and package size. Searches and shopping-list additions work without a saved shopping location. Set a location to see eligible nearby branch observations and active promotions. Products without a usable observation remain in the results.

Run the Spring Boot backend and Vite frontend against a local or staging catalogue with PostGIS and pg_trgm enabled. For local development, start PostgreSQL and Redis with `docker compose up -d`, configure the backend for that database and a local JWT secret, run `mvnw.cmd spring-boot:run` in `backend`, then run `npm ci` and `npm run dev` in `frontend`. Point `VITE_API_URL` at the local API or use `/api/v1` with the existing Vite proxy.

To run the API integration tests, start a disposable PostGIS database from the repository root. The suite connects to the loopback address and database below and rolls back its fixtures.

~~~powershell
docker run -d --rm --name shopping-diary-discovery-test --tmpfs /var/lib/postgresql/data -e POSTGRES_PASSWORD=discovery_test -e POSTGRES_DB=discovery_test -p 127.0.0.1:55436:5432 postgis/postgis:16-3.4
Push-Location backend
$env:DISCOVERY_POSTGIS_TEST = 'true'
.\mvnw.cmd test
Remove-Item Env:DISCOVERY_POSTGIS_TEST
Pop-Location
docker stop shopping-diary-discovery-test
~~~

Run `npm run lint` and `npm run build` in `frontend`. On staging, verify `bread`, `Sasko`, `rice 2kg` and a known barcode. Confirm category filtering, an unpriced add-to-list, and a priced branch comparison.

Before release, snapshot the target database and validate Flyway migrations V9 and V10 on staging. V9 adds package-size normalization, default departments in the existing categories table and two provenance columns. V10 indexes catalogue text and legacy category searches with pg_trgm. Deploy the Render API and let Flyway finish before releasing the Vercel frontend. Use the existing environment settings for each deployment.

The Open Food Facts admin panel requires an admin account and `OFF_CONTACT_EMAIL`. Preview first on an isolated catalogue. Imports require explicit confirmation and `SHOPPING_DIARY_CATALOGUE_IMPORT_ENABLED=true`, which defaults to false. Confirm the ODbL reuse obligations for the published catalogue and the image licensing and attribution requirements before enabling public imports. [Open Food Facts API and licensing](https://openfoodfacts.github.io/documentation/docs/Product-Opener/api/)

### Supermarket price comparison

Comparison uses the exact catalogue product and a branch-specific observation. The latest non-future row for each product and branch determines current eligibility. A newer out-of-stock, disputed, or incomplete row prevents the earlier price from reappearing. Physical branches must be active, and the latest observation must report stock and a positive price with an eligible verification status. The configured freshness cap defaults to seven days. Community, receipt and brochure observations have an additional three-day cap. Verification and freshness are tracked separately.

Discovery, comparisons, shopping-list totals, basket optimisation, and price alerts use the shared branch-price rule. The comparison quantity and basket quantity determine multibuy eligibility. Member prices are listed separately and excluded from public totals. Unit prices use known product weight, volume, or count. History displays up to 2,000 observations from the past 180 days. Nearby areas come from the registered branch directory. Users can also choose a map location or use GPS.

#### Retailer and branch coverage

The directory seeds twenty distinct brands. Group ownership does not affect how the same product is compared.

- Shoprite, Checkers, Usave and OK Foods. [Shoprite Holdings brand portfolio](https://www.shopriteholdings.co.za/docs/interim-results-presentation-2024.pdf)
- Pick n Pay and Boxer. [Pick n Pay group store network](https://picknpay-ir.co.za/pdf/annual-report/2025/integrated-annual-report-2025-spread.pdf)
- SPAR, Woolworths and Food Lover's Market. [SPAR locator](https://www.spar.co.za/Store-Finder) · [Woolworths locator](https://www.woolworths.co.za/storelocator) · [Food Lover's Market locator](https://foodloversmarket.co.za/stores/)
- Makro, Game and Walmart. [Walmart Africa brands](https://corporate.walmart.com/about/international/markets/africa) · [Makro locator](https://www.makro.co.za/pages/store-finder) · [Walmart locator](https://www.walmart.co.za/store-finder)
- Kit Kat Cash & Carry, President Hyper, Devland Cash & Carry, Oxford Freshmarket, Hyperland, Big Save, FreshStop and Checkmart. [Kit Kat](https://kitkatgroup.com/Shop/Locator) · [President Hyper](https://www.presidenthyper.co.za/contact-us/) · [Devland](https://www.devland.co.za/stores/) · [Oxford Freshmarket](https://www.oxfordfreshmarket.co.za/stores) · [Hyperland](https://hyperland.co.za/) · [Big Save](https://bigsave.co.za/) · [FreshStop](https://freshstop.co.za/) · [Checkmart stockist listing](https://mumbaispiceworks.co.za/stockists/)

A fresh database includes three locator-backed branches across two brands and zero price observations. V14 skips branches that already exist at the same site. The admin directory reports each retailer's active branches, branches with eligible prices, last observation and last recorded collection. Production coverage must be read from that directory after deployment. [FreshStop Caledon](https://freshstop.co.za/store/caledon/) · [Food Lover's Market Garden Route Mall](https://foodloversmarket.co.za/stores/south-western-districts/food-lovers-market-garden-route-mall/) · [Food Lover's Market Harvest Place](https://foodloversmarket.co.za/stores/gauteng/food-lovers-market-harvest-place/)

#### Price sources and rollout

- For manual or authorised price feeds, upload an admin CSV with the retailer slug, branch code, product identity, stock status, price and explicit `observed_at`. Use an ISO date for a day-precision record, or an ISO timestamp with offset. Send unconditional promotions in the CSV promo column. Use optional source_type and source_reference columns to distinguish manual, receipt and brochure observations. Receipt and brochure rows require a source reference. The sample CSV is historical test data.
- Community shelf prices require a branch, timestamp, stock confirmation and admin approval. The optional reference can identify receipt or shelf-label evidence for the reviewer. Pending submissions stay outside price comparisons.
- ReefAPI promotion imports require a confirmed branch and a trusted product alias. Admins can create an alias matching the exact feed name to a verified catalogue barcode. LoyaltyHub's online observations, historical HDX prices, and Open Food Facts catalogue records do not supply branch offers. Retailer-wide advertisements require branch confirmation before they can enter the comparison. Live coverage depends on approved data sources and branch onboarding.
- Phase 2 adds V11 for shared price rules, V12 for the retailer directory, V13 for submissions, V14 for locator-backed branches, and V15 for counted package sizes. Run the isolated PostGIS test command above, then lint and build the frontend. Snapshot staging first and verify existing branches, offers, and the admin moderation queue. Release the backend and migrations before the frontend. To roll back, restore the earlier application builds and retain the additive migrations and observation history. Use a database restore only after reconciling writes made since the snapshot.
