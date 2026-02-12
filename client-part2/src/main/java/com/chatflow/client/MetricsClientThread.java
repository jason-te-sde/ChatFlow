package com.chatflow.client;

import com.chatflow.client.metrics.MetricsTracker;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MetricsClientThread implements Runnable {
  private final String serverUrl;
  private final BlockingQueue<MessageWrapper> messageQueue;
  private final int messagesToSend;
  private final AtomicInteger successCount;
  private final AtomicInteger failureCount;
  private final CountDownLatch completionLatch;
  private final MetricsTracker metricsTracker;
  private final Gson gson;
  private final int threadId;

  public MetricsClientThread(String serverUrl, BlockingQueue<MessageWrapper> messageQueue,
      int messagesToSend, AtomicInteger successCount,
      AtomicInteger failureCount, CountDownLatch completionLatch,
      MetricsTracker metricsTracker, int threadId) {
    this.serverUrl = serverUrl;
    this.messageQueue = messageQueue;
    this.messagesToSend = messagesToSend;
    this.successCount = successCount;
    this.failureCount = failureCount;
    this.completionLatch = completionLatch;
    this.metricsTracker = metricsTracker;
    this.gson = new Gson();
    this.threadId = threadId;
  }

  @Override
  public void run() {
    try {
      for (int i = 0; i < messagesToSend; i++) {
        MessageWrapper wrapper = messageQueue.take();
        sendMessageWithMetrics(wrapper, 0);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      completionLatch.countDown();
    }
  }

  private void sendMessageWithMetrics(MessageWrapper wrapper, int retryCount) {
    WebSocketClient client = null;
    long sendTime = System.currentTimeMillis();

    try {
      int roomId = wrapper.getRoomId();
      URI uri = new URI(serverUrl + "/chat/" + roomId);

      CountDownLatch connectionLatch = new CountDownLatch(1);
      AtomicLong receiveTime = new AtomicLong(0);
      AtomicInteger statusCode = new AtomicInteger(0);

      client = new WebSocketClient(uri) {
        @Override
        public void onOpen(ServerHandshake handshake) {
          connectionLatch.countDown();
        }

        @Override
        public void onMessage(String message) {
          receiveTime.set(System.currentTimeMillis());
          try {
            JsonObject response = gson.fromJson(message, JsonObject.class);
            String status = response.get("status").getAsString();
            statusCode.set("SUCCESS".equals(status) ? 200 : 400);
          } catch (Exception e) {
            statusCode.set(500);
          }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception ex) {
          connectionLatch.countDown();
        }
      };

      client.connectBlocking();
      connectionLatch.await();

      if (client.isOpen()) {
        String messageJson = gson.toJson(wrapper.getMessage());
        client.send(messageJson);

        Thread.sleep(100);

        if (receiveTime.get() > 0) {
          successCount.incrementAndGet();
          metricsTracker.recordMetric(
              sendTime,
              receiveTime.get(),
              wrapper.getMessage().getMessageType().toString(),
              statusCode.get(),
              roomId
          );
        } else if (retryCount < 5) {
          Thread.sleep((long) Math.pow(2, retryCount) * 100);
          sendMessageWithMetrics(wrapper, retryCount + 1);
          return;
        } else {
          failureCount.incrementAndGet();
          metricsTracker.recordMetric(
              sendTime,
              System.currentTimeMillis(),
              wrapper.getMessage().getMessageType().toString(),
              0,
              roomId
          );
        }
      } else {
        if (retryCount < 5) {
          Thread.sleep((long) Math.pow(2, retryCount) * 100);
          sendMessageWithMetrics(wrapper, retryCount + 1);
          return;
        } else {
          failureCount.incrementAndGet();
        }
      }

      client.closeBlocking();
    } catch (Exception e) {
      if (retryCount < 5) {
        try {
          Thread.sleep((long) Math.pow(2, retryCount) * 100);
          sendMessageWithMetrics(wrapper, retryCount + 1);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          failureCount.incrementAndGet();
        }
      } else {
        failureCount.incrementAndGet();
      }
    } finally {
      if (client != null && client.isOpen()) {
        client.close();
      }
    }
  }
}