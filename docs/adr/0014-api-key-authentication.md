# ADR-0014: API key authentication

- Status: Accepted
- Date: 2026-11-05

## Context

ADR-0013 shipped an HTTP API with no authentication. That is fine on a laptop and unacceptable on a public server: anyone who finds the port could start workflows, poll tasks (and read their payloads, which are customer data in a real system) or complete tasks with forged results.

## Decision

**A static bearer API key, checked in a servlet filter on `/api/*`.** Callers send `Authorization: Bearer <key>`. A missing or wrong key gets `401` with `WWW-Authenticate: Bearer` and one fixed JSON body, so the answer never says whether a key was absent or merely wrong. `/actuator/health` stays open, because Docker and load balancers probe it without credentials.

**Several keys at once** (`REVENANT_API_KEYS`, comma separated). Rotation is: add the new key, move clients to it, remove the old one. No downtime.

**Keys are compared by hash in constant time.** Each configured key is stored as a SHA-256 digest and compared with `MessageDigest.isEqual`. All keys are checked on every request with no early exit. A timing side channel (measuring how long a refusal takes to learn how many leading characters were correct) therefore has nothing to measure. Hashing first also makes both sides the same length, so the length of a real key is not revealed either. Keys must be at least 16 characters; shorter ones stop the server at startup.

**Off by default, loud about it.** With no keys configured the API is open and the server logs a warning. `REVENANT_SECURITY_REQUIRED=true` turns a missing key into a startup failure, so a deployment cannot go live open by accident. Every deployment reachable from a network must set it.

**The SDK takes the key as a constructor argument** (`new RevenantClient(url, apiKey)`); the demo worker reads `REVENANT_API_KEY`.

## Why not Spring Security, OAuth or mTLS

Spring Security would add a large dependency and a second configuration model for one rule: "this header must match". A static key is the simplest thing that closes the hole, and machine-to-machine workflow engines commonly start there. Per-key permissions, OAuth/OIDC and mTLS are the natural next steps and fit the same filter position.

## Consequences and limits

- **Use HTTPS.** A bearer key sent over plain HTTP can be read on the network. The server speaks plain HTTP; put a TLS-terminating reverse proxy in front of it (Phase 6b).
- **One level of trust.** Any valid key can do everything, including reading every workflow. There are no per-client scopes and no audit of which key did what.
- **No rate limiting.** Brute-forcing a 16+ character random key is infeasible, but a flood of bad requests still costs CPU; limit at the proxy.
- **Keys live in environment variables**, visible to anyone who can read the process environment. Use the platform's secret store.
