package com.chatflow.server;

import com.chatflow.model.ChatMessage;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChatServer extends WebSocketServer {
  private static final Gson gson = new Gson();
  private final Map<WebSocket, String> connectionRooms = new ConcurrentHashMap<>();

  public ChatServer(int port) {
    super(new InetSocketAddress(port));
  }

  @Override
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
    String uri = handshake.getResourceDescriptor();
    String roomId = extractRoomId(uri);

    if (roomId != null) {
      connectionRooms.put(conn, roomId);
      System.out.println("New connection to room: " + roomId +
          " from " + conn.getRemoteSocketAddress());
    } else {
      System.out.println("Invalid connection attempt - no room ID");
      conn.close(1008, "Invalid room ID");
    }
  }

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    String roomId = connectionRooms.remove(conn);
    System.out.println("Connection closed for room: " + roomId);
  }

  @Override
  public void onMessage(WebSocket conn, String message) {
    try {
      ChatMessage chatMessage = gson.fromJson(message, ChatMessage.class);
      ChatMessage.ValidationResult validation = chatMessage.validate();

      if (validation.isValid()) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("originalMessage", chatMessage);
        response.put("serverTimestamp", Instant.now().toString());
        response.put("roomId", connectionRooms.get(conn));

        conn.send(gson.toJson(response));
      } else {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("status", "ERROR");
        errorResponse.put("message", validation.getMessage());
        errorResponse.put("serverTimestamp", Instant.now().toString());

        conn.send(gson.toJson(errorResponse));
      }
    } catch (JsonSyntaxException e) {
      Map<String, Object> errorResponse = new HashMap<>();
      errorResponse.put("status", "ERROR");
      errorResponse.put("message", "Invalid JSON format");
      errorResponse.put("serverTimestamp", Instant.now().toString());

      conn.send(gson.toJson(errorResponse));
    } catch (Exception e) {
      System.err.println("Error processing message: " + e.getMessage());
      e.printStackTrace();
    }
  }

  @Override
  public void onError(WebSocket conn, Exception ex) {
    System.err.println("WebSocket error: " + ex.getMessage());
    ex.printStackTrace();
  }

  @Override
  public void onStart() {
    System.out.println("ChatFlow WebSocket Server started successfully!");
    System.out.println("Listening on port: " + getPort());
    // OPTIMIZATION: Increase timeouts for slow t2.micro
    setConnectionLostTimeout(90);     // Increase from 30s to 90s
    setTcpNoDelay(true);
    setReuseAddr(true);
  }

  private String extractRoomId(String uri) {
    if (uri.startsWith("/chat/")) {
      String[] parts = uri.split("/");
      if (parts.length >= 3) {
        return parts[2].split("\\?")[0];
      }
    }
    return null;
  }

  public static void main(String[] args) {
    int port = 8080;
    int healthPort = 8081;

    if (args.length > 0) {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        System.err.println("Invalid port number, using default 8080");
      }
    }

    try {
      HealthServer.start(healthPort);
    } catch (Exception e) {
      System.err.println("Failed to start health server: " + e.getMessage());
    }

    ChatServer server = new ChatServer(port);
    server.setReuseAddr(true);
    server.start();

    System.out.println("ChatFlow Server starting on port " + port);
  }
}