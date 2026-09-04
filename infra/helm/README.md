# PAS Helm charts

One chart per deployable, each installed and versioned independently:

- `services/<svc>/helm/` — the 10 backend services
- `web/helm/` — the React frontend (nginx)
- `infra/helm/pas-common/` — a **library chart** holding the shared templates
  (Deployment, Service, Secret, PVC, Traefik IngressRoute + Middlewares). It
  deploys nothing on its own; every service chart depends on it, so the
  per-service charts stay thin.

## Prerequisites (the charts assume external infra)

The charts deploy only the application workloads. Before installing, the
cluster must be able to reach:

- **PostgreSQL** with the per-service databases and users already created
  (`pas_identity`, `pas_workflow`, …) — each service runs Flyway on startup.
- **Kafka** with the topics created (`pas.events`, `pas.audit`, and their DLTs).
- **Redis**.
- **Traefik** installed as the ingress controller, with the `jwt` plugin
  available (the `jwt-auth` Middleware validates the RS256 `pas_at` cookie and
  injects the `X-User-*` headers).
- A **Secret** named `pas-jwt-private` holding `jwt-private.pem` (identity signs
  tokens with it), and the RS256 **public key** passed as `jwt.publicKey`.

Point the charts at those endpoints with a shared values file, e.g.
`values-staging.yaml`, applied to every install.

## Install

```sh
# Vendor the library chart into each chart (run once, or after editing it):
helm dependency build services/identity/helm

# Install one service:
helm install identity services/identity/helm \
  -f values-staging.yaml \
  --set jwt.publicKey="$(cat jwt-public.pem)"
```

`values-staging.yaml` carries the shared bits (`postgres`, `redis`, `kafka`,
`jwt`, image tag) so every chart gets the same infra config.

## Layout of a service chart

`values.yaml` describes the one service; the `templates/` are one-line includes
of the library templates. To change behaviour for all services, edit
`infra/helm/pas-common/`; to change one service, edit its `values.yaml`.
