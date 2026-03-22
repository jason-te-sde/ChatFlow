# ChatFlow — CS6650 Assignment 2

Distributed real-time chat system built with WebSocket, RabbitMQ, and AWS.

## Repository Structure

```
/server-v2      WebSocket server (validates messages, publishes to RabbitMQ)
/consumer       Consumer application (pulls from queue, broadcasts to clients)
/client         Multithreaded load test client (500K / 1M messages)
/deployment     AWS deployment guide and scripts
/monitoring     RabbitMQ monitoring guide
```

## System Overview

```
Client → ALB → WS Servers (1-4) → RabbitMQ → Consumer → broadcast to sessions
```

- **WS Server**: accepts WebSocket connections at `/chat/{roomId}`, validates messages, publishes to `chat.exchange` via channel pool
- **RabbitMQ**: topic exchange with 20 durable queues (`room.1` ~ `room.20`), TTL 60s, max-length 100k
- **Consumer**: 20 consumer threads (one per room), broadcasts to all sessions in room via `SessionRegistry`
- **ALB**: sticky session routing ensures WebSocket connection affinity

## Quick Start (Local)

**Prerequisites:** Java 21+, Maven 3.8+, Docker

```bash
# 1. Start RabbitMQ
docker run -d --name rabbitmq \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:3-management

# 2. Start server
cd server-v2
mvn clean package -DskipTests
java -jar target/server-v2-1.0.0.jar

# 3. Start consumer (new terminal)
cd consumer
mvn clean package -DskipTests
java -jar target/consumer-1.0.0.jar

# 4. Run load test (new terminal)
cd client
mvn clean package -DskipTests
java -jar target/client-1.0.0.jar
```

RabbitMQ management UI: `http://localhost:15672` (guest / guest)

## AWS Deployment

See [deployment/README.md](deployment/README.md) for full step-by-step instructions.

## Test Results Summary

| Test | Instances | Messages | Throughput | Failed |
|---|---|---|---|---|
| Local baseline | 1 | 500K | 75,478 msg/s | 0 |
| AWS 2-instance | 2 | 500K | 128,197 msg/s | 0 |
| AWS 4-instance | 4 | 500K | 129,727 msg/s | 0 |
| AWS 4-instance stress | 4 | 1M | 24,442 msg/s | 16 |

## Tech Stack

- Java 21, Spring Boot 3.2
- RabbitMQ 3 (amqp-client 5.20.0)
- AWS EC2 t2.micro, Application Load Balancer
- Spring WebSocket (StandardWebSocketClient)