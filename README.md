# StagePass 🎫

**StagePass** is a premium, high-performance distributed event ticketing and booking platform built on a modern microservices architecture using Spring Boot, Spring Cloud, Apache Kafka, Redis, and PostgreSQL.

Designed for resilience, extreme scalability, and secure high-volume transaction processing, StagePass provides a complete end-to-end ticketing marketplace backend.

---

## ✨ Key Features

* **🛡️ Edge Security & Routing**: Solitary edge entry via Spring Cloud API Gateway with JWT validation, CORS enforcement, and Netty-driven reactive routing.
* **⚡ Distributed Rate Limiting**: Employs a Redis-backed token bucket algorithm globally and dynamically (e.g., limiting booking checkout requests to 3/min per user).
* **🔄 6-Step SAGA Booking Orchestration**: Manages concurrent ticket checkouts with atomic seat locks using **Redis TTLs** to ensure zero seat conflicts.
* **💳 Idempotent Payment Gateway**: Fully integrated with the Razorpay API, featuring webhook signature verification and Redis idempotency locks to guarantee zero double charges.
* **📬 Event-Driven Notifications**: Decoupled, asynchronous email dispatching driven by **Apache Kafka** events, producing automatic HTML confirmation emails with dynamic PDF ticket attachments.
* **🔍 Distributed Tracing**: Distributed request tracing across HTTP (Feign) and messaging (Kafka) boundaries using **Zipkin** (via Micrometer/OpenTelemetry).
* **🔑 Zero-Secret Configuration**: Out-of-the-box support for secure GitHub hosting; all development credentials are isolated inside local `.env` variables and dynamically bound at runtime.

---

## 🏗️ System Architecture

StagePass is architected as a highly decoupled microservices ecosystem.

![StagePass System Architecture](./stagepass_architecture.png)

```
                    ┌───────────────────────────┐
                    │  Client (Postman/Browser) │
                    └─────────────┬─────────────┘
                                  │ :8080
         ┌────────────────────────▼────────────────────────┐
         │                  API GATEWAY                    │
         │          (Security, Routing & Rate Limits)      │
         └────────┬───────┬───────┬───────┬───────┬────────┘
                  │       │       │       │       │ lb://
         ┌────────▼┐ ┌────▼┐ ┌────▼┐ ┌────▼┐ ┌────▼────────┐
         │  User   │ │Event│ │Book-│ │Pay- │ │Notification │
         │ Service │ │Service│  ing│ |ment │ │  Service    │
         │  :8081  │ │:8082│ │:8083│ │:8084│ │  :8085      │
         └───┬─────┘ └───┬─┘ └───┬─┘ └───┬─┘ └────┬────────┘
             │           │       │       │        │
         ┌───▼───┐   ┌───▼──┐ ┌──▼──┐ ┌──▼──┐ ┌───▼──┐
         │db-user│   │db-evt│ │db-bk│ │db-py│ │db-nt │
         └───────┘   └──────┘ └─────┘ └─────┘ └──────┘
             │           │       │       │        │
             └───────────┼───────┼───────┼────────┘
                         │       │       │
      ┌──────────────────▼──┐ ┌──▼───────▼──┐
      │ Apache Kafka Broker │ │ Redis Cache │
      │   (Event-Driven)    │ │   & Locks   │
      └─────────────────────┘ └─────────────┘
```

For an in-depth technical analysis of database persistence schemas, SAGA failure compensation patterns, payment webhook security, and the complete microservices data structures, see the **[Architecture Deep-Dive Guide (ARCHITECTURE.md)](./ARCHITECTURE.md)**.

---

## 🔌 Port Reference

| Service | Container Name | Port | Description |
|---|---|---|---|
| **API Gateway** | `api-gateway` | `8080` | Edge Router & Rate Limiter |
| **Eureka Server** | `eureka-server` | `8761` | Service Registry & Discovery |
| **User Service** | `user-service` | `8081` | Authentication & User Management |
| **Event Service** | `event-service` | `8082` | Shows & Venue Catalogue |
| **Booking Service** | `booking-service` | `8083` | Ticket Bookings & Seat Locking |
| **Payment Service** | `payment-service` | `8084` | Razorpay Gateway Processing |
| **Notification Service**| `notification-service`| `8085` | Kafka-Driven Mail Processor (No HTTP) |
| **Zipkin UI** | `zipkin` | `9411` | Distributed Tracing Dashboard |
| **Mailpit UI** | `mailpit` | `8025` | Mock SMTP Mail Inbox |

---

## 🚀 Quick Start Guide

### Prerequisites
* Docker Desktop (with Compose)
* Java 21+

### 1. Configure the Environment Secrets
StagePass uses a secure, uncommitted environment configuration. Copy the template and edit the `.env` file with your local keys:
```bash
cp .env.example .env
```
Open `.env` and configure your:
* **JWT Secret** (Minimum 32-character signing key)
* **Google & GitHub OAuth Client Credentials**
* **Razorpay Test API Keys & Webhook Secret**

### 2. Boot the Entire Containerized Suite
Bring up the isolated databases, Kafka, Redis, Zipkin, Mailpit, and all StagePass microservices with a single command:
```bash
docker compose up -d
```

### 3. Verification & Live Dashboards
Once the containers boot, you can access the live dashboards in your browser:
* **Eureka Registry Console**: [http://localhost:8761](http://localhost:8761) *(Wait ~20s for all services to register)*
* **Zipkin Distributed Tracing**: [http://localhost:9411](http://localhost:9411)
* **Mailpit Development Mailbox**: [http://localhost:8025](http://localhost:8025)

---

## 🛠️ Hybrid Development Setup (IntelliJ + Docker)

If you are developing or debugging code, you can run the services as local Java processes inside your IDE while leaving the complex infrastructure running inside Docker:

1. **Spin up ONLY the databases and brokers in Docker**:
   ```bash
   docker compose up -d db-user db-booking db-event db-payment db-notification redis kafka zipkin mailpit
   ```
2. **Install the "EnvFile" Plugin in IntelliJ**:
   * Go to Settings -> Plugins and install **EnvFile**.
   * Open the Run Configurations for your services, go to the **EnvFile** tab, check **Enable EnvFile**, and link your root **`.env`** file.
3. **Start the Services in IntelliJ**:
   * Start your services normally in your IDE. They will connect automatically to `localhost` databases and brokers, utilizing the built-in fallbacks!
