package com.chatflow.server;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class HealthServer {

  public static void start(int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

    server.createContext("/health", new HttpHandler() {
      @Override
      public void handle(HttpExchange exchange) throws IOException {
        String response = "{\"status\":\"UP\",\"service\":\"ChatFlow WebSocket Server\"}";
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length());

        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
      }
    });

    server.setExecutor(null);
    server.start();
    System.out.println("Health check endpoint started on port " + port + "/health");
  }
}