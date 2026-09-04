# Stateful real-backend journeys

This opt-in Playwright suite drives the browser through the live gateway, services, and databases. Unlike `e2e-real`, it creates persistent business data.

Run headless:

```bash
bun run test:e2e:journey
```

Watch the workflow:

```bash
bun run test:e2e:journey:demo
```

Created business records use a unique note such as `Mock Test 2026090414301501`. The suite reuses these accounts instead of creating more users on every run:

- `Mock Test 01 - Sales Manager`
- `Mock Test 02 - Director`
- `Mock Test 03 - Operations Officer`

The price-list journey creates a list and version, saves a price, submits it, approves it as the sales manager and director, and checks notifications plus audit history. The volume journey creates an unused monthly period covered by an active contract, creates a labelled volume, locks the period, and verifies that an ordinary operations user can no longer edit it.

Run this only against a local or disposable environment. The application currently has no delete/reopen operations for these records, so the suite does not clean them up.
