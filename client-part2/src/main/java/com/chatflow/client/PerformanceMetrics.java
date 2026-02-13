package com.chatflow.client;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PerformanceMetrics {
  // Message counters
  private final AtomicInteger successCount = new AtomicInteger(0);
  private final AtomicInteger failureCount = new AtomicInteger(0);

  // Connection tracking
  private final AtomicInteger totalConnectionsCreated = new AtomicInteger(0);
  private final AtomicInteger reconnectionCount = new AtomicInteger(0);
  private final AtomicInteger activeConnections = new AtomicInteger(0);

  // ALL messages metrics - MUST track ALL for Part 3!
  private final ConcurrentLinkedQueue<DetailedMetric> allMetrics = new ConcurrentLinkedQueue<>();

  // Room and type tracking for statistical analysis
  private final ConcurrentHashMap<Integer, AtomicInteger> roomMessageCount = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicInteger> messageTypeCount = new ConcurrentHashMap<>();

  // Phase timing
  private long warmupStartTime = 0;
  private long warmupEndTime = 0;
  private long mainPhaseStartTime = 0;
  private long mainPhaseEndTime = 0;

  public void recordSuccess() {
    successCount.incrementAndGet();
  }

  public void recordFailure() {
    failureCount.incrementAndGet();
  }

  public void recordConnectionCreated() {
    totalConnectionsCreated.incrementAndGet();
    activeConnections.incrementAndGet();
  }

  public void recordConnectionClosed() {
    activeConnections.decrementAndGet();
  }

  public void recordReconnection() {
    reconnectionCount.incrementAndGet();
  }

  /**
   * Record detailed metrics for EVERY message (Part 3 requirement)
   */
  public void recordDetailedMetric(long timestamp, String messageType, long latencyMs,
      int statusCode, int roomId) {
    allMetrics.offer(new DetailedMetric(timestamp, messageType, latencyMs,
        statusCode, roomId));

    // Track per-room counts
    roomMessageCount.computeIfAbsent(roomId, k -> new AtomicInteger(0)).incrementAndGet();

    // Track per-type counts
    messageTypeCount.computeIfAbsent(messageType, k -> new AtomicInteger(0)).incrementAndGet();
  }

  // Phase timing
  public void startWarmup() {
    warmupStartTime = System.nanoTime();
  }

  public void endWarmup() { warmupEndTime = System.nanoTime(); }
  public void startMainPhase() { mainPhaseStartTime = System.nanoTime(); }
  public void endMainPhase() { mainPhaseEndTime = System.nanoTime(); }

  // Getters
  public int getSuccessCount() { return successCount.get(); }
  public int getFailureCount() { return failureCount.get(); }
  public int getTotalConnectionsCreated() { return totalConnectionsCreated.get(); }
  public int getReconnectionCount() { return reconnectionCount.get(); }
  public int getActiveConnections() { return activeConnections.get(); }

  public long getWarmupDurationMs() {
    return (warmupEndTime - warmupStartTime) / 1_000_000;
  }

  public long getMainPhaseDurationMs() {
    return (mainPhaseEndTime - mainPhaseStartTime) / 1_000_000;
  }

  public long getTotalDurationMs() {
    return (mainPhaseEndTime - warmupStartTime) / 1_000_000;
  }

  /**
   * Calculate statistics from ALL recorded metrics
   */
  public Statistics calculateStatistics() {
    if (allMetrics.isEmpty()) {
      return new Statistics();
    }

    List<Long> latencies = new ArrayList<>();
    for (DetailedMetric metric : allMetrics) {
      latencies.add(metric.latency);
    }

    Collections.sort(latencies);

    long sum = 0;
    for (long latency : latencies) {
      sum += latency;
    }

    double mean = sum / (double) latencies.size();
    long median = latencies.get(latencies.size() / 2);
    long p95 = latencies.get((int) (latencies.size() * 0.95));
    long p99 = latencies.get((int) (latencies.size() * 0.99));
    long min = latencies.get(0);
    long max = latencies.get(latencies.size() - 1);

    return new Statistics(mean, median, p95, p99, min, max);
  }

  /**
   * Get throughput per room
   */
  public Map<Integer, Integer> getRoomThroughput() {
    Map<Integer, Integer> result = new TreeMap<>();
    for (Map.Entry<Integer, AtomicInteger> entry : roomMessageCount.entrySet()) {
      result.put(entry.getKey(), entry.getValue().get());
    }
    return result;
  }

  /**
   * Get message type distribution
   */
  public Map<String, Integer> getMessageTypeDistribution() {
    Map<String, Integer> result = new HashMap<>();
    for (Map.Entry<String, AtomicInteger> entry : messageTypeCount.entrySet()) {
      result.put(entry.getKey(), entry.getValue().get());
    }
    return result;
  }

  /**
   * Write ALL metrics to CSV (Part 3 requirement)
   */
  public void writeMetricsCSV(String filename) throws IOException {
    try (FileWriter writer = new FileWriter(filename)) {
      writer.write("timestamp,messageType,latency,statusCode,roomId\n");

      int count = 0;
      for (DetailedMetric metric : allMetrics) {
        writer.write(String.format("%d,%s,%d,%d,%d\n",
            metric.timestamp,
            metric.messageType,
            metric.latency,
            metric.statusCode,
            metric.roomId
        ));
        count++;
      }
      System.out.println("✓ Wrote " + count + " message metrics to " + filename);
    }
  }

  /**
   * Calculate throughput over time in 10-second buckets
   */
  public Map<Long, Integer> getThroughputOverTime() {
    Map<Long, Integer> throughput = new TreeMap<>();

    for (DetailedMetric metric : allMetrics) {
      // 10-second buckets (10000 ms)
      long bucket = (metric.timestamp / 10000) * 10;
      throughput.merge(bucket, 1, Integer::sum);
    }

    return throughput;
  }

  /**
   * Inner class to store detailed metrics
   */
  private static class DetailedMetric {
    final long timestamp;
    final String messageType;
    final long latency;
    final int statusCode;
    final int roomId;

    DetailedMetric(long timestamp, String messageType, long latency,
        int statusCode, int roomId) {
      this.timestamp = timestamp;
      this.messageType = messageType;
      this.latency = latency;
      this.statusCode = statusCode;
      this.roomId = roomId;
    }
  }

  /**
   * Statistics holder
   */
  public static class Statistics {
    public final double mean;
    public final long median;
    public final long p95;
    public final long p99;
    public final long min;
    public final long max;

    public Statistics() {
      this(0, 0, 0, 0, 0, 0);
    }

    public Statistics(double mean, long median, long p95, long p99, long min, long max) {
      this.mean = mean;
      this.median = median;
      this.p95 = p95;
      this.p99 = p99;
      this.min = min;
      this.max = max;
    }
  }

  /**
   * Legacy method for compatibility - delegates to recordDetailedMetric
   */
  public void recordMessageTiming(long sendTimeNanos, long receiveTimeNanos) {
    // This method is now a no-op, all tracking done via recordDetailedMetric
    // Kept for backward compatibility
  }
}