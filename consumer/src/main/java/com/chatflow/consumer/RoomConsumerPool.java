package com.chatflow.consumer;

import com.chatflow.session.SessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RoomConsumerPool {

  private static final Logger log = LoggerFactory.getLogger(RoomConsumerPool.class);
  private static final int ROOM_COUNT = 20;
  private static final int CONSUMER_THREADS = 20; // one per room, tune as needed

  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicLong processed = new AtomicLong(0);

  @Value("${rabbitmq.host:localhost}")
  private String rabbitHost;

  @Value("${rabbitmq.port:5672}")
  private int rabbitPort;

  @Value("${rabbitmq.username:guest}")
  private String rabbitUsername;

  @Value("${rabbitmq.password:guest}")
  private String rabbitPassword;

  @Autowired
  private SessionRegistry registry;

  public void start() {
    ExecutorService executor = Executors.newFixedThreadPool(CONSUMER_THREADS);
    for (int i = 1; i <= ROOM_COUNT; i++) {
      final String queueName = "room." + i;
      executor.submit(() -> runConsumer(queueName));
    }
    log.info("Started {} consumer threads", CONSUMER_THREADS);
  }

  private void runConsumer(String queueName) {
    while (true) { // auto-restart on failure
      try {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitHost);
        factory.setPort(rabbitPort);
        factory.setUsername(rabbitUsername);
        factory.setPassword(rabbitPassword);
        factory.setAutomaticRecoveryEnabled(true);

        Connection conn = factory.newConnection();
        Channel channel = conn.createChannel();
        channel.basicQos(100); // prefetch: process 100 msgs before ack

        DeliverCallback deliverCallback = (tag, delivery) -> {
          try {
            JsonNode msg = mapper.readTree(delivery.getBody());
            String roomId = msg.get("roomId").asText();
            broadcast(roomId, mapper.writeValueAsString(msg));
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            long total = processed.incrementAndGet();
            if (total % 10000 == 0) log.info("Processed {} messages", total);
          } catch (Exception e) {
            log.error("Error processing message", e);
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
          }
        };

        channel.basicConsume(queueName, false, deliverCallback, t -> {});
        log.info("Consumer started for {}", queueName);

        // Block this thread until connection dies
        synchronized (conn) { conn.wait(); }

      } catch (Exception e) {
        log.warn("Consumer for {} crashed, restarting in 3s: {}", queueName, e.getMessage());
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
      }
    }
  }

  private void broadcast(String roomId, String json) {
    Set<WebSocketSession> sessions = registry.getSessions(roomId);
    TextMessage msg = new TextMessage(json);
    for (WebSocketSession s : sessions) {
      try {
        if (s.isOpen()) {
          synchronized (s) { s.sendMessage(msg); }
        }
      } catch (Exception e) {
        log.warn("Failed to send to session {}", s.getId());
      }
    }
  }
}