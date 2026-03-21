package com.chatflow.websocket;

import com.chatflow.rabbitmq.ChannelPool;
import com.chatflow.rabbitmq.RabbitMQConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
  private static final Pattern ALPHANUM = Pattern.compile("^[a-zA-Z0-9]{3,20}$");
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    log.info("Client connected: {} room={}", session.getId(), extractRoomId(session));
  }

  @Value("${server.id:server-1}")
  private String serverId;

  @Autowired
  private ChannelPool channelPool;

  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    String roomId = extractRoomId(session);
    ObjectNode node;
    try {
      node = (ObjectNode) mapper.readTree(message.getPayload());
    } catch (Exception e) {
      session.sendMessage(new TextMessage("{\"error\":\"invalid JSON\"}"));
      return;
    }

    // --- Validation (Assignment 1 requirement) ---
    String err = validate(node);
    if (err != null) {
      session.sendMessage(new TextMessage("{\"error\":\"" + err + "\"}"));
      return;
    }

    // --- Build queue message (Assignment 2 requirement) ---
    ObjectNode qMsg = mapper.createObjectNode();
    qMsg.put("messageId",   UUID.randomUUID().toString());
    qMsg.put("roomId",      roomId);
    qMsg.put("userId",      node.get("userId").asText());
    qMsg.put("username",    node.get("username").asText());
    qMsg.put("message",     node.get("message").asText());
    qMsg.put("timestamp",   node.get("timestamp").asText());
    qMsg.put("messageType", node.get("messageType").asText());
    qMsg.put("serverId",    serverId);
    qMsg.put("clientIp",    session.getRemoteAddress() != null
        ? session.getRemoteAddress().toString() : "unknown");

    String routingKey = "room." + roomId;
    byte[] body = mapper.writeValueAsBytes(qMsg);

    // --- Publish to RabbitMQ ---
    Channel ch = channelPool.borrowChannel();
    try {
      ch.basicPublish(RabbitMQConfig.EXCHANGE, routingKey, null, body);
    } finally {
      channelPool.returnChannel(ch);
    }

    // --- Ack back to sender (only if still connected) ---
    if (session.isOpen()) {
      try {
        ObjectNode ack = mapper.createObjectNode();
        ack.put("status",     "ok");
        ack.put("messageId",  qMsg.get("messageId").asText());
        ack.put("serverTime", Instant.now().toString());
        session.sendMessage(new TextMessage(mapper.writeValueAsString(ack)));
      } catch (IOException e) {
        log.debug("Client disconnected before ack could be sent: {}", session.getId());
      }
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    log.debug("Session closed: {} status={}", session.getId(), status);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable ex) {
    if (ex instanceof IOException) {
      log.debug("Transport closed (client disconnected): {}", session.getId());
    } else {
      log.warn("Transport error for session {}: {}", session.getId(), ex.getMessage());
    }
  }

  // --- helpers ---

  private String extractRoomId(WebSocketSession session) {
    String path = session.getUri().getPath(); // /chat/{roomId}
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private String validate(ObjectNode n) {
    if (!n.hasNonNull("userId") || !n.hasNonNull("username")
        || !n.hasNonNull("message") || !n.hasNonNull("timestamp")
        || !n.hasNonNull("messageType")) {
      return "missing required fields";
    }
    int userId = n.get("userId").asInt(-1);
    if (userId < 1 || userId > 100000) return "userId must be 1-100000";

    String username = n.get("username").asText("");
    if (!ALPHANUM.matcher(username).matches()) return "username must be 3-20 alphanumeric";

    String msg = n.get("message").asText("");
    if (msg.isEmpty() || msg.length() > 500) return "message must be 1-500 chars";

    String ts = n.get("timestamp").asText("");
    try { Instant.parse(ts); } catch (Exception e) { return "timestamp must be ISO-8601"; }

    String type = n.get("messageType").asText("");
    if (!type.equals("TEXT") && !type.equals("JOIN") && !type.equals("LEAVE"))
      return "messageType must be TEXT|JOIN|LEAVE";

    return null;
  }
}