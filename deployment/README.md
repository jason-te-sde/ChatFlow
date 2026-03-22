# Deployment Guide — ChatFlow Assignment 2

## Prerequisites

- AWS account, us-west-2 region
- Java 21+, Maven 3.8+ installed locally
- SSH key pair (.pem file)

## EC2 Instances

| Role | Name | Type | Inbound ports |
|---|---|---|---|
| RabbitMQ | chatflow-rabbitmq | t2.micro | 22, 5672, 15672 |
| WS Server 1 | chatflow-server-1 | t2.micro | 22, 8080 |
| WS Server 2 | chatflow-server-2 | t2.micro | 22, 8080 |
| WS Server 3 | chatflow-server-3 | t2.micro | 22, 8080 |
| WS Server 4 | chatflow-server-4 | t2.micro | 22, 8080 |
| Consumer | chatflow-consumer | t2.micro | 22 |

All instances: Ubuntu 22.04 LTS

---

## Step 1 — Install Java on all EC2 instances

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk
java -version
```

---

## Step 2 — Deploy RabbitMQ

SSH into `chatflow-rabbitmq`:

```bash
sudo apt-get install -y rabbitmq-server
sudo systemctl enable rabbitmq-server
sudo systemctl start rabbitmq-server
sudo rabbitmq-plugins enable rabbitmq_management

# Create remote-accessible user
sudo rabbitmqctl add_user chatflow chatflow123
sudo rabbitmqctl set_user_tags chatflow administrator
sudo rabbitmqctl set_permissions -p / chatflow ".*" ".*" ".*"

# Verify
sudo systemctl status rabbitmq-server
```

Management UI: `http://<rabbitmq-public-ip>:15672` (chatflow / chatflow123)

---

## Step 3 — Build locally

```bash
cd server-v2 && mvn clean package -DskipTests
cd ../consumer && mvn clean package -DskipTests
```

---

## Step 4 — Upload JARs

```bash
# Server (repeat for each server EC2)
scp -i your-key.pem \
  server-v2/target/server-v2-1.0.0.jar \
  ubuntu@<server-public-ip>:~/

# Consumer
scp -i your-key.pem \
  consumer/target/consumer-1.0.0.jar \
  ubuntu@<consumer-public-ip>:~/
```

---

## Step 5 — Start WS Servers

On each server EC2 (change `server.id` for each):

```bash
# Kill any existing process
pkill -f server-v2

nohup java -jar server-v2-1.0.0.jar \
  --rabbitmq.host=<rabbitmq-private-ip> \
  --rabbitmq.username=chatflow \
  --rabbitmq.password=chatflow123 \
  --server.id=server-1 \
  > server.log 2>&1 &

# Verify
sleep 5 && curl http://localhost:8080/health
# Expected: {"status":"ok"}
```

---

## Step 6 — Start Consumer

```bash
pkill -f consumer

nohup java -jar consumer-1.0.0.jar \
  --rabbitmq.host=<rabbitmq-private-ip> \
  --rabbitmq.username=chatflow \
  --rabbitmq.password=chatflow123 \
  > consumer.log 2>&1 &

# Verify
sleep 5 && tail -5 consumer.log
# Expected: Started consumer threads
```

---

## Step 7 — Configure ALB

1. EC2 → Load Balancers → Create → **Application Load Balancer**
2. Name: `chatflow-alb`, Scheme: Internet-facing, VPC: default
3. Security group: allow inbound HTTP port 80
4. **Create Target Group:**
    - Name: `chatflow-servers`
    - Protocol: HTTP, Port: 8080
    - Health check path: `/health`
    - Health check interval: 30s, healthy threshold: 2, unhealthy threshold: 3
    - Register all server instances
5. Listener: HTTP:80 → forward to `chatflow-servers`
6. After creation — **enable sticky sessions:**
    - Target Groups → `chatflow-servers` → Attributes → Edit
    - Stickiness: Load balancer generated cookie, Duration: 1 day
7. **Set idle timeout:**
    - Load Balancers → `chatflow-alb` → Attributes → Edit
    - Idle timeout: 4000 seconds

---

## Step 8 — Run load test

Edit `client/src/main/java/com/chatflow/LoadTestClient.java`:

```java
static final String SERVER_URL = "ws://<alb-dns-name>/chat/";
```

```bash
cd client
mvn clean package -DskipTests

# 500K test
java -jar target/client-1.0.0.jar

# 1M stress test (change TOTAL_MESSAGES = 1_000_000 first, then repackage)
java -jar target/client-1.0.0.jar
```

---

## Stop All Services

```bash
# On each server / consumer EC2
pkill -f "server-v2\|consumer"

# RabbitMQ
sudo systemctl stop rabbitmq-server

# Local Docker
docker stop rabbitmq && docker rm rabbitmq
```

---

## Configuration Reference

| Parameter | Default | Description |
|---|---|---|
| `rabbitmq.host` | localhost | RabbitMQ hostname or IP |
| `rabbitmq.port` | 5672 | RabbitMQ AMQP port |
| `rabbitmq.username` | guest | RabbitMQ username |
| `rabbitmq.password` | guest | RabbitMQ password |
| `server.id` | server-1 | Identifies server in queue messages |
| `server.port` | 8080 | HTTP / WebSocket port |