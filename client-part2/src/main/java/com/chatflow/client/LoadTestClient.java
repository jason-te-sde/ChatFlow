package com.chatflow.client;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadTestClient {
  private static final int TOTAL_MESSAGES = 500_000;
  private static final int WARMUP_THREADS = 32;
  private static final int MESSAGES_PER_WARMUP_THREAD = 1000;
  private static final int WARMUP_TOTAL = WARMUP_THREADS * MESSAGES_PER_WARMUP_THREAD;
  private static final int MAIN_PHASE_MESSAGES = TOTAL_MESSAGES - WARMUP_TOTAL;

  private final String serverUrl;
  private final BlockingQueue<MessageWrapper> messageQueue;
  private final AtomicInteger successCount;
  private final AtomicInteger failureCount;
  private final AtomicInteger reconnectCount;

  public LoadTestClient(String serverUrl) {
    this.serverUrl = serverUrl;
    this.messageQueue = new LinkedBlockingQueue<>(100000);
    this.successCount = new AtomicInteger(0);
    this.failureCount = new AtomicInteger(0);
    this.reconnectCount = new AtomicInteger(0);
  }

  public void runLoadTest() {
    System.out.println("=".repeat(60));
    System.out.println("ChatFlow Load Test Client");
    System.out.println("=".repeat(60));
    System.out.println("Server URL: " + serverUrl);
    System.out.println("Total Messages: " + TOTAL_MESSAGES);
    System.out.println("Warmup: " + WARMUP_THREADS + " threads x " +
        MESSAGES_PER_WARMUP_THREAD + " messages = " + WARMUP_TOTAL);
    System.out.println("Main Phase: " + MAIN_PHASE_MESSAGES + " messages");
    System.out.println("=".repeat(60));

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
    long warmupDuration = warmupEnd - warmupStart;

    System.out.println("\nWarmup completed in " + warmupDuration + " ms");
    System.out.println("Warmup throughput: " +
        String.format("%.2f", (WARMUP_TOTAL * 1000.0 / warmupDuration)) +
        " messages/second");

    System.out.println("\n--- MAIN PHASE ---");
    long mainStart = System.currentTimeMillis();
    runMainPhase();
    long mainEnd = System.currentTimeMillis();
    long mainDuration = mainEnd - mainStart;

    try {
      generatorThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    long totalDuration = System.currentTimeMillis() - startTime;

    printResults(totalDuration, warmupDuration, mainDuration);
  }

  private void runWarmupPhase() {
    ExecutorService executor = Executors.newFixedThreadPool(WARMUP_THREADS);
    CountDownLatch warmupLatch = new CountDownLatch(WARMUP_THREADS);

    for (int i = 0; i < WARMUP_THREADS; i++) {
      executor.submit(new ChatClientThread(
          serverUrl, messageQueue, MESSAGES_PER_WARMUP_THREAD,
          successCount, failureCount, warmupLatch, i
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
      executor.submit(new ChatClientThread(
          serverUrl, messageQueue, messagesToSend,
          successCount, failureCount, mainLatch, i + WARMUP_THREADS
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
    System.out.println("\n" + "=".repeat(60));
    System.out.println("LOAD TEST RESULTS");
    System.out.println("=".repeat(60));
    System.out.println("Successful messages: " + successCount.get());
    System.out.println("Failed messages: " + failureCount.get());
    System.out.println("Total messages attempted: " + TOTAL_MESSAGES);
    System.out.println("Success rate: " +
        String.format("%.2f%%", (successCount.get() * 100.0 / TOTAL_MESSAGES)));
    System.out.println();
    System.out.println("Warmup duration: " + warmupDuration + " ms");
    System.out.println("Main phase duration: " + mainDuration + " ms");
    System.out.println("Total runtime: " + totalDuration + " ms (" +
        (totalDuration / 1000.0) + " seconds)");
    System.out.println();
    System.out.println("Overall throughput: " +
        String.format("%.2f", (TOTAL_MESSAGES * 1000.0 / totalDuration)) +
        " messages/second");
    System.out.println("Main phase throughput: " +
        String.format("%.2f", (MAIN_PHASE_MESSAGES * 1000.0 / mainDuration)) +
        " messages/second");
    System.out.println("=".repeat(60));
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