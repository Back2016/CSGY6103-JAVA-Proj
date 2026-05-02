package edu.neu.cs6103.p2p.db;

import edu.neu.cs6103.p2p.model.DownloadHistoryEntry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ClientDatabase {
    private final String jdbcUrl;

    public ClientDatabase(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        initialize();
    }

    private void initialize() {
        String ddl = """
                CREATE TABLE IF NOT EXISTS download_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    filename TEXT NOT NULL,
                    source_peers TEXT NOT NULL,
                    destination_path TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize client database", exception);
        }
    }

    public synchronized void recordDownload(String filename, String sourcePeers, String destinationPath, String status) {
        String sql = """
                INSERT INTO download_history (filename, source_peers, destination_path, status, created_at)
                VALUES (?, ?, ?, ?, ?);
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, filename);
            statement.setString(2, sourcePeers);
            statement.setString(3, destinationPath);
            statement.setString(4, status);
            statement.setString(5, LocalDateTime.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to record download history", exception);
        }
    }

    public synchronized List<DownloadHistoryEntry> listHistory() {
        String sql = """
                SELECT filename, source_peers, destination_path, status, created_at
                FROM download_history
                ORDER BY id DESC;
                """;
        List<DownloadHistoryEntry> entries = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                entries.add(new DownloadHistoryEntry(
                        resultSet.getString("filename"),
                        resultSet.getString("source_peers"),
                        resultSet.getString("destination_path"),
                        resultSet.getString("status"),
                        resultSet.getString("created_at")
                ));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load download history", exception);
        }
        return entries;
    }
}
