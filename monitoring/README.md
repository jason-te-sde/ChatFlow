# Monitoring — ChatFlow Assignment 2

## Scripts

### 1. queue_monitor.sh — Poll RabbitMQ queue depths every 5 seconds

```bash
#!/bin/bash
# Usage: ./queue_monitor.sh <rabbitmq-host> <username> <password>
HOST=${1:-localhost}
USER=${2:-chatflow}
PASS=${3:-chatflow123}

echo "timestamp,queue,ready,unacked,total"
while true; do
  TS=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  curl -s -u "$USER:$PASS" \
    "http://$HOST:15672/api/queues" | \
    python3 -c "
import sys, json
data = json.load(sys.stdin)
for q in data:
    print(f\"{sys.argv[1]},{q['name']},{q.get('messages_ready',0)},{q.get('messages_unacknowledged',0)},{q.get('messages',0)}\")
" "$TS"
  sleep 5
done
```

### 2. server_metrics.sh — Poll server health and connection count

```bash
#!/bin/bash
# Usage: ./server_metrics.sh <server-host> <port>
HOST=${1:-localhost}
PORT=${2:-8080}

while true; do
  TS=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://$HOST:$PORT/health")
  echo "$TS health=$STATUS"
  sleep 10
done
```

### 3. consumer_metrics.sh — Tail consumer log for processed count

```bash
#!/bin/bash
# Usage: ./consumer_metrics.sh
grep "Processed" ~/consumer.log | tail -20
```

## How to use during load test

Open 3 terminal tabs during client run:

```bash
# Tab 1: monitor queue depths
./queue_monitor.sh <rabbitmq-public-ip> chatflow chatflow123 | tee queue_depths.csv

# Tab 2: monitor server 1
./server_metrics.sh <server1-public-ip> 8080

# Tab 3: watch consumer progress
ssh -i key.pem ubuntu@<consumer-ip> "tail -f consumer.log | grep Processed"
```

## RabbitMQ Management UI Metrics to Screenshot

During load test, capture these pages:

| Page | URL | What to capture |
|---|---|---|
| Overview | http://\<mq-ip\>:15672/#/ | Queued messages chart + Message rates chart |
| Queues | http://\<mq-ip\>:15672/#/queues | All rooms with incoming/deliver/ack rates |
| Connections | http://\<mq-ip\>:15672/#/connections | Active connections list |

## CloudWatch Metrics (ALB)

In AWS Console → EC2 → Load Balancers → chatflow-alb → Monitoring:

- RequestCount: total requests per instance
- HealthyHostCount: should equal target instance count
- TargetResponseTime: p50/p99 latency