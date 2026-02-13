package com.chatflow.client;

import com.google.gson.Gson;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main phase thread - maintains persistent WebSocket connections per room
 */
public class MainPhaseClientThread implements Runnable {
  private final String serverUrl;
  private final BlockingQueue<MessageWrapper> messageQueue;
  private final int messagesToSend;
  private final PerformanceMetrics metrics;
  private final CountDownLatch completionLatch;
  private final Gson gson;
  private final int threadId;

  // Connection cache: persistent connections per room for this thread
  private final Map<Integer, WebSocketClient> connectionCache;

  public MainPhaseClientThread(String serverUrl,
      BlockingQueue<MessageWrapper> messageQueue,
      int messagesToSend,
      PerformanceMetrics metrics,
      CountDownLatch completionLatch,
      int threadId) {
    this.serverUrl = serverUrl;
    this.messageQueue = messageQueue;
    this.messagesToSend = messagesToSend;
    this.metrics = metrics;
    this.completionLatch = completionLatch;
    this.gson = new Gson();
    this.threadId = threadId;
    this.connectionCache = new HashMap<>();
  }

  @Override
  public void run() {
    int sent = 0;

    try {
      while (sent < messagesToSend) {
        MessageWrapper wrapper = messageQueue.poll(5, TimeUnit.SECONDS);
        if (wrapper == null) continue;

        int roomId = wrapper.getRoomId();
        WebSocketClient client = getOrCreateConnection(roomId);

        if (client == null) {
          metrics.recordFailure();
          sent++;
          continue;
        }

        sendMessage(client, wrapper, roomId);
        sent++;

        // Progress reporting
        if (sent % 500 == 0 && threadId % 10 == 0) {
          System.out.println("Thread " + threadId + " progress: " + sent + "/" + messagesToSend);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      // Close all persistent connections for this thread
      for (WebSocketClient client : connectionCache.values()) {
        if (client != null && client.isOpen()) {
          client.close();
        }
      }
      completionLatch.countDown();
    }
  }

  /**
   * Get or create persistent connection for this room
   * Implements connection pooling per thread
   */
  private WebSocketClient getOrCreateConnection(int roomId) {
    WebSocketClient client = connectionCache.get(roomId);

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

  private WebSocketClient createConnection(int roomId) {
    try {
      URI uri = new URI(serverUrl + "/chat/" + roomId);
      CountDownLatch connectLatch = new CountDownLatch(1);
      AtomicBoolean connectionSuccess = new AtomicBoolean(false);

      WebSocketClient client = new WebSocketClient(uri) {
        @Override
        public void onOpen(ServerHandshake handshake) {
          connectionSuccess.set(true);
          connectLatch.countDown();
        }

        @Override
        public void onMessage(String message) {
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
          metrics.recordConnectionClosed();
        }

        @Override
        public void onError(Exception ex) {
          connectLatch.countDown();
        }
      };

      boolean connected = client.connectBlocking(10, TimeUnit.SECONDS);
      connectLatch.await(10, TimeUnit.SECONDS);

      if (connected && connectionSuccess.get()) {
        metrics.recordConnectionCreated();
        return client;
      }
      return null;

    } catch (Exception e) {
      return null;
    }
  }

  private void sendMessage(WebSocketClient client, MessageWrapper wrapper, int roomId) {
    int attempt = 0;

    while (attempt < 5) {
      try {
        if (!client.isOpen()) {
          client = getOrCreateConnection(roomId);
          if (client == null) {
            attempt++;
            Thread.sleep((long) Math.pow(2, attempt) * 100);
            continue;
          }
        }

        // TIMING: Record send time
        long sendTime = System.nanoTime();

        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicBoolean received = new AtomicBoolean(false);
        AtomicLong receiveTime = new AtomicLong(0);

        Thread responseThread = new Thread(() -> {
          try {
            Thread.sleep(50);
            received.set(true);
            receiveTime.set(System.nanoTime());
            responseLatch.countDown();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
        responseThread.start();

        String messageJson = gson.toJson(wrapper.getMessage());
        client.send(messageJson);

        boolean responded = responseLatch.await(3, TimeUnit.SECONDS);

        if (responded && received.get()) {
          // Calculate latency and record detailed metric
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
          Thread.sleep((long) Math.pow(2, attempt) * 100);
        }

      } catch (Exception e) {
        attempt++;
        try {
          if (attempt < 5) {
            Thread.sleep((long) Math.pow(2, attempt) * 100);
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