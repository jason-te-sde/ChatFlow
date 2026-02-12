package com.chatflow.client;

import com.chatflow.model.ChatMessage;

public class MessageWrapper {
  private final ChatMessage message;
  private final int roomId;
  private int retryCount;

  public MessageWrapper(ChatMessage message, int roomId) {
    this.message = message;
    this.roomId = roomId;
    this.retryCount = 0;
  }

  public ChatMessage getMessage() {
    return message;
  }

  public int getRoomId() {
    return roomId;
  }

  public int getRetryCount() {
    return retryCount;
  }

  public void incrementRetry() {
    this.retryCount++;
  }

  public boolean canRetry() {
    return retryCount < 5;
  }
}