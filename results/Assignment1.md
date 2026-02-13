# ChatFlow WebSocket Load Testing Project

## Project Overview
High-performance WebSocket chat server and multithreaded load testing client capable of handling 500,000 messages with detailed performance analysis.

## Git Repository URL
- [`/server`](https://github.com/jason-te-sde/ChatFlow/tree/main/server) - WebSocket server implementation
- [`/client-part1`](https://github.com/jason-te-sde/ChatFlow/tree/main/client-part1) - Basic load testing client (Part 1)
- [`/client-part2`](https://github.com/jason-te-sde/ChatFlow/tree/main/client-part2) - Enhanced client with detailed metrics (Part 2)
- [`/results`](https://github.com/jason-te-sde/ChatFlow/tree/main/results) - Test results, charts, and analysis
- [`README.md`](https://github.com/jason-te-sde/ChatFlow/blob/main/README.md)
- [`Server Readme.md`](https://github.com/jason-te-sde/ChatFlow/blob/main/README.md)
- [`Client Readme.md`](https://github.com/jason-te-sde/ChatFlow/blob/main/client-part1/README.md)

## Design Document

### 1. Architecture diagram
<img src="System Architecture Diagram.png" height="500">

### 2. Major Classes and Their Relationships
<img src="Major Classes and Their Relationships.png" height="500">

### 3. Threading Model Explanation
<img src="Threading Model Explanation.png" height="500">

### 4. WebSocket Connection Management Strategy
<img src="WebSocket Connection Management Strategy.png" height="500">

### 5. Little's Law calculations and predictions
- Little's Law Formula Visualization

<img src="Little's Law calculations and predictions1.png" width="400">

- Calculation Example with Actual Results

<img src="Little's Law calculations and predictions2.png" height="600">

## Test Results

### Screenshot of Part 1 output (basic metrics)

<img src="part1-server-test1.png" width="300">
<img src="part1-server-test2.png" width="300" >


### Screenshot of Part 2 output (detailed metrics)

<img src="part2-server-test1.png" width="300">
<img src="part2-server-test2.png" width="300">

### Performance analysis charts
![throughput-chart.png](throughput-chart.png)

### Evidence of EC2 deployment (EC2 console screenshot)
![ubuntu-deployment.png](ubuntu-deployment.png)
![ubuntu-deployment2.png](ubuntu-deployment2.png)
![ubuntu-deployment3.png](ubuntu-deployment3.png)
