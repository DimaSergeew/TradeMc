package com.bedepay.trademc;

import com.bedepay.trademc.manager.*;
import com.bedepay.trademc.server.CallbackServer;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.Date;

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
            // Инициализация менеджеров
            configManager = new ConfigManager(this);
            
            // Проверка настроек магазина
            String shopId = getConfig().getString("shops", "0");
            if (shopId.equals("0") || shopId.isEmpty()) {
                getLogger().severe("╔════════════════════════════════════════════════╗");
                getLogger().severe("║                   ВНИМАНИЕ!                    ║");
                getLogger().severe("║        Не указан ID магазина в config.yml      ║");
                getLogger().severe("║     Пожалуйста, укажите параметр 'shops'      ║");
                getLogger().severe("╚════════════════════════════════════════════════╝");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            String callbackKey = getConfig().getString("callback-key", "ВАШ_CALLBACK_KEY");
            if (callbackKey.equals("ВАШ_CALLBACK_KEY") || callbackKey.isEmpty()) {
                getLogger().severe("╔════════════════════════════════════════════════╗");
                getLogger().severe("║                   ВНИМАНИЕ!                    ║");
                getLogger().severe("║     Не указан CALLBACK-KEY в config.yml        ║");
                getLogger().severe("║   Пожалуйста, укажите параметр 'callback-key' ║");
                getLogger().severe("╚════════════════════════════════════════════════╝");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            databaseManager = new DatabaseManager(this);
            purchaseManager = new PurchaseManager(this);
            commandManager = new CommandManager(this);
            callbackServer = new CallbackServer(this);

            // Регистрация слушателей и команд
            getServer().getPluginManager().registerEvents(this, this);
            Objects.requireNonNull(getCommand("trademc")).setExecutor(commandManager);
            Objects.requireNonNull(getCommand("trademc")).setTabCompleter(commandManager);

            getLogger().info("TradeMC plugin enabled successfully");
        } catch (Exception e) {
            getLogger().severe("Error starting plugin: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        if (callbackServer != null) {
            callbackServer.stop();
        }
        getLogger().info("TradeMC plugin disabled successfully");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        String playerName = event.getPlayer().getName().toLowerCase();
        purchaseManager.processPendingPurchases(playerName);
    }

    // Геттеры для доступа к менеджерам
    public ConfigManager getConfigManager() { return configManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public PurchaseManager getPurchaseManager() { return purchaseManager; }
    public CommandManager getCommandManager() { return commandManager; }
    public CallbackServer getCallbackServer() { return callbackServer; }
}