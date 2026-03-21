package com.chatflow;

import com.chatflow.consumer.RoomConsumerPool;
import com.chatflow.session.SessionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class ConsumerApp {
  public static void main(String[] args) {
    ApplicationContext ctx = SpringApplication.run(ConsumerApp.class, args);
    // Start consuming after Spring context is ready
    ctx.getBean(RoomConsumerPool.class).start();
  }
}