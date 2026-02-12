package com.chatflow.client;

import com.chatflow.model.ChatMessage;
import com.google.gson.Gson;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatClientThread implements Runnable {
  private final String serverUrl;
  private final BlockingQueue<MessageWrapper> messageQueue;
  private final int messagesToSend;
  private final AtomicInteger successCount;
  private final AtomicInteger failureCount;
  private final CountDownLatch completionLatch;
  private final Gson gson;
  private final BlockingQueue<MessageWrapper> retryQueue;
  private final int threadId;

  public ChatClientThread(String serverUrl, BlockingQueue<MessageWrapper> messageQueue,
      int messagesToSend, AtomicInteger successCount,
      AtomicInteger failureCount, CountDownLatch completionLatch,
      int threadId) {
    this.serverUrl = serverUrl;
    this.messageQueue = messageQueue;
    this.messagesToSend = messagesToSend;
    this.successCount = successCount;
    this.failureCount = failureCount;
    this.completionLatch = completionLatch;
    this.gson = new Gson();
    this.retryQueue = new LinkedBlockingQueue<>();
    this.threadId = threadId;
  }

  @Override
  public void run() {
    try {
      for (int i = 0; i < messagesToSend; i++) {
        MessageWrapper wrapper = messageQueue.take();
        sendMessage(wrapper);
      }

      while (!retryQueue.isEmpty()) {
        MessageWrapper wrapper = retryQueue.poll();
        if (wrapper != null && wrapper.canRetry()) {
          Thread.sleep((long) Math.pow(2, wrapper.getRetryCount()) * 100);
          wrapper.incrementRetry();
          sendMessage(wrapper);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      completionLatch.countDown();
    }
  }

  private void sendMessage(MessageWrapper wrapper) {
    WebSocketClient client = null;
    try {
      int roomId = wrapper.getRoomId();
      URI uri = new URI(serverUrl + "/chat/" + roomId);

      CountDownLatch connectionLatch = new CountDownLatch(1);
      CountDownLatch messageLatch = new CountDownLatch(1);

      client = new WebSocketClient(uri) {
        @Override
        public void onOpen(ServerHandshake handshake) {
          connectionLatch.countDown();
        }

        @Override
        public void onMessage(String message) {
          messageLatch.countDown();
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception ex) {
          connectionLatch.countDown();
          messageLatch.countDown();
        }
      };

      client.connectBlocking();
      connectionLatch.await();

      if (client.isOpen()) {
        String messageJson = gson.toJson(wrapper.getMessage());
        client.send(messageJson);

        boolean received = messageLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        if (received) {
          successCount.incrementAndGet();
        } else {
          handleFailure(wrapper);
        }
      } else {
        handleFailure(wrapper);
      }

      client.closeBlocking();
    } catch (Exception e) {
      handleFailure(wrapper);
    } finally {
      if (client != null && client.isOpen()) {
        client.close();
      }
    }
  }

  private void handleFailure(MessageWrapper wrapper) {
    if (wrapper.canRetry()) {
      wrapper.incrementRetry();
      retryQueue.offer(wrapper);
    } else {
      failureCount.incrementAndGet();
    }
  }
}