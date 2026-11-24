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

### Location-aware store discovery

Nearby Stores supports a confirmed town, suburb, province, shopping area, postcode, or manually entered coordinate. The GeoNames directory is bundled with the backend and can resolve places without registered stores. GPS is requested on demand as a single reading. Denied permission, timeouts, unavailable services and readings with an uncertainty radius above 1 km leave manual selection available.

The active shopping location is held in memory and shared by Discover, branch comparisons and Basket Optimiser. Clearing the location or leaving the app session removes it. The app clears the old local-storage location and does not create a location history. Nearby and comparison requests send coordinates to the Shopping Diary API. The map is opt-in and requests tiles covering the viewed area. Opening directions passes the branch pin to the map provider.

Nearby branches use PostGIS spheroid distance in metres and return kilometres. The list labels the straight-line distance. Basket transport estimates use a separate straight-line round trip for each branch. External directions handle travel routes. Price coverage counts distinct recorded products separately from eligible and verified eligible products, using the shared Phase 2 pricing rules. A physical branch can appear with no observations and an unknown operating schedule.

#### Geography and branch sources

The bundled GeoNames snapshot contains 17,467 reference records from the South African gazetteer and postal downloads. It retains populated places, primary administrative regions, shopping features and geocoded postcodes. The display uses provinces and available district names for disambiguation. Postal rows include province only when the exact locality name maps to one province in the gazetteer. Scores prefer exact names and then larger localities. Reference points are approximate locality or postcode centres. [GeoNames data downloads and CC BY 4.0 terms](https://www.geonames.org/export/)

The baseline now contains 20 supported brands and seven pinned branches across two brands and six provinces when installed on an empty database. V18 adds four manually checked Food Lover's Market branches using addresses and place pins from their official locators. The additions skip likely duplicate sites and supply no test prices. Live branch and price coverage varies by area. Import approved retailer locator data or propose a branch with source evidence. An administrator verifies the retailer, coordinates and physical address before publishing. Existing admin branch edits preserve the branch ID and its price history. [Polokwane](https://foodloversmarket.co.za/stores/limpopo/food-lovers-market-the-farmyard/) · [Mbombela](https://foodloversmarket.co.za/stores/mpumalanga/food-lovers-market-nelspruit/) · [Kimberley](https://foodloversmarket.co.za/stores/northern-cape/food-lovers-market-kimberley/) · [Bloemfontein](https://foodloversmarket.co.za/stores/free-state/food-lovers-market-showgate/)

#### API and rollout

These routes use the app's existing store access rules.

| Method and route | Parameters and result |
| --- | --- |
| GET /api/v1/stores/provinces | Nine province labels |
| GET /api/v1/stores/areas | q and optional province. Up to 20 reference points with ID, name, province, area, coordinates and kind |
| GET /api/v1/stores/nearby | lat, lon, r in kilometres, optional retailer slug, area, priced, promotions and exact productId. sort accepts distance, freshness, name or price. Price sorting requires a product ID. offset and limit page the original array response |
| GET /api/v1/stores/{id} | Branch metadata and coverage. Pass both lat and lon to include distance |
| GET /api/v1/stores/{id}/offers | Paged eligible prices and a representative active promotion per product |
| GET /api/v1/stores/{id}/observations | Paged recorded shelf observations, separate from current offers |
| POST /api/v1/stores/submissions | Authenticated branch proposal with retailer ID, physical address, coordinates and HTTPS evidence |
| GET /api/v1/admin/directory/submissions | Pending branch proposals |
| POST /api/v1/admin/directory/submissions/{id} | Admin approve or reject with the approve query parameter |

The feature reuses React Leaflet and PostGIS. The default raster map is best-effort with OpenStreetMap attribution. Deployment can set VITE_MAP_TILE_URL and VITE_MAP_ATTRIBUTION to a compatible tile provider. Vite values are public browser configuration. Store secret keys outside these variables. A commercial tile provider may charge by usage. Geocoding uses no runtime service or key. [OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/)

Run the isolated PostGIS integration suite and the frontend lint and build above. Apply V16 through V18 to staging and confirm map, list, branch moderation and existing price-comparison workflows. V16 adds branch contact and provenance fields and a branch-first price index. V17 adds the pending directory queue. V18 adds verified locator records. Deploy the API and migrations before the frontend. For rollback, restore the earlier frontend and API artifacts and retain the additive migrations and branch records. Check the index build time against staging data before releasing.

For the browser smoke test, start an API bound to loopback port 18081 against the disposable database. Allow the Vite origin at http://localhost:55175 and point VITE_API_URL at http://127.0.0.1:18081/api/v1. Set VITE_MAP_TILE_URL to http://localhost:55175/test-map-tiles/{z}/{x}/{y}.png to exercise tile failure. Launch Chrome with a temporary profile and a loopback DevTools port of 59223, then run node frontend/scripts/location-smoke.mjs from the repository root. The test uses simulated GPS callbacks and writes screenshots to backend/target.

### Smart shopping lists

Lists and entries use the existing PostgreSQL shopping-list tables. Each list belongs to its authenticated owner and can save a description, budget and explicitly chosen preferred location. Setting no preferred location follows the active shopping location. Archived lists are read-only for item changes until restored. Duplication preserves quantities, notes, brand preferences and approved alternatives and resets all purchases on the new list.

Catalogue quantities are package counts. Custom entries support kilograms, grams, litres and millilitres with up to three decimals, plus whole counts for items, packs, bottles, cartons, bags and dozens. Custom entries are private. Linking a custom entry to a catalogue product converts its unit to packages and requires a compatible whole count. Adding a catalogue product merges only with an unpurchased entry with the same unit, preferences, substitute and notes.

The optimiser and estimates use the same eligible branch offers from Phase 2. A shopper can approve a specific alternative from the same category with the same measured size. This is a manual compatibility choice. The app does not infer ingredient or dietary equivalence. A strict brand preference filters eligible products. When substitutes are allowed, a preferred brand breaks same-branch price ties. Estimates show the lowest available line totals across branches and omit unknown prices from the monetary subtotal. Basket optimisation excludes purchased items and reports unresolved or missing items.

#### List API

Existing create, detail, list, add, increment, remove, basket and optimisation endpoints remain available under /api/v1/lists. Unknown or out-of-scope lists return 404.

| Route | Behaviour |
| --- | --- |
| PUT /lists/{id}?revision=… | Save name, description, budget, and preferred location. Optional status sets ACTIVE, ARCHIVED, COMPLETED or DRAFT |
| POST /lists/{id}/duplicate | Copy a list using the supplied name |
| POST /lists/{id}/items | Add by productId or customName, with quantity, unit, preferredBrand, flexibleBrand, substituteProductId, notes and purchased |
| PUT /lists/{id}/items/{itemId}?revision=… | Replace the item settings and update or unlink a catalogue reference |
| DELETE /lists/{id}/items/purchased?revision=… | Clear purchased entries |
| GET /lists/{id}/estimate | Best eligible line offers using lat, lon and optional r in kilometres. Entries without a quote are absent |
| GET /lists/coverage | Counts of priced entries by owned list. Optional lat, lon and r supply the active location when the list has no saved location |

List and item edits use the saved list revision and return 409 when the revision is stale. The UI submits revisions for deletions as well. Create, duplicate and add accept an optional UUID Idempotency-Key and reuse it for retries of the same request. The API returns the saved list or item on a replay while its record exists.

#### Testing and release

V19 adds list metadata, item notes and purchasing status, an approved alternate reference, add-retry tracking and the shared list-offer calculation. It preserves existing items and backfills catalogue names so an item can remain readable if its product is removed later. V20 adds per-owner creation retry keys. Both changes are additive. Run the isolated PostGIS suite and frontend lint and production build above. Deploy the backend with V19 and V20 before the frontend. To roll back, restore the earlier app builds and retain the migrations and saved list data.

The browser demo uses a disposable API on loopback port 18082, Vite on localhost port 55176, Chrome DevTools on loopback port 59224, and the fixture in backend/src/test/resources/shopping-list-browser-fixture.sql applied to the disposable PostGIS database. Run node frontend/scripts/lists-smoke.mjs from the repository root to exercise the flow and write screenshots to backend/target. Keep verification prices confined to the disposable database.

## Grocery budget planning and tracking

Open **Grocery Budgets** or start a budget from an existing shopping list. Budgets support single trips, weekly and fortnightly periods, monthly periods starting on the chosen day, and custom date ranges. The dashboard links one or more owned lists. Marking a list item purchased changes only the list. Record the receipt or individual purchase separately to update actual spending. A list can be reused across periods. When two distinct lists both call for a product, their quantities represent separate requests. Repeated links to the same list are stored once.

Phase 4 stored only a cap on each list and calculated a local balance from the returned price rows. It had no dated budget history, actual purchases or independent ledger. The cap and existing list and optimiser API contracts are still supported. Flyway V21 adds budgets, owned list links and actual expenses. Archived budgets preserve a dated aggregate estimate. Reuse copies the budget settings and links.

The backend uses PostgreSQL decimal values and Java BigDecimal. It quotes using the same exact package IDs, approved substitutions, fresh branch observations and eligible promotions as the shopping list. A selected branch restricts all quotes to that branch. With no branch selected, the estimate uses the best eligible line available for each item, so prices can span branches. Lists use their saved location first and otherwise use the active shopping location. A missing quote has a null value. When some prices are unavailable, the balance applies only to the known subtotal. A known subtotal above the cap triggers an overspending warning even with missing lines. A full estimate is marked within budget only when all priced lines are available at the same branch. Verification, observation dates and known promotion expiry dates are shown when available. The dashboard rechecks visible estimates every minute and supports manual refresh.

Actual balance is the budget cap minus the sum of recorded purchases. Purchases must be dated within the budget period and may be corrected later. The dashboard shows category totals and a comparison when the full plan has eligible prices and purchases have been recorded. The comparison uses the recorded period total and the full list estimate. Purchase-level price attribution requires an item-by-item receipt breakdown. No trend is inferred from periods with unknown receipt completeness.

### Budget API

All routes require the current user. The base path is /api/v1/budgets.

| Operation | Method and path |
| --- | --- |
| View budget history or a budget and its ledger | GET / or GET /{id}, with optional offset |
| Create or reuse a budget | POST / or POST /{id}/reuse |
| Edit or delete a budget | PUT or DELETE /{id}, with revision |
| Request an estimate or archive with a snapshot | GET /{id}/estimate or POST /{id}/archive |
| Record a purchase | POST /{id}/expenses with an Idempotency-Key UUID |
| Correct or remove a purchase | PUT or DELETE /{id}/expenses/{expenseId}, with revision |

Estimate and archive accept optional lat, lon, r (kilometres, default 15), and branchId. Archive also requires the current revision. Create and reuse accept an optional Idempotency-Key UUID for safe retry. A stale revision returns HTTP 409.

### Budget release and rollback

1. Snapshot the database beforehand and use a separate Neon staging database or disposable PostGIS database first. Start the backend and confirm Flyway applies V21 successfully.
2. Run Maven verify with DISCOVERY_POSTGIS_TEST=true against the disposable discovery_test database at loopback port 55436. Run npm run lint and npm run build from frontend.
3. Smoke-test create, link, partial-pricing warnings, actual spending, correction and reuse. The loopback browser script is frontend/scripts/budget-smoke.mjs. It uses the fixture backend/src/test/resources/shopping-list-browser-fixture.sql on discovery_test, the API on port 18083, Vite on localhost:55177 and headless Chrome CDP on port 59225. Run it with Node from the repository root. Screenshots go to backend/target.
4. Deploy the Render API with the existing runtime settings, confirm Flyway and health, then deploy the Vercel frontend. Verify the authenticated dashboard with staging data before promoting the release.
5. For rollback, revert the Vercel frontend first and leave the additive V21 schema in place to preserve purchases. If the earlier API image fails Flyway validation because it predates V21, use a rollback build with the previous application code and the unchanged V21 migration bundled. A staging snapshot can be restored to a separate database for recovery verification. Any production restore needs an explicit recovery plan for purchases recorded after the snapshot.

## Intelligent basket optimisation

The original optimiser summed independent straight-line return journeys for every store, applied a default cost per kilometre even without a travel estimate, and omitted custom items from the recommendation. It also filtered out lower-coverage strategy cards, hid the difference between exact and substituted baskets, and had no saved-plan history. Phase 6 extends the existing optimiser and keeps the shopping-list and branch-price APIs as its data sources.

### How plans are compared

The optimiser starts with unpurchased list entries, the selected location and radius, and active physical branches. Retailer exclusions are hard constraints. A preferred retailer is a tie-breaker. Prices come from the existing shopping_list_item_offer and current_branch_price rules. Quantities count packages for catalogue items. A substitute must already be approved on the shopping-list item. The optimiser does no name matching and suggests no package conversions or extra quantities to qualify for a discount.

The engine evaluates every priced single branch and combinations of up to three shortlisted branches. The visit cap defaults to two. A split shortlist contains up to 24 branches, retaining branches that add coverage first and filling the rest by standalone coverage and cost. It evaluates combinations independently for exact products and approved alternatives. Within a combination, it uses the least expensive eligible line per entry, preferring verified and newer observations for equal prices. A branch gets counted only if it supplies at least one line. Results rank by number of requirements covered, known cost, actual visits, number of substitutes, preferred retailer, verified evidence, estimated distance and a stable branch ID tie-break. When the shortlist is applied, the interface labels the recommendation as the best plan found.

Driving uses the shortest straight-line loop through the assigned branches at the user's stated rate. This distance is an approximation. Walking uses zero monetary transport cost. For public transport or a fixed-cost trip, the user supplies the full return cost. If transport is unknown, the engine ranks by grocery subtotal and leaves the trip total unknown. The interface shows a full trip estimate only with complete price coverage and known transport. It reports savings only against a complete one-store estimate with known transport. Incomplete strategies list every unresolved entry and show what is known separately.

Price observations must be current, at the actual branch, in stock and verified or unverified under the shared freshness policy. Each line includes the source, timestamp and verification status. Public fixed discounts and qualifying multibuy totals use existing branch rules and expire on the South African calendar. Disputed, out-of-stock and historical observations are ineligible. Loyalty-only prices and unsupported promotion types are excluded because the app cannot validate eligibility or terms for those offers. A selected Phase 5 budget uses its remaining funds after recorded expenses. An explicit amount or the list cap also works.

### Optimiser API and saved plans

- POST /api/v1/lists/{id}/optimize accepts lat, lon, radiusKm, maxStores (1 to 3), allowAlternatives, rejectedAlternatives (list entry IDs), excludedRetailers, preferredRetailer, budgetId or budgetAmount, travelMode and its optional costPerKm or tripCost. It returns three strategy cards, a recommendation, estimate time, branch coverage, unresolved entries, and the assumptions used. A partial or transport-unknown plan omits grandTotal and exposes knownCost. Each priced line includes an observation reference.
- GET /api/v1/lists/{id}/optimization-budgets lists active budgets linked to the owned list and their remaining funds.
- POST /api/v1/shopping-plans saves the selected strategy, listId, name, fingerprint and assumptions with an Idempotency-Key UUID. The server recalculates first and rejects a stale preview with HTTP 409 instead of overwriting the estimate the user reviewed.
- GET /api/v1/shopping-plans with optional offset lists owned snapshots. GET /api/v1/shopping-plans/{id} opens one. POST /api/v1/shopping-plans/{id}/copy with a name and Idempotency-Key duplicates one. DELETE /api/v1/shopping-plans/{id} removes a snapshot.
- Flyway V22 adds immutable estimate and assumption snapshots using JSONB, including the quotation timestamp, list name and ownership. Deleting a shopping list preserves the historical plan. Recalculation uses the current list and prices and leaves the saved estimate intact.

### Verification, deployment and rollback

1. Apply V22 on a disposable PostGIS database or isolated Neon staging database. Run Maven verify with DISCOVERY_POSTGIS_TEST=true on the discovery_test loopback database and run frontend lint and build.
2. For the browser demonstration, apply backend/src/test/resources/optimizer-browser-fixture.sql only to discovery_test. Start the API on 18084, Vite on localhost:55178 and Chrome CDP on 59226. Run node frontend/scripts/optimizer-smoke.mjs from the repository root. The fixture contains synthetic branches and prices in Polokwane. The demo verifies a linked R800 budget, ten priced entries, two visits, three calculated strategies, saved history and an honest partial recalculation after adding an unpriced item.
3. Deploy the Render API first and confirm health and successful V22 migration. Deploy the Vercel frontend after the API is verified. Test an owned list with representative branch coverage before release.
4. Roll back the frontend first if needed. Preserve V22 and its saved plans. If the previous API requires Flyway validation against V22, rebuild it with the unchanged migration bundled. Keep the pre-release backup and test any restoration against a separate database first.

## Specials & Deals

The earlier schema stored branch offers, but there was no discovery or moderation screen. Start dates alone could leave scheduled offers unusable, and product comparison calculated multibuys separately from budget and basket pricing. Phase 7 adds branch and channel scopes, date and evidence states, and a shared PostgreSQL quantity quote. Flyway V23 extends the existing promotion table and adds an audit trail. V24 records import reports and failures. Historical observations and offers stay in place.

The specials page searches product, brand, package, category and retailer terms. It filters by date, location, branch, offer type and evidence. Its coverage report counts stored current and verified offers by retailer. The isolated seed contains 20 active retailer brands. The promotion schema supports additional directory entries without code changes. A retailer with zero offers has no qualifying records in the report and may still have branch or shelf-price coverage.

### Eligibility and savings

- The start and final expiry dates are inclusive in Africa/Johannesburg. Advertised, verified, unverified, upcoming and expired records are distinguishable. Pending, rejected and suspended records are excluded from public discovery and estimates.
- A branch offer applies only to its registered branch. A documented national scope expands only to active physical branches of that retailer. A regional offer requires a matching province. Online and unknown scopes can appear in general browsing as advertised terms and cannot discount an in-store estimate.
- The shared quote uses fresh, in-stock branch observations and exact products or approved compatible alternatives. Fixed reductions, percentage reductions rounded per package, full multibuy blocks and same-product buy-and-get-free quantities are evaluated independently. Remainders use the shelf price. The quote chooses one best pricing rule and never increases the requested quantity. Known expired or suspended dated promotions cannot leak back into the current quote through matching raw discount observations. Unstructured observations without a matching dated offer still expire under the observation freshness policy.
- Loyalty prices require explicit confirmation. The optimiser and budget previews accept the confirmed retailer IDs. Budget archives use the same selections when capturing their estimate. Shopping-list and price-comparison defaults use public eligibility. Coupon and mixed-product bundle terms remain visible, with no automatic discount where the system cannot verify the full redemption or combination rules.
- Savings require a verified, current shelf reference at the same branch for the same product and quantity. The offer details separate advertised terms, observed stock, shelf cost and the optional saving. The optimiser can run a separate shelf-only plan using the same trip assumptions. Compare the full trip totals only when both cover every entry and have known transport.

### API and moderation

- GET /api/v1/specials, GET /api/v1/specials/{id} and GET /api/v1/specials/coverage serve public discovery and evidence. Location uses lat, lon and radius. Other filters include retailer, category, brand, productId, branchId, kind, verification, loyalty, validOn, minPrice, maxPrice, sort, limit and offset. The detail endpoint accepts quantity and memberConfirmed.
- GET /api/v1/lists/{id}/specials uses quantities from the signed-in shopper's owned list and checks its exact catalogue entries. POST /api/v1/lists/{id}/optimize accepts confirmedLoyaltyRetailers and ignorePromotions. GET /api/v1/budgets/{id}/estimate and POST /api/v1/budgets/{id}/archive accept repeated loyaltyRetailer IDs.
- /api/v1/admin/specials provides review, creation and correction operations. POST /{id}/review approves, rejects or suspends with a revision check. GET /{id}/audit shows changes and the actor. GET /imports shows provider import runs. Corrections return an offer to review. Manual offers require the exact catalogue product, a registered anchor branch, dates and a source reference.

### Sources and operations

The existing ReefAPI adapter is limited to the first page of Pick n Pay promotions for one mapped branch per call. It uses an exact barcode or an approved retailer alias, queues dated offers for review and records unmatched titles in the import report. Repeated source identities are deduplicated in PostgreSQL. A provider failure leaves stored discovery available. Scheduling is disabled by default. To enable it, configure the server-only ReefAPI key and mapped store code, then set SPECIALS_REEF_IMPORT_ENABLED=true. The default schedule polls once daily at 05:25 South African time and can be configured with SPECIALS_REEF_IMPORT_CRON. Check account credits and the terms before enabling collection. [ReefAPI documentation](https://reefapi.com/docs) · [ReefAPI terms](https://reefapi.com/terms)

The existing LoyaltyHub integration remains a price-observation source. A membership price or an undated price observation does not establish a branch-specific promotion. Keep credentials server-side and check the provider's quotas, pricing, retention and attribution requirements before using a feed. CSV price imports continue to use the existing flow. Dated offer records can be entered through the admin review screen. [LoyaltyHub API and pricing](https://loyaltyhub.co.za/developers) · [LoyaltyHub API terms](https://loyaltyhub.co.za/developers/terms)

### Verification, deployment and rollback

1. Apply V23 and V24 against a disposable PostGIS or isolated Neon staging database. Run Maven verify with DISCOVERY_POSTGIS_TEST=true against discovery_test on loopback port 55436, plus frontend lint and build.
2. For the browser demo, seed backend/src/test/resources/specials-browser-fixture.sql on discovery_test only. Use API port 18085, Vite localhost:55179 and Chrome CDP port 59227. Run node frontend/scripts/specials-smoke.mjs from the repository root. The fixture advertises a Polokwane multibuy at R42 for two cartons against R48 at shelf. One carton costs R24, and an unconfirmed loyalty offer is excluded.
3. Back up the database and deploy the Render API first with scheduling disabled. Confirm both migrations, API health, local scope and a qualifying estimate. Deploy the Vercel frontend after staging verification. Enable a provider schedule only after reviewing its configured branch, credentials and quota.
4. For rollback, disable the import schedule and roll back the frontend. Keep stored offers, audit rows and import history. If the previous API requires Flyway validation, bundle the unchanged migrations in the rollback build. To reverse shared SQL pricing behaviour, add a forward migration restoring the previous function and view definitions. Test that change in staging before release.
