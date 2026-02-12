package com.chatflow.client;

import com.chatflow.model.ChatMessage;
import com.chatflow.model.ChatMessage.MessageType;

import java.time.Instant;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class MessageGenerator implements Runnable {
  private final BlockingQueue<MessageWrapper> messageQueue;
  private final int totalMessages;
  private final AtomicInteger messagesGenerated;
  private final Random random;

  private static final String[] SAMPLE_MESSAGES = {
      "Hello, how are you?",
      "Great to see you here!",
      "What's the plan for today?",
      "I love this chat system!",
      "Anyone up for a meeting?",
      "Let's discuss the project.",
      "This is working smoothly!",
      "How's everyone doing?",
      "Happy to be here!",
      "Let's collaborate on this.",
      "Great idea!",
      "I agree with that approach.",
      "Can we schedule a call?",
      "Looking forward to the demo.",
      "This feature is amazing!",
      "Thanks for the update!",
      "Let me know if you need help.",
      "On it!",
      "Checking the docs now.",
      "Will do!",
      "Sounds good to me.",
      "Perfect timing!",
      "I'll get back to you shortly.",
      "Reviewing the code now.",
      "Deployment looks good.",
      "Tests are passing!",
      "Bug fixed and pushed.",
      "Ready for review.",
      "LGTM!",
      "Approved!",
      "Merged to main.",
      "Release is ready.",
      "Production deploy successful!",
      "Monitoring metrics...",
      "Everything looks stable.",
      "Great work team!",
      "Impressive throughput!",
      "Low latency achieved.",
      "System is scaling well.",
      "No errors reported.",
      "All green!",
      "Outstanding performance!",
      "High availability confirmed.",
      "Load test passed!",
      "Optimization worked!",
      "Memory usage is optimal.",
      "CPU usage under control.",
      "Network throughput excellent.",
      "Response times are great!",
      "Mission accomplished!"
  };

  public MessageGenerator(BlockingQueue<MessageWrapper> messageQueue, int totalMessages) {
    this.messageQueue = messageQueue;
    this.totalMessages = totalMessages;
    this.messagesGenerated = new AtomicInteger(0);
    this.random = new Random();
  }

  @Override
  public void run() {
    System.out.println("Message generator started...");

    try {
      while (messagesGenerated.get() < totalMessages) {
        MessageWrapper wrapper = generateMessage();
        messageQueue.put(wrapper);
        messagesGenerated.incrementAndGet();

        if (messagesGenerated.get() % 10000 == 0) {
          System.out.println("Generated " + messagesGenerated.get() + " messages");
        }
      }
      System.out.println("Message generation complete: " + messagesGenerated.get() + " messages");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.println("Message generator interrupted");
    }
  }

  private MessageWrapper generateMessage() {
    int userId = random.nextInt(100000) + 1;
    String username = "user" + userId;
    String message = SAMPLE_MESSAGES[random.nextInt(SAMPLE_MESSAGES.length)];
    String timestamp = Instant.now().toString();
    int roomId = random.nextInt(20) + 1;

    MessageType messageType;
    int typeRand = random.nextInt(100);
    if (typeRand < 90) {
      messageType = MessageType.TEXT;
    } else if (typeRand < 95) {
      messageType = MessageType.JOIN;
    } else {
      messageType = MessageType.LEAVE;
    }

    ChatMessage chatMessage = new ChatMessage(
        String.valueOf(userId),
        username,
        message,
        timestamp,
        messageType
    );

    return new MessageWrapper(chatMessage, roomId);
  }

  public int getMessagesGenerated() {
    return messagesGenerated.get();
  }
}