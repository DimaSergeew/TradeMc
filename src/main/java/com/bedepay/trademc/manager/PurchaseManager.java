package com.bedepay.trademc.manager;

import com.bedepay.trademc.TradeMc;
import com.bedepay.trademc.util.Utils;
import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Менеджер для обработки покупок с TradeMC
 */
public class PurchaseManager {
    private final TradeMc plugin;
    private final boolean isCallbackEnabled;

    public PurchaseManager(TradeMc plugin) {
        this.plugin = plugin;
        this.isCallbackEnabled = plugin.getConfig().getBoolean("callback.enabled", false);

        // Логируем режим работы
        plugin.getLogger().info("TradeMC работает в режиме: " +
            (isCallbackEnabled ? "Callback (ожидание уведомлений)" : "Poll (периодическая проверка)"));
    }

    /**
     * Проверяет наличие новых покупок (используется только в режиме Poll)
     */
    public void checkNewPurchases(boolean isRetryCall) {
        if (isCallbackEnabled) {
            // В режиме callback не проверяем покупки периодически
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                String shopsParam = "shops=" + plugin.getConfig().getString("shops", "0");
                String response = callTradeMcApi("shop", "getLastPurchases", shopsParam);

                plugin.getLogger().info("[Poll] Проверка покупок...");

                if (response.contains("\"error\"")) {
                    plugin.getLogger().warning("[Poll] Ошибка получения покупок: " + response);
                    if (!isRetryCall) {
                        retryPurchaseCheck();
                    }
                    return;
                }

                processPurchasesResponse(response, "Poll");
            } catch (Exception e) {
                plugin.getLogger().severe("[Poll] Ошибка проверки покупок: " + e.getMessage());
                e.printStackTrace();
            }
        }, plugin.getExecutorService());
    }

    /**
     * Повторяет попытку проверки покупок при ошибке
     */
    private void retryPurchaseCheck() {
        int maxAttempts = plugin.getConfig().getInt("retry-attempts", 3);
        int retryDelay = plugin.getConfig().getInt("retry-delay-seconds", 5);

        for (int i = 1; i <= maxAttempts; i++) {
            final int attempt = i;
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                plugin.getLogger().info("Retry attempt #" + attempt);
                checkNewPurchases(true);
            }, 20L * retryDelay);
        }
    }

    /**
     * Обрабатывает callback от TradeMC
     */
    public void handlePurchaseCallback(String jsonData) {
        if (!isCallbackEnabled) {
            plugin.getLogger().warning("[Callback] Получен callback, но режим callback отключен!");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                plugin.getLogger().info("[Callback] Получено уведомление о покупке");
                processPurchasesResponse(jsonData, "Callback");
            } catch (Exception e) {
                plugin.getLogger().severe("[Callback] Ошибка обработки callback: " + e.getMessage());
                e.printStackTrace();
            }
        }, plugin.getExecutorService());
    }

    /**
     * Обрабатывает список купленных предметов
     */
    private void processItems(String buyerName, JsonArray items) {
        for (JsonElement el : items) {
            if (!el.isJsonObject()) continue;

            JsonObject itemObj = el.getAsJsonObject();
            String itemId = itemObj.has("id") ? itemObj.get("id").getAsString() : "UnknownID";
            String itemName = itemObj.has("name") ? itemObj.get("name").getAsString() : "Item#" + itemId;
            boolean result = itemObj.has("result") && itemObj.get("result").getAsBoolean();

            if (!result) {
                plugin.getLogger().warning("Item ID=" + itemId + " not delivered. Result: false");
                continue;
            }

            String uniqueKey = buyerName + "_" + itemId;
            if (plugin.getConfigManager().getProcessedPurchases().add(uniqueKey)) {
                processPurchase(buyerName, itemId, itemName);
            } else {
                plugin.getLogger().info("[" + (isCallbackEnabled ? "Callback" : "Poll") + "] Покупка уже обработана: " + uniqueKey);
            }
        }
    }

    /**
     * Обрабатывает одну покупку
     */
    private void processPurchase(String buyer, String itemId, String itemName) {
        Player player = Bukkit.getPlayerExact(buyer);
        if (player != null && player.isOnline()) {
            giveDonation(buyer, itemName);
        } else {
            plugin.getConfigManager().getPendingPurchases()
                .computeIfAbsent(buyer.toLowerCase(), k -> new ArrayList<>())
                .add(itemName);
            plugin.getLogger().info("Player " + buyer + " offline, donation '" + itemName + "' stored.");
            plugin.getConfigManager().saveCurrentData();
        }
    }

    /**
     * Выдает донат игроку
     */
    public void giveDonation(String buyer, String itemName) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                // Выполнение команды от имени консоли
                String command = itemName.replace("%player%", buyer);
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);

                // Логирование и оповещение
                logAndNotify(buyer, itemName);
            } catch (Exception e) {
                plugin.getLogger().severe("Error giving donation: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void logAndNotify(String buyer, String itemName) {
        // Логирование в файл
        String logMessage = plugin.getConfigManager().getLocaleMsg("messages.purchase-log")
            .replace("%buyer%", buyer)
            .replace("%item%", itemName);
        Utils.logToFile(plugin, logMessage);

        // Запись в БД
        if (plugin.getDatabaseManager().isEnabled()) {
            plugin.getDatabaseManager().logDonation(buyer, itemName);
        }

        // Оповещение
        String broadcastMsg = Utils.color(
            plugin.getConfigManager().getLocaleMsg("messages.purchase-broadcast")
                .replace("%buyer%", buyer)
                .replace("%item%", itemName)
        );

        plugin.getServer().broadcastMessage(broadcastMsg);
    }

    /**
     * Отправляет запрос к API TradeMC
     */
    public String callTradeMcApi(String controller, String action, String params) {
        HttpURLConnection con = null;
        try {
            int apiVer = plugin.getConfig().getInt("api-version", 3);
            String urlStr = "https://api.trademc.org/" + controller + "." + action + "?" + params + "&v=" + apiVer;
            URL url = URI.create(urlStr).toURL();
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);

            int status = con.getResponseCode();
            try (BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    (status >= 200 && status < 300) ? con.getInputStream() : con.getErrorStream(),
                    StandardCharsets.UTF_8
                )
            )) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }
        } catch (Exception e) {
            return "{\"error\": {\"message\": \"" + e.getMessage() + "\"}}";
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    /**
     * Парсит JSON строку в JsonElement
     */
    private JsonElement parseJson(String json) {
        return JsonParser.parseString(json);
    }

    /**
     * Проверяет валидность хэша в callback запросе
     */
    private boolean validateHash(JsonObject obj) {
        if (!obj.has("hash")) return false;

        String givenHash = obj.get("hash").getAsString();
        obj.remove("hash");
        String pureJson = obj.toString();

        String shopKey = plugin.getConfig().getString("callback-key", "");
        if (shopKey.isEmpty()) {
            plugin.getLogger().warning("callback-key not set in config.yml!");
            return false;
        }

        String calcHash = Utils.sha256(pureJson + shopKey);
        return calcHash.equalsIgnoreCase(givenHash);
    }

    /**
     * Обрабатывает ответ от API с покупками
     */
    private void processPurchasesResponse(String response, String mode) {
        try {
            JsonElement root = parseJson(response);
            if (!root.isJsonObject()) {
                plugin.getLogger().warning("[" + mode + "] Неверный формат JSON");
                return;
            }

            JsonObject obj = root.getAsJsonObject();

            // Проверяем хэш для callback режима
            if (isCallbackEnabled && !validateHash(obj)) {
                plugin.getLogger().warning("[" + mode + "] Неверная подпись callback");
                return;
            }

            // Разная обработка для callback и poll режимов
            if (isCallbackEnabled) {
                // Для callback проверяем items
                if (obj.has("items") && obj.get("items").isJsonArray()) {
                    String buyer = obj.has("buyer") ? obj.get("buyer").getAsString() : "";
                    processItems(buyer, obj.get("items").getAsJsonArray());
                }
            } else {
                // Для poll проверяем response
                if (obj.has("response") && obj.get("response").isJsonArray()) {
                    JsonArray purchases = obj.get("response").getAsJsonArray();
                    for (JsonElement el : purchases) {
                        if (el.isJsonObject()) {
                            processSinglePurchase(el.getAsJsonObject(), mode);
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("[" + mode + "] Ошибка обработки ответа: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Обрабатывает одну покупку
     */
    private void processSinglePurchase(JsonObject purchase, String mode) {
        try {
            String buyer = purchase.has("buyer") ? purchase.get("buyer").getAsString().toLowerCase() : "";
            JsonObject itemObj = purchase.has("item") ? purchase.get("item").getAsJsonObject() : null;

            if (itemObj == null || buyer.isEmpty()) {
                plugin.getLogger().warning("[" + mode + "] Неполные данные покупки");
                return;
            }

            String itemId = itemObj.has("id") ? itemObj.get("id").getAsString() : "";
            String itemName = itemObj.has("name") ? itemObj.get("name").getAsString() : itemId;

            String uniqueKey = buyer + "_" + itemId;
            if (plugin.getConfigManager().getProcessedPurchases().add(uniqueKey)) {
                plugin.getLogger().info("[" + mode + "] Обработка покупки: " + uniqueKey);
                processPurchase(buyer, itemId, itemName);
            } else {
                plugin.getLogger().info("[" + mode + "] Покупка уже обработана: " + uniqueKey);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("[" + mode + "] Ошибка обработки покупки: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Обрабатывает ожидающие покупки при входе игрока
     */
    public void processPendingPurchases(String playerName) {
        List<String> items = plugin.getConfigManager().getPendingPurchases().get(playerName);
        if (items != null && !items.isEmpty()) {
            for (String itemName : new ArrayList<>(items)) {
                giveDonation(playerName, itemName);
                items.remove(itemName);
            }
            plugin.getConfigManager().saveCurrentData();
        }
    }
}