package com.bedepay.trademc.manager;

import com.bedepay.trademc.TradeMc;
import com.bedepay.trademc.util.Utils;
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
        if (!cmd.getName().equalsIgnoreCase("trademc")) return false;
        
        // Проверка базового разрешения
        if (!sender.hasPermission("trademc.use")) {
            sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.not-allowed")));
            return true;
        }
        
        try {
            // Обработка команд с одним аргументом
            if (args.length == 1) {
                switch (args[0].toLowerCase()) {
                    case "reload":
                        return handleReloadCommand(sender);
                    case "check":
                        return handleCheckCommand(sender);
                    case "getonline":
                        return handleGetOnlineCommand(sender);
                    case "history":
                        return handleHistoryCommand(sender);
                    default:
                        showHelp(sender);
                        return true;
                }
            } 
            // Обработка команды debugpurchase
            else if (args.length >= 3 && args[0].equalsIgnoreCase("debugpurchase")) {
                return handleDebugPurchaseCommand(sender, args);
            }
            
            showHelp(sender);
            return true;
            
        } catch (Exception e) {
            sender.sendMessage(Utils.color("&cCommand execution error: " + e.getMessage()));
            plugin.getLogger().severe("Command execution error: " + e.getMessage());
            e.printStackTrace();
            return true;
        }
    }
    
    /**
     * Проверяет статус подключения к TradeMC и callback серверу
     */
    private boolean handleCheckCommand(CommandSender sender) {
        if (!sender.hasPermission("trademc.admin")) {
            sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.not-allowed")));
            return true;
        }
        
        boolean trademcStatus = testConnection();
        boolean callbackStatus = plugin.getCallbackServer().isEnabled();
        
        String trademcStatusMsg = trademcStatus ? "&aTradeMC API: OK" : "&cTradeMC API: FAIL";
        String callbackStatusMsg = callbackStatus ? "&aCallback: OK" : "&cCallback: FAIL";
        
        sender.sendMessage(Utils.color("&eTradeMC Status: " + trademcStatusMsg + ", " + callbackStatusMsg));
        return true;
    }
    
    /**
     * Получает информацию о статусе онлайн магазина
     */
    private boolean handleGetOnlineCommand(CommandSender sender) {
        if (!sender.hasPermission("trademc.admin")) {
            sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.not-allowed")));
            return true;
        }
        
        String response = plugin.getPurchaseManager().callTradeMcApi(
            "shop", 
            "getOnline", 
            "shop=" + plugin.getConfig().getString("shops", "0")
        );
        
        sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.getonline")));
        sender.sendMessage(response);
        return true;
    }
    
    /**
     * Показывает историю последних покупок
     */
    private boolean handleHistoryCommand(CommandSender sender) {
        if (!sender.hasPermission("trademc.admin")) {
            sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.not-allowed")));
            return true;
        }
        
        List<String> logLines = Utils.loadLogLines(plugin, 10);
        sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.history-title")));
        
        if (logLines.isEmpty()) {
            sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.history-empty")));
        } else {
            for (String line : logLines) {
                sender.sendMessage(Utils.color("&7" + line));
            }
        }
        return true;
    }
    
    /**
     * Обрабатывает тестовую покупку для отладки
     */
    private boolean handleDebugPurchaseCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("trademc.admin")) {
            sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.not-allowed")));
            return true;
        }
        
        String buyer = args[1];
        String itemId = args[2];
        String itemName = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        if (itemName.isEmpty()) {
            itemName = itemId;
        }
        
        // Создаем тестовый JSON для обработки
        String jsonStr = Utils.createDebugPurchaseJson(plugin, buyer, itemId, itemName);
        if (jsonStr == null) {
            sender.sendMessage(Utils.color("&cError: callback-key not set in config.yml!"));
            return true;
        }
        
        try {
            plugin.getPurchaseManager().handlePurchaseCallback(jsonStr);
            sender.sendMessage(Utils.color("&aDebug purchase processed. Check console/logs for details."));
        } catch (Exception e) {
            sender.sendMessage(Utils.color("&cError: " + e.getMessage()));
        }
        return true;
    }
    
    /**
     * Перезагружает конфигурацию плагина
     */
    private boolean handleReloadCommand(CommandSender sender) {
        if (!sender.hasPermission("trademc.admin")) {
            sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.not-allowed")));
            return true;
        }
        
        try {
            plugin.getConfigManager().loadConfigs();
            sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.reload-success")));
        } catch (Exception e) {
            sender.sendMessage(Utils.color(plugin.getConfigManager().getLocaleMsg("messages.reload-error")));
            plugin.getLogger().severe("Error reloading config: " + e.getMessage());
        }
        return true;
    }
    
    /**
     * Показывает справку по командам
     */
    private void showHelp(CommandSender sender) {
        sender.sendMessage(Utils.color("&e/trademc reload, /trademc check, /trademc getOnline, " +
                                     "/trademc history, /trademc debugPurchase <buyer> <itemId> <itemName>"));
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
        }
        return null;
    }
}