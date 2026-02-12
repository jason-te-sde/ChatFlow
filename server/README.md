# ChatFlow WebSocket Server

## Overview
WebSocket server implementation for the ChatFlow chat system. Handles real-time messaging with validation and room-based connections.

## Building

```bash
cd server
mvn clean package
```

This creates `target/websocket-server-1.0-SNAPSHOT.jar`

## Running Locally

```bash
java -jar target/websocket-server-1.0-SNAPSHOT.jar [port]
```

Default port is 8080. Example:
```bash
java -jar target/websocket-server-1.0-SNAPSHOT.jar 8080
```

## Testing with wscat

Install wscat:
```bash
npm install -g wscat
```

Test connection:
```bash
wscat -c ws://localhost:8080/chat/1
```

Send a message:
```json
{"userId":"123","username":"testuser","message":"Hello World","timestamp":"2026-02-11T12:00:00Z","messageType":"TEXT"}
```

## Health Check

```bash
curl http://localhost:8081/health
```

Expected response:
```json
{"status":"UP","service":"ChatFlow WebSocket Server"}
```

## AWS EC2 Deployment

### 1. Launch EC2 Instance
- AMI: Amazon Linux 2023 or Ubuntu 22.04
- Instance type: t2.micro (free tier)
- Region: us-west-2

### 2. Configure Security Group
Add inbound rules:
- Port 8080 (TCP) - WebSocket server
- Port 8081 (TCP) - Health endpoint
- Port 22 (TCP) - SSH

### 3. Connect and Install Java

For Amazon Linux:
```bash
ssh -i your-key.pem ec2-user@your-instance-ip
sudo yum update -y
sudo yum install java-11-amazon-corretto -y
```

For Ubuntu:
```bash
ssh -i your-key.pem ubuntu@your-instance-ip
sudo apt update
sudo apt install openjdk-11-jdk -y
```

### 4. Upload and Run Server

Upload JAR:
```bash
scp -i your-key.pem target/websocket-server-1.0-SNAPSHOT.jar ec2-user@your-instance-ip:~/
```

Run server in background:
```bash
nohup java -jar websocket-server-1.0-SNAPSHOT.jar 8080 > server.log 2>&1 &
```

### 5. Verify Deployment

```bash
# Check health
curl http://your-instance-ip:8081/health

# Test WebSocket (from local machine)
wscat -c ws://your-instance-ip:8080/chat/1
```

## API Specification

### WebSocket Endpoint

**URL:** `ws://<host>:<port>/chat/{roomId}`

**Message Format:**
```json
{
  "userId": "string (1-100000)",
  "username": "string (3-20 alphanumeric)",
  "message": "string (1-500 chars)",
  "timestamp": "ISO-8601 timestamp",
  "messageType": "TEXT|JOIN|LEAVE"
}
```

**Success Response:**
```json
{
  "status": "SUCCESS",
  "originalMessage": {...},
  "serverTimestamp": "2026-02-11T12:00:00.123Z",
  "roomId": "1"
}
```

**Error Response:**
```json
{
  "status": "ERROR",
  "message": "Validation error description",
  "serverTimestamp": "2026-02-11T12:00:00.123Z"
}
```

## Architecture

- **ChatServer**: Main WebSocket server handling connections
- **ChatMessage**: Message model with validation
- **HealthServer**: HTTP server for health checks
- Thread-safe message handling using ConcurrentHashMap
- Connection tracking per room

## Validation Rules

- userId: 1-100000
- username: 3-20 alphanumeric characters
- message: 1-500 characters
- timestamp: Valid ISO-8601 format
- messageType: TEXT, JOIN, or LEAVE