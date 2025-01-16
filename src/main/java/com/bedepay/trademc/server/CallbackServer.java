package com.bedepay.trademc.server;

import com.bedepay.trademc.TradeMc;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.*;

/**
 * Сервер для обработки обратных вызовов от TradeMC
 */
public class CallbackServer {
    private final TradeMc plugin;
    private HttpServer server;
    private boolean enabled;

    public CallbackServer(TradeMc plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("callback.enabled", false);
        if (enabled) {
            start();
        }
    }

    /**
     * Запускает сервер обратных вызовов
     */
    public void start() {
        try {
            String host = plugin.getConfig().getString("callback.host", "0.0.0.0");
            int port = plugin.getConfig().getInt("callback.port", 8080);
            String path = plugin.getConfig().getString("callback.path", "/tradecallback");

            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext(path, new CallbackHandler(plugin));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();

            plugin.getLogger().info("Сервер обратных вызовов запущен на " + host + ":" + port + path);
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось запустить сервер обратных вызовов: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Останавливает сервер обратных вызовов
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            plugin.getLogger().info("Сервер обратных вызовов остановлен");
        }
    }

    /**
     * Проверяет, включен ли сервер
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Обработчик HTTP запросов для callback
     */
    private static class CallbackHandler implements HttpHandler {
        private final TradeMc plugin;

        public CallbackHandler(TradeMc plugin) {
            this.plugin = plugin;
        }

        @Override
        public void handle(HttpExchange exchange) {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            InputStream is = exchange.getRequestBody();
                            String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                            plugin.getLogger().info("[Callback] Получен запрос: " + requestBody);

                            // Валидация и обработка
                            plugin.getPurchaseManager().handlePurchaseCallback(requestBody);

                            // Отправка ответа клиенту
                            String response = "OK";
                            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
                            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                            try (OutputStream os = exchange.getResponseBody()) {
                                os.write(response.getBytes(StandardCharsets.UTF_8));
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Ошибка обработки обратного вызова: " + e.getMessage());
                            try {
                                exchange.sendResponseHeaders(500, -1);
                            } catch (Exception ex) {
                                plugin.getLogger().warning("Ошибка отправки 500 ответа: " + ex.getMessage());
                            }
                        }
                    }
                }.runTaskAsynchronously(plugin);
            } else {
                try {
                    exchange.sendResponseHeaders(405, -1); // Метод не разрешен
                } catch (Exception e) {
                    plugin.getLogger().warning("Ошибка отправки 405 ответа: " + e.getMessage());
                }
            }
        }
    }
}