package com.chatflow.client;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

public class EnhancedLoadTestClient {
  private static final int TOTAL_MESSAGES = 500_000;

  // WARMUP PHASE: Keep 32 threads as required by assignment
  private static final int WARMUP_THREADS = 32;
  private static final int MESSAGES_PER_WARMUP_THREAD = 1000;
  private static final int WARMUP_TOTAL = WARMUP_THREADS * MESSAGES_PER_WARMUP_THREAD;
  private static final int MAIN_PHASE_MESSAGES = TOTAL_MESSAGES - WARMUP_TOTAL;

  // OPTIMIZATION: Limit main phase threads to prevent overwhelming t2.micro
  private static final int MAX_MAIN_THREADS = 256;

  // OPTIMIZATION: Rate limiting to prevent server overload
  private static final int MESSAGES_PER_SECOND_LIMIT = 1000;

  private final String serverUrl;
  private final BlockingQueue<MessageWrapper> messageQueue;
  private final PerformanceMetrics metrics;
  private final RateLimiter rateLimiter;

  public EnhancedLoadTestClient(String serverUrl) {
    this.serverUrl = serverUrl;
    this.messageQueue = new LinkedBlockingQueue<>(100000);
    this.metrics = new PerformanceMetrics();
    this.rateLimiter = new RateLimiter(MESSAGES_PER_SECOND_LIMIT);
  }

  public void runLoadTest() {
    System.out.println("=".repeat(70));
    System.out.println("ChatFlow Enhanced Load Test - Optimized for t2.micro");
    System.out.println("=".repeat(70));
    System.out.println("Server URL: " + serverUrl);
    System.out.println("Total Messages: " + TOTAL_MESSAGES);
    System.out.println("Warmup: " + WARMUP_THREADS + " threads × " + MESSAGES_PER_WARMUP_THREAD + " msgs");
    System.out.println("Main Phase: Max " + MAX_MAIN_THREADS + " threads");
    System.out.println("Rate Limit: " + MESSAGES_PER_SECOND_LIMIT + " msg/s");
    System.out.println("=".repeat(70));

    // Start message generator thread
    Thread generatorThread = new Thread(
        new MessageGenerator(messageQueue, TOTAL_MESSAGES),
        "MessageGenerator"
    );
    generatorThread.start();
    System.out.println("✓ Message generator thread started");

    // Wait for initial message buffer to fill
    System.out.println("Building initial message buffer...");
    while (messageQueue.size() < 5000) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    System.out.println("✓ Buffer ready with " + messageQueue.size() + " messages");

    // WARMUP PHASE
    System.out.println("\n--- WARMUP PHASE ---");
    metrics.startWarmup();
    runWarmupPhase();
    metrics.endWarmup();
    System.out.println("✓ Warmup completed");

    // Pause to allow server recovery between phases
    System.out.println("\n⏸ Pausing 5 seconds to allow server recovery...");
    try {
      Thread.sleep(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // MAIN PHASE
    System.out.println("\n--- MAIN PHASE ---");
    metrics.startMainPhase();
    runMainPhase();
    metrics.endMainPhase();
    System.out.println("✓ Main phase completed");

    // Wait for generator to finish
    try {
      generatorThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    printResults();
    generateOutputFiles();
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
          i,
          rateLimiter  // Pass rate limiter to control send rate
      ));
    }

    try {
      warmupLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    executor.shutdown();
    try {
      executor.awaitTermination(30, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void runMainPhase() {
    // Limit thread count for t2.micro capacity
    int optimalThreads = Math.min(MAX_MAIN_THREADS,
        Runtime.getRuntime().availableProcessors() * 8);
    System.out.println("Using " + optimalThreads + " threads (optimized for t2.micro)");

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
          i,
          rateLimiter  // Pass rate limiter to control send rate
      ));
    }

    try {
      mainLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    executor.shutdown();
    try {
      executor.awaitTermination(30, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void printResults() {
    long warmupMs = metrics.getWarmupDurationMs();
    long mainMs = metrics.getMainPhaseDurationMs();
    long totalMs = metrics.getTotalDurationMs();

    PerformanceMetrics.Statistics stats = metrics.calculateStatistics();

    System.out.println("\n" + "=".repeat(70));
    System.out.println("PART 3: ENHANCED PERFORMANCE ANALYSIS");
    System.out.println("=".repeat(70));

    System.out.println("\n--- MESSAGE STATISTICS ---");
    System.out.println("Successful messages: " + metrics.getSuccessCount());
    System.out.println("Failed messages: " + metrics.getFailureCount());
    System.out.println("Total: " + TOTAL_MESSAGES);
    System.out.println("Success Rate: " +
        String.format("%.2f%%", (metrics.getSuccessCount() * 100.0 / TOTAL_MESSAGES)));

    System.out.println("\n--- TIMING ---");
    System.out.println("Total runtime: " + totalMs + " ms (" +
        String.format("%.2f", totalMs / 1000.0) + " seconds)");
    System.out.println("Warmup: " + warmupMs + " ms");
    System.out.println("Main: " + mainMs + " ms");

    System.out.println("\n--- THROUGHPUT ---");
    double overallThroughput = (TOTAL_MESSAGES * 1000.0) / totalMs;
    System.out.println("Overall: " + String.format("%.2f", overallThroughput) + " msg/s");

    System.out.println("\n--- LATENCY STATISTICS (milliseconds) ---");
    System.out.println("Mean response time: " + String.format("%.2f", stats.mean) + " ms");
    System.out.println("Median response time: " + stats.median + " ms");
    System.out.println("95th percentile: " + stats.p95 + " ms");
    System.out.println("99th percentile: " + stats.p99 + " ms");
    System.out.println("Min response time: " + stats.min + " ms");
    System.out.println("Max response time: " + stats.max + " ms");

    System.out.println("\n--- THROUGHPUT PER ROOM ---");
    Map<Integer, Integer> roomThroughput = metrics.getRoomThroughput();
    for (Map.Entry<Integer, Integer> entry : roomThroughput.entrySet()) {
      double roomMsgPerSec = (entry.getValue() * 1000.0) / totalMs;
      System.out.println("Room " + entry.getKey() + ": " +
          entry.getValue() + " messages (" +
          String.format("%.2f", roomMsgPerSec) + " msg/s)");
    }

    System.out.println("\n--- MESSAGE TYPE DISTRIBUTION ---");
    Map<String, Integer> typeDistribution = metrics.getMessageTypeDistribution();
    for (Map.Entry<String, Integer> entry : typeDistribution.entrySet()) {
      double percentage = (entry.getValue() * 100.0) / TOTAL_MESSAGES;
      System.out.println(entry.getKey() + ": " +
          entry.getValue() + " messages (" +
          String.format("%.2f%%", percentage) + ")");
    }

    System.out.println("\n--- CONNECTION STATISTICS ---");
    System.out.println("Total connections created: " + metrics.getTotalConnectionsCreated());
    System.out.println("Reconnections: " + metrics.getReconnectionCount());

    System.out.println("\n" + "=".repeat(70));
  }

  private void generateOutputFiles() {
    try {
      System.out.println("\n📊 Generating output files...");

      metrics.writeMetricsCSV("metrics.csv");
      System.out.println("✓ Created: metrics.csv");

      writeThroughputCSV();
      System.out.println("✓ Created: throughput.csv");

      System.out.println("\n📈 Visualization:");
      System.out.println("   Use Excel, Python, or R to create charts from CSV files");
      System.out.println("   Example: Open throughput.csv in Excel → Insert Line Chart");

    } catch (IOException e) {
      System.err.println("Error writing output files: " + e.getMessage());
    }
  }

  private void writeThroughputCSV() throws IOException {
    Map<Long, Integer> throughputMap = metrics.getThroughputOverTime();

    try (FileWriter writer = new FileWriter("throughput.csv")) {
      writer.write("time_seconds,messages_per_10_seconds\n");
      for (Map.Entry<Long, Integer> entry : throughputMap.entrySet()) {
        writer.write(entry.getKey() + "," + entry.getValue() + "\n");
      }
    }
  }

  public static void main(String[] args) {
    String serverUrl = "ws://localhost:8080";

    if (args.length > 0) {
      serverUrl = args[0];
    }

    EnhancedLoadTestClient client = new EnhancedLoadTestClient(serverUrl);
    client.runLoadTest();
  }
}