# Real-stack browser smoke tests

These tests do not mock API calls. They sign in through the gateway and verify that the frontend can consume live responses from workflow, pricing, operations, audit, and notification services.

Playwright signs in once, stores the authenticated browser state under the ignored `test-results/` directory, and gives every feature test an isolated browser context initialized from that state. The tests do not log out between cases.

Start the local stack from the repository root:

```bash
make keys # only when infra/keys/jwt-private.pem is missing
make up
make ps
```

Then run from `web/`:

```bash
bun run test:e2e:real
```

To watch the tests run:

```bash
bun run test:e2e:real:demo
```

Defaults are `http://127.0.0.1:18080` and the seeded `admin` account. Override them without editing test code:

```bash
PAS_E2E_BASE_URL=http://127.0.0.1:18080 \
PAS_E2E_USERNAME=admin \
PAS_E2E_PASSWORD=admin12345 \
bun run test:e2e:real
```

The suite is deliberately read-only so it remains repeatable against a developer's persistent Compose volume.

For complete workflows that create clearly labelled records, use `bun run test:e2e:journey` or watch them with `bun run test:e2e:journey:demo`. Read `e2e-journey/README.md` first because those records persist.
