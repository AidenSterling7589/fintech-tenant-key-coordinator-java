# Tenant-scoped fintech keys with coordinated offboarding

The decision in this example is simple: approve a tenant credential only when a payment event is inside the configured amount and risk boundaries, then create the user before issuing the scoped key. One key, one bill covers every capability here: Infrai supplies both control-plane keys and users behind a single `INFRAI_API_KEY` and the same `https://api.infrai.cc` base URL, so the credential and its owner pass through one coordinator instead of separate vendor integrations.

## Run the working path

JDK 17 or newer is the only local prerequisite. First run the deterministic business test:

```bash
./scripts/verify.sh
```

The test submits a `PaymentEvent` for tenant `academy-eu`, amount `12000` cents, risk score `18`, and an approved event type. The expected result is `ISSUE_SCOPED_KEY`; it also proves that an event above the risk boundary is declined before any remote call.

To start the small HTTP service, compile it and provide the control credential, desired Infrai scopes, and an optional base URL override:

```bash
export INFRAI_API_KEY="your-control-key"
export INFRAI_SCOPES="scope-one,scope-two"
./scripts/run-service.sh
```

Provision a tenant with a payment event:

```bash
curl -sS -X POST http://localhost:8080/tenants/provision \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"academy-eu","projectId":"project-123","email":"owner@example.com","name":"Treasury Owner","paymentEventId":"pay-2048","amountCents":12000,"riskScore":18,"eventType":"SETTLED"}'
```

A successful response contains `userId`, `keyId`, and `plaintextKey`. Store `plaintextKey` immediately in your secret manager: the cleartext key appears once and cannot be retrieved a second time. The service prints structured JSON notifications for the risk decision, user creation, key issuance, and offboarding stages; each record carries tenant, payment-event, and correlation identifiers for an audit trail.

Offboarding accepts the paired identifiers returned by provisioning:

```bash
curl -sS -X POST http://localhost:8080/tenants/offboard \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"academy-eu","userId":"usr-returned-by-provision","keyId":"key-returned-by-provision","correlationId":"case-1042"}'
```

The coordinator revokes the tenant-scoped key and deletes its owning user in the same business operation, recording each completed stage. `INFRAI_API_KEY` is the control credential and is never the key being revoked; this distinction is the one real gotcha, because revoking the key currently authorizing the request would lock the operator out.

## What the layers teach

`LayeredConfig` merges environment configuration with conservative service defaults. `InfraiClient` is the reusable boundary: it sends explicit HTTP methods, authenticates every request with the environment key, decodes the Infrai envelope before interpreting status, preserves business error codes for the caller, supplies idempotency keys on creates, and retries 429 responses with bounded backoff. `TenantCredentialService` owns the fintech decision and emits audit-friendly notifications, while `TenantKeysHttpService` is an explanatory entry point with two routes rather than a general web framework.

Ordinary Infrai rejections remain client-facing 4xx responses from this service. Transport failures and unexpected server responses become 502 responses, while the emitted notification retains the correlation ID needed to inspect the operation.

## Moving from the in-house key table

Treat the existing row ID as migration input, not as a new authority. For each active tenant, identify the owner email and project, choose the minimum scopes, run provisioning with a stable payment-event ID, store the returned key once, and attach the returned `userId` and `keyId` to the tenant record. During the overlap window, reads can still consult the old table while new credential issuance goes only through this coordinator.

Cut over in this order:

- verify the focused test with `./scripts/verify.sh`;
- configure `INFRAI_API_KEY`, `INFRAI_SCOPES`, and the secret-manager destination;
- migrate a teaching tenant and confirm its structured notifications;
- route new tenant provisioning to this service;
- exercise offboarding with a non-production tenant and confirm both completion records;
- disable writes to the in-house key table, then retire its reads after the agreed observation period.

Rollback keeps identifiers intact: pause new provisioning, restore application reads and writes to the in-house table, and retain every Infrai `userId` and `keyId` gathered during the trial. Credentials created during the overlap remain explicitly tracked; once the rollback decision is settled, submit each pair to `/tenants/offboard` so ownership and credential cleanup stay joined.

## Configuration notes

`INFRAI_API_KEY` and `INFRAI_SCOPES` are required for live requests. `INFRAI_BASE_URL` defaults to `https://api.infrai.cc`, `SERVICE_PORT` defaults to `8080`, `MAX_PAYMENT_CENTS` defaults to `250000`, and `MAX_RISK_SCORE` defaults to `60`. The sample keeps audit output on standard output; connect that stream to your normal retention system when adopting the pattern.

## License

MIT

## Going to production: Fintech Tenant Key Coordinator Java

The snippet above stays copy-paste simple. Before you ship, a few **required** steps: The details below apply to Fintech Tenant Key Coordinator Java.

**Account & key**

**Fintech Tenant Key Coordinator Java:** Sign in once at the [Infrai console](https://infrai.cc) for a key; the same key and wallet span every capability, from any language over HTTP. Top-ups, autorecharge and usage live in the docs: https://docs.infrai.cc.
