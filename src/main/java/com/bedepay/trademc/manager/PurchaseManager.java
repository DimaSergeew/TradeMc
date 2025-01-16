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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Менеджер для обработки покупок с TradeMC
 */
public class PurchaseManager {
    private final TradeMc plugin;
    private static final boolean HAS_PARSE_STRING;
    
    // Проверяем наличие метода parseString в JsonParser
    static {
        boolean parseStringExists = false;
        try {
            Class.forName("com.google.gson.JsonParser");
            parseStringExists = JsonParser.class.getDeclaredMethod("parseString", String.class) != null;
        } catch (Exception ignore) {
        }
        HAS_PARSE_STRING = parseStringExists;
    }
    
    public PurchaseManager(TradeMc plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Проверяет наличие новых покупок
     * @param isRetryCall флаг повторной попытки
     */
    public void checkNewPurchases(boolean isRetryCall) {
        String shopsParam = "shops=" + plugin.getConfig().getString("shops", "0");
        String response = callTradeMcApi("shop", "getLastPurchases", shopsParam);

        if (response.contains("\"error\"")) {
            plugin.getLogger().warning("Error getting purchases: " + response);
            if (!isRetryCall) {
                retryPurchaseCheck();
            }
            return;
        }

        processPurchasesResponse(response);
    }
    
    /**
     * Повторяет попытку проверки покупок при ошибке
     */
    private void retryPurchaseCheck() {
        int maxAttempts = plugin.getConfig().getInt("retry-attempts", 3);
        int retryDelay = plugin.getConfig().getInt("retry-delay-seconds", 5);
        
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                Thread.sleep(1000L * retryDelay);
                plugin.getLogger().info("Retry attempt #" + i);
                checkNewPurchases(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
    
    /**
     * Обрабатывает callback от TradeMC
     */
    public void handlePurchaseCallback(String jsonStr) {
        try {
            JsonElement root = parseJson(jsonStr);
            if (!root.isJsonObject()) return;
            
            JsonObject obj = root.getAsJsonObject();
            
            // Проверка хэша для безопасности
            if (!validateHash(obj)) {
                plugin.getLogger().warning("Invalid hash in callback!");
                return;
            }
            
            String buyerName = obj.has("buyer") ? obj.get("buyer").getAsString() : "";
            
            if (obj.has("items") && obj.get("items").isJsonArray()) {
                JsonArray items = obj.get("items").getAsJsonArray();
                processItems(buyerName, items);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error processing callback: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Обрабатывает список купленных предметов
     */
    private void processItems(String buyerName, JsonArray items) {
        for (JsonElement el : items) {
            if (!el.isJsonObject()) continue;
            
            JsonObject itemObj = el.getAsJsonObject();
            String itemId = itemObj.has("id") ? itemObj.get("id").getAsString() : "UnknownID";
            boolean result = itemObj.has("result") && itemObj.get("result").getAsBoolean();
            
            if (!result) {
                plugin.getLogger().warning("Item ID=" + itemId + " not delivered. Result: false");
                continue;
            }
            
            String uniqueKey = buyerName + "_" + itemId;
            if (!plugin.getConfigManager().getProcessedPurchases().contains(uniqueKey)) {
                processPurchase(buyerName, itemId);
            }
        }
    }
    
    /**
     * Обрабатывает одну покупку
     */
    private void processPurchase(String buyerName, String itemId) {
        String uniqueKey = buyerName + "_" + itemId;
        plugin.getConfigManager().getProcessedPurchases().add(uniqueKey);
        
        Player player = Bukkit.getPlayerExact(buyerName);
        if (player != null && player.isOnline()) {
            giveDonation(buyerName, "Item#" + itemId);
        } else {
            plugin.getConfigManager().getPendingPurchases()
                .computeIfAbsent(buyerName.toLowerCase(), k -> new ArrayList<>())
                .add("Item#" + itemId);
            plugin.getLogger().info("Player " + buyerName + " offline, donation 'Item#" + itemId + "' stored.");
        }
    }
    
    /**
     * Выдает донат игроку
     */
    public void giveDonation(String buyer, String itemName) {
        // Выполнение команды выдачи доната
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + buyer + " group add VIP");
        
        // Логирование в файл
        String logMessage = plugin.getConfigManager().getLocaleMsg("messages.purchase-log")
            .replace("%buyer%", buyer)
            .replace("%item%", itemName);
        Utils.logToFile(plugin, logMessage);
        
        // Запись в базу данных
        if (plugin.getDatabaseManager().isEnabled()) {
            plugin.getDatabaseManager().logDonation(buyer, itemName);
        }
        
        // Оповещение всех игроков
        String broadcastMsg = Utils.color(
            plugin.getConfigManager().getLocaleMsg("messages.purchase-broadcast")
                .replace("%buyer%", buyer)
                .replace("%item%", itemName)
        );
        
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(broadcastMsg);
        }
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
        if (HAS_PARSE_STRING) {
            return JsonParser.parseString(json);
        } else {
            return JsonParser.parseReader(new StringReader(json));
        }
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
    private void processPurchasesResponse(String response) {
        try {
            JsonElement root = parseJson(response);
            if (!root.isJsonObject()) return;
            
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("response") && obj.get("response").isJsonArray()) {
                JsonArray purchases = obj.get("response").getAsJsonArray();
                for (JsonElement el : purchases) {
                    if (el.isJsonObject()) {
                        JsonObject purchase = el.getAsJsonObject();
                        String buyer = purchase.has("buyer") ? purchase.get("buyer").getAsString() : "";
                        JsonObject itemObj = purchase.has("item") ? purchase.get("item").getAsJsonObject() : null;
                        String itemId = (itemObj != null && itemObj.has("id")) ? itemObj.get("id").getAsString() : "";
                        
                        if (!buyer.isEmpty() && !itemId.isEmpty()) {
                            processPurchase(buyer, itemId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error processing purchases response: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void processPendingPurchases(String playerName) {
        Map<String, List<String>> pendingPurchases = plugin.getConfigManager().getPendingPurchases();
        if (pendingPurchases.containsKey(playerName)) {
            List<String> items = pendingPurchases.get(playerName);
            if (items != null) {
                for (String itemName : new ArrayList<>(items)) {
                    giveDonation(playerName, itemName);
                }
                items.clear();
                plugin.getConfigManager().saveDataFile();
            }
        }
    }
}