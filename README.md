# Real-Time Margin & Liquidation Engine

A production-inspired financial risk management system that continuously monitors leveraged trading accounts, calculates real-time margin requirements, evaluates account risk, and performs automated liquidations to protect broker capital during volatile market conditions.

---

## Overview

The Real-Time Margin & Liquidation Engine simulates the core risk infrastructure used by modern brokers, cryptocurrency exchanges, futures platforms, and derivatives trading systems.

The system continuously:

* Consumes market price updates
* Marks positions to market (MTM)
* Recalculates account equity
* Computes margin requirements
* Detects risk threshold breaches
* Restricts risky trading activity
* Executes automated liquidations
* Maintains a complete audit trail

### Core Objective

> Prevent client losses from becoming broker losses.

---

# System Architecture

```text
                    Market Data Feed
                           │
                           ▼
                      Apache Kafka
                           │
                           ▼
                     MTM Engine
                           │
                           ▼
                    Margin Engine
                           │
                           ▼
                Risk Threshold Engine
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
        ▼                  ▼                  ▼
      SAFE          MARGIN CALL         LIQUIDATION
                                             │
                                             ▼
                                    Liquidation Engine
                                             │
                                             ▼
                                       Audit Engine
```

---

# Key Features

## Position Management

* Position creation
* Position updates
* Partial close
* Full close
* Cross-margin support
* Realized and unrealized PnL tracking

---

## Real-Time Mark-to-Market Engine

* Kafka-based market data ingestion
* Continuous price updates
* Redis-backed mark price cache
* High precision BigDecimal calculations
* Deterministic valuation logic

---

## Margin Engine

Calculates:

* Initial Margin
* Maintenance Margin
* Available Margin
* Account Equity
* Liquidation Price

Supports:

* Volatility adjustments
* Concentration risk penalties
* Cross-margin accounts
* Dynamic risk calculations

---

## Risk Management Engine

### SAFE

* Full trading allowed

### MARGIN_CALL

* Warning state
* Risk-reducing orders only

### LIQUIDATION

* Automated partial liquidation

### EMERGENCY

* Full liquidation execution

---

## Liquidation Engine

Features:

* Portfolio optimized liquidation
* Dynamic liquidation prioritization
* Partial liquidation execution
* Emergency liquidation handling
* Dynamic slippage modelling
* Liquidation penalties
* Negative balance protection
* Iterative risk reduction

---

## Concurrency & Consistency

* Pessimistic locking
* Transactional safety
* Race condition protection
* Double execution prevention
* Idempotent liquidation execution

---

## Audit & Compliance

Tracks:

* Margin snapshots
* Risk state changes
* Liquidation events
* Trigger rules
* Execution decisions
* Timestamps

Supports:

* Regulatory audits
* Client dispute investigation
* Event replay

---

# Technology Stack

## Backend

* Java 21
* Spring Boot 3
* Spring Security
* Spring Data JPA

## Databases

* MySQL
* MongoDB
* Redis

## Messaging

* Apache Kafka

## Authentication

* JWT Authentication

## Testing

* JUnit 5
* Mockito
* Embedded Kafka

---

# Core Financial Calculations

## Position Value

```text
Position Value = Quantity × Mark Price
```

## Unrealized PnL

```text
LONG:
PnL = Quantity × (Mark Price − Entry Price)

SHORT:
PnL = Quantity × (Entry Price − Mark Price)
```

## Account Equity

```text
Equity = Balance + Unrealized PnL
```

## Available Margin

```text
Available Margin = Equity − Initial Margin
```

## Liquidation Trigger

```text
Equity ≤ Maintenance Margin
```

---

# Risk States

| State       | Condition                         | Action                          |
| ----------- | --------------------------------- | ------------------------------- |
| SAFE        | Equity >= 120% Maintenance Margin | Normal Trading                  |
| MARGIN_CALL | Equity between 100% and 120% MM   | Restrict Risk Increasing Orders |
| LIQUIDATION | Equity <= 100% MM                 | Partial Liquidation             |
| EMERGENCY   | Equity <= 80% MM                  | Full Liquidation                |

---

# Persistence Layer

## MySQL

Stores:

* Users
* Positions
* Instrument Configurations
* Liquidation Events
* Audit Logs
* Idempotency Records

## MongoDB

Stores:

* User Profiles
* Dynamic User Metadata

## Redis

Stores:

* Market Prices
* Instrument Configurations
* Dynamic Volatility
* User Security Cache
* Notification State Cache

---

# API Modules

## User APIs

* User Registration
* Login
* Deposit Funds
* Margin Status

## Position APIs

* Place Orders
* Close Positions
* View Positions

## MTM APIs

* Update Mark Prices
* Trigger Revaluation

## Instrument APIs

* Create Instrument Config
* Fetch Instrument Config

## Stress APIs

* Get Market Stress Level
* Update Market Stress Level

## Profile APIs

* Create Profile
* Update Profile
* View Profile

---

# Reliability Features

## Redis Fallback

If Redis becomes unavailable:

* Application remains operational
* Data is fetched directly from databases

## Kafka Fallback

If Kafka becomes unavailable:

* Audit logs are written directly to MySQL
* No audit information is lost

## Negative Balance Protection

If liquidation causes negative account balance:

```text
User Balance = 0
Broker absorbs remaining loss
```

---

# Failure Scenario Simulations

The test suite validates:

* Sudden market crashes
* Extreme volatility spikes
* Low liquidity conditions
* Simultaneous liquidations
* Concurrent user actions
* Partial fills
* Negative balance scenarios

---

# Running the Project

## Clone Repository

```bash
git clone https://github.com/your-username/real-time-margin-liquidation-engine.git
```

## Build Project

```bash
mvn clean install
```

## Run Application

```bash
mvn spring-boot:run
```

## Run Tests

```bash
mvn test
```

---

# Future Enhancements

* Order book depth modelling
* Advanced market impact simulation
* Email notification engine
* Push notification support
* Multi-exchange integration
* Event sourcing architecture
* CCP Clearing & Default Management Engine
* Systemic risk modelling

---

# Authors

### Milan Tyagi

Backend Developer | Distributed Systems & Financial Infrastructure Enthusiast

### Vaibhavi Garg

Software Developer | Backend Engineering & System Design

---

# Disclaimer

This project is built for educational and portfolio purposes and is inspired by real-world risk management systems used by brokers, exchanges, and derivatives trading platforms. It is not intended for production trading without extensive validation, regulatory review, and operational hardening.
