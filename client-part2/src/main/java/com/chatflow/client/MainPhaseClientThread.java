package com.chatflow.client;

import com.google.gson.Gson;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main phase thread - maintains persistent WebSocket connections per room
 * Uses rate limiting to prevent server overload
 * Optimized for sustained high-volume message sending
 */
public class MainPhaseClientThread implements Runnable {
  private final String serverUrl;
  private final BlockingQueue<MessageWrapper> messageQueue;
  private final int messagesToSend;
  private final PerformanceMetrics metrics;
  private final CountDownLatch completionLatch;
  private final Gson gson;
  private final int threadId;
  private final RateLimiter rateLimiter;  // Rate limiter to control send rate

  // Connection cache: persistent connections per room for this thread
  private final Map<Integer, ConnectionWithCallback> connectionCache;

  public MainPhaseClientThread(String serverUrl,
      BlockingQueue<MessageWrapper> messageQueue,
      int messagesToSend,
      PerformanceMetrics metrics,
      CountDownLatch completionLatch,
      int threadId,
      RateLimiter rateLimiter) {
    this.serverUrl = serverUrl;
    this.messageQueue = messageQueue;
    this.messagesToSend = messagesToSend;
    this.metrics = metrics;
    this.completionLatch = completionLatch;
    this.gson = new Gson();
    this.threadId = threadId;
    this.rateLimiter = rateLimiter;
    this.connectionCache = new HashMap<>();
  }

  @Override
  public void run() {
    int sent = 0;

    try {
      while (sent < messagesToSend) {
        // Rate limiting: acquire permit before sending
        rateLimiter.acquire();

        MessageWrapper wrapper = messageQueue.poll(5, TimeUnit.SECONDS);
        if (wrapper == null) continue;

        int roomId = wrapper.getRoomId();
        ConnectionWithCallback client = getOrCreateConnection(roomId);

        if (client == null) {
          metrics.recordFailure();
          sent++;
          continue;
        }

        sendMessage(client, wrapper, roomId);
        sent++;

        // Progress reporting (only from subset of threads to avoid spam)
        if (sent % 1000 == 0 && threadId % 5 == 0) {
          System.out.println("Thread " + threadId + " progress: " + sent + "/" + messagesToSend);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      // Close all persistent connections for this thread
      for (ConnectionWithCallback client : connectionCache.values()) {
        if (client != null && client.isOpen()) {
          client.close();
        }
      }
      completionLatch.countDown();
    }
  }

  /**
   * Get or create persistent connection for this room
   * Implements connection pooling per thread for efficiency
   */
  private ConnectionWithCallback getOrCreateConnection(int roomId) {
    ConnectionWithCallback client = connectionCache.get(roomId);

    if (client != null && client.isOpen()) {
      return client; // Reuse persistent connection
    }

    // Need to create/recreate connection
    if (client != null && !client.isOpen()) {
      metrics.recordReconnection();
    }

    client = createConnection(roomId);
    if (client != null) {
      connectionCache.put(roomId, client);
    }
    return client;
  }

  /**
   * Create new WebSocket connection to specified room
   * Increased timeout to 30 seconds to accommodate slow networks and server load
   */
  private ConnectionWithCallback createConnection(int roomId) {
    try {
      URI uri = new URI(serverUrl + "/chat/" + roomId);
      CountDownLatch connectLatch = new CountDownLatch(1);
      AtomicBoolean connectionSuccess = new AtomicBoolean(false);

      ConnectionWithCallback client = new ConnectionWithCallback(
          uri,
          () -> {  // onOpen callback
            connectionSuccess.set(true);
            connectLatch.countDown();
          },
          () -> {  // onClose callback
            metrics.recordConnectionClosed();
          }
      );

      // ADD: Set longer connection timeout for WebSocket
      client.setConnectionLostTimeout(90);  // 90 seconds keep-alive
      // Increased connection timeout to 30 seconds (from 10s)
      boolean connected = client.connectBlocking(30, TimeUnit.SECONDS);

      if (connected && connectionSuccess.get()) {
        metrics.recordConnectionCreated();
        return client;
      }
      return null;

    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Send message with retry logic and exponential backoff
   * Records detailed metrics for each successful send including actual RTT
   */
  private void sendMessage(ConnectionWithCallback client, MessageWrapper wrapper, int roomId) {
    int attempt = 0;

    while (attempt < 5) {
      try {
        if (!client.isOpen()) {
          client = getOrCreateConnection(roomId);
          if (client == null) {
            attempt++;
            // Increased backoff time from 100ms to 200ms
            Thread.sleep((long) Math.pow(2, attempt) * 200);
            continue;
          }
        }

        // TIMING: Record send time for RTT measurement
        long sendTime = System.nanoTime();

        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicLong receiveTime = new AtomicLong(0);

        // Set callback BEFORE sending - captures REAL response time
        client.setResponseCallback((receiveTimeNanos) -> {
          receiveTime.set(receiveTimeNanos);
          responseLatch.countDown();
        });

        String messageJson = gson.toJson(wrapper.getMessage());
        client.send(messageJson);

        // Increased response wait timeout to 5 seconds (from 3s)
        boolean responded = responseLatch.await(5, TimeUnit.SECONDS);

        if (responded && receiveTime.get() > 0) {
          long latencyMs = (receiveTime.get() - sendTime) / 1_000_000;
          metrics.recordDetailedMetric(
              System.currentTimeMillis(),
              wrapper.getMessage().getMessageType().toString(),
              latencyMs,
              200,
              roomId
          );
          metrics.recordSuccess();
          return;
        }

        attempt++;
        if (attempt < 5) {
          // Increased backoff base from 100ms to 200ms
          Thread.sleep((long) Math.pow(2, attempt) * 200);
        }

      } catch (Exception e) {
        attempt++;
        try {
          if (attempt < 5) {
            Thread.sleep((long) Math.pow(2, attempt) * 200);
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          metrics.recordFailure();
          return;
        }
      }
    }

    metrics.recordFailure();
  }
}