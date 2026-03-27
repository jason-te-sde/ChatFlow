package com.chatflow.db;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MessageBatchWriter {

  private static final Logger log = LoggerFactory.getLogger(MessageBatchWriter.class);

  // Tunable: optimal found through testing (batch=500, flush=500ms)
  @Value("${db.batch.size:500}")
  private int batchSize;

  @Value("${db.batch.flush-ms:500}")
  private long flushIntervalMs;

  @Value("${db.writer.threads:4}")
  private int writerThreads;

  private final BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>(100_000);
  private ExecutorService writerPool;
  private ScheduledExecutorService flusher;

  public static final AtomicLong dbWritten = new AtomicLong(0);
  public static final AtomicLong dbFailed = new AtomicLong(0);

  @Autowired
  private JdbcTemplate jdbc;

  @PostConstruct
  public void init() {
    writerPool = Executors.newFixedThreadPool(writerThreads);

    // Periodic flush ensures messages don't sit too long even if batch isn't full
    flusher = Executors.newSingleThreadScheduledExecutor();
    flusher.scheduleAtFixedRate(this::flush, flushIntervalMs, flushIntervalMs,
        TimeUnit.MILLISECONDS);

    log.info("MessageBatchWriter started: batchSize={} flushMs={} threads={}",
        batchSize, flushIntervalMs, writerThreads);
  }

  public void enqueue(JsonNode msg) {
    if (!queue.offer(msg)) {
      log.warn("DB write queue full, dropping message");
      dbFailed.incrementAndGet();
    }
    // Trigger flush if batch is ready
    if (queue.size() >= batchSize) {
      writerPool.submit(this::flush);
    }
  }

  private synchronized void flush() {
    if (queue.isEmpty()) return;

    List<JsonNode> batch = new ArrayList<>(batchSize);
    queue.drainTo(batch, batchSize);
    if (batch.isEmpty()) return;

    writerPool.submit(() -> writeBatch(batch));
  }

  private void writeBatch(List<JsonNode> batch) {
    String sql = """
            INSERT IGNORE INTO messages
              (message_id, room_id, user_id, username, message,
               message_type, server_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    // Retry with exponential backoff
    int attempts = 0;
    while (attempts < 3) {
      try {
        jdbc.batchUpdate(sql, batch, batch.size(), (ps, node) -> {
          ps.setString(1, node.path("messageId").asText());
          ps.setString(2, node.path("roomId").asText());
          ps.setInt(3, node.path("userId").asInt());
          ps.setString(4, node.path("username").asText());
          ps.setString(5, node.path("message").asText());
          ps.setString(6, node.path("messageType").asText("TEXT"));
          ps.setString(7, node.path("serverId").asText("unknown"));
          ps.setTimestamp(8, Timestamp.from(
              Instant.parse(node.path("timestamp").asText())));
        });
        dbWritten.addAndGet(batch.size());
        return;
      } catch (Exception e) {
        attempts++;
        long backoff = (long) Math.pow(2, attempts) * 100;
        log.warn("Batch write failed (attempt {}), retrying in {}ms: {}",
            attempts, backoff, e.getMessage());
        try { Thread.sleep(backoff); } catch (InterruptedException ignored) {}
      }
    }
    // Dead letter — log and count
    log.error("Batch of {} messages failed after 3 attempts", batch.size());
    dbFailed.addAndGet(batch.size());
  }

  @PreDestroy
  public void shutdown() {
    flusher.shutdown();
    // Final flush
    flush();
    writerPool.shutdown();
    try { writerPool.awaitTermination(10, TimeUnit.SECONDS); }
    catch (InterruptedException ignored) {}
    log.info("MessageBatchWriter shutdown. Written={} Failed={}",
        dbWritten.get(), dbFailed.get());
  }
}