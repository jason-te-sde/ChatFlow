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

  @Autowired
  private ChannelPool channelPool;

  @PostConstruct
  public void setup() throws IOException, TimeoutException {
    channelPool.init(host, port);

    // Declare exchange and queues once at startup
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(host);
    factory.setPort(port);
    try (Connection conn = factory.newConnection();
        Channel ch = conn.createChannel()) {
      ch.exchangeDeclare(EXCHANGE, "topic", true);
      for (int i = 1; i <= ROOM_COUNT; i++) {
        String queueName = "room." + i;
        ch.queueDeclare(queueName, true, false, false, null);
        ch.queueBind(queueName, EXCHANGE, queueName);
      }
    }
  }
}