package com.chatflow.client;

import com.google.gson.Gson;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.java_websocket.client.WebSocketClient;

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
  private final Map<Integer, ConnectionWithCallback> connectionsByRoom;

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
        if (wrapper == null)
          continue;

        int roomId = wrapper.getRoomId();

        // Get or create connection for this room
        ConnectionWithCallback client = getOrCreateConnection(roomId);

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
   * Get existing connection for room, or create new one This implements connection pooling per
   * thread
   */
  private ConnectionWithCallback getOrCreateConnection(int roomId) {
    ConnectionWithCallback client = connectionsByRoom.get(roomId);

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

  private ConnectionWithCallback createConnection(int roomId) {
    try {
      URI uri = new URI(serverUrl + "/chat/" + roomId);
      CountDownLatch connectLatch = new CountDownLatch(1);
      AtomicBoolean connectionSuccess = new AtomicBoolean(false);

      ConnectionWithCallback client = new ConnectionWithCallback(
          uri,
          () -> {  // onOpen
            connectionSuccess.set(true);
            connectLatch.countDown();
          },
          () -> {  // onClose
            metrics.recordConnectionClosed();
          }
      );

      boolean connected = client.connectBlocking(10, TimeUnit.SECONDS);

      if (connected && connectionSuccess.get()) {
        metrics.recordConnectionCreated();
        return client;
      }
      return null;

    } catch (Exception e) {
      return null;
    }
  }

  private void sendMessage(ConnectionWithCallback client, MessageWrapper wrapper, int roomId) {
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

        // TIMING: Record send time in nanoseconds
        long sendTime = System.nanoTime();

        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicLong receiveTime = new AtomicLong(0);

        // DEBUG: Add logging for first message
//        if (threadId == 0 && attempt == 0) {
//          System.out.println("DEBUG Thread-0: Setting callback before send");
//        }

        // Set callback BEFORE sending - this captures REAL response time
        client.setResponseCallback((receiveTimeNanos) -> {
          receiveTime.set(receiveTimeNanos);
          responseLatch.countDown();

//          // DEBUG
//          if (threadId == 0) {
//            System.out.println("DEBUG Thread-0: Callback executed! ReceiveTime set");
//          }
        });

        String messageJson = gson.toJson(wrapper.getMessage());
        client.send(messageJson);

//        // DEBUG
//        if (threadId == 0 && attempt == 0) {
//          System.out.println("DEBUG Thread-0: Message sent, waiting for response...");
//        }

        // Wait for ACTUAL response from server
        boolean responded = responseLatch.await(3, TimeUnit.SECONDS);

//        // DEBUG
//        if (threadId == 0 && attempt == 0) {
//          System.out.println("DEBUG Thread-0: responded=" + responded +
//              ", receiveTime=" + receiveTime.get());
//        }

        if (responded && receiveTime.get() > 0) {
          // Calculate REAL latency and record detailed metric
          long latencyMs = (receiveTime.get() - sendTime) / 1_000_000;

//          // DEBUG
//          if (threadId == 0 && attempt == 0) {
//            System.out.println("DEBUG Thread-0: Recording metric with latency=" + latencyMs + "ms");
//          }

          metrics.recordDetailedMetric(
              System.currentTimeMillis(),
              wrapper.getMessage().getMessageType().toString(),
              latencyMs,
              200,
              roomId
          );
          metrics.recordSuccess();
          return; // Success!
        } else {
          // DEBUG
          if (threadId == 0 && attempt == 0) {
            System.out.println("DEBUG Thread-0: FAILED - responded=" + responded +
                ", receiveTime.get()=" + receiveTime.get());
          }
        }

        // Retry with exponential backoff
        attempt++;
        if (attempt < 5) {
          Thread.sleep((long) Math.pow(2, attempt) * 100);
        }

      } catch (Exception e) {
        System.err.println("DEBUG: Exception in sendMessage: " + e.getMessage());
        e.printStackTrace();
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
    System.err.println("DEBUG Thread-" + threadId + ": Message FAILED after 5 retries");
    metrics.recordFailure();
  }
}