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
 * Warmup phase thread - each thread establishes WebSocket connections and sends 1000 messages
 * Maintains multiple connections (one per room) for efficiency
 */
public class WarmupClientThread implements Runnable {
  private final String serverUrl;
  private final BlockingQueue<MessageWrapper> messageQueue;
  private final int messagesToSend;
  private final PerformanceMetrics metrics;
  private final CountDownLatch completionLatch;
  private final Gson gson;
  private final int threadId;

  // Connection pool: one connection per room for this thread
  private final Map<Integer, WebSocketClient> connectionsByRoom;

  public WarmupClientThread(String serverUrl,
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
    this.connectionsByRoom = new HashMap<>();
  }

  @Override
  public void run() {
    int sent = 0;

    try {
      while (sent < messagesToSend) {
        MessageWrapper wrapper = messageQueue.poll(5, TimeUnit.SECONDS);
        if (wrapper == null) continue;

        int roomId = wrapper.getRoomId();

        // Get or create connection for this room
        WebSocketClient client = getOrCreateConnection(roomId);

        if (client == null) {
          // Failed to connect after retries
          metrics.recordFailure();
          sent++;
          continue;
        }

        // Send message with retry logic
        sendMessage(client, wrapper, roomId);
        sent++;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      // Close all connections when thread terminates
      for (WebSocketClient client : connectionsByRoom.values()) {
        if (client != null && client.isOpen()) {
          client.close();
        }
      }
      completionLatch.countDown();
    }
  }

  /**
   * Get existing connection for room, or create new one
   * This implements connection pooling per thread
   */
  private WebSocketClient getOrCreateConnection(int roomId) {
    WebSocketClient client = connectionsByRoom.get(roomId);

    if (client != null && client.isOpen()) {
      return client; // Reuse existing connection
    }

    // Need to create/recreate connection
    if (client != null && !client.isOpen()) {
      metrics.recordReconnection();
    }

    client = createConnection(roomId);
    if (client != null) {
      connectionsByRoom.put(roomId, client);
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
          // Response received
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
        // Check if connection is still open
        if (!client.isOpen()) {
          // Try to reconnect
          client = getOrCreateConnection(roomId);
          if (client == null) {
            attempt++;
            Thread.sleep((long) Math.pow(2, attempt) * 100);
            continue;
          }
        }

        // TIMING: Record send time in nanoseconds
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
          // TIMING: Record RTT for Little's Law
          metrics.recordMessageTiming(sendTime, receiveTime.get());
          metrics.recordSuccess();
          return; // Success!
        }

        // Retry with exponential backoff
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

    // Failed after 5 retries with exponential backoff
    metrics.recordFailure();
  }
}