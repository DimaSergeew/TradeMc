package com.bedepay.trademc.server;

import com.bedepay.trademc.TradeMc;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Сервер для обработки обратных вызовов (callback) от TradeMC
 */
public class CallbackServer {
    private final TradeMc plugin;
    private HttpServer server;
    private boolean enabled;

    public CallbackServer(TradeMc plugin) {
        this.plugin = plugin;
        startServer();
    }

    private void startServer() {
        try {
            String host = plugin.getConfig().getString("callback.host", "0.0.0.0");
            int port = plugin.getConfig().getInt("callback.port", 8080);
            String path = plugin.getConfig().getString("callback.path", "/tradecallback");

            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext(path, new CallbackHandler(plugin));
            server.setExecutor(null); // Использовать дефолтный исполнитель
            server.start();
            enabled = true;
            plugin.getLogger().info("Callback server started on " + host + ":" + port + path);
        } catch (IOException e) {
            plugin.getLogger().severe("Не удалось запустить Callback сервер: " + e.getMessage());
            e.printStackTrace();
            enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled && server != null;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            plugin.getLogger().info("Callback server stopped.");
        }
        enabled = false;
    }

    /**
     * Обработчик запросов от TradeMC
     */
    static class CallbackHandler implements HttpHandler {
        private final TradeMc plugin;

        public CallbackHandler(TradeMc plugin) {
            this.plugin = plugin;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                return;
            }

            InputStream is = exchange.getRequestBody();
            String body = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .reduce("", (accumulator, actual) -> accumulator + actual);

            plugin.getLogger().info("[Callback] Получены данные: " + body);

            // Передаем данные PurchaseManager для обработки
            plugin.getPurchaseManager().handlePurchaseCallback(body);

            String response = "OK";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
}