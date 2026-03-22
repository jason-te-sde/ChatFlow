# Deployment Guide — ChatFlow Assignment 2

## Prerequisites

- AWS account with EC2 access (us-west-2)
- Java 21, Maven 3.8+ installed locally
- SSH key pair (.pem file)

---

## EC2 Instances Required

| Role | Name | Type | Ports |
|---|---|---|---|
| RabbitMQ | chatflow-rabbitmq | t2.micro | 22, 5672, 15672 |
| WS Server 1 | chatflow-server-1 | t2.micro | 22, 8080 |
| WS Server 2 | chatflow-server-2 | t2.micro | 22, 8080 |
| Consumer | chatflow-consumer | t2.micro | 22 |

All instances: Ubuntu 22.04 LTS, us-west-2.

---

## Step 1 — Install Java on all EC2 instances

Run on each instance:

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

Management UI: `http://<rabbitmq-public-ip>:15672` (login: chatflow / chatflow123)

---

## Step 3 — Build and upload server

On your local machine:

```bash
cd server-v2
mvn clean package -DskipTests

scp -i your-key.pem target/server-v2-1.0.0.jar ubuntu@<server1-public-ip>:~/
scp -i your-key.pem target/server-v2-1.0.0.jar ubuntu@<server2-public-ip>:~/
```

---

## Step 4 — Start WS Servers

On `chatflow-server-1`:

```bash
nohup java -jar server-v2-1.0.0.jar \
  --rabbitmq.host=<rabbitmq-private-ip> \
  --rabbitmq.username=chatflow \
  --rabbitmq.password=chatflow123 \
  --server.id=server-1 \
  > server.log 2>&1 &

# Verify
curl http://localhost:8080/health
```

On `chatflow-server-2` (change server.id):

```bash
nohup java -jar server-v2-1.0.0.jar \
  --rabbitmq.host=<rabbitmq-private-ip> \
  --rabbitmq.username=chatflow \
  --rabbitmq.password=chatflow123 \
  --server.id=server-2 \
  > server.log 2>&1 &

curl http://localhost:8080/health
```

---

## Step 5 — Build and deploy Consumer

On your local machine:

```bash
cd consumer
mvn clean package -DskipTests

scp -i your-key.pem target/consumer-1.0.0.jar ubuntu@<consumer-public-ip>:~/
```

On `chatflow-consumer`:

```bash
nohup java -jar consumer-1.0.0.jar \
  --rabbitmq.host=<rabbitmq-private-ip> \
  --rabbitmq.username=chatflow \
  --rabbitmq.password=chatflow123 \
  > consumer.log 2>&1 &

tail -f consumer.log
# Should see: Started consumer threads for room.1 ... room.20
```

---

## Step 6 — Configure ALB

1. EC2 → Load Balancers → Create → Application Load Balancer
2. Name: `chatflow-alb`, Scheme: Internet-facing, VPC: default
3. Security group: allow inbound HTTP port 80
4. Create Target Group:
    - Name: `chatflow-servers`, Protocol: HTTP, Port: 8080
    - Health check path: `/health`
    - Register server-1 and server-2
5. Listener: HTTP:80 → forward to `chatflow-servers`
6. After creation: Target Groups → `chatflow-servers` → Attributes → Edit
    - Enable Stickiness: Load balancer generated cookie, Duration: 1 day

---

## Step 7 — Run load test

Edit `client/src/main/java/com/chatflow/LoadTestClient.java`:

```java
static final String SERVER_URL = "ws://<alb-dns-name>/chat/";
```

```bash
cd client
mvn clean package -DskipTests
java -jar target/client-1.0.0.jar
```

---

## Stopping all services

```bash
# On each server/consumer EC2
pkill -f "server-v2\|consumer"

# Stop RabbitMQ
sudo systemctl stop rabbitmq-server

# Local Docker (if running locally)
docker stop rabbitmq && docker rm rabbitmq
```

---

## Configuration Reference

| Parameter | Default | Description |
|---|---|---|
| `rabbitmq.host` | localhost | RabbitMQ host |
| `rabbitmq.port` | 5672 | RabbitMQ AMQP port |
| `rabbitmq.username` | guest | RabbitMQ username |
| `rabbitmq.password` | guest | RabbitMQ password |
| `server.id` | server-1 | Server identifier in queue messages |
| `server.port` | 8080 | HTTP/WebSocket port |