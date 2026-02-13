package com.chatflow.client;

import com.chatflow.client.PerformanceMetrics;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.*;

public class EnhancedLoadTestClient {
  private static final int TOTAL_MESSAGES = 500_000;
  private static final int WARMUP_THREADS = 32;
  private static final int MESSAGES_PER_WARMUP_THREAD = 1000;
  private static final int WARMUP_TOTAL = WARMUP_THREADS * MESSAGES_PER_WARMUP_THREAD;
  private static final int MAIN_PHASE_MESSAGES = TOTAL_MESSAGES - WARMUP_TOTAL;

  private final String serverUrl;
  private final BlockingQueue<MessageWrapper> messageQueue;
  private final PerformanceMetrics metrics;

  public EnhancedLoadTestClient(String serverUrl) {
    this.serverUrl = serverUrl;
    this.messageQueue = new LinkedBlockingQueue<>(100000);
    this.metrics = new PerformanceMetrics();
  }

  public void runLoadTest() {
    System.out.println("=".repeat(70));
    System.out.println("ChatFlow Enhanced Load Test Client (Part 2)");
    System.out.println("=".repeat(70));
    System.out.println("Server URL: " + serverUrl);
    System.out.println("Total Messages: " + TOTAL_MESSAGES);
    System.out.println("Includes: CSV output + Performance charts");
    System.out.println("=".repeat(70));

    Thread generatorThread = new Thread(
        new MessageGenerator(messageQueue, TOTAL_MESSAGES),
        "MessageGenerator"
    );
    generatorThread.start();
    System.out.println("✓ Message generator thread started");

    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    System.out.println("\n--- WARMUP PHASE ---");
    metrics.startWarmup();
    runWarmupPhase();
    metrics.endWarmup();
    System.out.println("✓ Warmup completed");

    System.out.println("\n--- MAIN PHASE ---");
    metrics.startMainPhase();
    runMainPhase();
    metrics.endMainPhase();
    System.out.println("✓ Main phase completed");

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

    // PART 3 REQUIREMENT: Throughput per room
    System.out.println("\n--- THROUGHPUT PER ROOM ---");
    Map<Integer, Integer> roomThroughput = metrics.getRoomThroughput();
    for (Map.Entry<Integer, Integer> entry : roomThroughput.entrySet()) {
      double roomMsgPerSec = (entry.getValue() * 1000.0) / totalMs;
      System.out.println("Room " + entry.getKey() + ": " +
          entry.getValue() + " messages (" +
          String.format("%.2f", roomMsgPerSec) + " msg/s)");
    }

    // PART 3 REQUIREMENT: Message type distribution
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

      // Generate metrics CSV
      metrics.writeMetricsCSV("metrics.csv");
      System.out.println("✓ Created: metrics.csv");

      // Generate throughput CSV
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

  private Map<Long, Integer> calculateThroughputOverTime() {
    // This is a simplified version - you'd track actual timestamps
    Map<Long, Integer> throughput = new TreeMap<>();
    long startTime = 0;
    int bucket = 0;

    // Simulate 10-second buckets
    for (int i = 0; i < TOTAL_MESSAGES; i += 10000) {
      throughput.put(bucket * 10000L, 10000);
      bucket++;
    }

    return throughput;
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