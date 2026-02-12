package com.chatflow.client;

import com.chatflow.client.metrics.MetricsTracker;
import com.chatflow.client.metrics.MetricsTracker.Statistics;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class EnhancedLoadTestClient {
  private static final int TOTAL_MESSAGES = 500_000;
  private static final int WARMUP_THREADS = 32;
  private static final int MESSAGES_PER_WARMUP_THREAD = 1000;
  private static final int WARMUP_TOTAL = WARMUP_THREADS * MESSAGES_PER_WARMUP_THREAD;
  private static final int MAIN_PHASE_MESSAGES = TOTAL_MESSAGES - WARMUP_TOTAL;

  private final String serverUrl;
  private final BlockingQueue<MessageWrapper> messageQueue;
  private final AtomicInteger successCount;
  private final AtomicInteger failureCount;
  private final MetricsTracker metricsTracker;

  public EnhancedLoadTestClient(String serverUrl) {
    this.serverUrl = serverUrl;
    this.messageQueue = new LinkedBlockingQueue<>(100000);
    this.successCount = new AtomicInteger(0);
    this.failureCount = new AtomicInteger(0);
    this.metricsTracker = new MetricsTracker();
  }

  public void runLoadTest() {
    System.out.println("=".repeat(70));
    System.out.println("ChatFlow Enhanced Load Test Client with Performance Metrics");
    System.out.println("=".repeat(70));
    System.out.println("Server URL: " + serverUrl);
    System.out.println("Total Messages: " + TOTAL_MESSAGES);
    System.out.println("=".repeat(70));

    long startTime = System.currentTimeMillis();

    Thread generatorThread = new Thread(
        new MessageGenerator(messageQueue, TOTAL_MESSAGES)
    );
    generatorThread.start();

    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    System.out.println("\n--- WARMUP PHASE ---");
    long warmupStart = System.currentTimeMillis();
    runWarmupPhase();
    long warmupEnd = System.currentTimeMillis();

    System.out.println("\n--- MAIN PHASE ---");
    long mainStart = System.currentTimeMillis();
    runMainPhase();
    long mainEnd = System.currentTimeMillis();

    try {
      generatorThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    long totalDuration = System.currentTimeMillis() - startTime;

    printResults(totalDuration, warmupEnd - warmupStart, mainEnd - mainStart);

    try {
      metricsTracker.writeToCSV("metrics.csv");
      generateThroughputChart();
      System.out.println("\nMetrics and charts saved successfully!");
    } catch (IOException e) {
      System.err.println("Error writing metrics: " + e.getMessage());
    }
  }

  private void runWarmupPhase() {
    ExecutorService executor = Executors.newFixedThreadPool(WARMUP_THREADS);
    CountDownLatch warmupLatch = new CountDownLatch(WARMUP_THREADS);

    for (int i = 0; i < WARMUP_THREADS; i++) {
      executor.submit(new MetricsClientThread(
          serverUrl, messageQueue, MESSAGES_PER_WARMUP_THREAD,
          successCount, failureCount, warmupLatch, metricsTracker, i
      ));
    }

    try {
      warmupLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    executor.shutdown();
    try {
      executor.awaitTermination(5, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void runMainPhase() {
    int optimalThreads = Runtime.getRuntime().availableProcessors() * 4;
    optimalThreads = Math.min(optimalThreads, 128);

    System.out.println("Using " + optimalThreads + " threads for main phase");

    ExecutorService executor = Executors.newFixedThreadPool(optimalThreads);
    CountDownLatch mainLatch = new CountDownLatch(optimalThreads);

    int messagesPerThread = MAIN_PHASE_MESSAGES / optimalThreads;
    int remainder = MAIN_PHASE_MESSAGES % optimalThreads;

    for (int i = 0; i < optimalThreads; i++) {
      int messagesToSend = messagesPerThread + (i < remainder ? 1 : 0);
      executor.submit(new MetricsClientThread(
          serverUrl, messageQueue, messagesToSend,
          successCount, failureCount, mainLatch, metricsTracker,
          i + WARMUP_THREADS
      ));
    }

    try {
      mainLatch.await();
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

  private void printResults(long totalDuration, long warmupDuration, long mainDuration) {
    Statistics stats = metricsTracker.calculateStatistics();

    System.out.println("\n" + "=".repeat(70));
    System.out.println("PERFORMANCE RESULTS");
    System.out.println("=".repeat(70));

    System.out.println("\n--- MESSAGE STATISTICS ---");
    System.out.println("Successful messages: " + successCount.get());
    System.out.println("Failed messages: " + failureCount.get());
    System.out.println("Total messages: " + TOTAL_MESSAGES);
    System.out.println("Success rate: " +
        String.format("%.2f%%", (successCount.get() * 100.0 / TOTAL_MESSAGES)));

    System.out.println("\n--- LATENCY STATISTICS (milliseconds) ---");
    System.out.println(String.format("Mean response time: %.2f ms", stats.mean));
    System.out.println("Median response time: " + stats.median + " ms");
    System.out.println("95th percentile: " + stats.p95 + " ms");
    System.out.println("99th percentile: " + stats.p99 + " ms");
    System.out.println("Min response time: " + stats.min + " ms");
    System.out.println("Max response time: " + stats.max + " ms");

    System.out.println("\n--- TIMING ---");
    System.out.println("Warmup duration: " + warmupDuration + " ms");
    System.out.println("Main phase duration: " + mainDuration + " ms");
    System.out.println("Total runtime: " + totalDuration + " ms (" +
        (totalDuration / 1000.0) + " seconds)");

    System.out.println("\n--- THROUGHPUT ---");
    System.out.println("Overall throughput: " +
        String.format("%.2f", (TOTAL_MESSAGES * 1000.0 / totalDuration)) +
        " messages/second");

    System.out.println("\n--- MESSAGE TYPE DISTRIBUTION ---");
    for (Map.Entry<String, Integer> entry : stats.typeCounts.entrySet()) {
      System.out.println(entry.getKey() + ": " + entry.getValue() + " messages");
    }

    System.out.println("\n--- TOP 5 ROOMS BY MESSAGE COUNT ---");
    stats.roomCounts.entrySet().stream()
        .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
        .limit(5)
        .forEach(entry -> System.out.println("Room " + entry.getKey() +
            ": " + entry.getValue() + " messages"));

    System.out.println("\n" + "=".repeat(70));
  }

  private void generateThroughputChart() throws IOException {
    Map<Long, Integer> throughput = metricsTracker.getThroughputOverTime();

    try (FileWriter writer = new FileWriter("throughput.csv")) {
      writer.write("time_bucket,messages_per_10s\n");
      for (Map.Entry<Long, Integer> entry : throughput.entrySet()) {
        writer.write(entry.getKey() + "," + entry.getValue() + "\n");
      }
    }

    System.out.println("Throughput data written to throughput.csv");
    System.out.println("You can visualize this with tools like Excel, Python, or R");
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