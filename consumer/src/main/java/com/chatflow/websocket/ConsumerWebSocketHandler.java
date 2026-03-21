package com.chatflow.websocket;

import com.chatflow.session.SessionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * On the consumer side, the WebSocket handler just registers/deregisters sessions.
 * Messages are pushed BY the consumer (RoomConsumerPool), not echoed here.
 */
@Component
public class ConsumerWebSocketHandler extends TextWebSocketHandler {

  @Autowired
  private SessionRegistry registry;

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    registry.join(roomId(session), session);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    registry.leave(roomId(session), session);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    // Consumer side does not process inbound messages
  }

  private String roomId(WebSocketSession s) {
    String path = s.getUri().getPath();
    return path.substring(path.lastIndexOf('/') + 1);
  }
}

@Configuration
@EnableWebSocket
class ConsumerWebSocketConfig implements WebSocketConfigurer {
  @Autowired ConsumerWebSocketHandler handler;

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, "/chat/{roomId}").setAllowedOrigins("*");
  }
}

@RestController
class ConsumerHealthController {
  @GetMapping("/health")
  public String health() { return "{\"status\":\"ok\"}"; }
}