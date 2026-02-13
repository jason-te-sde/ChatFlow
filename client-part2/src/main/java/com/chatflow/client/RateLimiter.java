package com.chatflow.client;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple token bucket rate limiter to prevent overwhelming the server
 * Implements a basic token bucket algorithm for controlling message send rate
 */
public class RateLimiter {
  private final long permitsPerSecond;
  private final AtomicLong lastRefillTime;
  private final AtomicLong availablePermits;
  private final long refillIntervalNanos;

  public RateLimiter(long permitsPerSecond) {
    this.permitsPerSecond = permitsPerSecond;
    this.lastRefillTime = new AtomicLong(System.nanoTime());
    this.availablePermits = new AtomicLong(permitsPerSecond);
    this.refillIntervalNanos = 1_000_000_000L; // 1 second in nanoseconds
  }

  /**
   * Acquire a permit. Blocks if no permits available.
   * This ensures we don't exceed the configured rate limit.
   */
  public void acquire() {
    while (true) {
      refill();

      long permits = availablePermits.get();
      if (permits > 0) {
        if (availablePermits.compareAndSet(permits, permits - 1)) {
          return; // Successfully acquired a permit
        }
      } else {
        // No permits available, sleep briefly
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /**
   * Try to acquire without blocking
   * Returns true if permit acquired, false otherwise
   */
  public boolean tryAcquire() {
    refill();

    long permits = availablePermits.get();
    if (permits > 0) {
      return availablePermits.compareAndSet(permits, permits - 1);
    }
    return false;
  }

  /**
   * Refill tokens if enough time has passed
   * Uses atomic operations to ensure thread safety
   */
  private void refill() {
    long now = System.nanoTime();
    long lastRefill = lastRefillTime.get();
    long timeSinceLastRefill = now - lastRefill;

    if (timeSinceLastRefill > refillIntervalNanos) {
      if (lastRefillTime.compareAndSet(lastRefill, now)) {
        // Refill tokens to maximum
        availablePermits.set(permitsPerSecond);
      }
    }
  }
}