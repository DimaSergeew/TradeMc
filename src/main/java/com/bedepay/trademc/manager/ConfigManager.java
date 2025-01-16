package com.bedepay.trademc.manager;

import com.bedepay.trademc.TradeMc;
import com.bedepay.trademc.util.Utils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер конфигурации плагина
 * Отвечает за загрузку и сохранение всех конфигурационных файлов
 */
public class ConfigManager {
    private final TradeMc plugin;
    private FileConfiguration config;         // Основной конфиг
    private FileConfiguration locale;         // Файл локализации
    private File dataFile;                    // Файл данных
    private FileConfiguration dataConfig;     // Конфигурация данных
    private final Set<String> processedPurchases; // Обработанные покупки
    private final Map<String, List<String>> pendingPurchases; // Ожидающие покупки

    /**
     * Конструктор менеджера конфигурации
     * @param plugin экземпляр основного плагина
     */
    public ConfigManager(TradeMc plugin) {
        this.plugin = plugin;
        this.processedPurchases = ConcurrentHashMap.newKeySet();
        this.pendingPurchases = new ConcurrentHashMap<>();

        // Сохраняем конфиги по умолчанию
        plugin.saveDefaultConfig();
        saveDefaultFiles();

        loadConfigs();
    }

    /**
     * Загружает все конфигурационные файлы
     */
    public void loadConfigs() {
        // Перезагружаем основной конфиг
        plugin.reloadConfig();
        config = plugin.getConfig();

        // Загрузка локализации
        loadLocale();

        // Загрузка data.yml
        loadDataFile();
        loadProcessedPurchases();
        loadPendingPurchases();
    }

    /**
     * Загружает файл локализации
     */
    public void loadLocale() {
        File localeFile = new File(plugin.getDataFolder(), "locale.yml");
        if (!localeFile.exists()) {
            plugin.saveResource("locale.yml", false);
        }
        locale = YamlConfiguration.loadConfiguration(localeFile);

        // Дополнительно, поддержка внешних ресурсов, например, если locale поддерживает языки
        // Можно добавить загрузку дополнительных файлов при необходимости
    }

    /**
     * Загружает файл данных
     */
    public void loadDataFile() {
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            plugin.saveResource("data.yml", false);
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    /**
     * Сохраняет файл данных
     */
    public void saveDataFile() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save data.yml: " + e.getMessage());
        }
    }

    /**
     * Создает резервную копию файла данных
     */
    public void backupDataFile() {
        try {
            if (dataFile.exists()) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
                String backupName = "data_" + sdf.format(new Date()) + ".yml";
                File backupFile = new File(plugin.getDataFolder(), backupName);
                Utils.copyFile(dataFile, backupFile);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to backup data.yml: " + e.getMessage());
        }
    }

    /**
     * Сохраняет все данные и создает резервную копию
     */
    public void saveAll() {
        saveDataFile();
        backupDataFile();
    }

    /**
     * Загружает список обработанных покупок
     */
    private void loadProcessedPurchases() {
        processedPurchases.clear();
        List<String> processed = dataConfig.getStringList("processed-purchases");
        if (processed != null) {
            processedPurchases.addAll(processed);
        }
    }

    /**
     * Загружает список ожидающих покупок
     */
    private void loadPendingPurchases() {
        pendingPurchases.clear();
        if (dataConfig.contains("pending-purchases")) {
            for (String player : dataConfig.getConfigurationSection("pending-purchases").getKeys(false)) {
                List<String> items = dataConfig.getStringList("pending-purchases." + player);
                if (items != null) {
                    pendingPurchases.put(player.toLowerCase(), new ArrayList<>(items));
                }
            }
        }
    }

    /**
     * Сохраняет файл данных
     */
    public void saveData() {
        // Обновляем dataConfig из текущих данных
        dataConfig.set("processed-purchases", new ArrayList<>(processedPurchases));
        dataConfig.set("pending-purchases", pendingPurchases);
        saveDataFile();
    }

    /**
     * Создает резервную копию файла данных
     */
    public void backupData() {
        backupDataFile();
    }

    /**
     * Сохраняет файлы конфигурации по умолчанию
     */
    private void saveDefaultFiles() {
        String[] files = {"config.yml", "locale.yml", "data.yml"};
        for (String file : files) {
            File configFile = new File(plugin.getDataFolder(), file);
            if (!configFile.exists()) {
                plugin.saveResource(file, false);
            }
        }
    }

    // Геттеры для доступа к конфигурациям
    public FileConfiguration getConfig() { return config; }
    public FileConfiguration getLocale() { return locale; }
    public FileConfiguration getDataConfig() { return dataConfig; }
    public Set<String> getProcessedPurchases() { return processedPurchases; }
    public Map<String, List<String>> getPendingPurchases() { return pendingPurchases; }

    /**
     * Получает сообщение из файла локализации
     * @param path путь к сообщению
     * @return локализованное сообщение или путь, если сообщение не найдено
     */
    public String getLocaleMsg(String path) {
        return locale.getString(path, path);
    }

    /**
     * Сохраняет текущие данные в конфиг
     */
    public void saveCurrentData() {
        saveData();
    }
}