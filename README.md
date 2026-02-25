# Rental Turnover Optimization Engine — POC

A Spring Boot proof-of-concept demonstrating how an **event-driven architecture** (modelled after Apache Kafka) can dramatically reduce the time between a tenant's move-out and the next tenant's move-in.

---

## The Problem

Property managers lose revenue every day a unit sits vacant. The main culprit is an **unoptimised turnover process** where work orders are executed sequentially:

```
Move-out → INSPECTION (6h) → CLEANING (10h) → REPAIR (44h) → Ready
                                                               ↑ 60h total
                                                          KPI target: ≤36h
```

Each step waits for the previous one to finish. A delayed REPAIR cascades into a 24-hour overrun on the KPI target — increasing vacancy costs and reducing occupancy rates.

---

## The Solution

Introduce an event-driven pipeline where **INSPECTION acts as a gate** and then unlocks **CLEANING and REPAIR in parallel**:

```
Move-out → INSPECTION (3h) → ┬─ CLEANING (7h)  ─┐
                              └─ REPAIR   (23h) ─┴→ Ready
                                                     ↑ 26h total
                                                 10h under KPI target ✓
```

This is achieved through a **publish/subscribe event bus** — modelled here with Spring's `ApplicationEventPublisher` and `@EventListener`, which maps directly to Kafka topics and consumer groups in a production system.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        REST Layer                               │
│  POST /turnovers/moveout          POST /workorders/{id}/complete│
└──────────────────────┬──────────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────────┐
│                   TurnoverService                               │
│                                                                 │
│  @EventListener ← TenantMovedOutEvent                          │
│      └─ creates INSPECTION work order                           │
│                                                                 │
│  @EventListener ← WorkOrderCompletedEvent                      │
│      ├─ INSPECTION done → fan-out: create CLEANING + REPAIR    │
│      └─ all done → mark COMPLETED + publish ReadyForMoveIn     │
└───────────────────────────────────┬─────────────────────────────┘
                                    │ events
┌───────────────────────────────────▼─────────────────────────────┐
│                   WorkOrderService                              │
│  complete(id) → sets COMPLETED + publishes WorkOrderCompleted  │
└───────────────────────────────────┬─────────────────────────────┘
                                    │
┌───────────────────────────────────▼─────────────────────────────┐
│              H2 In-Memory Database  (JPA / Hibernate)          │
│  turnovers table          work_order table                      │
└─────────────────────────────────────────────────────────────────┘
```

### Kafka mapping

| Spring construct              | Kafka equivalent             |
|-------------------------------|------------------------------|
| `TenantMovedOutEvent`         | Topic `tenant.moved-out`     |
| `WorkOrderCompletedEvent`     | Topic `workorder.completed`  |
| `TurnoverReadyForMoveInEvent` | Topic `property.ready-for-move-in` |
| `@EventListener`              | Consumer group               |
| `ApplicationEventPublisher`   | KafkaTemplate / Producer     |

Replacing Spring events with a real Kafka producer/consumer is a **pure infrastructure swap** — no business logic changes required.

---

## Domain Model

```
Turnover
  id            UUID
  propertyId    String
  status        IN_PROGRESS | COMPLETED
  startedAt     LocalDateTime
  completedAt   LocalDateTime

WorkOrder
  id            UUID
  turnoverId    UUID
  type          INSPECTION | CLEANING | REPAIR
  status        PENDING | IN_PROGRESS | COMPLETED
  slaHours      (from WorkOrderType enum)
  slaDeadline   LocalDateTime   ← startedAt + slaHours
  startedAt     LocalDateTime
  completedAt   LocalDateTime
```

### SLA targets per work order type

| Type       | SLA  | Role                                |
|------------|------|-------------------------------------|
| INSPECTION | 4h   | Critical-path gate — must run first |
| CLEANING   | 8h   | Runs in parallel after inspection   |
| REPAIR     | 24h  | Runs in parallel after inspection   |

---

## Getting Started

### Prerequisites

- Java 17+
- Gradle (wrapper included)

### Run

```bash
./gradlew bootRun
```

The application starts on **http://localhost:8080**.  
On startup, two historical turnovers are seeded automatically (see [Seeded Data](#seeded-data)).

### H2 Console

Browse the database at **http://localhost:8080/h2-console**

| Field    | Value                   |
|----------|-------------------------|
| JDBC URL | `jdbc:h2:mem:turnoverdb`|
| Username | `sa`                    |
| Password | *(empty)*               |

---

## API Reference

### Turnover lifecycle

| Method | Endpoint                                          | Description                                    |
|--------|---------------------------------------------------|------------------------------------------------|
| POST   | `/turnovers/moveout?propertyId={id}`              | Tenant moves out — starts the event pipeline   |
| GET    | `/turnovers/{turnoverId}/workorders`              | List work orders for a turnover                |
| POST   | `/turnovers/{turnoverId}/workorders/{woId}/complete` | Complete a work order (triggers next events) |

### Metrics / KPIs

| Method | Endpoint                      | Description                                  |
|--------|-------------------------------|----------------------------------------------|
| GET    | `/turnovers/{id}/kpi`         | Full KPI breakdown for a single turnover     |
| GET    | `/turnovers/kpi/summary`      | Aggregate KPIs across all turnovers          |

### Simulation shortcuts

| Method | Endpoint                                    | Description                                       |
|--------|---------------------------------------------|---------------------------------------------------|
| POST   | `/turnovers/simulate?scenario=bottleneck`   | Seeds a historical record of the "before" state   |
| POST   | `/turnovers/simulate?scenario=optimized`    | Seeds a historical record of the "after" state    |

---

## Demo Playbook

### 1. See the aggregate KPI impact immediately

```bash
curl http://localhost:8080/turnovers/kpi/summary
```

```json
{
  "totalTurnovers": 2,
  "completedTurnovers": 2,
  "avgCycleTimeHours": 43,
  "kpiTargetHours": 36,
  "withinKpiCount": 1,
  "kpiCompliancePct": 50
}
```

### 2. Drill into the bottleneck turnover

```bash
# get the id from the summary endpoint first
curl http://localhost:8080/turnovers/{PROP-BEFORE-id}/kpi
```

```json
{
  "propertyId": "PROP-BEFORE",
  "cycleTimeHours": 60,
  "kpiTargetHours": 36,
  "slaBreached": true,
  "varianceHours": 24,
  "bottleneck": {
    "type": "REPAIR",
    "slaHours": 24,
    "actualHours": 44,
    "overrunHours": 20
  }
}
```

### 3. Drill into the optimised turnover

```bash
curl http://localhost:8080/turnovers/{PROP-AFTER-id}/kpi
```

```json
{
  "propertyId": "PROP-AFTER",
  "cycleTimeHours": 26,
  "kpiTargetHours": 36,
  "slaBreached": false,
  "varianceHours": -10,
  "bottleneck": null,
  "workOrdersOnTimePct": 100
}
```

### 4. Run the live event-driven flow

Watch the logs as events propagate through the system in real time.

**Step 1 — tenant leaves.** Copy the `id` from the response.

```bash
curl -s -X POST "http://localhost:8080/turnovers/moveout?propertyId=PROP-LIVE-1"
```
```json
{ "id": "a1b2c3d4-...", "propertyId": "PROP-LIVE-1", "status": "IN_PROGRESS", ... }
```
```
→ log: [EVENT → tenant.moved-out]
→ log: [WORK ORDER CREATED] type=INSPECTION sla=4h
```

**Step 2 — list work orders.** Only INSPECTION exists at this point — CLEANING and REPAIR are gated behind it. Copy the INSPECTION work order `id`.

```bash
TURNOVER_ID="a1b2c3d4-..."   # from Step 1

curl -s "http://localhost:8080/turnovers/$TURNOVER_ID/workorders"
```
```json
[
  { "id": "f1e2d3c4-...", "type": "INSPECTION", "status": "PENDING", "slaDeadline": "..." }
]
```

**Step 3 — inspection team completes their report.** This fires `WorkOrderCompletedEvent`, which unlocks CLEANING and REPAIR in parallel. Copy both new IDs from Step 4.

```bash
INSPECTION_WO_ID="f1e2d3c4-..."   # from Step 2

curl -s -X POST "http://localhost:8080/turnovers/$TURNOVER_ID/workorders/$INSPECTION_WO_ID/complete"
```
```
→ log: [EVENT → workorder.completed] type=INSPECTION
→ log: [WORK ORDER CREATED] type=CLEANING sla=8h
→ log: [WORK ORDER CREATED] type=REPAIR   sla=24h  ← parallel fan-out
```

**Step 4 — list work orders again.** CLEANING and REPAIR now exist. Copy their IDs.

```bash
curl -s "http://localhost:8080/turnovers/$TURNOVER_ID/workorders"
```
```json
[
  { "id": "f1e2d3c4-...", "type": "INSPECTION", "status": "COMPLETED" },
  { "id": "a9b8c7d6-...", "type": "CLEANING",   "status": "PENDING" },
  { "id": "11223344-...", "type": "REPAIR",      "status": "PENDING" }
]
```

**Step 5 — complete CLEANING and REPAIR** (order doesn't matter — they are independent).

```bash
CLEANING_WO_ID="a9b8c7d6-..."
REPAIR_WO_ID="11223344-..."

curl -s -X POST "http://localhost:8080/turnovers/$TURNOVER_ID/workorders/$CLEANING_WO_ID/complete"
curl -s -X POST "http://localhost:8080/turnovers/$TURNOVER_ID/workorders/$REPAIR_WO_ID/complete"
```
```
→ log: [EVENT → property.ready-for-move-in] cycleTime=Xh
```

**Step 6 — final KPI report.**

```bash
curl -s "http://localhost:8080/turnovers/$TURNOVER_ID/kpi"
```

### 5. Generate named scenario snapshots on demand

```bash
curl -X POST "http://localhost:8080/turnovers/simulate?scenario=bottleneck"
curl -X POST "http://localhost:8080/turnovers/simulate?scenario=optimized"
```

---

## Seeded Data

Two turnovers are inserted on every startup to tell the before/after story without any manual steps.

### PROP-BEFORE — bottleneck (sequential)

```
Hour  0   move-out
Hour  0   INSPECTION starts  (SLA 4h)
Hour  6   INSPECTION done    ← +2h over SLA
Hour  6   CLEANING starts    (SLA 8h)   ← blocked until now
Hour 16   CLEANING done      ← +2h over SLA
Hour 16   REPAIR starts      (SLA 24h)  ← blocked until now
Hour 60   REPAIR done        ← +20h over SLA   ★ bottleneck
─────────────────────────────────────────────────
Cycle: 60h   KPI target: 36h   Variance: +24h   ✗
```

### PROP-AFTER — optimised (parallel)

```
Hour  0   move-out
Hour  0   INSPECTION starts  (SLA 4h)
Hour  3   INSPECTION done    ← within SLA ✓
Hour  3   CLEANING starts    (SLA 8h)   ─┐ parallel
Hour  3   REPAIR starts      (SLA 24h)  ─┘ fan-out
Hour 10   CLEANING done      ← within SLA ✓
Hour 26   REPAIR done        ← within SLA ✓
─────────────────────────────────────────────────
Cycle: 26h   KPI target: 36h   Saved: 34h vs before   ✓
```

---

## Production Roadmap

This POC uses Spring events and an H2 database. Moving to production means:

| POC                             | Production                                   |
|---------------------------------|----------------------------------------------|
| `ApplicationEventPublisher`     | Apache Kafka (KafkaTemplate + @KafkaListener)|
| H2 in-memory                    | PostgreSQL / Aurora                          |
| Single JVM                      | Independent microservices per domain         |
| Manual `/complete` endpoint     | Vendor mobile app / webhook integration      |
| In-process logging              | Grafana + Prometheus dashboards              |
