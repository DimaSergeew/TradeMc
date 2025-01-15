package com.bedepay.trademc;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

public class TradeMc extends JavaPlugin {

    // Проверяем, доступен ли метод JsonParser.parseString(...)
    private static final boolean HAS_PARSE_STRING;
    static {
        boolean parseStringExists = false;
        try {
            Method m = JsonParser.class.getDeclaredMethod("parseString", String.class);
            parseStringExists = (m != null);
        } catch (NoSuchMethodException ignore) {
        }
        HAS_PARSE_STRING = parseStringExists;
    }

    // --- Основные конфиги ---
    private FileConfiguration config;         // config.yml
    private FileConfiguration locale;         // locale.yml

    // --- Работа с data.yml ---
    private File dataFile;                    // файл data.yml
    private FileConfiguration dataConfig;     // конфигурация data.yml

    // Храним уникальные ключи уже выданных покупок: buyer_itemId
    private final Set<String> processedPurchases = new HashSet<>();

    // Отложенные покупки (buyer -> список названий товаров)
    private final Map<String, List<String>> pendingPurchases = new HashMap<>();

    @Override
    public void onEnable() {
        // Загружаем основной config.yml
        saveDefaultConfig();
        config = getConfig();

        // Загружаем locale.yml
        loadLocale();

        // Создаём/загружаем data.yml
        loadDataFile();
        loadProcessedPurchases();
        loadPendingPurchases();

        // Выведем в консоль, что за сервер и GSON у нас
        getLogger().info("Server brand: " + Bukkit.getName() 
            + ", version: " + Bukkit.getBukkitVersion());
        getLogger().info("Has JsonParser.parseString? " + HAS_PARSE_STRING);

        // Однократная проверка покупок при старте (false = не ретраим в этом вызове)
        checkNewPurchases(false);

        // Регулярная проверка покупок через указанный интервал
        int interval = config.getInt("check-interval-seconds", 60);
        Bukkit.getScheduler().scheduleSyncRepeatingTask(
                this,
                () -> checkNewPurchases(false),
                20L * interval,
                20L * interval
        );

        // Слушатель входа игроков — выдадим отложенные покупки
        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(), this);

        getLogger().info("TradeMC плагин включен.");
    }

    @Override
    public void onDisable() {
        // Сохраняем данные и делаем бэкап
        saveDataFile();
        backupDataFile();
        getLogger().info("TradeMC плагин отключен.");
    }

    // --- Обработка команд /trademc ... ---
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("trademc")) return false;

        if (!sender.hasPermission("trademc.use")) {
            sender.sendMessage(color(getLocaleMsg("messages.not-allowed")));
            return true;
        }

        // /trademc check
        if (args.length == 1 && args[0].equalsIgnoreCase("check")) {
            if (!sender.hasPermission("trademc.admin")) {
                sender.sendMessage(color(getLocaleMsg("messages.not-allowed")));
                return true;
            }
            boolean success = testConnection();
            sender.sendMessage(color(success
                ? getLocaleMsg("messages.check-ok")
                : getLocaleMsg("messages.check-fail")));
            return true;
        }

        // /trademc getOnline
        if (args.length == 1 && args[0].equalsIgnoreCase("getOnline")) {
            if (!sender.hasPermission("trademc.admin")) {
                sender.sendMessage(color(getLocaleMsg("messages.not-allowed")));
                return true;
            }
            String response = callTradeMcApi("shop", "getOnline", "shop=" + getFirstShop());
            sender.sendMessage(color(getLocaleMsg("messages.getonline")));
            sender.sendMessage(response);
            return true;
        }

        // /trademc history
        if (args.length == 1 && args[0].equalsIgnoreCase("history")) {
            if (!sender.hasPermission("trademc.admin")) {
                sender.sendMessage(color(getLocaleMsg("messages.not-allowed")));
                return true;
            }
            // Покажем 10 последних записей из лог-файла
            List<String> logLines = loadLogLines(10);
            sender.sendMessage(color(getLocaleMsg("messages.history-title")));
            if (logLines.isEmpty()) {
                sender.sendMessage(color(getLocaleMsg("messages.history-empty")));
            } else {
                for (String line : logLines) {
                    sender.sendMessage(color("&7" + line));
                }
            }
            return true;
        }

        // Иначе подсказка
        sender.sendMessage(color("&e/trademc check, /trademc getOnline, /trademc history"));
        return true;
    }

    /**
     * Проверяем соединение через метод shop.getOnline
     */
    private boolean testConnection() {
        String response = callTradeMcApi("shop", "getOnline", "shop=" + getFirstShop());
        return !response.contains("\"error\"");
    }

    /**
     * Проверка новых покупок через shop.getLastPurchases (shops=...)
     * @param isRetryCall признак, что это повторная попытка (чтобы не зациклиться бесконечно)
     */
    private void checkNewPurchases(boolean isRetryCall) {
        String shopsParam = "shops=" + config.getString("shops", "168130");
        String response = callTradeMcApi("shop", "getLastPurchases", shopsParam);

        if (response.contains("\"error\"")) {
            getLogger().warning("[TradeMC] Ошибка при получении покупок: " + response);
            if (!isRetryCall) {
                int maxAttempts = config.getInt("retry-attempts", 3);
                for (int i = 1; i <= maxAttempts; i++) {
                    try {
                        Thread.sleep(1000L * config.getInt("retry-delay-seconds", 5));
                    } catch (InterruptedException ignored) {}
                    getLogger().info("[TradeMC] Повторная попытка #" + i);
                    checkNewPurchases(true);
                }
            }
            return;
        }

        try {
            // Парсим ответ (через метод parseJson для совместимости)
            JsonElement root = parseJson(response);
            if (!root.isJsonObject()) return;

            JsonObject rootObj = root.getAsJsonObject();
            if (!rootObj.has("response")) return;

            JsonElement respElem = rootObj.get("response");
            if (!respElem.isJsonArray()) return;

            JsonArray purchases = respElem.getAsJsonArray();
            for (JsonElement purchaseElem : purchases) {
                if (!purchaseElem.isJsonObject()) continue;
                JsonObject purchaseObj = purchaseElem.getAsJsonObject();

                String buyer = purchaseObj.has("buyer") 
                    ? purchaseObj.get("buyer").getAsString() 
                    : "";
                JsonObject itemObj = purchaseObj.has("item") 
                    ? purchaseObj.get("item").getAsJsonObject() 
                    : null;
                int itemId = (itemObj != null && itemObj.has("id")) 
                    ? itemObj.get("id").getAsInt() : -1;
                String itemName = (itemObj != null && itemObj.has("name")) 
                    ? itemObj.get("name").getAsString() 
                    : "Unknown";

                String uniqueKey = buyer + "_" + itemId;
                if (!processedPurchases.contains(uniqueKey)) {
                    processedPurchases.add(uniqueKey);
                    saveProcessedPurchases();

                    // Проверяем, онлайн ли игрок
                    Player p = Bukkit.getPlayerExact(buyer);
                    if (p != null && p.isOnline()) {
                        giveDonation(buyer, itemName);
                    } else {
                        // Если офлайн — откладываем до входа
                        pendingPurchases.computeIfAbsent(buyer.toLowerCase(), k -> new ArrayList<>()).add(itemName);
                        savePendingPurchases();
                        getLogger().info("[TradeMC] Игрок " + buyer + " офлайн, донат '" + itemName + "' отложен.");
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("[TradeMC] Исключение при обработке покупок: " + e.getMessage());
        }
    }

    /**
     * Выдача доната, + лог, + широковещательное сообщение
     */
    private void giveDonation(String buyer, String itemName) {
        // Пример команды: LuckPerms
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + buyer + " group add VIP");

        // Запишем в лог
        String logMessage = getLocaleMsg("messages.purchase-log")
                .replace("%buyer%", buyer)
                .replace("%item%", itemName);
        logToFile(logMessage);

        // Цветной анонс всем игрокам
        String broadcastMsg = color(getLocaleMsg("messages.purchase-broadcast")
                .replace("%buyer%", buyer)
                .replace("%item%", itemName));
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(broadcastMsg);
        }
    }

    /**
     * Универсальный метод GET-запроса к API TradeMC
     */
    private String callTradeMcApi(String apiName, String methodName, String params) {
        try {
            int apiVer = config.getInt("api-version", 3);
            String urlStr = "https://api.trademc.org/" + apiName + "." + methodName + "?" + params + "&v=" + apiVer;

            HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);

            int status = con.getResponseCode();
            BufferedReader in = new BufferedReader(new InputStreamReader(
                (status >= 200 && status < 300) ? con.getInputStream() : con.getErrorStream()
            ));

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                sb.append(line);
            }
            in.close();
            con.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\": {\"message\": \"" + e.getMessage() + "\"}}";
        }
    }

    /**
     * Парсим JSON через рефлексию:
     * - Если есть JsonParser.parseString(...), используем
     * - Иначе используем new JsonParser().parse(...)
     */
    private JsonElement parseJson(String json) {
        if (HAS_PARSE_STRING) {
            // Новый метод из GSON 2.8.6+
            return JsonParser.parseString(json);
        } else {
            // Старый метод для 1.8.8/старых GSON
            JsonParser parser = new JsonParser();
            return parser.parse(json);
        }
    }

    /**
     * Берём первый магазин, если их несколько
     */
    private String getFirstShop() {
        String shops = config.getString("shops", "168130");
        String[] parts = shops.split(",");
        return parts[0].trim();
    }

    // --- Работа с data.yml ---
    private void loadDataFile() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            saveResource("data.yml", false);
        }
        dataConfig = new YamlConfiguration();
        try {
            dataConfig.load(dataFile);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().warning("[TradeMC] Не удалось загрузить data.yml: " + e.getMessage());
        }
    }

    private void saveDataFile() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("[TradeMC] Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    /**
     * Резервная копия data.yml при выключении
     */
    private void backupDataFile() {
        try {
            if (dataFile.exists()) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
                String backupName = "data_" + sdf.format(new Date()) + ".yml";
                File backupFile = new File(getDataFolder(), backupName);
                Files.copy(dataFile.toPath(), backupFile.toPath());
            }
        } catch (Exception e) {
            getLogger().warning("[TradeMC] Не удалось сделать бэкап data.yml: " + e.getMessage());
        }
    }

    private void loadProcessedPurchases() {
        if (dataConfig.isList("processed")) {
            for (Object obj : dataConfig.getList("processed")) {
                if (obj instanceof String) {
                    processedPurchases.add((String) obj);
                }
            }
        }
    }

    private void saveProcessedPurchases() {
        dataConfig.set("processed", new ArrayList<>(processedPurchases));
        saveDataFile();
    }

    private void loadPendingPurchases() {
        if (dataConfig.isConfigurationSection("pending")) {
            for (String buyerKey : dataConfig.getConfigurationSection("pending").getKeys(false)) {
                List<String> items = dataConfig.getStringList("pending." + buyerKey);
                pendingPurchases.put(buyerKey, items);
            }
        }
    }

    private void savePendingPurchases() {
        for (Map.Entry<String, List<String>> entry : pendingPurchases.entrySet()) {
            dataConfig.set("pending." + entry.getKey(), entry.getValue());
        }
        saveDataFile();
    }

    // --- Локализация ---
    private void loadLocale() {
        File localeFile = new File(getDataFolder(), "locale.yml");
        if (!localeFile.exists()) {
            saveResource("locale.yml", false);
        }
        locale = YamlConfiguration.loadConfiguration(localeFile);
    }

    private String getLocaleMsg(String path) {
        return locale.getString(path, path);
    }

    private String color(String input) {
        return input.replace("&", "§");
    }

    // --- Запись в лог-файл ---
    private void logToFile(String message) {
        try {
            File logDir = new File(getDataFolder(), "logs");
            if (!logDir.exists()) {
                logDir.mkdir();
            }
            File logFile = new File(logDir, "trademc.log");
            try (FileWriter fw = new FileWriter(logFile, true)) {
                fw.write("[" + new Date() + "] " + message + "\n");
            }
        } catch (IOException e) {
            getLogger().warning("[TradeMC] Не удалось записать в лог: " + e.getMessage());
        }
    }

    /**
     * Читаем последние N строк из logs/trademc.log
     */
    private List<String> loadLogLines(int n) {
        List<String> lines = new ArrayList<>();
        File logFile = new File(getDataFolder(), "logs/trademc.log");
        if (!logFile.exists()) return lines;

        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            long length = raf.length();
            long pointer = length - 1;
            int lineCount = 0;
            StringBuilder sb = new StringBuilder();

            while (pointer >= 0 && lineCount < n) {
                raf.seek(pointer);
                char c = (char) raf.readByte();
                if (c == '\n') {
                    if (sb.length() > 0) {
                        lines.add(sb.reverse().toString());
                        sb.setLength(0);
                        lineCount++;
                    }
                } else {
                    sb.append(c);
                }
                pointer--;
            }
            if (sb.length() > 0 && lineCount < n) {
                lines.add(sb.reverse().toString());
            }
        } catch (IOException e) {
            getLogger().warning("[TradeMC] Не удалось прочитать log-файл: " + e.getMessage());
        }
        Collections.reverse(lines);
        return lines;
    }

    // --- Событие входа игрока ---
    public class PlayerJoinListener implements Listener {
        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            String buyerKey = event.getPlayer().getName().toLowerCase();
            if (pendingPurchases.containsKey(buyerKey)) {
                List<String> items = pendingPurchases.get(buyerKey);
                // Выдаём все отложенные покупки
                for (String itemName : items) {
                    giveDonation(event.getPlayer().getName(), itemName);
                }
                items.clear();
                savePendingPurchases();
            }
        }
    }
}