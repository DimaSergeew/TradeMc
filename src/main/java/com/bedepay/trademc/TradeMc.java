package com.bedepay.trademc;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
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
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;

public class TradeMc extends JavaPlugin implements TabCompleter, Listener {

    // --- GSON совместимость с 1.8.8 ---
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
    private FileConfiguration config;      // config.yml
    private FileConfiguration locale;      // locale.yml

    // --- Data.yml (локальное хранение) ---
    private File dataFile;
    private FileConfiguration dataConfig;

    // Храним уже выданные покупки (buyer_itemId).
    private final Set<String> processedPurchases = new HashSet<>();
    // Если игрок офлайн — откладываем ему донат (buyer -> список товаров).
    private final Map<String, List<String>> pendingPurchases = new HashMap<>();

    // --- MySQL ---
    private Connection connection = null;
    private boolean mysqlEnabled = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();

        // Загружаем локализацию locale.yml
        loadLocale();

        // Проверяем, нужно ли MySQL, если да — подключаемся
        mysqlEnabled = config.getBoolean("mysql.enabled", false);
        if (mysqlEnabled) {
            connectToMySQL();
            createTableIfNotExists();
        }

        // Загружаем/создаём data.yml
        loadDataFile();
        loadProcessedPurchases();
        loadPendingPurchases();

        // Регистрируем слушатель входа игрока
        Bukkit.getPluginManager().registerEvents(this, this);

        // Регистрируем автодополнение для /trademc
        getCommand("trademc").setTabCompleter(this);

        // Однократная проверка покупок при старте
        checkNewPurchases(false);

        // Периодическая проверка покупок
        int interval = config.getInt("check-interval-seconds", 60);
        Bukkit.getScheduler().scheduleSyncRepeatingTask(
            this,
            () -> checkNewPurchases(false),
            20L * interval,
            20L * interval
        );

        getLogger().info("TradeMC плагин включён. GSON parseString? " + HAS_PARSE_STRING);
    }

    @Override
    public void onDisable() {
        // Сохраняем всё в data.yml + бэкап
        saveDataFile();
        backupDataFile();

        // Закрываем MySQL (если активен)
        closeMySQL();

        getLogger().info("TradeMC плагин отключён.");
    }

    // --- Реализация команды /trademc ---
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

        // Подсказка
        sender.sendMessage(color("&e/trademc check, /trademc getOnline, /trademc history"));
        return true;
    }

    // --- Автодополнение подкоманд ---
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("trademc")) return null;

        if (args.length == 1) {
            // Подкоманды
            List<String> subCommands = Arrays.asList("check", "getOnline", "history");
            List<String> result = new ArrayList<>();
            for (String sc : subCommands) {
                if (sc.toLowerCase().startsWith(args[0].toLowerCase())) {
                    result.add(sc);
                }
            }
            return result;
        }
        return null;
    }

    // --- Событие при входе игрока: выдаём отложенные покупки ---
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String buyerKey = event.getPlayer().getName().toLowerCase();
        if (pendingPurchases.containsKey(buyerKey)) {
            List<String> items = pendingPurchases.get(buyerKey);
            for (String itemName : items) {
                giveDonation(event.getPlayer().getName(), itemName);
            }
            items.clear();
            savePendingPurchases();
        }
    }

    // --- Проверка соединения shop.getOnline ---
    private boolean testConnection() {
        String response = callTradeMcApi("shop", "getOnline", "shop=" + getFirstShop());
        return !response.contains("\"error\"");
    }

    // --- Проверяем новые покупки (shops=...) ---
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
                    ? itemObj.get("id").getAsInt() 
                    : -1;
                String itemName = (itemObj != null && itemObj.has("name")) 
                    ? itemObj.get("name").getAsString() 
                    : "Unknown";

                String uniqueKey = buyer + "_" + itemId;
                if (!processedPurchases.contains(uniqueKey)) {
                    processedPurchases.add(uniqueKey);
                    saveProcessedPurchases();

                    // Если онлайн — выдаём сразу, иначе откладываем
                    Player p = Bukkit.getPlayerExact(buyer);
                    if (p != null && p.isOnline()) {
                        giveDonation(buyer, itemName);
                    } else {
                        pendingPurchases.computeIfAbsent(
                            buyer.toLowerCase(), 
                            k -> new ArrayList<>()
                        ).add(itemName);
                        savePendingPurchases();
                        getLogger().info("[TradeMC] Игрок " + buyer + " офлайн, донат '" + itemName + "' отложен.");
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("[TradeMC] Исключение при обработке покупок: " + e.getMessage());
        }
    }

    // --- Выдача доната игроку ---
    private void giveDonation(String buyer, String itemName) {
        // Пример команды (LuckPerms)
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + buyer + " group add VIP");

        // Лог
        String logMessage = getLocaleMsg("messages.purchase-log")
            .replace("%buyer%", buyer)
            .replace("%item%", itemName);
        logToFile(logMessage);

        // Если MySQL включен, пишем в таблицу
        if (mysqlEnabled && connection != null) {
            logDonationToDB(buyer, itemName);
        }

        // Красивый анонс в чат
        String broadcastMsg = color(
            getLocaleMsg("messages.purchase-broadcast")
                .replace("%buyer%", buyer)
                .replace("%item%", itemName)
        );
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(broadcastMsg);
        }
    }

    // --- Запись факта выдачи доната в таблицу tradedonations ---
    private void logDonationToDB(String buyer, String itemName) {
        if (connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tradedonations (buyer, item_name) VALUES (?, ?)")) {
            ps.setString(1, buyer);
            ps.setString(2, itemName);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().warning("[TradeMC] Ошибка записи доната в БД: " + e.getMessage());
        }
    }

    // --- Метод GET-запроса к API ---
    private String callTradeMcApi(String apiName, String methodName, String params) {
        try {
            int apiVer = config.getInt("api-version", 3);
            String urlStr = "https://api.trademc.org/" + apiName + "." + methodName + "?" + params + "&v=" + apiVer;
            HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);

            int status = con.getResponseCode();
            BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    (status >= 200 && status < 300) 
                        ? con.getInputStream() 
                        : con.getErrorStream()
                )
            );
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

    // --- GSON: обёртка для parseString(...) ---
    private JsonElement parseJson(String json) {
        if (HAS_PARSE_STRING) {
            return JsonParser.parseString(json); 
        } else {
            JsonParser parser = new JsonParser();
            return parser.parse(json);
        }
    }

    // --- Работа с данными (data.yml) ---

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

    // --- Лог-файл ---
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

    // --- MySQL подключение и работа ---
    private void connectToMySQL() {
        String host = config.getString("mysql.host", "localhost");
        int port = config.getInt("mysql.port", 3306);
        String database = config.getString("mysql.database", "trade_db");
        String user = config.getString("mysql.user", "root");
        String pass = config.getString("mysql.password", "");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&characterEncoding=UTF-8";

        try {
            // Для старых версий драйвера
            Class.forName("com.mysql.jdbc.Driver"); 
            // Или для новых: Class.forName("com.mysql.cj.jdbc.Driver");
            connection = DriverManager.getConnection(url, user, pass);
            getLogger().info("Успешно подключились к MySQL!");
        } catch (Exception e) {
            getLogger().warning("Не удалось подключиться к MySQL: " + e.getMessage());
            mysqlEnabled = false; // отключим дальнейшие действия
        }
    }

    private void createTableIfNotExists() {
        if (connection == null) return;
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS tradedonations ("
                + "id INT AUTO_INCREMENT PRIMARY KEY,"
                + "buyer VARCHAR(64),"
                + "item_name VARCHAR(255)"
                + ")");
            getLogger().info("Таблица tradedonations проверена/создана.");
        } catch (SQLException e) {
            getLogger().warning("Ошибка при создании таблицы: " + e.getMessage());
        }
    }

    private void closeMySQL() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {}
        }
    }

    private String getFirstShop() {
        String shops = config.getString("shops", "168130");
        String[] parts = shops.split(",");
        return parts[0].trim();
    }
}