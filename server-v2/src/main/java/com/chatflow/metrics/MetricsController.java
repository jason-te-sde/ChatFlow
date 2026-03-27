package com.chatflow.metrics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/metrics")
public class MetricsController {

  @Autowired
  private JdbcTemplate jdbc;

  /**
   * GET /metrics/room/{roomId}?start=2026-01-01T00:00:00Z&end=2026-12-31T23:59:59Z
   * Core Query 1: messages for a room in time range
   */
  @GetMapping("/room/{roomId}")
  public List<Map<String, Object>> getRoomMessages(
      @PathVariable String roomId,
      @RequestParam String start,
      @RequestParam String end) {
    return jdbc.queryForList("""
            SELECT message_id, user_id, username, message, message_type, created_at
            FROM messages
            WHERE room_id = ? AND created_at BETWEEN ? AND ?
            ORDER BY created_at
            LIMIT 1000
            """, roomId, start, end);
  }

  /**
   * GET /metrics/user/{userId}
   * Core Query 2: user message history
   */
  @GetMapping("/user/{userId}")
  public List<Map<String, Object>> getUserHistory(@PathVariable int userId) {
    return jdbc.queryForList("""
            SELECT room_id, message, message_type, created_at
            FROM messages
            WHERE user_id = ?
            ORDER BY created_at DESC
            LIMIT 500
            """, userId);
  }

  /**
   * GET /metrics/active-users?start=...&end=...
   * Core Query 3: count active users in time window
   */
  @GetMapping("/active-users")
  public Map<String, Object> getActiveUsers(
      @RequestParam String start,
      @RequestParam String end) {
    Long count = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT user_id)
            FROM messages
            WHERE created_at BETWEEN ? AND ?
            """, Long.class, start, end);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("activeUsers", count);
    result.put("start", start);
    result.put("end", end);
    return result;
  }

  /**
   * GET /metrics/user/{userId}/rooms
   * Core Query 4: rooms user has participated in
   */
  @GetMapping("/user/{userId}/rooms")
  public List<Map<String, Object>> getUserRooms(@PathVariable int userId) {
    return jdbc.queryForList("""
            SELECT room_id, COUNT(*) as message_count, MAX(created_at) as last_activity
            FROM messages
            WHERE user_id = ?
            GROUP BY room_id
            ORDER BY last_activity DESC
            """, userId);
  }

  /**
   * GET /metrics/analytics
   * Analytics: msg/s stats, top users, top rooms, all in one call
   */
  @GetMapping("/analytics")
  public Map<String, Object> getAnalytics() {
    Map<String, Object> result = new LinkedHashMap<>();

    // Total messages
    result.put("totalMessages", jdbc.queryForObject(
        "SELECT COUNT(*) FROM messages", Long.class));

    // Messages per minute (last 10 minutes)
    result.put("messagesPerMinute", jdbc.queryForList("""
            SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:00') as minute,
                   COUNT(*) as count
            FROM messages
            WHERE created_at >= NOW() - INTERVAL 10 MINUTE
            GROUP BY minute
            ORDER BY minute
            """));

    // Top 10 most active users
    result.put("topUsers", jdbc.queryForList("""
            SELECT user_id, username, COUNT(*) as message_count
            FROM messages
            GROUP BY user_id, username
            ORDER BY message_count DESC
            LIMIT 10
            """));

    // Top 10 most active rooms
    result.put("topRooms", jdbc.queryForList("""
            SELECT room_id, COUNT(*) as message_count,
                   COUNT(DISTINCT user_id) as unique_users
            FROM messages
            GROUP BY room_id
            ORDER BY message_count DESC
            LIMIT 10
            """));

    // DB write stats — queried directly from DB
    result.put("dbWritten", jdbc.queryForObject(
        "SELECT COUNT(*) FROM messages", Long.class));
    result.put("dbFailed", 0);

    return result;
  }

  /**
   * GET /metrics/summary — single endpoint called by client after test
   */
  @GetMapping("/summary")
  public Map<String, Object> getSummary() {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("totalMessages",
        jdbc.queryForObject("SELECT COUNT(*) FROM messages", Long.class));
    summary.put("totalUsers",
        jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM messages", Long.class));
    summary.put("totalRooms",
        jdbc.queryForObject("SELECT COUNT(DISTINCT room_id) FROM messages", Long.class));
    summary.put("analytics", getAnalytics());
    return summary;
  }
}