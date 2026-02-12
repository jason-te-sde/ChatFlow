package com.chatflow.client.metrics;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MetricsTracker {
  private final Queue<MessageMetric> metrics;
  private final Map<Integer, Integer> roomMessageCount;
  private final Map<String, Integer> messageTypeCount;

  public MetricsTracker() {
    this.metrics = new ConcurrentLinkedQueue<>();
    this.roomMessageCount = new ConcurrentHashMap<>();
    this.messageTypeCount = new ConcurrentHashMap<>();
  }

  public void recordMetric(long sendTime, long receiveTime, String messageType,
      int statusCode, int roomId) {
    long latency = receiveTime - sendTime;
    MessageMetric metric = new MessageMetric(sendTime, messageType, latency,
        statusCode, roomId);
    metrics.add(metric);

    roomMessageCount.merge(roomId, 1, Integer::sum);
    messageTypeCount.merge(messageType, 1, Integer::sum);
  }

  public void writeToCSV(String filename) throws IOException {
    try (FileWriter writer = new FileWriter(filename)) {
      writer.write("timestamp,messageType,latency,statusCode,roomId\n");

      for (MessageMetric metric : metrics) {
        writer.write(String.format("%d,%s,%d,%d,%d\n",
            metric.timestamp,
            metric.messageType,
            metric.latency,
            metric.statusCode,
            metric.roomId
        ));
      }
    }
    System.out.println("Metrics written to " + filename);
  }

  public Statistics calculateStatistics() {
    if (metrics.isEmpty()) {
      return new Statistics();
    }

    List<Long> latencies = new ArrayList<>();
    for (MessageMetric metric : metrics) {
      latencies.add(metric.latency);
    }

    Collections.sort(latencies);

    long sum = 0;
    long min = latencies.get(0);
    long max = latencies.get(latencies.size() - 1);

    for (long latency : latencies) {
      sum += latency;
    }

    double mean = sum / (double) latencies.size();
    long median = latencies.get(latencies.size() / 2);
    long p95 = latencies.get((int) (latencies.size() * 0.95));
    long p99 = latencies.get((int) (latencies.size() * 0.99));

    return new Statistics(mean, median, p95, p99, min, max,
        roomMessageCount, messageTypeCount);
  }

  public Map<Long, Integer> getThroughputOverTime() {
    Map<Long, Integer> throughput = new TreeMap<>();

    for (MessageMetric metric : metrics) {
      long bucket = (metric.timestamp / 10000) * 10000;
      throughput.merge(bucket, 1, Integer::sum);
    }

    return throughput;
  }

  private static class MessageMetric {
    final long timestamp;
    final String messageType;
    final long latency;
    final int statusCode;
    final int roomId;

    MessageMetric(long timestamp, String messageType, long latency,
        int statusCode, int roomId) {
      this.timestamp = timestamp;
      this.messageType = messageType;
      this.latency = latency;
      this.statusCode = statusCode;
      this.roomId = roomId;
    }
  }

  public static class Statistics {
    public final double mean;
    public final long median;
    public final long p95;
    public final long p99;
    public final long min;
    public final long max;
    public final Map<Integer, Integer> roomCounts;
    public final Map<String, Integer> typeCounts;

    public Statistics() {
      this(0, 0, 0, 0, 0, 0, new HashMap<>(), new HashMap<>());
    }

    public Statistics(double mean, long median, long p95, long p99, long min,
        long max, Map<Integer, Integer> roomCounts,
        Map<String, Integer> typeCounts) {
      this.mean = mean;
      this.median = median;
      this.p95 = p95;
      this.p99 = p99;
      this.min = min;
      this.max = max;
      this.roomCounts = roomCounts;
      this.typeCounts = typeCounts;
    }
  }
}