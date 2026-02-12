package com.chatflow.model;

import java.time.Instant;
import java.time.format.DateTimeParseException;

public class ChatMessage {
  private String userId;
  private String username;
  private String message;
  private String timestamp;
  private MessageType messageType;

  public enum MessageType {
    TEXT, JOIN, LEAVE
  }

  public ChatMessage() {}

  public ChatMessage(String userId, String username, String message,
      String timestamp, MessageType messageType) {
    this.userId = userId;
    this.username = username;
    this.message = message;
    this.timestamp = timestamp;
    this.messageType = messageType;
  }

  public ValidationResult validate() {
    if (userId == null || userId.isEmpty()) {
      return new ValidationResult(false, "userId is required");
    }

    try {
      int userIdInt = Integer.parseInt(userId);
      if (userIdInt < 1 || userIdInt > 100000) {
        return new ValidationResult(false, "userId must be between 1 and 100000");
      }
    } catch (NumberFormatException e) {
      return new ValidationResult(false, "userId must be a valid number");
    }

    if (username == null || username.length() < 3 || username.length() > 20) {
      return new ValidationResult(false, "username must be 3-20 characters");
    }

    if (!username.matches("^[a-zA-Z0-9]+$")) {
      return new ValidationResult(false, "username must be alphanumeric");
    }

    if (message == null || message.length() < 1 || message.length() > 500) {
      return new ValidationResult(false, "message must be 1-500 characters");
    }

    if (timestamp == null) {
      return new ValidationResult(false, "timestamp is required");
    }

    try {
      Instant.parse(timestamp);
    } catch (DateTimeParseException e) {
      return new ValidationResult(false, "timestamp must be valid ISO-8601 format");
    }

    if (messageType == null) {
      return new ValidationResult(false, "messageType is required");
    }

    return new ValidationResult(true, "Valid");
  }

  // Getters and setters
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }

  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }

  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }

  public String getTimestamp() { return timestamp; }
  public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

  public MessageType getMessageType() { return messageType; }
  public void setMessageType(MessageType messageType) { this.messageType = messageType; }

  public static class ValidationResult {
    private final boolean valid;
    private final String message;

    public ValidationResult(boolean valid, String message) {
      this.valid = valid;
      this.message = message;
    }

    public boolean isValid() { return valid; }
    public String getMessage() { return message; }
  }
}