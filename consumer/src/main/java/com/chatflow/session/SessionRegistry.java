package com.chatflow.session;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which WebSocket sessions belong to each room.
 * Used by both the WebSocket handler (to register sessions)
 * and the consumer (to broadcast messages).
 */
@Component
public class SessionRegistry {

  // roomId -> set of live sessions
  private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms =
      new ConcurrentHashMap<>();

  public void join(String roomId, WebSocketSession session) {
    rooms.computeIfAbsent(roomId, k ->
        Collections.newSetFromMap(new ConcurrentHashMap<>())
    ).add(session);
  }

  public void leave(String roomId, WebSocketSession session) {
    Set<WebSocketSession> sessions = rooms.get(roomId);
    if (sessions != null) sessions.remove(session);
  }

  public Set<WebSocketSession> getSessions(String roomId) {
    return rooms.getOrDefault(roomId, Collections.emptySet());
  }
}