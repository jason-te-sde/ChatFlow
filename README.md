# ChatFlow — CS6650 Assignment 2

Distributed WebSocket chat system with RabbitMQ message queue and AWS load balancing.

## Repository Structure

```
/server-v2      WebSocket server (message producer)
/consumer       Consumer application (message broadcaster)
/client         Multithreaded load test client
/deployment     Deployment scripts and configuration guide
```

## Quick Start (Local)

```bash
# 1. Start RabbitMQ
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management

# 2. Start server
cd server-v2 && mvn clean package -DskipTests
java -jar target/server-v2-1.0.0.jar

# 3. Start consumer
cd consumer && mvn clean package -DskipTests
java -jar target/consumer-1.0.0.jar

# 4. Run load test (edit SERVER_URL to ws://localhost:8080/chat/ first)
cd client && mvn clean package -DskipTests
java -jar target/client-1.0.0.jar
```

## AWS Deployment

See [deployment/README.md](deployment/README.md) for full AWS deployment instructions.

## Results

| Test | Instances | Messages | Throughput | Failed |
|---|---|---|---|---|
| Local baseline | 1 | 500,000 | ~79,000 msg/s | 0 |
| AWS 2-instance | 2 (ALB) | 500,000 | ~8,000 msg/s | 0 |

## Tech Stack

- Java 21, Spring Boot 3.2
- RabbitMQ 3 (topic exchange, 20 queues)
- AWS EC2 t2.micro, Application Load Balancer