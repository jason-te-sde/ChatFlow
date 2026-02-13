package com.chatflow.client;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Custom WebSocket client that supports response callbacks for RTT measurement
 * Extends WebSocketClient to add callback mechanism for precise timing
 */
public class ConnectionWithCallback extends WebSocketClient {
  private final AtomicReference<ResponseCallback> callbackRef;
  private final Runnable onOpenCallback;
  private final Runnable onCloseCallback;

  public ConnectionWithCallback(URI serverUri,
      Runnable onOpenCallback,
      Runnable onCloseCallback) {
    super(serverUri);
    this.callbackRef = new AtomicReference<>();
    this.onOpenCallback = onOpenCallback;
    this.onCloseCallback = onCloseCallback;
  }

  @Override
  public void onOpen(ServerHandshake handshake) {
    if (onOpenCallback != null) {
      onOpenCallback.run();
    }
  }

  @Override
  public void onMessage(String message) {
    // Don't clear callback - let it be naturally overwritten by next message
    // This prevents race conditions in high-throughput scenarios
    ResponseCallback callback = callbackRef.get();
    if (callback != null) {
      callback.onResponse(System.nanoTime());
    }
  }

  @Override
  public void onClose(int code, String reason, boolean remote) {
    if (onCloseCallback != null) {
      onCloseCallback.run();
    }
  }

  @Override
  public void onError(Exception ex) {
    // Silent error handling to avoid spam
    // Errors are handled at higher level through timeouts and retries
  }

  /**
   * Set callback to be invoked when response is received
   * Callback will be overwritten by subsequent calls
   */
  public void setResponseCallback(ResponseCallback callback) {
    callbackRef.set(callback);
  }

  /**
   * Callback interface for receiving response notifications
   */
  public interface ResponseCallback {
    void onResponse(long receiveTimeNanos);
  }
}