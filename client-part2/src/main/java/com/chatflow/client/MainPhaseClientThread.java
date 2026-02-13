package com.chatflow.client;

import com.google.gson.Gson;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MainPhaseClientThread implements Runnable {
  private final String serverUrl;
  private final BlockingQueue<MessageWrapper> messageQueue;
  private final int messagesToSend;
  private final PerformanceMetrics metrics;
  private final CountDownLatch completionLatch;
  private final Gson gson;
  private final int threadId;

  private final Map<Integer, ConnectionWithCallback> connectionCache;

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
        ConnectionWithCallback client = getOrCreateConnection(roomId);

        if (client == null) {
          metrics.recordFailure();
          sent++;
          continue;
        }

        sendMessage(client, wrapper, roomId);
        sent++;

        if (sent % 500 == 0 && threadId % 10 == 0) {
          System.out.println("Thread " + threadId + " progress: " + sent + "/" + messagesToSend);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      for (ConnectionWithCallback client : connectionCache.values()) {
        if (client != null && client.isOpen()) {
          client.close();
        }
      }
      completionLatch.countDown();
    }
  }

  private ConnectionWithCallback getOrCreateConnection(int roomId) {
    ConnectionWithCallback client = connectionCache.get(roomId);

    if (client != null && client.isOpen()) {
      return client;
    }

    if (client != null && !client.isOpen()) {
      metrics.recordReconnection();
    }

    client = createConnection(roomId);
    if (client != null) {
      connectionCache.put(roomId, client);
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
          () -> {
            connectionSuccess.set(true);
            connectLatch.countDown();
          },
          () -> {
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

        long sendTime = System.nanoTime();
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicLong receiveTime = new AtomicLong(0);

        client.setResponseCallback((receiveTimeNanos) -> {
          receiveTime.set(receiveTimeNanos);
          responseLatch.countDown();
        });

        String messageJson = gson.toJson(wrapper.getMessage());
        client.send(messageJson);

        boolean responded = responseLatch.await(3, TimeUnit.SECONDS);

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