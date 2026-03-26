package com.chatflow;

import com.chatflow.consumer.RoomConsumerPool;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class ConsumerV3App {
  public static void main(String[] args) {
    ApplicationContext ctx = SpringApplication.run(ConsumerV3App.class, args);
    ctx.getBean(RoomConsumerPool.class).start();
  }
}