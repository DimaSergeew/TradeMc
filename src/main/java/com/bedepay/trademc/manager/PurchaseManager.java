package com.bedepay.trademc.manager;

import com.bedepay.trademc.TradeMc;
import com.bedepay.trademc.util.Utils;
import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Менеджер для обработки покупок с TradeMC
 */
public class PurchaseManager {
    private final TradeMc plugin;
    private final boolean isCallbackEnabled;

    // Набор разрешённых команд для повышения безопасности
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
        "lp user %player% group set Guardian",
        "give %player% diamond 1"
        // Добавьте другие разрешённые команды здесь
    );

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
                String shopsParam = "shop=" + plugin.getConfig().getString("shops", "0");
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

            // Обработка команд из rcon
            if (itemObj.has("rcon") && itemObj.get("rcon").isJsonArray()) {
                JsonArray rconCommands = itemObj.get("rcon").getAsJsonArray();
                for (JsonElement cmdElement : rconCommands) {
                    if (cmdElement.isJsonArray()) {
                        JsonArray cmdArray = cmdElement.getAsJsonArray();
                        if (cmdArray.size() >= 1) {
                            String command = cmdArray.get(0).getAsString();
                            executeCommand(buyerName, command, itemName);
                        }
                    }
                }
            }

            // Дополнительная обработка, если необходимо
            // Например, логирование, оповещения и т.д.
        }
    }

    /**
     * Выполняет команду от имени консоли
     */
    private void executeCommand(String buyer, String command, String itemName) {
        String executedCommand = command.replace("%player%", buyer);
        plugin.getLogger().info("Executing command for purchase: " + executedCommand);

        // Проверяем, разрешена ли команда для выполнения
        if (!ALLOWED_COMMANDS.contains(command)) {
            plugin.getLogger().warning("Попытка выполнения неразрешённой команды: " + command);
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), executedCommand);
                plugin.getLogger().info("Command executed successfully for player: " + buyer);

                // Логирование и оповещение
                logAndNotify(buyer, itemName);
            } catch (Exception e) {
                plugin.getLogger().severe("Error executing command for player " + buyer + ": " + e.getMessage());
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

            // Обработка команд из rcon
            if (itemObj.has("rcon") && itemObj.get("rcon").isJsonArray()) {
                JsonArray rconCommands = itemObj.get("rcon").getAsJsonArray();
                for (JsonElement cmdElement : rconCommands) {
                    if (cmdElement.isJsonArray()) {
                        JsonArray cmdArray = cmdElement.getAsJsonArray();
                        if (cmdArray.size() >= 1) {
                            String command = cmdArray.get(0).getAsString();
                            executeCommand(buyer, command, itemName);
                        }
                    }
                }
            }

            // Дополнительная обработка, если необходимо
            // Например, логирование, оповещения и т.д.

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
            for (String itemId : new ArrayList<>(items)) {
                // В данном случае, команды уже отправляются через callback, поэтому можно просто убрать запись из pendingPurchases
                items.remove(itemId);
                plugin.getLogger().info("Processed pending donation '" + itemId + "' for player " + playerName);
            }
            plugin.getConfigManager().saveAll();
        }
    }
}