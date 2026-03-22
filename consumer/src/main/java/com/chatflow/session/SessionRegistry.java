package com.chatflow.session;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {

  // roomId -> set of live sessions
  private final ConcurrentHashMap<String, Set<WebSocketSession>> roomSessions =
      new ConcurrentHashMap<>();

  // sessionId -> UserInfo
  private final ConcurrentHashMap<String, UserInfo> activeUsers =
      new ConcurrentHashMap<>();

  public void join(String roomId, WebSocketSession session) {
    roomSessions.computeIfAbsent(roomId, k ->
        Collections.newSetFromMap(new ConcurrentHashMap<>())
    ).add(session);
    activeUsers.put(session.getId(), new UserInfo(session.getId(), roomId));
  }

  public void leave(String roomId, WebSocketSession session) {
    Set<WebSocketSession> sessions = roomSessions.get(roomId);
    if (sessions != null) sessions.remove(session);
    activeUsers.remove(session.getId());
  }

  public Set<WebSocketSession> getSessions(String roomId) {
    return roomSessions.getOrDefault(roomId, Collections.emptySet());
  }

  public int getActiveUserCount() {
    return activeUsers.size();
  }

  public int getActiveRoomCount() {
    return (int) roomSessions.values().stream()
        .filter(s -> !s.isEmpty()).count();
  }

  // Simple user info record
  public static class UserInfo {
    public final String sessionId;
    public final String roomId;
    public final long joinTime = System.currentTimeMillis();

    public UserInfo(String sessionId, String roomId) {
      this.sessionId = sessionId;
      this.roomId = roomId;
    }
  }
}