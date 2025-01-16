package com.bedepay.trademc.manager;

import com.bedepay.trademc.TradeMc;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

/**
 * Менеджер конфигурации для TradeMc
 * Управляет загрузкой и сохранением конфигурационных файлов
 */
public class ConfigManager {
    private final TradeMc plugin;
    private final Set<String> processedPurchases;
    private final Map<String, List<String>> pendingPurchases;

    public ConfigManager(TradeMc plugin) {
        this.plugin = plugin;
        this.processedPurchases = new HashSet<>();
        this.pendingPurchases = new HashMap<>();
    }

    public void loadConfigs() {
        plugin.reloadConfig();
        // Загрузка других конфигураций, если есть
    }

    public void saveAll() {
        // Сохранение данных, если необходимо
    }

    public Set<String> getProcessedPurchases() {
        return processedPurchases;
    }

    public Map<String, List<String>> getPendingPurchases() {
        return pendingPurchases;
    }

    public String getLocaleMsg(String path) {
        FileConfiguration locale = plugin.getConfig(); // Или отдельный файл locale.yml
        return locale.getString(path, "&cСообщение не найдено");
    }
}