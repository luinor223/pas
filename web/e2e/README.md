# Frontend browser integration tests

These Playwright tests run the production frontend build in a real browser while mocking API responses. They verify user flows, routing, accessibility-facing controls, request payloads, permission gating, and refetch behavior.

They do not verify compatibility with running backend services. API/service integration remains covered by backend integration tests and should eventually include a small real-stack browser smoke test.

Run the suite with `bun run test:e2e`. Use `bun run test:e2e:demo -- approvals.spec.ts` to watch one feature at a slower pace.
