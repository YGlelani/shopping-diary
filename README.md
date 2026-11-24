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
