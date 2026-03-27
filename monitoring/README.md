# Monitoring Guide — ChatFlow Assignment 3

## RabbitMQ Management UI

Access at `http://<rabbitmq-public-ip>:15672` (chatflow / chatflow123)

During load tests, capture the following pages:

### Overview page

Shows two charts to screenshot during load test:

- **Queued messages (last minute):** shows queue depth over time. Good profile = brief peak then returns to 0. Bad profile = sawtooth pattern indicating consumer lag.
- **Message rates (last minute):** shows publish rate and consumer ack rate. These two lines should be close together, confirming no message loss.

Also shows global counts: Connections, Channels, Queues, Consumers.

### Queues page

Shows all 20 room queues with per-queue metrics:
- Ready: messages waiting to be consumed (should stay near 0)
- Unacked: messages delivered but not yet acked
- Incoming / Deliver / Ack rates in msg/s

### Connections page

Shows all active AMQP connections with:
- Source IP and port
- State (running)
- Channel count
- Network throughput (From client / To client)

One connection from the server (20 channels = channel pool), one from the consumer (20 channels = one per room thread).

---

## Monitoring During Load Test

Open three terminal tabs while running the client:

```bash
# Tab 1: watch consumer progress
ssh -i key.pem ubuntu@<consumer-ip> \
  "tail -f consumer.log | grep Processed"

# Tab 2: watch server log
ssh -i key.pem ubuntu@<server-ip> \
  "tail -f server.log"

# Tab 3: check RabbitMQ queue summary (run once)
curl -s -u chatflow:chatflow123 \
  http://<rabbitmq-ip>:15672/api/queues | \
  python3 -c "
import sys, json
data = json.load(sys.stdin)
for q in sorted(data, key=lambda x: x['name']):
    print(f\"{q['name']}: ready={q.get('messages_ready',0)} unacked={q.get('messages_unacknowledged',0)}\")
"
```

---

## AWS CloudWatch Metrics (ALB)

In AWS Console → EC2 → Load Balancers → `chatflow-alb` → Monitoring:

| Metric | What to look for |
|---|---|
| RequestCount | spike during load test confirms ALB is routing traffic |
| HealthyHostCount | should equal number of registered server instances |
| TargetResponseTime | p50 and p99 latency at the ALB level |
| ActiveConnectionCount | number of live WebSocket connections |

---

---

## MySQL Metrics (Assignment 3)

### During load test — run on MySQL EC2

```bash
# CPU and memory usage
top -bn3 | grep -E "Cpu|Mem|mysql"

# DB write progress
watch -n 10 "mysql -u chatflow -pchatflow123 -h localhost chatflow \
  -e 'SELECT COUNT(*) as written, NOW() as time FROM messages;'"

# Active connections
mysql -u chatflow -pchatflow123 -h localhost chatflow \
  -e "SHOW STATUS LIKE 'Threads_connected';"

# InnoDB buffer pool hit ratio
mysql -u chatflow -pchatflow123 -h localhost chatflow \
  -e "SHOW STATUS LIKE 'Innodb_buffer_pool%';"
```

### Metrics API Endpoints

```bash
# Full summary (call after test completes)
curl http://<alb-dns>/metrics/summary

# Room messages in time range
curl "http://<alb-dns>/metrics/room/1?start=2026-01-01T00:00:00Z&end=2026-12-31T23:59:59Z"

# User history
curl http://<alb-dns>/metrics/user/1

# Active users
curl "http://<alb-dns>/metrics/active-users?start=2026-01-01T00:00:00Z&end=2026-12-31T23:59:59Z"

# User rooms
curl http://<alb-dns>/metrics/user/1/rooms
```

## Key Performance Targets

| Metric | Target | Achieved |
|---|---|---|
| DB write throughput | sustained | 19,693 msg/s (endurance) |
| DB failed writes | 0 | 0 (all tests) |
| Query 1 response | <100ms | 1.425s (limited by t3.micro) |
| Query 2 response | <200ms | 0.277s ✅ |
| Query 3 response | <500ms | 1.179s (limited by t3.micro) |
| Query 4 response | <50ms | 0.249s ✅ |
| Memory leak | none | none detected |
| Connection exhaustion | none | none detected |

| Metric | Target | Achieved |
|---|---|---|
| Queue depth | < 1000 consistently | Ready = 0 throughout |
| Consumer lag | < 100ms | ack rate ≈ publish rate |
| Message loss | 0 | 0 failed (500K tests) |
| Throughput (local) | good baseline | 75,478 msg/s |
| Throughput (2 instance) | improvement over baseline | 128,197 msg/s |
| Throughput (4 instance) | maximum | 129,727 msg/s |