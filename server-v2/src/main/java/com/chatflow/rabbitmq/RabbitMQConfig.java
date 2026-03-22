package com.chatflow.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

@Configuration
public class RabbitMQConfig {

  public static final String EXCHANGE = "chat.exchange";
  public static final int ROOM_COUNT = 20;

  @Value("${rabbitmq.host:localhost}")
  private String host;

  @Value("${rabbitmq.port:5672}")
  private int port;

  @Value("${rabbitmq.username:guest}")
  private String username;

  @Value("${rabbitmq.password:guest}")
  private String password;

  @Autowired
  private ChannelPool channelPool;

  @PostConstruct
  public void setup() throws IOException, TimeoutException {
    channelPool.init(host, port, username, password);

    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(host);
    factory.setPort(port);
    factory.setUsername(username);
    factory.setPassword(password);

    try (Connection conn = factory.newConnection();
        Channel ch = conn.createChannel()) {
      ch.exchangeDeclare(EXCHANGE, "topic", true);

      // Queue args: TTL = 60s, max length = 100000 messages
      java.util.Map<String, Object> args = new java.util.HashMap<>();
      args.put("x-message-ttl", 60_000);       // messages expire after 60s
      args.put("x-max-length", 100_000);        // max 100k messages per queue

      for (int i = 1; i <= ROOM_COUNT; i++) {
        String queueName = "room." + i;
        ch.queueDeclare(queueName, true, false, false, args);
        ch.queueBind(queueName, EXCHANGE, queueName);
      }
    }
  }
}