# Requirement coverage

The real browser suite verifies live gateway authentication and frontend compatibility with the five wired backend services. Test names refer to the requirement rules where the browser is an appropriate enforcement boundary.

| Area | Covered in real browser suite | Covered elsewhere |
| --- | --- | --- |
| Approval inbox | Three independent queues, server filters/search, URL and Back behavior, live response shape | APR-01/02 assignee and step enforcement, APR-03 comment constraint, approve race and double-submit require workflow-service integration tests |
| Price lists | Live paging/filtering, PRC-01 scope gating, eligible-contract lookup, keyboard selection | PRC-02/03 overlap validation, PRC-04 superseding, PRC-05 immutability and workflow transitions require pricing/workflow integration tests |
| Volume records | Live periods/catalog/records composition, URL-backed tabs, server filtering, contract lookup, required inputs | Locked-period authorization, post-lock audit, contract/period validity and numeric precision require operations-service integration tests |
| Audit | Live page metadata, server-side filters, invalid range gating, refresh, readable details | Outbox durability, immutable snapshots and Kafka dedup require producer/audit integration tests |
| Notifications | Recipient inbox shape/counts, category/unread filtering, refresh and read-all | Event fan-out, recipient isolation, dedup, malformed-event DLT behavior and APR-07 resilience require notification/Kafka integration tests |

The mocked `e2e/` suite remains the deterministic place for frontend error rendering and mutation payload assertions. The real suite intentionally does not claim to prove database races, Kafka retry guarantees, or cross-service transaction invariants from browser pixels.
