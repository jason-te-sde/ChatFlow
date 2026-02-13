# ChatFlow WebSocket Load Testing Project

## Project Overview
High-performance WebSocket chat server and multithreaded load testing client capable of handling 500,000 messages with detailed performance analysis.

## Repository Structure
- [`/server`](https://github.com/jason-te-sde/ChatFlow/tree/main/server) - WebSocket server implementation
- [`/client-part1`](https://github.com/jason-te-sde/ChatFlow/tree/main/client-part1) - Basic load testing client (Part 1)
- [`/client-part2`](https://github.com/jason-te-sde/ChatFlow/tree/main/client-part2) - Enhanced client with detailed metrics (Part 2)
- [`/results`](https://github.com/jason-te-sde/ChatFlow/tree/main/results) - Test results, charts, and analysis

## Quick Start

### Prerequisites
- Java 17 or higher
- Maven 3.6+
- AWS account (for Ubuntu deployment)

### Running Locally

1. **Start Server:**
```bash
   cd server
   mvn clean package
   java -jar target/websocket-server-1.0-SNAPSHOT.jar
```

2. **Run Part 1 Client:**
```bash
   cd client-part1
   mvn clean package
   java -jar target/chatflow-client.jar ws://localhost:8080
```

3. **Run Part 2 Client:**
```bash
   cd client-part2
   mvn clean package
   java -jar websocket-client-1.0-SNAPSHOT.jar ws://localhost:8080
```

### Running on Server (AWS Ubuntu)

1. **Start Server:**
```bash
   cd server
   mvn clean package
   scp -i ~/you-server.pem target/websocket-server-1.0-SNAPSHOT.jar ubuntu@your-server-ip:~/
   java -jar websocket-server-1.0-SNAPSHOT.jar 8080
```

2. **Run Part 1 Client:**
```bash
   cd client-part1
   mvn clean package
   java -jar target/chatflow-client.jar ws://your-server-ip:8080
```

3. **Run Part 2 Client:**
```bash
   cd client-part2
   mvn clean package
   java -jar websocket-client-1.0-SNAPSHOT.jar ws://your-server-ip:8080
```
## Key Features
- ✅ WebSocket server with room-based routing
- ✅ Multi-threaded client with connection pooling
- ✅ Comprehensive validation and error handling
- ✅ Real-time performance metrics and analysis
- ✅ CSV export for visualization
- ✅ Little's Law analysis

## Author
[Jason Te]
[te.r@northeastern.edu]

