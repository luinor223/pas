# PAS - Business Document Management System

A centralized system for managing the **lifecycle of business documents** at Company ABC, a logistics provider offering port operations, transportation, warehousing, and cargo handling services.

## Scope

- Customer, contract, and contract-addendum management
- Price list management (multiple versions, time-based validity)
- Recording actual operational volume per period
- Creating and reconciling payment statements
- Configurable approval workflows (no hard-coded logic)
- E-signature integration (asynchronous)
- Notifications, logging, and audit trail

> Coursework / capstone project. Currently in development.

## Configuration

Set `PAGINATION_CURSOR_SECRET` to the same private value of at least 32 characters on every
contract-service replica. It signs pagination cursors; changing it invalidates cursors that are
currently in use. Docker Compose supplies a local-development value that must be overridden in
shared environments.
