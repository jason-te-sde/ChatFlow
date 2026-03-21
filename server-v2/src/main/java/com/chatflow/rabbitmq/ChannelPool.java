package com.chatflow.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;

@Component
public class ChannelPool {

  private static final int POOL_SIZE = 20;
  private final BlockingQueue<Channel> pool = new ArrayBlockingQueue<>(POOL_SIZE);
  private Connection connection;

  public void init(String host, int port, String username, String password)
      throws IOException, TimeoutException {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(host);
    factory.setPort(port);
    factory.setUsername(username);
    factory.setPassword(password);
    factory.setAutomaticRecoveryEnabled(true);
    this.connection = factory.newConnection();
    for (int i = 0; i < POOL_SIZE; i++) {
      pool.offer(connection.createChannel());
    }
  }

  public Channel borrowChannel() throws InterruptedException {
    return pool.take();
  }

  public void returnChannel(Channel ch) {
    if (ch != null && ch.isOpen()) {
      pool.offer(ch);
    }
  }

  @PreDestroy
  public void shutdown() {
    try {
      for (Channel ch : pool) {
        if (ch.isOpen()) ch.close();
      }
      if (connection != null && connection.isOpen()) connection.close();
    } catch (Exception ignored) {}
  }
}