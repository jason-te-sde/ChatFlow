package com.chatflow;

import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class LoadTestClient {

  static final String SERVER_URL  = "ws://chatflow-alb-1796650720.us-east-1.elb.amazonaws.com/chat/";
  static final int TOTAL_MESSAGES = 1_000_000;
  static final int WARMUP_THREADS = 32;
  static final int WARMUP_MSGS    = 1000;
  static final int MAIN_THREADS   = 128;

  static final AtomicLong sent    = new AtomicLong(0);
  static final AtomicLong failed  = new AtomicLong(0);
  static final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
  static final ConcurrentHashMap<Integer, AtomicLong> buckets = new ConcurrentHashMap<>();
  static final BlockingQueue<String> msgQueue = new ArrayBlockingQueue<>(50_000);

  static final String[] MSG_POOL = {
      "Hello everyone!", "How is it going?", "Great to be here",
      "Anyone around?", "Lets chat!", "Whats up?", "Good morning!",
      "Testing 1 2 3", "This is a message", "Chatting away",
      "Distributed systems are fun", "Assignment 2 in progress",
      "RabbitMQ is great", "WebSocket connected", "Spring Boot rocks",
      "Load testing now", "Message delivery confirmed", "Queue depth looks good",
      "Consumer lag is low", "All systems go", "Throughput is high",
      "Latency is low", "Retry logic works", "Connection pooling helps",
      "Thread pool optimized", "Channel pool ready", "Broadcast successful",
      "Room joined", "User connected", "User disconnected",
      "Keep it going", "Almost there", "500k messages coming",
      "Stable queue profile", "No message loss", "ALB routing works",
      "Sticky session active", "EC2 instances up", "Monitoring enabled",
      "Health check passed", "Circuit breaker ready", "Backpressure handled",
      "Prefetch count set", "Batch ack configured", "Consumer thread healthy",
      "Message acknowledged", "Delivery guaranteed", "Ordering preserved",
      "Room manager ready", "Session tracking active", "Metrics collected"
  };

  public static void main(String[] args) throws Exception {
    // Start message generator thread
    Thread gen = new Thread(LoadTestClient::generateMessages, "msg-gen");
    gen.setDaemon(true);
    gen.start();
    Thread.sleep(300); // let queue fill up a bit

    long wallStart = System.currentTimeMillis();

    // --- Warmup phase ---
    System.out.println("=== WARMUP PHASE ===");
    long warmupStart = System.currentTimeMillis();
    runPhase(WARMUP_THREADS, WARMUP_MSGS);
    long warmupMs = System.currentTimeMillis() - warmupStart;
    long warmupTotal = (long) WARMUP_THREADS * WARMUP_MSGS;
    System.out.printf("Warmup done: %d msgs in %dms (%.0f msg/s)%n",
        warmupTotal, warmupMs, warmupTotal * 1000.0 / warmupMs);

    // --- Main phase ---
    System.out.println("=== MAIN PHASE ===");
    int remaining     = TOTAL_MESSAGES - (int) warmupTotal;
    int msgsPerThread = remaining / MAIN_THREADS;
    runPhase(MAIN_THREADS, msgsPerThread);

    long totalMs = System.currentTimeMillis() - wallStart;
    printResults(totalMs);
  }

  static void runPhase(int nThreads, int msgsEach) throws InterruptedException {
    ExecutorService pool = Executors.newFixedThreadPool(nThreads);
    CountDownLatch latch = new CountDownLatch(nThreads);
    for (int i = 0; i < nThreads; i++) {
      pool.submit(() -> { runWorker(msgsEach); latch.countDown(); });
    }
    latch.await();
    pool.shutdown();
  }

  static void runWorker(int count) {
    Random rng   = new Random();
    int roomId   = rng.nextInt(20) + 1;
    ChatClient ws = null;
    try {
      ws = connect(SERVER_URL + roomId);
      if (ws == null) { failed.addAndGet(count); return; }

      for (int i = 0; i < count; i++) {
        String msg = msgQueue.poll(2, TimeUnit.SECONDS);
        if (msg == null) { failed.incrementAndGet(); continue; }

        // Reconnect if session closed
        if (!ws.isOpen()) {
          ws = connect(SERVER_URL + roomId);
          if (ws == null) { failed.incrementAndGet(); continue; }
        }

        long t0 = System.currentTimeMillis();
        try {
          ws.send(msg);
          sent.incrementAndGet();
          long lat = System.currentTimeMillis() - t0;
          latencies.add(lat);
          int bucket = (int)(t0 / 10_000);
          buckets.computeIfAbsent(bucket, k -> new AtomicLong()).incrementAndGet();
        } catch (Exception e) {
          failed.incrementAndGet();
        }
      }
    } catch (Exception e) {
      failed.addAndGet(count);
    } finally {
      if (ws != null) ws.close();
    }
  }

  static ChatClient connect(String url) {
    for (int attempt = 0; attempt < 5; attempt++) {
      try {
        ChatClient client = new ChatClient();
        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        WebSocketSession session = wsClient
            .execute(client, new WebSocketHttpHeaders(), URI.create(url))
            .get(5, TimeUnit.SECONDS);
        client.setSession(session);
        return client;
      } catch (Exception e) {
        try { Thread.sleep(200L * (attempt + 1)); }
        catch (InterruptedException ie) { break; }
      }
    }
    return null;
  }

  static void generateMessages() {
    Random rng = new Random();
    String[] types = new String[20];
    Arrays.fill(types, "TEXT");
    types[18] = "JOIN";
    types[19] = "LEAVE";
    while (!Thread.currentThread().isInterrupted()) {
      int userId = rng.nextInt(100_000) + 1;
      String msg = String.format(
          "{\"userId\":%d,\"username\":\"user%05d\",\"message\":\"%s\"," +
              "\"timestamp\":\"%s\",\"messageType\":\"%s\"}",
          userId, userId,
          MSG_POOL[rng.nextInt(MSG_POOL.length)],
          Instant.now(),
          types[rng.nextInt(20)]
      );
      try { msgQueue.put(msg); }
      catch (InterruptedException e) { break; }
    }
  }

  static void printResults(long totalMs) {
    long s = sent.get(), f = failed.get();
    System.out.println("\n========== RESULTS ==========");
    System.out.printf("Messages sent:    %d%n", s);
    System.out.printf("Messages failed:  %d%n", f);
    System.out.printf("Total time:       %.2f s%n", totalMs / 1000.0);
    System.out.printf("Throughput:       %.0f msg/s%n", s * 1000.0 / totalMs);

    if (!latencies.isEmpty()) {
      List<Long> sorted = new ArrayList<>(latencies);
      Collections.sort(sorted);
      int n = sorted.size();
      long sum = sorted.stream().mapToLong(Long::longValue).sum();
      System.out.printf("Mean latency:     %.1f ms%n", sum * 1.0 / n);
      System.out.printf("Median latency:   %d ms%n", sorted.get(n / 2));
      System.out.printf("p95 latency:      %d ms%n", sorted.get((int)(n * 0.95)));
      System.out.printf("p99 latency:      %d ms%n", sorted.get((int)(n * 0.99)));
      System.out.printf("Min/Max latency:  %d / %d ms%n", sorted.get(0), sorted.get(n - 1));
    }

    System.out.println("\n--- Throughput over time (10s buckets) ---");
    int minB = buckets.keySet().stream().min(Integer::compare).orElse(0);
    buckets.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> {
          long v   = e.getValue().get();
          int bars = (int)(v / 500);
          System.out.printf("t+%3ds | %-40s %d%n",
              (e.getKey() - minB) * 10,
              "#".repeat(Math.min(bars, 40)), v);
        });
    System.out.println("==============================");
  }
}

// WebSocket client wrapper using Spring's StandardWebSocketClient
class ChatClient extends TextWebSocketHandler {

  private volatile WebSocketSession session;
  private volatile boolean open = false;

  void setSession(WebSocketSession s) {
    this.session = s;
    this.open    = true;
  }

  void send(String msg) throws Exception {
    session.sendMessage(new TextMessage(msg));
  }

  boolean isOpen() {
    return open && session != null && session.isOpen();
  }

  void close() {
    try { if (session != null) session.close(); }
    catch (Exception ignored) {}
    open = false;
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus st) {
    open = false;
  }

  @Override
  protected void handleTextMessage(WebSocketSession s, TextMessage msg) {
    // fire-and-forget, ignore acks
  }
}