# Security

This is a prototype intended to run on a developer machine. The controls below are real and tested,
but the deployment assumptions are not production assumptions, and the gap is stated rather than
glossed over.

## The demo keys are not secrets

`application.yml` ships two API keys, as SHA-256 hashes, whose plaintext is published in the README:

| Key | Scopes |
|---|---|
| `demo-operator-key` | READ, WRITE, CONTROL |
| `demo-reviewer-key` | READ |

They exist so a reviewer can run the system without a setup step. **Anything reachable by anyone
else needs different keys.** Generate a hash and replace the entries:

```bash
printf '%s' 'your-key-here' | sha256sum
```

Then set them through the environment rather than the file, and keep `application-local.yml` out of
version control — `.gitignore` already excludes it.

## What is enforced

| Control | Where | Test |
|---|---|---|
| Scheme allowlist: only `http` and `https` | `UrlValidator` | `UrlValidatorTest` |
| Hosts resolving to loopback / link-local / RFC1918 / ULA / CGNAT are refused at create time | `UrlValidator.isNonPublic` | `UrlValidatorTest` |
| Internal names (`localhost`, `.internal`, `.local`) refused without even resolving them | `UrlValidator` | `UrlValidatorTest` |
| URLs carrying credentials refused | `UrlValidator` | `UrlValidatorTest` |
| Redirect target read from storage only, never from the request | `RedirectController` | `LinksApiContractTest` |
| API keys stored as SHA-256, compared with `MessageDigest.isEqual`, all candidates compared | `ApiKeyRegistry` | `LinksApiContractTest` |
| Read and write scopes separated | `ApiKeyFilter` | `LinksApiContractTest` |
| Rate limits per key (create) and per address (redirect) | `RateLimitFilter` | — |
| `Referrer-Policy: no-referrer`, `X-Content-Type-Options`, frame denial | `SecurityHeadersFilter` | `LinksApiContractTest` |
| Secrets masked in logs | `logback-spring.xml` | — |
| No PII in click rows | `ClickEvent`, `V2__clicks.sql` | `ShortLinkPersistenceTest` |
| Audit rows append-only and hash-chained | `AuditSink`, `AuditHasher` | `AuditHasherTest` |
| Workflow branch expressions cannot call methods or construct types | `BranchEvaluator` | `WorkflowEngineTest` |
| H2 console disabled; Actuator limited to health, info, prometheus | `application.yml` | — |

## Why the redirect endpoint is the interesting one

It is the only route that serves anonymous traffic, and its whole job is to send a caller somewhere
else. Two things keep it from becoming a liability:

1. **Validation happens at create time, not redirect time.** A host that resolves to a private
   address never gets a short code, so there is nothing to follow later.
2. **The `Location` header is built from the stored canonical target.** Nothing on the incoming
   request can influence where a redirect goes.

DNS can change between creation and use, so a name that resolved publicly could later point
somewhere private. That is a real residual risk and it is listed in the limitations rather than
quietly ignored.

## What is not covered

- No dependency vulnerability scanner runs. The security agent reports this as a gap, the policy
  set raises an exception request, and a human has to decide. It does not report a clean scan.
- No secret scanning, no SBOM, no signed artifacts.
- No user accounts, no multi-tenancy, no per-tenant isolation.
- Rate-limit buckets are process-local, so they do not survive a restart or span instances.
- The audit hash chain is tamper-*evident*, not tamper-*proof*: someone with database access could
  recompute the whole chain. Detecting that needs an external anchor, which is out of scope here.

## Reporting

This is an assessment artifact and has no security contact. Raise anything you find with the repository owner.
