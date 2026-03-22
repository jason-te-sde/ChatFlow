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