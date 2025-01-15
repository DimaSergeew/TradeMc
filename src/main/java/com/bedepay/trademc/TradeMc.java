package com.bedepay.trademc;

import com.bedepay.trademc.manager.*;
import com.bedepay.trademc.server.CallbackServer;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Главный класс плагина для интеграции с TradeMC
 * Обрабатывает покупки с маркетплейса TradeMC и распределяет награды игрокам
 */
public class TradeMc extends JavaPlugin implements Listener {
    // Менеджеры для различных аспектов плагина
    private ConfigManager configManager;      // Управление конфигурацией
    private DatabaseManager databaseManager;  // Управление базой данных
    private PurchaseManager purchaseManager;  // Управление покупками
    private CommandManager commandManager;    // Управление командами
    private CallbackServer callbackServer;    // Сервер для обратных вызовов

    @Override
    public void onEnable() {
        try {
            // Инициализация всех менеджеров плагина
            configManager = new ConfigManager(this);
            
            // Проверка ID магазина
            String shopId = getConfig().getString("shops", "0");
            if ("0".equals(shopId)) {
                getLogger().warning("========================================");
                getLogger().warning("Внимание! ID магазина не установлен!");
                getLogger().warning("Установите ID магазина в config.yml");
                getLogger().warning("shops: \"ВАШ_ID_МАГАЗИНА\"");
                getLogger().warning("========================================");
            }
            
            databaseManager = new DatabaseManager(this);
            purchaseManager = new PurchaseManager(this);
            commandManager = new CommandManager(this);
            callbackServer = new CallbackServer(this);

            // Регистрация этого класса как слушателя событий
            getServer().getPluginManager().registerEvents(this, this);

            // Регистрация команды /trademc и её автодополнения
            Objects.requireNonNull(getCommand("trademc")).setExecutor(commandManager);
            Objects.requireNonNull(getCommand("trademc")).setTabCompleter(commandManager);

            // Первичная проверка наличия новых покупок
            purchaseManager.checkNewPurchases(false);

            // Настройка периодической проверки покупок (минимум раз в 30 секунд)
            int interval = Math.max(getConfig().getInt("check-interval-seconds", 60), 30);
            getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                () -> purchaseManager.checkNewPurchases(false),
                20L * interval,
                20L * interval
            );

            getLogger().info("TradeMC plugin enabled successfully");
        } catch (Exception e) {
            getLogger().severe("Error starting plugin: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            // Сохранение всех данных перед выключением
            configManager.saveAll();
            
            // Закрытие соединения с базой данных
            if (databaseManager != null) {
                databaseManager.disconnect();
            }
            
            // Остановка сервера обратных вызовов
            if (callbackServer != null) {
                callbackServer.stop();
            }

            getLogger().info("TradeMC plugin disabled successfully");
        } catch (Exception e) {
            getLogger().severe("Error disabling plugin: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Обработчик события входа игрока
     * Проверяет наличие отложенных наград и выдает их
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Получаем ключ игрока (имя в нижнем регистре)
        String buyerKey = event.getPlayer().getName().toLowerCase();
        // Получаем список всех отложенных покупок
        Map<String, List<String>> pendingPurchases = configManager.getPendingPurchases();
        
        // Если есть отложенные покупки для данного игрока
        if (pendingPurchases.containsKey(buyerKey)) {
            List<String> items = pendingPurchases.get(buyerKey);
            if (items != null) {
                // Выдаем все отложенные предметы
                for (String itemName : new ArrayList<>(items)) {
                    purchaseManager.giveDonation(event.getPlayer().getName(), itemName);
                }
                // Очищаем список отложенных предметов
                items.clear();
                configManager.saveDataFile();
            }
        }
    }

    // Геттеры для доступа к менеджерам из других классов
    public ConfigManager getConfigManager() { return configManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public PurchaseManager getPurchaseManager() { return purchaseManager; }
    public CommandManager getCommandManager() { return commandManager; }
    public CallbackServer getCallbackServer() { return callbackServer; }
}