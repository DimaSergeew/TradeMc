package com.bedepay.trademc.manager;

import com.bedepay.trademc.TradeMc;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.*;

/**
 * Менеджер базы данных для TradeMc
 * Управляет подключением и взаимодействием с базой данных
 */
public class DatabaseManager {
    private final TradeMc plugin;
    private Connection connection;
    private boolean enabled;

    public DatabaseManager(TradeMc plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("mysql.enabled", false);
        if (enabled) {
            connect();
        }
    }

    private void connect() {
        FileConfiguration config = plugin.getConfig();
        String host = config.getString("mysql.host", "localhost");
        int port = config.getInt("mysql.port", 3306);
        String database = config.getString("mysql.database", "trade_db");
        String user = config.getString("mysql.user", "root");
        String password = config.getString("mysql.password", "password");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false";

        try {
            connection = DriverManager.getConnection(url, user, password);
            plugin.getLogger().info("Подключение к MySQL успешно установлено.");
            initializeDatabase();
        } catch (SQLException e) {
            plugin.getLogger().severe("Не удалось подключиться к MySQL: " + e.getMessage());
            e.printStackTrace();
            enabled = false;
        }
    }

    private void initializeDatabase() {
        String createTable = "CREATE TABLE IF NOT EXISTS donations (" +
                "id INT AUTO_INCREMENT PRIMARY KEY," +
                "buyer VARCHAR(255) NOT NULL," +
                "item VARCHAR(255) NOT NULL," +
                "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ");";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTable);
            plugin.getLogger().info("Таблица 'donations' готова.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка создания таблицы 'donations': " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Записывает информацию о донате в базу данных
     */
    public void logDonation(String buyer, String item) {
        String insert = "INSERT INTO donations (buyer, item) VALUES (?, ?);";
        try (PreparedStatement pstmt = connection.prepareStatement(insert)) {
            pstmt.setString(1, buyer);
            pstmt.setString(2, item);
            pstmt.executeUpdate();
            plugin.getLogger().info("Донат записан в базу данных: " + buyer + " - " + item);
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка записи доната в базу данных: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Проверяет, включена ли база данных
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Отключает соединение с базой данных
     */
    public void disconnect() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("Соединение с MySQL закрыто.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Ошибка при закрытии соединения с MySQL: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}