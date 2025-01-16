package com.bedepay.trademc.util;

import com.bedepay.trademc.TradeMc;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Collections;

/**
 * Утилитарный класс с вспомогательными методами
 */
public class Utils {

    /**
     * Заменяет символы & на § для цветового форматирования текста
     */
    public static String color(String input) {
        return input.replace("&", "§");
    }

    /**
     * Вычисляет SHA-256 хеш для входной строки
     */
    public static String sha256(String input) {
        if (input == null) return "";
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

    /**
     * Записывает сообщение в лог-файл плагина
     */
    public static void logToFile(TradeMc plugin, String message) {
        try {
            File logDir = new File(plugin.getDataFolder(), "logs");
            if (!logDir.exists() && !logDir.mkdir()) {
                plugin.getLogger().warning("Failed to create logs directory");
                return;
            }

            File logFile = new File(logDir, "trademc.log");
            try (FileWriter fw = new FileWriter(logFile, true);
                 BufferedWriter bw = new BufferedWriter(fw)) {
                bw.write("[" + new Date() + "] " + message);
                bw.newLine();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write to log: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Загружает последние n строк из лог-файла
     */
    public static List<String> loadLogLines(TradeMc plugin, int n) {
        List<String> lines = new ArrayList<>();
        File logFile = new File(plugin.getDataFolder(), "logs/trademc.log");
        if (!logFile.exists()) return lines;

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            List<String> allLines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                allLines.add(line);
            }
            // Получаем последние n строк
            int start = Math.max(0, allLines.size() - n);
            lines.addAll(allLines.subList(start, allLines.size()));
            Collections.reverse(lines);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read log file: " + e.getMessage());
        }
        return lines;
    }

    /**
     * Копирует файл из одного места в другое
     */
    public static void copyFile(File source, File dest) throws IOException {
        try (InputStream is = new FileInputStream(source);
             OutputStream os = new FileOutputStream(dest)) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        }
    }

    /**
     * Создает тестовый JSON-объект покупки для отладки
     */
    public static Optional<String> createDebugPurchaseJson(TradeMc plugin, String buyer, String itemId, String itemName) {
        JsonObject purchaseObj = new JsonObject();
        purchaseObj.addProperty("shop_id", plugin.getConfig().getString("shops", "0"));
        purchaseObj.addProperty("buyer", buyer);

        JsonArray itemsArray = new JsonArray();
        JsonObject itemObj = new JsonObject();
        itemObj.addProperty("id", itemId);
        itemObj.addProperty("name", itemName);
        itemObj.addProperty("cost", 9.99);
        itemObj.addProperty("result", true);
        itemsArray.add(itemObj);

        purchaseObj.add("items", itemsArray);

        String shopKey = plugin.getConfig().getString("callback-key", "");
        if (shopKey.isEmpty()) {
            return Optional.empty();
        }

        String pureJson = purchaseObj.toString();
        String hash = sha256(pureJson + shopKey);
        purchaseObj.addProperty("hash", hash);

        return Optional.of(purchaseObj.toString());
    }
}