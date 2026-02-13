package com.chatflow.client;

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

  // Timing for Little's Law
  private final ConcurrentLinkedQueue<Long> roundTripTimes = new ConcurrentLinkedQueue<>();
  private final AtomicLong totalSendTime = new AtomicLong(0);
  private final AtomicInteger sampledMessages = new AtomicInteger(0);

  // Phase timing
  private long warmupStartTime = 0;
  private long warmupEndTime = 0;
  private long mainPhaseStartTime = 0;
  private long mainPhaseEndTime = 0;

  // Sample every Nth message for RTT to avoid overhead
  private static final int SAMPLE_RATE = 100;

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

  public void recordMessageTiming(long sendTimeNanos, long receiveTimeNanos) {
    // Sample to avoid memory issues
    if (sampledMessages.incrementAndGet() % SAMPLE_RATE == 0) {
      long rtt = receiveTimeNanos - sendTimeNanos;
      roundTripTimes.offer(rtt);
      totalSendTime.addAndGet(rtt);
    }
  }

  // Phase timing
  public void startWarmup() { warmupStartTime = System.nanoTime(); }
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

  public double getAverageRTTMs() {
    if (sampledMessages.get() == 0) return 0;
    return (totalSendTime.get() / 1_000_000.0) / (sampledMessages.get() / SAMPLE_RATE);
  }

  public double getMedianRTTMs() {
    if (roundTripTimes.isEmpty()) return 0;
    Long[] rtts = roundTripTimes.toArray(new Long[0]);
    java.util.Arrays.sort(rtts);
    int mid = rtts.length / 2;
    return rtts[mid] / 1_000_000.0;
  }

  public double get99thPercentileRTTMs() {
    if (roundTripTimes.isEmpty()) return 0;
    Long[] rtts = roundTripTimes.toArray(new Long[0]);
    java.util.Arrays.sort(rtts);
    int idx = (int) (rtts.length * 0.99);
    return rtts[idx] / 1_000_000.0;
  }
}