package com.bedepay.trademc;

import com.bedepay.trademc.manager.*;
import com.bedepay.trademc.server.CallbackServer;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.Date;
import java.util.concurrent.ExecutorService;

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
    private boolean configValid = false;      // Поле для отслеживания валидности конфигурации
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Override
    public void onEnable() {
        try {
            getLogger().info("╔═══════════════════════════════════════════════════╗");
            getLogger().info("║             ЗАПУСК ПЛАГИНА TRADEMC               ║");
            getLogger().info("╠═══════════════════════════════════════════════════╣");

            // Сохраняем конфиги
            saveDefaultConfig();

            // Инициализация менеджеров
            configManager = new ConfigManager(this);

            // Проверка настроек
            if (!checkAndUpdateConfig()) {
                getLogger().info("║ ⚠ Требуется настройка плагина:                  ║");
                getLogger().info("║ 1. Откройте файл plugins/TradeMC/config.yml     ║");
                getLogger().info("║ 2. Укажите ID магазина в параметре 'shops'     ║");
                if (getConfig().getBoolean("callback.enabled", false)) {
                    getLogger().info("║ 3. Укажите ключ в параметре 'callback-key'     ║");
                }
                getLogger().info("║ 4. Используйте /trademc reload                  ║");
                getLogger().info("╚═══════════════════════════════════════════════════╝");
                return; // Прекращаем инициализацию, если конфигурация недействительна
            }

            // Инициализация компонентов
            databaseManager = new DatabaseManager(this);
            purchaseManager = new PurchaseManager(this);
            commandManager = new CommandManager(this);

            // Настройка режима работы
            boolean callbackEnabled = getConfig().getBoolean("callback.enabled", false);
            if (callbackEnabled) {
                callbackServer = new CallbackServer(this);
                getLogger().info("║ ✓ Режим работы: Callback (мгновенные уведомления) ║");
            } else {
                int interval = getConfig().getInt("check-interval-seconds", 60);
                startPurchaseChecker(interval);
                getLogger().info("║ ✓ Режим работы: Проверка каждые " + interval + " сек      ║");
            }

            // Регистрация команд
            Objects.requireNonNull(getCommand("trademc")).setExecutor(commandManager);
            Objects.requireNonNull(getCommand("trademc")).setTabCompleter(commandManager);

            // Регистрация слушателей событий
            getServer().getPluginManager().registerEvents(this, this);

            getLogger().info("║ ✓ Плагин успешно запущен                         ║");
            getLogger().info("╚═══════════════════════════════════════════════════╝");

        } catch (Exception e) {
            getLogger().severe("Ошибка запуска плагина: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startPurchaseChecker(int interval) {
        getServer().getScheduler().runTaskTimerAsynchronously(this,
            () -> {
                if (isConfigValid()) {
                    purchaseManager.checkNewPurchases(false);
                }
            },
            20L * 10,           // 10 секунд задержка
            20L * interval      // Интервал проверки
        );
    }

    /**
     * Проверяет и обновляет статус конфигурации
     */
    public boolean checkAndUpdateConfig() {
        String shopId = getConfig().getString("shops", "0");
        boolean callbackEnabled = getConfig().getBoolean("callback.enabled", false);
        String callbackKey = getConfig().getString("callback-key", "");

        boolean isShopIdValid = !shopId.equals("0") && !shopId.isEmpty();
        boolean isCallbackKeyValid = !callbackKey.isEmpty();

        // Вывод статуса настроек
        getLogger().info("╔═══════════════════════════════════════════════════╗");
        getLogger().info("║              ПРОВЕРКА НАСТРОЕК TRADEMC            ║");
        getLogger().info("╠═══════════════════════════════════════════════════╣");
        getLogger().info(String.format("║  ID Магазина (shops): %s%-20s  ║",
            isShopIdValid ? shopId : "НЕ УКАЗАН",
            isShopIdValid ? " " : " ⚠"));
        if (callbackEnabled) {
            getLogger().info(String.format("║  Ключ магазина (callback-key): %-13s║",
                isCallbackKeyValid ? "УКАЗАН ✓" : "НЕ УКАЗАН ⚠"));
        } else {
            getLogger().info("║  Callback отключен                                ║");
        }
        getLogger().info("╠═══════════════════════════════════════════════════╣");

        if (!isShopIdValid || (callbackEnabled && !isCallbackKeyValid)) {
            getLogger().info("║             ТРЕБУЕТСЯ НАСТРОЙКА:                  ║");
            if (!isShopIdValid) {
                getLogger().info("║  1. Укажите ID магазина в параметре 'shops'     ║");
            }
            if (callbackEnabled && !isCallbackKeyValid) {
                getLogger().info("║  2. Укажите ключ в параметре 'callback-key'     ║");
            }
            getLogger().info("║                                                   ║");
            getLogger().info("║  Откройте файл config.yml и заполните данные      ║");
            getLogger().info("╚═══════════════════════════════════════════════════╝");
        } else {
            getLogger().info("║              ВСЕ НАСТРОЙКИ КОРРЕКТНЫ! ✓          ║");
            getLogger().info("╚═══════════════════════════════════════════════════╝");
        }

        if (isShopIdValid && (!callbackEnabled || isCallbackKeyValid)) {
            configValid = true;
        } else {
            configValid = false;
        }
        return configValid;
    }

    /**
     * Проверяет, правильно ли настроен плагин
     */
    public boolean isConfigValid() {
        return configValid;
    }

    @Override
    public void onDisable() {
        executorService.shutdown();
        // Сохранение всех данных перед отключением
        if (configManager != null) {
            configManager.saveAll();
        }

        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        if (callbackServer != null) {
            callbackServer.stop();
        }
        getLogger().info("TradeMc plugin disabled successfully");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Запускаем проверку в асинхронном потоке
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            String playerName = event.getPlayer().getName().toLowerCase();
            purchaseManager.processPendingPurchases(playerName);
        });
    }

    // Геттеры для доступа к менеджерам
    public ConfigManager getConfigManager() { return configManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public PurchaseManager getPurchaseManager() { return purchaseManager; }
    public CommandManager getCommandManager() { return commandManager; }
    public CallbackServer getCallbackServer() { return callbackServer; }

    public ExecutorService getExecutorService() {
        return executorService;
    }
}