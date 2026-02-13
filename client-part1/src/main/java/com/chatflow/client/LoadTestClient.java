package com.chatflow.client;

import java.util.concurrent.*;

public class LoadTestClient {
  private static final int TOTAL_MESSAGES = 500_000;
  private static final int WARMUP_THREADS = 32;
  private static final int MESSAGES_PER_WARMUP_THREAD = 1000;
  private static final int WARMUP_TOTAL = WARMUP_THREADS * MESSAGES_PER_WARMUP_THREAD;
  private static final int MAIN_PHASE_MESSAGES = TOTAL_MESSAGES - WARMUP_TOTAL;

  private final String serverUrl;
  private final BlockingQueue<MessageWrapper> messageQueue;
  private final PerformanceMetrics metrics;

  public LoadTestClient(String serverUrl) {
    this.serverUrl = serverUrl;
    this.messageQueue = new LinkedBlockingQueue<>(100000);
    this.metrics = new PerformanceMetrics();
  }

  public void runLoadTest() {
    System.out.println("=".repeat(70));
    System.out.println("ChatFlow Load Test Client - Accurate Metrics");
    System.out.println("=".repeat(70));
    System.out.println("Server URL: " + serverUrl);
    System.out.println("Total Messages: " + TOTAL_MESSAGES);
    System.out.println("=".repeat(70));

    // Start message generator
    MessageGenerator generator = new MessageGenerator(messageQueue, TOTAL_MESSAGES);
    Thread generatorThread = new Thread(generator, "MessageGenerator");
    generatorThread.start();
    System.out.println("✓ Message generator thread started");

    // Wait for initial messages to be generated
    System.out.println("Waiting for initial message buffer...");
    while (messageQueue.size() < 5000) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    System.out.println("Buffer ready with " + messageQueue.size() + " messages");

    // WARMUP PHASE
    System.out.println("\n--- WARMUP PHASE ---");
    metrics.startWarmup();
    runWarmupPhase();
    metrics.endWarmup();

    System.out.println("✓ Warmup completed");

    // MAIN PHASE
    System.out.println("\n--- MAIN PHASE ---");
    metrics.startMainPhase();
    runMainPhase();
    metrics.endMainPhase();

    System.out.println("✓ Main phase completed");

    // Wait for generator
    try {
      generatorThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    printAccurateResults();
  }

  private void runWarmupPhase() {
    ExecutorService executor = Executors.newFixedThreadPool(WARMUP_THREADS);
    CountDownLatch warmupLatch = new CountDownLatch(WARMUP_THREADS);

    for (int i = 0; i < WARMUP_THREADS; i++) {
      executor.submit(new WarmupClientThread(
          serverUrl,
          messageQueue,
          MESSAGES_PER_WARMUP_THREAD,
          metrics,
          warmupLatch,
          i
      ));
    }

    try {
      warmupLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    executor.shutdown();
    try {
      executor.awaitTermination(10, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void runMainPhase() {
    int optimalThreads = Math.min(Runtime.getRuntime().availableProcessors() * 8, 256);
    System.out.println("Using " + optimalThreads + " threads");

    ExecutorService executor = Executors.newFixedThreadPool(optimalThreads);
    CountDownLatch mainLatch = new CountDownLatch(optimalThreads);

    int messagesPerThread = MAIN_PHASE_MESSAGES / optimalThreads;
    int remainder = MAIN_PHASE_MESSAGES % optimalThreads;

    for (int i = 0; i < optimalThreads; i++) {
      int messagesToSend = messagesPerThread + (i < remainder ? 1 : 0);
      executor.submit(new MainPhaseClientThread(
          serverUrl,
          messageQueue,
          messagesToSend,
          metrics,
          mainLatch,
          i
      ));
    }

    try {
      mainLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    executor.shutdown();
    try {
      executor.awaitTermination(15, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void printAccurateResults() {
    System.out.println("\n" + "=".repeat(70));
    System.out.println("PERFORMANCE RESULTS - ACCURATE METRICS");
    System.out.println("=".repeat(70));

    // Get timing data
    long warmupMs = metrics.getWarmupDurationMs();
    long mainMs = metrics.getMainPhaseDurationMs();
    long totalMs = metrics.getTotalDurationMs();

    // 1. Message Statistics
    System.out.println("\n1. SUCCESSFUL MESSAGES: " + metrics.getSuccessCount());
    System.out.println("2. FAILED MESSAGES: " + metrics.getFailureCount());
    System.out.println("   Total: " + TOTAL_MESSAGES);
    System.out.println("   Success Rate: " +
        String.format("%.2f%%", (metrics.getSuccessCount() * 100.0 / TOTAL_MESSAGES)));

    // 3. Wall Time (actual sending time only)
    System.out.println("\n3. TOTAL RUNTIME (Wall Time):");
    System.out.println("   Total: " + totalMs + " ms (" +
        String.format("%.2f", totalMs / 1000.0) + " seconds)");
    System.out.println("   Warmup: " + warmupMs + " ms");
    System.out.println("   Main: " + mainMs + " ms");

    // 4. Throughput (messages/second)
    System.out.println("\n4. THROUGHPUT (messages/second):");
    double warmupThroughput = (WARMUP_TOTAL * 1000.0) / warmupMs;
    double mainThroughput = (MAIN_PHASE_MESSAGES * 1000.0) / mainMs;
    double overallThroughput = (TOTAL_MESSAGES * 1000.0) / totalMs;

    System.out.println("   Overall: " + String.format("%.2f", overallThroughput) + " msg/s");
    System.out.println("   Warmup: " + String.format("%.2f", warmupThroughput) + " msg/s");
    System.out.println("   Main Phase: " + String.format("%.2f", mainThroughput) + " msg/s");

    // 5. Connection Statistics
    int totalConns = metrics.getTotalConnectionsCreated();
    int reconnections = metrics.getReconnectionCount();

    System.out.println("\n5. CONNECTION STATISTICS:");
    System.out.println("   Total connections created: " + totalConns);
    System.out.println("   Reconnections: " + reconnections);
    System.out.println("   Messages per connection: " +
        String.format("%.2f", TOTAL_MESSAGES / (double)totalConns));
    System.out.println("   Connection reuse rate: " +
        String.format("%.2f%%",
            ((TOTAL_MESSAGES - totalConns) * 100.0 / TOTAL_MESSAGES)));

    // LITTLE'S LAW ANALYSIS - CORRECTED
    System.out.println("\n" + "=".repeat(70));
    System.out.println("LITTLE'S LAW ANALYSIS");
    System.out.println("=".repeat(70));

    double avgRTT = metrics.getAverageRTTMs();
    double medianRTT = metrics.getMedianRTTMs();
    double p99RTT = metrics.get99thPercentileRTTMs();

    System.out.println("\nMEASURED LATENCY (Round-Trip Time):");
    System.out.println("   Average RTT: " + String.format("%.2f", avgRTT) + " ms");
    System.out.println("   Median RTT: " + String.format("%.2f", medianRTT) + " ms");
    System.out.println("   99th percentile RTT: " + String.format("%.2f", p99RTT) + " ms");

    System.out.println("\nLITTLE'S LAW: L = λ × W");
    System.out.println("   Where:");
    System.out.println("     L = Average number of CONCURRENT connections");
    System.out.println("     λ = Throughput (messages/second)");
    System.out.println("     W = Average latency (seconds)");

    double avgLatencySec = avgRTT / 1000.0;
    double predictedConcurrent = overallThroughput * avgLatencySec;

    // Estimate actual concurrent connections based on thread count and rooms
    int optimalThreads = Math.min(Runtime.getRuntime().availableProcessors() * 8, 256);
    int estimatedConcurrent = Math.min(optimalThreads * 3, totalConns);

    System.out.println("\nPREDICTED CONCURRENT CONNECTIONS:");
    System.out.println("   λ (throughput) = " + String.format("%.2f", overallThroughput) + " msg/s");
    System.out.println("   W (avg latency) = " + String.format("%.4f", avgLatencySec) + " seconds");
    System.out.println("   L (predicted concurrent) = " + String.format("%.2f", predictedConcurrent));

    System.out.println("\nACTUAL MEASUREMENTS:");
    System.out.println("   Total connections created: " + totalConns);
    System.out.println("   Estimated peak concurrent: ~" + estimatedConcurrent);
    System.out.println("   (Based on " + optimalThreads + " threads × ~3 connections/thread)");

    System.out.println("\nINTERPRETATION:");
    if (totalConns < 1000) {
      System.out.println("   ✓ EXCELLENT: Connection pooling working efficiently");
      System.out.println("   ✓ Created only " + totalConns + " connections for 500K messages");
      System.out.println("   ✓ Average " + String.format("%.0f", TOTAL_MESSAGES / (double)totalConns) +
          " messages per connection");
    } else if (totalConns < 5000) {
      System.out.println("   ✓ GOOD: Reasonable connection management");
      System.out.println("   ~ Could improve by increasing connection reuse");
    } else {
      System.out.println("   ✗ POOR: Too many connections created");
      System.out.println("   ✗ High connection churn impacts performance");
      System.out.println("   → Recommendation: Improve connection pooling");
    }

    // Connection overhead calculation
    double avgConnectionOverhead = 20.0; // TCP handshake ~20ms typical
    double totalOverheadSec = (avgConnectionOverhead * totalConns) / 1000.0;
    double overheadPercent = (totalOverheadSec / (totalMs / 1000.0)) * 100;

    System.out.println("\nCONNECTION OVERHEAD ANALYSIS:");
    System.out.println("   Estimated handshake time: ~" +
        String.format("%.0f", avgConnectionOverhead) + " ms per connection");
    System.out.println("   Total connection overhead: ~" +
        String.format("%.2f", totalOverheadSec) + " seconds");
    System.out.println("   Overhead as % of total time: " +
        String.format("%.2f%%", overheadPercent));

    if (overheadPercent > 10) {
      System.out.println("   → High overhead! Reduce connection creation for better performance");
    }

    System.out.println("\nVALIDATION:");
    System.out.println("   Predicted " + String.format("%.0f", predictedConcurrent) +
        " concurrent connections needed");
    System.out.println("   With " + totalConns + " total created over " +
        String.format("%.2f", totalMs/1000.0) + " seconds");
    System.out.println("   This indicates good connection reuse if total < 1000");

    System.out.println("\n" + "=".repeat(70));
  }

  public static void main(String[] args) {
    String serverUrl = "ws://localhost:8080";

    if (args.length > 0) {
      serverUrl = args[0];
    }

    LoadTestClient client = new LoadTestClient(serverUrl);
    client.runLoadTest();
  }
}