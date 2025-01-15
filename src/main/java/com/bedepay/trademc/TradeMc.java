package com.bedepay.trademc;

import org.bukkit.Bukkit;
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

import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.Date;

/**
 * Main plugin class for TradeMC integration
 * Handles purchases from TradeMC marketplace and distributes rewards to players
 */
public class TradeMc extends JavaPlugin implements TabCompleter, Listener {

    // --- GSON compatibility with 1.8.8 ---
    private static final boolean HAS_PARSE_STRING; 
    static {
        boolean parseStringExists = false;
        try {
            Class.forName("com.google.gson.JsonParser");
            // Check if parseString method exists
            Method m = JsonParser.class.getDeclaredMethod("parseString", String.class);
            parseStringExists = (m != null);
        } catch (Exception ignore) {
        }
        HAS_PARSE_STRING = parseStringExists;
    }

    // --- Main configs ---
    private FileConfiguration config;      // config.yml
    private FileConfiguration locale;      // locale.yml

    // --- Data.yml (local storage) ---
    private File dataFile;
    private FileConfiguration dataConfig;

    // Store already processed purchases (buyer_itemId)
    private final Set<String> processedPurchases = Collections.synchronizedSet(new HashSet<>());
    // Store pending rewards for offline players (buyer -> list of items)
    private final Map<String, List<String>> pendingPurchases = Collections.synchronizedMap(new HashMap<>());

    // --- MySQL ---
    private Connection connection = null;
    private boolean mysqlEnabled = false;

    // --- Callback HTTP server ---
    private HttpServer callbackServer;
    private boolean callbackEnabled = false;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            config = getConfig();

            // Load localization from locale.yml
            loadLocale();

            // Connect to MySQL if enabled
            mysqlEnabled = config.getBoolean("mysql.enabled", false);
            if (mysqlEnabled) {
                connectToMySQL();
                createTableIfNotExists();
            }

            // Start callback server if enabled
            callbackEnabled = config.getBoolean("callback.enabled", false);
            if (callbackEnabled) {
                String host = config.getString("callback.host", "0.0.0.0");
                int port = config.getInt("callback.port", 8080);
                String path = config.getString("callback.path", "/tradecallback");
                startCallbackServer(host, port, path);
            }

            // Load/create data.yml
            loadDataFile();
            loadProcessedPurchases();
            loadPendingPurchases();

            // Register player join listener
            Bukkit.getPluginManager().registerEvents(this, this);

            // Register tab completer for /trademc
            Objects.requireNonNull(getCommand("trademc")).setTabCompleter(this);

            // Initial purchase check on startup (useful if callback failed)
            checkNewPurchases(false);

            // Periodic purchase check
            int interval = Math.max(config.getInt("check-interval-seconds", 60), 30); // Minimum 30 seconds
            Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                () -> checkNewPurchases(false),
                20L * interval,
                20L * interval
            );

            getLogger().info("TradeMC plugin enabled. GSON parseString? " + HAS_PARSE_STRING);
        } catch (Exception e) {
            getLogger().severe("Error starting plugin: " + e.getMessage());
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            // Save everything to data.yml + backup
            saveDataFile();
            backupDataFile();

            // Close MySQL if active
            closeMySQL();

            // Stop callback server
            stopCallbackServer();

            getLogger().info("TradeMC plugin disabled.");
        } catch (Exception e) {
            getLogger().severe("Error disabling plugin: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- /trademc command implementation ---
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("trademc")) return false;

        if (!sender.hasPermission("trademc.use")) {
            sender.sendMessage(color(getLocaleMsg("messages.not-allowed")));
            return true;
        }

        try {
            // /trademc check
            if (args.length == 1 && args[0].equalsIgnoreCase("check")) {
                if (!sender.hasPermission("trademc.admin")) {
                    sender.sendMessage(color(getLocaleMsg("messages.not-allowed")));
                    return true;
                }
                boolean trademcStatus = testConnection();
                boolean callbackStatus = callbackEnabled && callbackServer != null;
                String trademcStatusMsg = trademcStatus ? "&aTradeMC API: OK" : "&cTradeMC API: FAIL";
                String callbackStatusMsg = callbackStatus ? "&aCallback: OK" : "&cCallback: FAIL";
                sender.sendMessage(color("&eTradeMC Status: " + trademcStatusMsg + ", " + callbackStatusMsg));
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

            // /trademc debugPurchase <buyer> <itemId> <itemName>
            if (args.length >= 3 && args[0].equalsIgnoreCase("debugPurchase")) {
                if (!sender.hasPermission("trademc.admin")) {
                    sender.sendMessage(color(getLocaleMsg("messages.not-allowed")));
                    return true;
                }
                String buyer = args[1];
                String itemId = args[2];
                // Combine remaining args as item name
                String itemName = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                if (itemName.isEmpty()) {
                    itemName = itemId; // Use itemId if no item name provided
                }

                // Create JSON and calculate hash inside plugin
                JsonObject purchaseObj = new JsonObject();
                purchaseObj.addProperty("shop_id", 168130); // Can be made configurable
                purchaseObj.addProperty("buyer", buyer);
                JsonArray itemsArray = new JsonArray();
                JsonObject itemObj = new JsonObject();
                itemObj.addProperty("id", itemId);
                itemObj.addProperty("name", itemName);
                itemObj.addProperty("cost", 9.99); // Can be made configurable
                itemObj.addProperty("result", true);
                itemsArray.add(itemObj);
                purchaseObj.add("items", itemsArray);

                // Get callback-key from config.yml
                String shopKey = config.getString("callback-key", "");
                if (shopKey.isEmpty()) {
                    sender.sendMessage(color("&cError: callback-key not set in config.yml!"));
                    return true;
                }

                // Convert object to string for hashing
                String pureJson = purchaseObj.toString();
                String hash = sha256(pureJson + shopKey);
                purchaseObj.addProperty("hash", hash);

                String jsonStr = purchaseObj.toString();

                // Process purchase
                try {
                    handlePurchaseCallback(jsonStr);
                    sender.sendMessage(color("&aDebug purchase processed. Check console/logs for details."));
                } catch (Exception e) {
                    sender.sendMessage(color("&cError: " + e.getMessage()));
                }
                return true;
            }

            // Show help
            sender.sendMessage(color("&e/trademc check, /trademc getOnline, /trademc history, /trademc debugPurchase <buyer> <itemId> <itemName>"));
            return true;
        } catch (Exception e) {
            sender.sendMessage(color("&cCommand execution error: " + e.getMessage()));
            getLogger().severe("Command execution error: " + e.getMessage());
            e.printStackTrace();
            return true;
        }
    }

    // --- Tab completion for subcommands ---
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("trademc")) return null;

        if (args.length == 1) {
            // Subcommands
            List<String> subCommands = Arrays.asList("check", "getOnline", "history", "debugPurchase");
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

    // --- Player join event: give pending rewards ---
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        String buyerKey = event.getPlayer().getName().toLowerCase();
        if (pendingPurchases.containsKey(buyerKey)) {
            List<String> items = pendingPurchases.get(buyerKey);
            if (items != null) {
                for (String itemName : new ArrayList<>(items)) { // Create copy of list
                    giveDonation(event.getPlayer().getName(), itemName);
                }
                items.clear();
                savePendingPurchases();
            }
        }
    }

    // --- Test shop.getOnline connection ---
    private boolean testConnection() {
        String response = callTradeMcApi("shop", "getOnline", "shop=" + getFirstShop());
        return !response.contains("\"error\"");
    }

    // --- Check new purchases (shops=...) ---
    private void checkNewPurchases(boolean isRetryCall) {
        String shopsParam = "shops=" + config.getString("shops", "168130");
        String response = callTradeMcApi("shop", "getLastPurchases", shopsParam);

        if (response.contains("\"error\"")) {
            getLogger().warning("[TradeMC] Error getting purchases: " + response);
            if (!isRetryCall) {
                int maxAttempts = config.getInt("retry-attempts", 3);
                int retryDelay = config.getInt("retry-delay-seconds", 5);
                for (int i = 1; i <= maxAttempts; i++) {
                    try {
                        Thread.sleep(1000L * retryDelay);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    getLogger().info("[TradeMC] Retry attempt #" + i);
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
                    ? purchaseObj.getAsJsonObject("item")
                    : null;
                String itemId = (itemObj != null && itemObj.has("id")) 
                    ? itemObj.get("id").getAsString() 
                    : "UnknownID";
                String itemName = (itemObj != null && itemObj.has("name")) 
                    ? itemObj.get("name").getAsString() 
                    : "Unknown";

                String uniqueKey = buyer + "_" + itemId;
                if (!processedPurchases.contains(uniqueKey)) {
                    processedPurchases.add(uniqueKey);
                    saveProcessedPurchases();

                    // Give immediately if online, otherwise store for later
                    Player p = Bukkit.getPlayerExact(buyer);
                    if (p != null && p.isOnline()) {
                        giveDonation(buyer, itemName);
                    } else {
                        pendingPurchases.computeIfAbsent(
                            buyer.toLowerCase(), 
                            k -> Collections.synchronizedList(new ArrayList<>())
                        ).add(itemName);
                        savePendingPurchases();
                        getLogger().info("Player " + buyer + " offline, donation '" + itemName + "' stored.");
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("[TradeMC] Exception processing purchases: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- Give donation to player ---
    private void giveDonation(String buyer, String itemName) {
        // Example command (LuckPerms)
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + buyer + " group add VIP");

        // Log
        String logMessage = getLocaleMsg("messages.purchase-log")
            .replace("%buyer%", buyer)
            .replace("%item%", itemName);
        logToFile(logMessage);

        // Write to MySQL if enabled
        if (mysqlEnabled && connection != null) {
            logDonationToDB(buyer, itemName);
        }

        // Broadcast to chat
        String broadcastMsg = color(
            getLocaleMsg("messages.purchase-broadcast")
                .replace("%buyer%", buyer)
                .replace("%item%", itemName)
        );
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(broadcastMsg);
        }
    }

    // --- Log donation to tradedonations table ---
    private void logDonationToDB(String buyer, String itemName) {
        if (connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tradedonations (buyer, item_name) VALUES (?, ?)")) {
            ps.setString(1, buyer);
            ps.setString(2, itemName);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().warning("[TradeMC] Error writing donation to DB: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- GET request to API ---
    private String callTradeMcApi(String apiName, String methodName, String params) {
        HttpURLConnection con = null;
        try {
            int apiVer = config.getInt("api-version", 3);
            String urlStr = "https://api.trademc.org/" + apiName + "." + methodName + "?" + params + "&v=" + apiVer;
            con = (HttpURLConnection) new URL(urlStr).openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);

            int status = con.getResponseCode();
            try (BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    (status >= 200 && status < 300) 
                        ? con.getInputStream() 
                        : con.getErrorStream(),
                    StandardCharsets.UTF_8
                )
            )) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return "{\"error\": {\"message\": \"" + e.getMessage() + "\"}}";
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    // --- GSON: wrapper for parseString(...) ---
    private JsonElement parseJson(String json) {
        if (HAS_PARSE_STRING) {
            return JsonParser.parseString(json); 
        } else {
            return new JsonParser().parse(json);
        }
    }

    // --- Data handling (data.yml) ---

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
            getLogger().warning("[TradeMC] Failed to load data.yml: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void saveDataFile() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("[TradeMC] Failed to save data.yml: " + e.getMessage());
            e.printStackTrace();
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
            getLogger().warning("[TradeMC] Failed to backup data.yml: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadProcessedPurchases() {
        if (dataConfig.isList("processed")) {
            processedPurchases.addAll(dataConfig.getStringList("processed"));
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
                pendingPurchases.put(buyerKey, Collections.synchronizedList(new ArrayList<>(items)));
            }
        }
    }

    private void savePendingPurchases() {
        // Clear "pending" section before saving
        dataConfig.set("pending", null);
        for (Map.Entry<String, List<String>> entry : pendingPurchases.entrySet()) {
            dataConfig.set("pending." + entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        saveDataFile();
    }

    // --- Localization ---
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

    // --- Log file ---
    private void logToFile(String message) {
        try {
            File logDir = new File(getDataFolder(), "logs");
            if (!logDir.exists() && !logDir.mkdir()) {
                getLogger().warning("[TradeMC] Failed to create logs directory");
                return;
            }
            File logFile = new File(logDir, "trademc.log");
            try (FileWriter fw = new FileWriter(logFile, true);
                 BufferedWriter bw = new BufferedWriter(fw)) {
                bw.write("[" + new Date() + "] " + message);
                bw.newLine();
            }
        } catch (IOException e) {
            getLogger().warning("[TradeMC] Failed to write to log: " + e.getMessage());
            e.printStackTrace();
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
            getLogger().warning("[TradeMC] Failed to read log file: " + e.getMessage());
            e.printStackTrace();
        }
        Collections.reverse(lines);
        return lines;
    }

    // --- MySQL connection and operations ---
    private void connectToMySQL() {
        String host = config.getString("mysql.host", "localhost");
        int port = config.getInt("mysql.port", 3306);
        String database = config.getString("mysql.database", "trade_db");
        String user = config.getString("mysql.user", "root");
        String pass = config.getString("mysql.password", "");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database + 
                    "?useSSL=false&characterEncoding=UTF-8&autoReconnect=true";

        try {
            // For old driver versions
            try {
                Class.forName("com.mysql.jdbc.Driver");
            } catch (ClassNotFoundException e) {
                // For new versions
                Class.forName("com.mysql.cj.jdbc.Driver");
            }
            connection = DriverManager.getConnection(url, user, pass);
            getLogger().info(color(getLocaleMsg("messages.mysql-ok")));
        } catch (Exception e) {
            getLogger().warning(color(getLocaleMsg("messages.mysql-fail")) + " " + e.getMessage());
            e.printStackTrace();
            mysqlEnabled = false; // disable further operations
        }
    }

    private void createTableIfNotExists() {
        if (connection == null) return;
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS tradedonations ("
                + "id INT AUTO_INCREMENT PRIMARY KEY,"
                + "buyer VARCHAR(64) NOT NULL,"
                + "item_name VARCHAR(255) NOT NULL,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            getLogger().info("Table tradedonations checked/created.");
        } catch (SQLException e) {
            getLogger().warning("Error creating table: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void closeMySQL() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                getLogger().warning("Error closing MySQL connection: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // --- Callback HTTP server ---
    private void startCallbackServer(String host, int port, String path) {
        try {
            callbackServer = HttpServer.create(new InetSocketAddress(host, port), 0);
            callbackServer.createContext(path, (exchange -> {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    try {
                        // Read request body
                        InputStream is = exchange.getRequestBody();
                        String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        
                        // Process asynchronously
                        Bukkit.getScheduler().runTask(this, () -> {
                            handlePurchaseCallback(requestBody);
                        });

                        // Respond to client
                        String response = "OK";
                        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
                        exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(response.getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (Exception e) {
                        getLogger().warning("Error processing callback: " + e.getMessage());
                        e.printStackTrace();
                        exchange.sendResponseHeaders(500, -1);
                    }
                } else {
                    // If not POST - 405 Method Not Allowed
                    exchange.sendResponseHeaders(405, -1);
                }
            }));
            callbackServer.setExecutor(Executors.newCachedThreadPool());
            callbackServer.start();
            getLogger().info(color("&aCallback server started on " + host + ":" + port + path));
        } catch (IOException e) {
            getLogger().warning(color("&cFailed to start callback server: " + e.getMessage()));
            e.printStackTrace();
        }
    }

    private void stopCallbackServer() {
        if (callbackServer != null) {
            callbackServer.stop(0);
            getLogger().info("Callback server stopped.");
        }
    }

    // --- Purchase Callback Processing ---
    /**
     * Processes JSON from callback.
     * JSON format see API docs (shop_id, buyer, items[], hash, etc.).
     */
    private void handlePurchaseCallback(String jsonStr) {
        JsonElement root = parseJson(jsonStr);
        if (!root.isJsonObject()) {
            getLogger().warning("handlePurchaseCallback: JSON not an object!");
            return;
        }
        JsonObject obj = root.getAsJsonObject();

        // Read hash
        if (!obj.has("hash")) {
            getLogger().warning("handlePurchaseCallback: No 'hash' property!");
            return;
        }
        String givenHash = obj.get("hash").getAsString();
        
        // Remove "hash" from object to get "pure" JSON for verification
        obj.remove("hash");
        
        // Convert object back to string:
        String pureJson = obj.toString();
        
        // Read key from config.yml
        String shopKey = config.getString("callback-key", "");
        if (shopKey.isEmpty()) {
            getLogger().warning("handlePurchaseCallback: callback-key not set in config.yml!");
            return;
        }
        
        // Calculate sha256(pureJson + shopKey)
        String calcHash = sha256(pureJson + shopKey);
        if (!calcHash.equalsIgnoreCase(givenHash)) {
            getLogger().warning("handlePurchaseCallback: invalid hash! Expected: " + calcHash + ", got: " + givenHash);
            return;
        }
        // If we got here, hash is valid

        // Read shop_id (can be used for multi-shops)
        int shopId = obj.has("shop_id") ? obj.get("shop_id").getAsInt() : -1;

        // Read buyer
        String buyerName = obj.has("buyer") ? obj.get("buyer").getAsString() : "";

        // Read items
        if (obj.has("items") && obj.get("items").isJsonArray()) {
            JsonArray itemsArray = obj.get("items").getAsJsonArray();
            for (JsonElement el : itemsArray) {
                if (!el.isJsonObject()) continue;
                JsonObject itemObj = el.getAsJsonObject();

                // Extract required fields
                String itemId = itemObj.has("id") ? itemObj.get("id").getAsString() : "UnknownID";
                double cost = itemObj.has("cost") ? itemObj.get("cost").getAsDouble() : 0.0;
                boolean result = itemObj.has("result") && itemObj.get("result").getAsBoolean();

                // Delivery status
                if (!result) {
                    getLogger().warning("handlePurchaseCallback: Item ID=" + itemId + " not delivered. Result: false");
                    continue;
                }

                // Unique key: buyer_itemId
                String uniqueKey = buyerName + "_" + itemId;
                if (!processedPurchases.contains(uniqueKey)) {
                    processedPurchases.add(uniqueKey);
                    saveProcessedPurchases();

                    // Проверяем, онлайн ли игрок
                    Player p = Bukkit.getPlayerExact(buyerName);
                    if (p != null && p.isOnline()) {
                        giveDonation(buyerName, "Item#" + itemId);
                    } else {
                        // Откладываем
                        pendingPurchases.computeIfAbsent(
                            buyerName.toLowerCase(), 
                            k -> new ArrayList<>()
                        ).add("Item#" + itemId);
                        savePendingPurchases();
                        getLogger().info("Игрок " + buyerName + " офлайн, донат 'Item#" + itemId + "' отложен.");
                    }
                }
            }
        }

        // Можно обработать дополнительные свойства: partners, rcon, fields, surcharge, coupon, game_currency
        // в зависимости от потребностей плагина
    }

    /**
     * Утилита: sha256(строка)
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // --- Дополнительные методы ---

    /**
     * Проверяет, доступен ли API TradeMC.
     */
    private boolean testTradeMcAPI() {
        String response = callTradeMcApi("shop", "getOnline", "shop=" + getFirstShop());
        return !response.contains("\"error\"");
    }

    /**
     * Возвращает первый ID магазина из списка.
     */
    private String getFirstShop() {
        String shops = config.getString("shops", "168130");
        String[] parts = shops.split(",");
        return parts[0].trim();
    }

    // --- Callback HTTP-сервер ---
    // Реализован выше

    // --- End of TradeMc.java ---
}