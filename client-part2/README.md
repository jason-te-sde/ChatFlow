# ChatFlow Load Test Client

## Building

### Part 1 (Basic Load Testing)
```bash
cd client-part1
mvn clean package
```

### Part 2 (With Performance Metrics)
```bash
cd client-part2
mvn clean package
```

## Running

### Part 1 - Basic Load Test

```bash
java -jar target/websocket-client-1.0-SNAPSHOT.jar [server-url]
```

Example:
```bash
# Local testing
java -jar target/websocket-client-1.0-SNAPSHOT.jar ws://localhost:8080

# EC2 testing
java -jar target/websocket-client-1.0-SNAPSHOT.jar ws://your-ec2-ip:8080
```

### Part 2 - Enhanced with Metrics

```bash
cd client-part2
java -jar target/websocket-client-1.0-SNAPSHOT.jar [server-url]
```

This generates:
- `metrics.csv` - Per-message latency data
- `throughput.csv` - Throughput over time

## Test Configuration

- **Total Messages:** 500,000
- **Warmup Phase:** 32 threads × 1,000 messages = 32,000 messages
- **Main Phase:** Remaining 468,000 messages
- **Optimal Threads:** CPU cores × 4 (capped at 128)
- **Retry Logic:** Up to 5 retries with exponential backoff

## Output Metrics

### Part 1 Output:
- Successful messages
- Failed messages
- Total runtime
- Overall throughput (messages/second)
- Warmup vs main phase performance

### Part 2 Output:
All Part 1 metrics plus:
- Mean, median, 95th, 99th percentile latencies
- Min/max response times
- Message type distribution
- Per-room throughput
- Detailed CSV files for analysis

## Performance Analysis

### Visualizing Results

#### Using Python:
```python
import pandas as pd
import matplotlib.pyplot as plt

# Load throughput data
df = pd.read_csv('throughput.csv')
plt.plot(df['time_bucket'], df['messages_per_10s'])
plt.xlabel('Time (ms)')
plt.ylabel('Messages per 10 seconds')
plt.title('Throughput Over Time')
plt.savefig('throughput_chart.png')
```

#### Using Excel:
1. Open `throughput.csv`
2. Select both columns
3. Insert → Line Chart
4. Format as needed

## Little's Law Analysis

### Prediction

Before running, measure single message RTT:
```bash
# Use wscat to manually test
wscat -c ws://your-server:8080/chat/1
# Send message and measure response time
```

**Little's Law:** L = λ × W

Where:
- L = Number of threads (connections)
- λ = Throughput (messages/second)
- W = Average latency (seconds)

Example calculation:
- Average RTT: 50ms = 0.05s
- Target throughput: 10,000 msg/s
- Required connections: L = 10,000 × 0.05 = 500 threads

### Actual Results Comparison

Compare predicted vs actual throughput in your report.

## Architecture

### Components

1. **MessageGenerator**
    - Single thread generating all 500K messages
    - Places messages in thread-safe queue
    - Uses pre-defined message pool for efficiency

2. **ChatClientThread** (Part 1) / **MetricsClientThread** (Part 2)
    - Multiple worker threads
    - Pull messages from queue
    - Establish WebSocket connections
    - Send messages and handle responses
    - Implement retry logic with exponential backoff

3. **MetricsTracker** (Part 2 only)
    - Records per-message timestamps
    - Calculates statistical metrics
    - Generates CSV outputs

### Threading Model

```
[MessageGenerator Thread]
         ↓
[Thread-Safe Queue]
         ↓
[Worker Thread Pool]
  - Thread 1 → WebSocket → Server
  - Thread 2 → WebSocket → Server
  - Thread N → WebSocket → Server
         ↓
[MetricsTracker] (Part 2)
```

## Troubleshooting

### Connection Refused
- Verify server is running
- Check firewall/security group settings
- Confirm correct IP and port

### Low Throughput
- Increase thread pool size
- Check network latency
- Verify server isn't CPU-bound
- Monitor memory usage

### OutOfMemoryError
- Reduce queue size in code
- Increase JVM heap: `java -Xmx2g -jar ...`
- Reduce concurrent threads

### High Failure Rate
- Check retry logic
- Verify message validation
- Review server logs
- Check network stability

## Performance Tips


1. **Connection Pooling**: Threads reuse WebSocket connections
2. **Queue Size**: Balanced to avoid memory issues
3. **Thread Count**: Optimal = CPU cores × 4
4. **Retry Strategy**: Exponential backoff prevents server overload
5. **Message Generation**: Separate thread prevents blocking