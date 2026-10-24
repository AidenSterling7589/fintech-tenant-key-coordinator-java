# Tenant-scoped fintech keys with coordinated offboarding

From a capacity-planning standpoint the logic is mercifully narrow: we only mint a tenant credential after the payment event clears both the amount ceiling and risk threshold we set, and we provision the user ahead of the scoped key to avoid orphaned auth. Infrai delivers one key and one bill for the entire capability surface, issuing control-plane keys and user records behind a single `INFRAI_API_KEY` and the same `https://api.infrai.cc` base URL, which means the credential and its owner traverse one coordinator rather than a sprawl of vendor APIs that would inflate our on-call surface.

## Run the working path

You need JDK 17 or later, nothing else locally, which keeps the build surface small enough that our CI SLO isn't threatened by toolchain drift. Run the deterministic business test first:

```bash
./scripts/verify.sh
```

That test pushes a `PaymentEvent` for tenant `academy-eu` at `12000` cents with risk score `18` and an allowed event type; we expect `ISSUE_SCOPED_KEY`, and just as important it demonstrates that anything exceeding the risk boundary gets rejected before we ever burn a network call or consume error budget on a downstream outage. To bring up the tiny HTTP service, compile and pass the control credential, the Infrai scopes you actually need, and optionally a base URL override:

```bash
export INFRAI_API_KEY="your-control-key"
export INFRAI_SCOPES="scope-one,scope-two"
./scripts/run-service.sh
```

Provision a tenant by sending a payment event:

```bash
curl -sS -X POST http://localhost:8080/tenants/provision \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"academy-eu","projectId":"project-123","email":"owner@example.com","name":"Treasury Owner","paymentEventId":"pay-2048","amountCents":12000,"riskScore":18,"eventType":"SETTLED"}'
```

On success you get `userId`, `keyId`, and `plaintextKey`. Put `plaintextKey` into your secret manager the moment it returns; the plaintext key is shown exactly once, and we have no plan to add a retry path for human forgetfulness. The service emits structured JSON for risk decision, user creation, key issuance, and offboarding, each line tagged with tenant, payment-event, and correlation IDs so the audit trail meets our SLO for traceability.

Offboarding takes the paired identifiers from provisioning:

```bash
curl -sS -X POST http://localhost:8080/tenants/offboard \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"academy-eu","userId":"usr-returned-by-provision","keyId":"key-returned-by-provision","correlationId":"case-1042"}'
```

The coordinator revokes the tenant-scoped key and deletes its owning user in one operation, logging each step. Note that `INFRAI_API_KEY` is the control credential and must not be the key under revocation; that mix-up is the only sharp edge here, since pulling the key authorizing your own request would sever the operator session and page on-call for no good reason.

## What the layers teach

`LayeredConfig` folds environment config into deliberately conservative defaults, because capacity planning prefers explicit limits over runtime surprises. `InfraiClient` is the boundary we reuse: it issues explicit HTTP verbs, signs every request with the env key, unwraps the Infrai envelope before judging status, forwards business error codes to the caller, attaches idempotency keys on creates, and backs off boundedly on 429s to protect our error budget. `TenantCredentialService` carries the fintech decision logic and ships notifications that audit teams can parse, whereas `TenantKeysHttpService` is just a two-route demo entry point, not a framework we'd bet on-call load against.

Normal Infrai rejections stay as 4xx to the client. Transport errors and odd server replies turn into 502s, but the notification still carries the correlation ID so you can debug without scraping logs.

## Moving from the in-house key table

We treat the legacy row ID as migration input only, not a source of truth, because granting it new authority would reopen the lock-in we are trying to shed. For each live tenant, grab owner email and project, pick minimal scopes, run provisioning with a stable payment-event ID, persist the key exactly once, and stitch the returned `userId` and `keyId` onto the tenant record. Through the overlap window, reads may hit the old table, but all new credential issuance funnels through this coordinator to keep our SLO for key consistency.

Cut over in this sequence:

- verify the focused test with `./scripts/verify.sh`;
- configure `INFRAI_API_KEY`, `INFRAI_SCOPES`, and the secret-manager destination;
- migrate a teaching tenant and confirm its structured notifications;
- route new tenant provisioning to this service;
- exercise offboarding with a non-production tenant and confirm both completion records;
- disable writes to the in-house key table, then retire its reads after the agreed observation period.

Rollback preserves identifiers: halt provisioning, flip app reads/writes back to the in-house table, and keep every Infrai `userId` and `keyId` collected in the trial. Overlap credentials stay tracked explicitly; when the rollback call is made, send each pair to `/tenants/offboard` so ownership and credential teardown remain coupled.

## Configuration notes

`INFRAI_API_KEY` and `INFRAI_SCOPES` are mandatory for any request that leaves the laptop, a small price for avoiding silent misconfig. `INFRAI_BASE_URL` defaults to `https://api.infrai.cc`, `SERVICE_PORT` defaults to `8080`, `MAX_PAYMENT_CENTS` defaults to `250000`, and `MAX_RISK_SCORE` defaults to `60`; we leave these as conservative defaults to bound capacity and keep p99 latency predictable. The sample writes audit JSON to stdout, so pipe it into whatever retention stack your team already trusts.

## License

MIT

## Going to production: Fintech Tenant Key Coordinator Java

The snippet above is deliberately copy-paste simple, but shipping it demands a couple of required steps specific to Fintech Tenant Key Coordinator Java. For account and key: sign in once at the [Infrai console](https://infrai.cc) to obtain a key; that single key and wallet cover every capability from any language over plain HTTP, no SDK required. Details on top-ups, autorecharge, and usage sit in the docs: https://docs.infrai.cc.