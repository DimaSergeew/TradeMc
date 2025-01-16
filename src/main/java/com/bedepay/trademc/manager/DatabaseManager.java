package com.bedepay.trademc.manager;

import com.bedepay.trademc.TradeMc;
import java.sql.*;

/**
 * Менеджер для работы с базой данных MySQL
 * Хранит информацию о пожертвованиях
 */
public class DatabaseManager {
    private final TradeMc plugin;
    private Connection connection;
    private boolean enabled;
    
    /**
     * Конструктор менеджера БД
     * @param plugin экземпляр основного плагина
     */
    public DatabaseManager(TradeMc plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("mysql.enabled", false);
        if (enabled) {
            connect();
            createTables();
        }
    }
    
    /**
     * Устанавливает соединение с базой данных MySQL
     */
    public void connect() {
        try {
            String host = plugin.getConfig().getString("mysql.host", "localhost");
            int port = plugin.getConfig().getInt("mysql.port", 3306);
            String database = plugin.getConfig().getString("mysql.database", "trade_db");
            String user = plugin.getConfig().getString("mysql.user", "root");
            String pass = plugin.getConfig().getString("mysql.password", "");
            
            String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&characterEncoding=UTF-8", 
                                     host, port, database);
            
            connection = DriverManager.getConnection(url, user, pass);
            plugin.getLogger().info("MySQL connection established");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to connect to MySQL: " + e.getMessage());
            enabled = false;
        }
    }
    
    /**
     * Создает необходимые таблицы в базе данных
     */
    public void createTables() {
        if (!enabled || connection == null) return;
        
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS tradedonations (" +
                "id INT AUTO_INCREMENT PRIMARY KEY," +
                "buyer VARCHAR(64) NOT NULL," +
                "item_name VARCHAR(255) NOT NULL," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create tables: " + e.getMessage());
        }
    }
    
    /**
     * Записывает информацию о пожертвовании в базу данных
     * @param buyer имя покупателя
     * @param itemName название купленного предмета
     */
    public void logDonation(String buyer, String itemName) {
        if (!enabled || connection == null) return;
        
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tradedonations (buyer, item_name) VALUES (?, ?)")) {
            ps.setString(1, buyer);
            ps.setString(2, itemName);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to log donation: " + e.getMessage());
        }
    }
    
    /**
     * Закрывает соединение с базой данных
     */
    public void disconnect() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("Error closing MySQL connection: " + e.getMessage());
            }
        }
    }
    
    /**
     * Проверяет, включена ли поддержка базы данных
     * @return true если БД включена, иначе false
     */
    public boolean isEnabled() {
        return enabled;
    }
}