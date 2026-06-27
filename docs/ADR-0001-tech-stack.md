# ADR-0001 — Technology stack for the migration platform

**Status:** Accepted · **Date:** 2026-06-26 · **Relates to:** issue #18 (target architecture), #19 (metadata store), #21 (backend)
**Decision owners:** Faiz Akram

## Context

We are turning a CLI/curl-driven Debezium CDC setup (MS SQL Server → PostgreSQL) into a
production-grade, browser-operated migration platform. Hard requirements: **real-time CDC**
and **horizontal scale**, plus a web UI, multi-project management, security, monitoring,
validation, scheduling, and auth (see GitHub Project #22).

A key architectural fact drove the decision: **real-time change capture lives in the data
plane (Debezium + Kafka + Kafka Connect), not in the control plane.** CDC throughput/latency
is governed by Kafka partitions, brokers, and Connect workers — never by the language of the
management service. The control plane orchestrates and observes; it is not in the event path.

## Decision

| Layer | Choice |
|---|---|
| **Data plane (CDC)** | Debezium + Apache Kafka (KRaft, multi-broker) + Kafka Connect (distributed) — kept and hardened |
| **Control-plane backend** | **Java 21 + Spring Boot 3** |
| **Metadata store** | PostgreSQL + Flyway migrations (JSONB for flexible config) |
| **Frontend** | React 18 + TypeScript + Vite + Ant Design + TanStack Query + WebSocket |
| **Observability** | Micrometer → Prometheus + Grafana; JMX exporter for Connect/Kafka |
| **Deployment** | Docker Compose (dev) → Kubernetes/Helm (prod) |

### Why Java 21 + Spring Boot for the control plane (not Go)

This was evaluated explicitly. Conclusion: **neither is universally superior; Java fits this
orchestrator tighter** because everything it manages is JVM.

- The CDC stack (Debezium, Kafka Connect) and the existing custom SMTs are **Java**. One runtime
  across data plane + control plane avoids language fragmentation and allows embedding the
  Debezium Engine in-process if a deployment ever needs Kafka-less CDC.
- Spring's integrated modules directly satisfy backlog epics with mature code rather than
  hand-wired libraries: **Spring Security (OIDC/JWT)** → Auth epic, **Quartz** → Scheduling epic,
  **Micrometer/Actuator** → Monitoring epic, **Spring Data JPA + Flyway** → metadata store.
- Control-plane language has **no effect on CDC performance** — that path is Kafka/Debezium.
  This service handles operator actions (dozens/min), where both Java and Go are far past
  "fast enough".
- Trade-off accepted: heavier footprint/slower cold start than Go. Mitigable later with GraalVM
  native image if needed.

Go would have been a reasonable lean alternative (single binary, low footprint, existing Go
expertise in this repo) and would only become clearly superior if we chose to **replace
Debezium with a self-owned embedded CDC engine** — which we explicitly reject, since Debezium
already solves MS SQL change capture well.

## Consequences

- New code lives under `platform/` as a separate module; the legacy Go MySQL→MySQL tool and the
  existing `debezium-setup/` are untouched by this work (their disposition tracked in #20/#18).
- The backend exposes a versioned REST API (issue #24 OpenAPI) that the UI and automation use;
  all Kafka Connect interaction goes through it (issue #45 — no direct unauthenticated :8083).
- Secrets are encrypted at rest from day one (AES-GCM) and never returned by the API, seeding the
  Security epic (#43) rather than reintroducing the plaintext-credential problem.

## Build/runtime notes

- Compile target: Java **21** (`--release 21`) for Spring Boot 3.3 compatibility, even when a
  newer JDK is installed locally. Production containers pin a JDK 21 runtime.
