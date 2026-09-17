# Lago billing on Coolify

`lago.coolify.yml` is a standalone Coolify Service definition, adapted from
[Lago v1.53.0](https://github.com/getlago/lago/blob/v1.53.0/docker-compose.yml).
Create a separate service in each project. Do not merge it into the LiveContext
Compose network or reuse volumes between installations.

## Deployment contract

- Assign independent HTTPS domains to `front` (port 80) and `api` (port 3000).
  Set `LAGO_FRONT_URL` and `LAGO_API_URL` to their public HTTPS URLs.
- Store separate, random secrets in each service's Coolify environment:
  `POSTGRES_PASSWORD`, `SECRET_KEY_BASE`, `LAGO_RSA_PRIVATE_KEY`,
  `LAGO_ENCRYPTION_PRIMARY_KEY`, `LAGO_ENCRYPTION_DETERMINISTIC_KEY`,
  `LAGO_ENCRYPTION_KEY_DERIVATION_SALT`, `LAGO_ORG_USER_PASSWORD`, and
  `LAGO_ORG_API_KEY`. The RSA key is a base64-encoded 2048-bit private key.
- Set `LAGO_ORG_NAME` and `LAGO_ORG_USER_EMAIL` for owner provisioning.
  The migration service seeds the owner and organization idempotently.
- Public signup, Segment analytics, and the Sidekiq web UI are disabled.
  No database ports are published. Database, Redis, and document storage
  use service-scoped persistent volumes.
- The stack includes API, frontend, migration, worker, clock, PostgreSQL,
  Redis, and Gotenberg for PDFs. The migration container exits successfully;
  this is expected, not an unhealthy persistent service.

## Before real billing

Installation does not configure a payment provider, SMTP delivery, prices,
customers, subscriptions, or LiveContext usage metering. No live charges should
be enabled until those choices are explicitly authorized and tested.
Configure backup retention and test restoration before keeping real billing data.
Never reuse a production payment-provider credential in a duplicate intended
for testing.
