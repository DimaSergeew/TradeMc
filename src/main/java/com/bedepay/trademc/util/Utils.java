package com.bedepay.trademc.util;

import com.bedepay.trademc.TradeMc;
import com.google.gson.*;
import org.bukkit.ChatColor;
import java.security.MessageDigest;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.io.*;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;

/**
 * Утилиты для TradeMc
 */
public class Utils {

    /**
     * Преобразует цветовые коды в Bukkit формат
     */
    public static String color(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    /**
     * Генерирует SHA-256 хэш
     */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return String.format("%064x", new BigInteger(1, hash));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Логирует сообщение в файл
     */
    public static void logToFile(TradeMc plugin, String message) {
        if (!plugin.getConfig().getBoolean("logging.enabled", true)) return;

        String logFilePath = plugin.getDataFolder() + File.separator + "logs" + File.separator + "purchases.log";
        File logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFilePath, true))) {
            writer.write(message);
            writer.newLine();
        } catch (IOException e) {
            plugin.getLogger().severe("Ошибка записи в лог-файл: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Загружает последние n строк из файла логов
     */
    public static List<String> loadLogLines(TradeMc plugin, int n) {
        List<String> lines = new ArrayList<>();
        File logFile = new File(plugin.getDataFolder(), "logs/purchases.log");
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
            plugin.getLogger().severe("Ошибка чтения лог-файла: " + e.getMessage());
            e.printStackTrace();
        }
        return lines;
    }

    /**
     * Создает тестовый JSON для отладки покупки
     */
    public static Optional<String> createDebugPurchaseJson(TradeMc plugin, String buyer, String itemId, String itemName) {
        String callbackKey = plugin.getConfig().getString("callback-key", "");
        if (callbackKey.isEmpty()) {
            return Optional.empty();
        }

        JsonObject json = new JsonObject();
        json.addProperty("shop_id", plugin.getConfig().getString("shops", "0"));
        json.addProperty("buyer", buyer);
        
        JsonArray itemsArray = new JsonArray();
        JsonObject itemObj = new JsonObject();
        itemObj.addProperty("id", itemId);
        itemObj.addProperty("name", itemName);
        itemObj.addProperty("result", true);
        // Добавляем rcon команды, если необходимо
        JsonArray rconArray = new JsonArray();
        JsonArray commandPair = new JsonArray();
        commandPair.add("lp user " + buyer + " group set Guardian");
        commandPair.add("Prefix successfully added!");
        rconArray.add(commandPair);
        itemObj.add("rcon", rconArray);
        itemsArray.add(itemObj);
        json.add("items", itemsArray);

        // Генерация хэша
        String pureJson = json.toString();
        String hash = sha256(pureJson + callbackKey);
        json.addProperty("hash", hash);

        return Optional.of(json.toString());
    }
}