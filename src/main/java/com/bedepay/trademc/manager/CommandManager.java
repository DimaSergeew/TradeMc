package com.bedepay.trademc.manager;

import com.bedepay.trademc.TradeMc;
import com.bedepay.trademc.util.Utils;
import com.bedepay.trademc.server.CallbackServer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Менеджер команд плагина TradeMC
 * Обрабатывает все команды и их аргументы
 */
public class CommandManager implements CommandExecutor, TabCompleter {
    private final TradeMc plugin;

    public CommandManager(TradeMc plugin) {
        this.plugin = plugin;
    }

    /**
     * Обработчик команд плагина
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            if (!cmd.getName().equalsIgnoreCase("trademc")) return false;

            plugin.getLogger().info("TradeMc Command Executed by " + sender.getName());

            // Проверка валидности конфигурации
            if (!plugin.isConfigValid()) {
                sender.sendMessage(Utils.color("&cПлагин не настроен. Выполните /trademc reload после настройки конфигурации."));
                return true;
            }

            if (args.length == 0) {
                showHelp(sender);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "reload":
                    if (!sender.hasPermission("trademc.admin")) {
                        sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.not-allowed")));
                        return true;
                    }
                    handleReloadCommand(sender);
                    break;

                case "check":
                    if (!sender.hasPermission("trademc.admin")) {
                        sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.not-allowed")));
                        return true;
                    }
                    handleCheckCommand(sender);
                    break;

                case "getonline":
                    if (!sender.hasPermission("trademc.admin")) {
                        sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.not-allowed")));
                        return true;
                    }
                    handleGetOnlineCommand(sender);
                    break;

                case "history":
                    if (!sender.hasPermission("trademc.admin")) {
                        sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.not-allowed")));
                        return true;
                    }
                    handleHistoryCommand(sender);
                    break;

                case "debugpurchase":
                    if (!sender.hasPermission("trademc.admin")) {
                        sender.sendMessage(Utils.color("&cНедостаточно прав!"));
                        return true;
                    }
                    if (args.length >= 3) {
                        handleDebugPurchaseCommand(sender, args);
                    } else {
                        sender.sendMessage(Utils.color("&cИспользование: /trademc debugPurchase <игрок> <itemId> [itemName]"));
                    }
                    break;

                default:
                    showHelp(sender);
                    break;
            }
            return true;
        } catch (Exception e) {
            sender.sendMessage(Utils.color("&cПроизошла ошибка: " + e.getMessage()));
            plugin.getLogger().severe("Error in command execution: " + e.getMessage());
            e.printStackTrace();
            return true;
        }
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(Utils.color("&6=== TradeMC Помощь ==="));
        if (sender.hasPermission("trademc.admin")) {
            sender.sendMessage(Utils.color("&e/trademc reload &7- Перезагрузить конфигурацию"));
            sender.sendMessage(Utils.color("&e/trademc check &7- Проверить состояние TradeMC API"));
            sender.sendMessage(Utils.color("&e/trademc getOnline &7- Проверить статус онлайн магазинов"));
            sender.sendMessage(Utils.color("&e/trademc history &7- Просмотреть последние действия"));
            sender.sendMessage(Utils.color("&e/trademc debugPurchase &7- Тестовая покупка для отладки"));
        }
    }

    /**
     * Проверяет статус подключения к TradeMC и callback серверу
     */
    private void handleCheckCommand(CommandSender sender) {
        boolean trademcStatus = testConnection();
        boolean callbackStatus = false;

        if (plugin.getCallbackServer() != null && plugin.getConfig().getBoolean("callback.enabled", false)) {
            callbackStatus = plugin.getCallbackServer().isEnabled();
        }

        String trademcStatusMsg = trademcStatus ? "&aTradeMC API: OK" : "&cTradeMC API: FAIL";
        String callbackStatusMsg = plugin.getConfig().getBoolean("callback.enabled", false)
            ? (callbackStatus ? "&aCallback: OK" : "&cCallback: FAIL")
            : "&7Callback: Disabled";

        sender.sendMessage(Utils.color("&eTradeMC Status: " + trademcStatusMsg + ", " + callbackStatusMsg));
        plugin.getLogger().info("TradeMc Check Command Executed by " + sender.getName());
    }

    /**
     * Получает информацию о статусе онлайн магазина
     */
    private void handleGetOnlineCommand(CommandSender sender) {
        String response = plugin.getPurchaseManager().callTradeMcApi(
            "shop",
            "getOnline",
            "shop=" + plugin.getConfig().getString("shops", "0")
        );

        sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.getonline")));
        sender.sendMessage(Utils.color(response));
        plugin.getLogger().info("TradeMc GetOnline Command Executed by " + sender.getName());
    }

    /**
     * Показывает историю последних покупок
     */
    private void handleHistoryCommand(CommandSender sender) {
        List<String> logs = Utils.loadLogLines(plugin, 10);
        if (logs.isEmpty()) {
            sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.history-empty")));
        } else {
            sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.history-title")));
            logs.forEach(line -> sender.sendMessage(Utils.color("&7" + line)));
        }
        plugin.getLogger().info("TradeMc History Command Executed by " + sender.getName());
    }

    /**
     * Обрабатывает тестовую покупку для отладки
     */
    private void handleDebugPurchaseCommand(CommandSender sender, String[] args) {
        String buyer = args[1];
        String itemId = args[2];
        String itemName = (args.length >= 4) ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : itemId;

        // Создаем тестовый JSON для обработки
        String jsonStr = Utils.createDebugPurchaseJson(plugin, buyer, itemId, itemName).orElse(null);
        if (jsonStr == null) {
            sender.sendMessage(Utils.color("&cError: callback-key not set in config.yml!"));
            return;
        }

        try {
            plugin.getPurchaseManager().handlePurchaseCallback(jsonStr);
            sender.sendMessage(Utils.color("&aDebug purchase processed. Check console/logs for details."));
            plugin.getLogger().info("TradeMc DebugPurchase Command Executed by " + sender.getName() + " for buyer " + buyer + ", item " + itemId);
        } catch (Exception e) {
            sender.sendMessage(Utils.color("&cError: " + e.getMessage()));
            plugin.getLogger().severe("Error in debugPurchase command: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Перезагружает конфигурацию плагина
     */
    private void handleReloadCommand(CommandSender sender) {
        try {
            // Перезагружаем конфигурацию
            plugin.reloadConfig();
            plugin.getConfigManager().loadConfigs();

            // Выводим текущие значения для отладки
            String shopId = plugin.getConfig().getString("shops", "0");
            String callbackKey = plugin.getConfig().getString("callback-key", "");
            plugin.getLogger().info("Текущие значения после перезагрузки:");
            plugin.getLogger().info("shops: " + shopId);
            plugin.getLogger().info("callback-key: " + callbackKey);

            boolean configValid = plugin.checkAndUpdateConfig();

            if (configValid) {
                // Перезапускаем компоненты, если это необходимо
                if (plugin.getCallbackServer() != null) {
                    plugin.getCallbackServer().stop();
                }

                boolean callbackEnabled = plugin.getConfig().getBoolean("callback.enabled", false);
                if (callbackEnabled) {
                    plugin.setCallbackServer(new CallbackServer(plugin));
                    plugin.getLogger().info("Callback server restarted.");
                } else {
                    int interval = plugin.getConfig().getInt("check-interval-seconds", 60);
                    plugin.startPurchaseChecker(interval);
                }

                sender.sendMessage(Utils.color("&a▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃"));
                sender.sendMessage(Utils.color("&aКонфигурация успешно перезагружена!"));
                sender.sendMessage(Utils.color("&aВсе настройки корректны."));
                sender.sendMessage(Utils.color("&a▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃"));
            } else {
                sender.sendMessage(Utils.color("&e▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃"));
                sender.sendMessage(Utils.color("&eКонфигурация перезагружена, но требует настройки!"));
                sender.sendMessage(Utils.color("&eТекущие значения:"));
                sender.sendMessage(Utils.color("&e- shops: " + shopId));
                sender.sendMessage(Utils.color("&e- callback-key: " + (callbackKey.isEmpty() ? "не указан" : "указан")));
                sender.sendMessage(Utils.color("&e▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃"));
            }
        } catch (Exception e) {
            sender.sendMessage(Utils.color("&c▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃"));
            sender.sendMessage(Utils.color("&cОшибка при перезагрузке конфигурации!"));
            sender.sendMessage(Utils.color("&cПроверьте консоль сервера для получения деталей."));
            sender.sendMessage(Utils.color("&c▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃▃"));
            plugin.getLogger().severe("Error reloading config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Проверяет подключение к API TradeMC
     */
    private boolean testConnection() {
        String response = plugin.getPurchaseManager().callTradeMcApi(
            "shop",
            "getOnline",
            "shop=" + plugin.getConfig().getString("shops", "0")
        );
        return !response.contains("\"error\"");
    }

    /**
     * Обработчик автодополнения команд
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("trademc")) return null;

        if (args.length == 1) {
            List<String> subCommands = Arrays.asList("reload", "check", "getOnline", "history", "debugPurchase");
            List<String> result = new ArrayList<>();

            for (String sc : subCommands) {
                if (sc.toLowerCase().startsWith(args[0].toLowerCase())) {
                    result.add(sc);
                }
            }
            return result;
        } else if (args.length == 2 && args[0].equalsIgnoreCase("debugPurchase")) {
            // Предоставляем список онлайн игроков для автодополнения
            List<String> onlinePlayers = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(player -> onlinePlayers.add(player.getName()));
            return onlinePlayers;
        } else if (args.length == 3 && args[0].equalsIgnoreCase("debugPurchase")) {
            // Предоставляем список доступных itemId для автодополнения
            // Поскольку маппинг удалён, можно предложить пустой список или другие релевантные значения
            return new ArrayList<>();
        }

        return null;
    }
}