package com.chatflow.client;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

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
    ResponseCallback callback = callbackRef.get();
    if (callback != null) {
      callback.onResponse(System.nanoTime());
      callbackRef.set(null); // Clear after use
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
    // Handle error
  }

  public void setResponseCallback(ResponseCallback callback) {
    callbackRef.set(callback);
  }

  public interface ResponseCallback {
    void onResponse(long receiveTimeNanos);
  }
}