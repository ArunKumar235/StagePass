# StagePass Microservices Architecture

Welcome to the technical architecture guide for **StagePass**, a premium, high-performance event ticketing and booking platform built on a distributed microservices paradigm.

This document details the system design, API surface, communication patterns, persistence schemas, resilience strategies, and infrastructure configurations of the StagePass ecosystem.

---

## 1. System Topology & Component Diagram

StagePass uses a decoupled, event-driven architecture to ensure high availability, horizontal scalability, and strict database isolation.

The system is organized in four horizontal tiers:

**Client** → **Edge layer** (API Gateway + Eureka) → **Services** (User, Event, Booking, Payment, Notification) → **Infrastructure** (Kafka, Redis, Zipkin, Mailpit, five isolated Postgres databases)

![StagePass System Topology](./stagepass_architecture.png)

```
                    ┌─────────────────────────────┐
                    │  Client (Postman/Browser)   │
                    └───────────────┬─────────────┘
                                    │ :8080
         ┌──────────────────────────▼────────────────────────────────────────────────┐
         │                  API GATEWAY                                              │
         │          (Security, Routing & Rate Limits)                                │
         └────┬─────────────┬────────────────┬──────────────────┬───────────────┬────┘
              │             │                │                  │               │ lb://
         ┌────▼─────┐ ┌─────▼───────┐ ┌──────▼─────────┐ ┌──────▼────────┐ ┌────▼────────┐
         │  User    │ │Event Service│ │Booking Service │ │Payment Service│ │Notification │
         │ Service  │ │             │ │                │ │               │ │             |
         │  :8081   │ │:8082        │ │ :8083          │ │:8084          │ │  :8085      │
         └───┬──────┘ └───┬─────────┘ └───┬────────────┘ └───┬───────────┘ └────┬────────┘
             │            │               │                  │                  │
         ┌───▼───┐   ┌───▼──┐          ┌──▼──┐            ┌──▼──┐           ┌───▼──┐
         │db-user│   │db-evt│          │db-bk│            │db-py│           │db-nt │
         └───────┘   └──────┘          └─────┘            └─────┘           └──────┘
             │           │                │                  │                  │
             └───────────┼────────────────┼──────────────────┼──────────────────┘
                         ▼                ▼                  ▼
                        ┌────────────────────┐ ┌─────────────┐
                        │ Apache Kafka Broker│ │ Redis Cache │
                        │   (Event-Driven)   │ │   & Locks   │
                        └────────────────────┘ └─────────────┘
```

---

## 2. Service Port Reference

| Service | Container Name | Port |
|---|---|---|
| API Gateway | `api-gateway` | `8080` |
| Eureka Discovery Server | `eureka-server` | `8761` |
| User Service | `user-service` | `8081` |
| Event Service | `event-service` | `8082` |
| Booking Service | `booking-service` | `8083` |
| Payment Service | `payment-service` | `8084` |
| Notification Service | `notification-service` | `8085` |
| Zipkin | `zipkin` | `9411` |
| Mailpit UI | `mailpit` | `8025` |

---

## 3. Core Components & Responsibilities

### ── EDGE LAYER ──

#### 1. API Gateway (`api-gateway` · Port 8080)

The sole entry point for all external traffic. Built on Spring Cloud Gateway with a Netty runtime, it processes every request through a global filter chain before routing it downstream.

**Filter Chain (in order)**

| Filter | Responsibility |
|---|---|
| `JwtAuthenticationFilter` | Validates Bearer token; extracts and forwards `X-User-Id` and `X-Role` headers to downstream services |
| `RequestHeaderEnrichmentFilter` | Appends `X-Trace-Id` and `X-Gateway-Timestamp` to every forwarded request |
| `LoggingFilter` | Logs request details (no auth/sensitive data) and response status + duration |
| `RedisRateLimiter` | Token bucket algorithm — global: `replenishRate=20, burstCapacity=40`; booking routes: `replenishRate=3, burstCapacity=5` |

**Other Responsibilities**

- **Dynamic Routing** — Resolves physical container IPs dynamically using `lb://` URIs registered in the Discovery Server.
- **CORS Management** — Enforces global CORS policy for the frontend tier.

#### 2. Discovery Registry (`eureka-server` · Port 8761)

The service registry powered by Spring Cloud Netflix Eureka.

- All StagePass services self-register with their internal Docker bridge network IP on startup.
- Enables resilient client-side load balancing via Spring Cloud LoadBalancer and OpenFeign.

---

### ── CORE SERVICES TIER ──

#### 3. User Service (`user-service` · Port 8081)

Manages customer profiles, registration, and credentials.

- Supports dual-channel authentication: traditional password hashing (BCrypt) and social OAuth2 logins (Google & GitHub).
- Issues HMAC-SHA256 signed JWT tokens containing user identity, roles, and session metadata consumed by the API Gateway.

**API Endpoints**

| Route | Method | Description |
|---|---|---|
| `/auth/` | GET | Serves the login/register UI page |
| `/auth/register` | POST | Registers a new user; returns `userId` |
| `/auth/login` | POST | Authenticates user; returns `accessToken` in body and `refreshToken` as an HTTP-only cookie |
| `/auth/logout` | POST | Clears the refresh token cookie |
| `/users/me` | GET | Fetches authenticated user's profile (resolved via `X-User-Id` header) |
| `/users/me` | PUT | Updates the authenticated user's username |

**Token Strategy**

Both an access token and a refresh token are generated on login. The access token is short-lived and returned in the response body for use as a `Bearer` token. The refresh token is long-lived and delivered as an HTTP-only cookie. On `POST /auth/refresh`, the old refresh token is validated, immediately invalidated, and a new pair is issued (rotation) to prevent replay attacks.

#### 4. Event Service (`event-service` · Port 8082)

The catalog engine for shows, venues, performers, and ticket tiers.

- Caches query results in **Redis** via Spring Cache (`stagepass:{key}`, JSON serialized) to minimize database load during peak on-sale periods.
- Applies optimistic locking on seat inventory definitions to prevent overselling at the data layer.

**API Endpoints**

| Route | Method | Description |
|---|---|---|
| `/venues/` | GET | Returns all venues |
| `/venues/` | POST | Creates a new venue record |
| `/venues/{venueId}` | GET | Returns a single venue's details |
| `/venues/{venueId}` | PUT | Updates a venue |
| `/events/` | GET | Returns all events |
| `/events/` | POST | Creates a new event in `DRAFT` state |
| `/events/{eventId}` | GET | Returns a single event by ID |
| `/events/{eventId}` | PUT | Updates event details |
| `/events/{eventId}` | DELETE | Marks event as `CANCELLED` |
| `/events/{eventId}/publish` | PATCH | Publishes event; requires `X-Role=ORGANISER` |
| `/events/{eventId}/seats/` | GET | Returns the full seat map for an event |
| `/events/{eventId}/seats/section/{sectionId}` | GET | Returns seat map for a specific section |
| `/events/{eventId}/seats/validate` | GET | Validates seat availability |

**Redis Cache TTLs**

| Cache Key | TTL |
|---|---|
| `venue-list` | 1 hour |
| `venue-detail` | 1 hour |
| `event-list` | 5 minutes |
| `event-detail` | 10 minutes |
| `seat-map` | 30 seconds |
| `available-count` | 15 seconds |

#### 5. Booking Service (`booking-service` · Port 8083)

Orchestrates seat reservation and ticket checkout pipelines using a 6-step SAGA pattern.

- Uses **Redis** (Lettuce) to manage transient seat locks with automatic TTL expiration, releasing seats held by abandoned checkouts without manual intervention.
- Calls `payment-service` synchronously via **OpenFeign**; a **Resilience4j** circuit breaker triggers a graceful fallback if the downstream payment system times out or becomes unavailable.
- Publishes and consumes Kafka events to coordinate state transitions with the Payment and Notification services.

**API Endpoints**

| Route | Method | Description |
|---|---|---|
| `/bookings/` | POST | Creates a new booking; executes the 6-step SAGA |
| `/bookings/{bookingId}` | GET | Returns a single booking record |
| `/bookings/{bookingId}` | DELETE | Cancels a booking |
| `/bookings/my` | GET | Returns all bookings for the authenticated user |
| `/bookings/admin/event/{eventId}` | GET | Returns all bookings for an event; requires `X-Role=ADMIN` |

#### 6. Payment Service (`payment-service` · Port 8084)

Interfaces securely with the external Razorpay payment gateway.

- Implements fully idempotent payment processing using Redis-backed key stores to guarantee no customer is ever charged or refunded twice, even under retries.
- Verifies Razorpay webhook HMAC signatures to authenticate all asynchronous payment-gateway callbacks before acting on them.

**API Endpoints**

| Route | Method | Description |
|---|---|---|
| `/payments/pay` | GET | Returns a Razorpay payment page and issues a payment token used to initiate a booking |
| `/payments/charge` | POST | Verifies and captures payment with the Razorpay server |
| `/payments/refund` | POST | Initiates a refund request with the Razorpay server |
| `/payments/webhook` | POST | Public callback endpoint called by Razorpay for `payment.captured`, `payment.failed`, and `refund.processed` events; authenticated via `WebhookSignatureVerifier` using the raw payload body and `X-Razorpay-Signature` header |

#### 7. Notification Service (`notification-service` · Port 8085)

An event-driven messaging service responsible for all transactional customer emails. It has **no inbound HTTP controllers** — it operates entirely by consuming Kafka topics.

**Internal Services**

- `EmailService` — Sends outgoing email via SMTP.
- `NotificationLogService` — Persists delivery records to the `notification_logs` table.
- `TemplateService` — Renders Thymeleaf HTML email templates.
- `TicketPdfService` — Generates PDF ticket attachments for booking confirmations.

**Connects to Mailpit** (local SMTP server) in development to capture outgoing emails without delivering them.

---

## 4. Communication Patterns

### Synchronous — OpenFeign (HTTP)

Used only where an immediate response is required within a single user-facing request:

- `booking-service` → `payment-service` during checkout initiation.
- All inter-service Feign calls propagate the distributed `traceId` header automatically.

### Asynchronous — Apache Kafka

For all state-changing domain events, services publish to Kafka topics rather than calling each other directly. This eliminates tight coupling and allows consumers to evolve independently.

**User Service — Published Topics**

| Topic | Consumed By |
|---|---|
| `user-registered` | Notification Service |

**Event Service — Published Topics**

| Topic | Consumed By |
|---|---|
| `event-created` | Notification Service |
| `event-cancelled` | Booking Service, Notification Service |
| `event-sold-out` | Notification Service |

**Booking Service — Published Topics**

| Topic | Consumed By |
|---|---|
| `booking-confirmed` | Notification Service |
| `booking-failed` | Notification Service |
| `booking-cancelled` | Notification Service |

**Payment Service — Published Topics**

| Topic | Consumed By |
|---|---|
| `payment-success` | Booking Service, Notification Service |
| `payment-failed` | Booking Service, Notification Service |
| `refund-processed` | Notification Service |

**Dead Letter Topics (DLT)** — All Kafka consumers are configured with error-handling interceptors that route unprocessable ("toxic") messages to a corresponding DLT (e.g. `payment-success.DLT`). This prevents a single bad message from stalling a consumer partition and enables safe manual retry and analysis.

### Distributed Tracing — Zipkin (OpenTelemetry / Micrometer)

Every request entering the system through the API Gateway is stamped with a unique `traceId`. This trace context propagates across:

- **HTTP boundaries** via Feign request headers.
- **Messaging boundaries** via Kafka record headers.

Traces are collected by **Zipkin** (instrumented via Micrometer), giving full visibility into request durations, database call latency, and inter-service network overhead across the entire ecosystem.

---

## 5. Booking SAGA — 6-Step Flow

The booking checkout is implemented as a choreography-based SAGA to ensure data consistency across the seat locking, payment, and confirmation steps.

### Happy Path

1. **Validate seats** — Calls `event-service` to confirm all requested seats are available (`/events/{eventId}/seats/validate`).
2. **Lock seats in Redis** — Acquires `seat:lock:{seatId}` keys with a TTL of 10 minutes using `SET NX EX`.
3. **Persist booking as PENDING** — Saves a booking record in `PENDING` state before touching payment.
4. **Charge payment** — Calls `payment-service/charge` synchronously via OpenFeign.
5. **Update booking to CONFIRMED** — Marks the booking record as confirmed on payment success.
6. **Publish `booking-confirmed`** — Triggers the Notification Service to send the PDF ticket email.

### Failure Paths

| Failure Point | Compensation |
|---|---|
| Seat unavailable | Return `409 Conflict` immediately; no lock acquired |
| Redis lock acquisition fails | Release all already-acquired locks (acquired in order to prevent deadlock); abort |
| Any other pre-payment failure | Release all acquired locks |
| Payment declined | Release all locks; update booking to `FAILED`; publish `booking-failed` |
| DB error after payment succeeds | Manual reconciliation required (edge case logged for ops review) |

### Redis Seat Lock Implementation

```
SET seat:lock:{seatId} {userId} NX EX 600
```

Locks are released using a **Lua script** that performs an atomic `GET` + conditional `DEL`. A plain `DEL` is unsafe: if User A's lock expired and User B acquired the same key, a `DEL` by the User A cleanup path would catastrophically release User B's lock.

---

## 6. Payment Idempotency

### Idempotent Charge (`POST /payments/charge`)

1. REDIS idempotency check — key: `payments:idempotency:{bookingId}`, value: `ChargeResponse` (JSON).
2. DB guard — catches any Redis cache miss.
3. Reject reused payment tokens.
4. Create a `PENDING` `PaymentRecord`.
5. Call Razorpay, isolated inside `RazorpayGatewayService`.
6. Update record based on gateway result.
7. Store result in idempotency cache.
8. Return `ChargeResponse`.

### Idempotent Refund (`POST /payments/refund`)

1. REDIS idempotency check — key: `refund:idempotency:{paymentId}`, value: `RefundResponse` (JSON).
2. Fetch original payment record; status must be `SUCCESS`.
3. Confirm no `RefundRecord` with status `SUCCESS` already exists.
4. Create a `PENDING` `RefundRecord`.
5. Call Razorpay, isolated inside `RazorpayGatewayService`.
6. Update record based on gateway result.
7. Store result in idempotency cache.
8. Return `RefundResponse`.

---

## 7. Notification Flow (Booking Confirmed)

When the Notification Service consumes a `booking-confirmed` Kafka message:

1. Parse payload → `BookingConfirmedEvent`.
2. Idempotency check — if already sent for this `bookingId`, acknowledge offset and return.
3. Build Thymeleaf template variables.
4. Render `booking-confirmed.html` → HTML string.
5. Generate PDF ticket → `byte[]` via `TicketPdfService`.
6. Send HTML email with PDF attachment via `EmailService`.
7. Log delivery record to `notification_logs`.
8. Acknowledge Kafka offset.

---

## 8. Database Schemas

Each service owns a fully isolated PostgreSQL instance. No cross-service schema access is permitted.

### `user-service-db`

**`users`**

| Column | Type |
|---|---|
| `id` | UUID PK |
| `email` | VARCHAR |
| `username` | VARCHAR |
| `password_hash` | VARCHAR |
| `role` | ENUM |
| `auth_provider` | ENUM |
| `provider_id` | VARCHAR |
| `created_at` | TIMESTAMP |
| `updated_at` | TIMESTAMP |

### `event-service-db`

**`venues`**

| Column | Type |
|---|---|
| `id` | UUID PK |
| `name` | VARCHAR |
| `address` | VARCHAR |
| `city` | VARCHAR |
| `state` | VARCHAR |
| `country` | VARCHAR |
| `total_capacity` | INT |
| `map_image_url` | VARCHAR |
| `created_at` | TIMESTAMP |
| `updated_at` | TIMESTAMP |

**`events`**

| Column | Type |
|---|---|
| `id` | UUID PK |
| `title` | VARCHAR |
| `description` | TEXT |
| `venue_id` | UUID FK |
| `event_date` | TIMESTAMP |
| `door_open_time` | TIME |
| `status` | ENUM |
| `category` | ENUM |
| `banner_image_url` | VARCHAR |
| `organizer_id` | UUID |
| `created_by` | UUID |
| `last_modified_by` | UUID |
| `created_at` | TIMESTAMP |
| `updated_at` | TIMESTAMP |

**`seat_sections`**

| Column | Type |
|---|---|
| `id` | UUID PK |
| `event_id` | UUID FK |
| `section_name` | VARCHAR |
| `tier` | ENUM |
| `row_count` | INT |
| `seats_per_row` | INT |
| `created_at` | TIMESTAMP |

**`seats`**

| Column | Type |
|---|---|
| `id` | UUID PK |
| `section_id` | UUID FK |
| `row_label` | VARCHAR |
| `seat_number` | INT |
| `status` | ENUM |
| `tier` | ENUM |
| `price` | DECIMAL |
| `version` | BIGINT (optimistic lock) |
| `created_at` | TIMESTAMP |
| `updated_at` | TIMESTAMP |

### `booking-service-db`

**`bookings`**

| Column | Type |
|---|---|
| `id` | UUID PK |
| `user_id` | UUID |
| `event_id` | UUID |
| `status` | ENUM |
| `total_amount` | DECIMAL |
| `payment_method` | VARCHAR |
| `payment_id` | UUID |
| `event_date` | TIMESTAMP |
| `expires_at` | TIMESTAMP |
| `created_at` | TIMESTAMP |
| `confirmed_at` | TIMESTAMP |
| `cancelled_at` | TIMESTAMP |

**`booking_items`**

| Column | Type |
|---|---|
| `id` | UUID PK |
| `booking_id` | UUID FK |
| `seat_id` | UUID |
| `section_id` | UUID |
| `section_name` | VARCHAR |
| `row_label` | VARCHAR |
| `seat_number` | INT |
| `tier` | ENUM |
| `price` | DECIMAL |

### `payment-service-db`

**`payment_records`**

| Column | Type |
|---|---|
| `id` | UUID PK |
| `booking_id` | UUID |
| `user_id` | UUID |
| `gateway_payment_id` | VARCHAR |
| `amount` | DECIMAL |
| `currency` | VARCHAR |
| `status` | ENUM |
| `method` | VARCHAR |
| `failure_reason` | VARCHAR |
| `version` | BIGINT (optimistic lock) |
| `created_at` | TIMESTAMP |
| `updated_at` | TIMESTAMP |

**`refund_records`**

| Column | Type |
|---|---|
| `id` | UUID PK |
| `payment_id` | UUID FK |
| `gateway_refund_id` | VARCHAR |
| `amount` | DECIMAL |
| `status` | ENUM |
| `reason` | VARCHAR |
| `created_at` | TIMESTAMP |
| `processed_at` | TIMESTAMP |

### `notification-service-db`

**`notification_logs`**

| Column | Type |
|---|---|
| `id` | UUID PK |
| `reference_id` | UUID (bookingId / paymentId) |
| `notification_type` | ENUM |
| `recipient_email` | VARCHAR |
| `status` | ENUM |
| `failure_reason` | VARCHAR |
| `attempt_count` | INT |
| `sent_at` | TIMESTAMP |

---

## 9. Resilience Patterns

### Circuit Breaker — Resilience4j

The `booking-service` wraps its synchronous Feign call to `payment-service` with a Resilience4j circuit breaker. When the failure rate exceeds the configured threshold, the circuit opens and a fallback method is invoked immediately — preventing cascading failures from propagating back to the user.

### Idempotency — Redis Token Store

The `payment-service` uses a Redis-backed idempotency key store to deduplicate payment capture and refund requests. If a network retry causes the same request to arrive twice, the second call returns the cached result from the first without re-processing the charge. See §6 for the full implementation detail.

### Seat Lock TTL — Redis

The `booking-service` uses Redis key TTLs to implement automatic seat-lock expiration. If a user abandons their checkout session, the lock expires and the seat is released for other buyers — with no scheduled job or manual cleanup required. See §5 for the Lua script release mechanism.

---

## 10. Secrets Management & Security

StagePass is preconfigured for safe public repository hosting. **No database credentials, JWT signing keys, OAuth client IDs, or Razorpay API keys are committed to version control.**

| Layer | Mechanism |
|---|---|
| Local development | Root `.env` file (git-ignored); loaded by Docker Compose at runtime |
| IntelliJ run configs | EnvFile plugin streams variables from `.env` to local JVM processes |
| `application.properties` / `application.yml` | Un-defaulted placeholders only (e.g. `${JWT_SECRET}`) — service fails fast if a variable is missing |
| CI / production | Inject via environment variables or a secrets manager (e.g. HashiCorp Vault, AWS Secrets Manager) |

---

## 11. Persistence Architecture

Each service owns its own **isolated PostgreSQL database** running as a dedicated container (`postgres:18-alpine`) under a shared Docker bridge network. Services never share a database or access another service's schema directly.

| Service | Database Container | Docker Volume |
|---|---|---|
| User Service | `user-service-db` | `pg-user-data` |
| Event Service | `event-service-db` | `pg-event-data` |
| Booking Service | `booking-service-db` | `pg-booking-data` |
| Payment Service | `payment-service-db` | `pg-payment-data` |
| Notification Service | `notification-service-db` | `pg-notification-data` |

Named Docker volumes (`pg-*-data`) provide data durability across container restarts and redeployments.

---

## 12. Local Development Setup

### Prerequisites

- Docker Desktop (or Docker Engine + Compose plugin)
- Java 21+
- IntelliJ IDEA with the **EnvFile** plugin (for running services outside Docker)

### Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/arunkumar235/StagePass.git
cd StagePass

# 2. Copy and populate the environment file
cp .env.example .env
# Edit .env with your secrets (JWT key, OAuth credentials, Razorpay keys)

# 3. Start all infrastructure and services
docker compose up --build

# 4. Verify service health
curl http://localhost:8761   # Eureka dashboard
curl http://localhost:9411   # Zipkin dashboard
open http://localhost:8025   # Mailpit inbox (development emails)
```

> **Note:** Services register with Eureka on startup. Allow 20–30 seconds after `docker compose up` for all instances to appear as UP in the Eureka dashboard before sending requests through the gateway.
