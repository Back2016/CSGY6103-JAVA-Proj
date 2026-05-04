package edu.neu.cs6103.p2p.db;

import edu.neu.cs6103.p2p.model.PeerInfo;
import edu.neu.cs6103.p2p.model.SearchResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TrackerDatabase {
    private final String jdbcUrl;

    public TrackerDatabase(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        initialize();
    }

    private void initialize() {
        String ddl = """
                CREATE TABLE IF NOT EXISTS shared_files (
                    filename TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    chunk_size INTEGER NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    peer_id TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (filename, peer_id)
                );
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize tracker database", exception);
        }
    }

    public synchronized void registerSharedFile(String filename,
                                                long size,
                                                int chunkSize,
                                                int chunkCount,
                                                String peerId,
                                                String host,
                                                int port) {
        String sql = """
                INSERT INTO shared_files (filename, size, chunk_size, chunk_count, peer_id, host, port, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(filename, peer_id)
                DO UPDATE SET
                    size = excluded.size,
                    chunk_size = excluded.chunk_size,
                    chunk_count = excluded.chunk_count,
                    host = excluded.host,
                    port = excluded.port,
                    updated_at = excluded.updated_at;
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, filename);
            statement.setLong(2, size);
            statement.setInt(3, chunkSize);
            statement.setInt(4, chunkCount);
            statement.setString(5, peerId);
            statement.setString(6, host);
            statement.setInt(7, port);
            statement.setString(8, LocalDateTime.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to register file " + filename, exception);
        }
    }

    public synchronized void clearSharedFiles() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM shared_files");
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to clear tracker database", exception);
        }
    }

    public synchronized List<SearchResult> searchFiles(String query) {
        String sql = """
                SELECT filename, size, chunk_size, chunk_count
                FROM shared_files
                WHERE filename LIKE ?
                GROUP BY filename, size, chunk_size, chunk_count
                ORDER BY filename;
                """;
        List<SearchResult> results = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "%" + query + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String filename = resultSet.getString("filename");
                    long size = resultSet.getLong("size");
                    int chunkSize = resultSet.getInt("chunk_size");
                    int chunkCount = resultSet.getInt("chunk_count");
                    results.add(new SearchResult(
                            filename,
                            size,
                            chunkSize,
                            chunkCount,
                            findPeersByFilename(filename)
                    ));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to search tracker database", exception);
        }
        return results;
    }

    private List<PeerInfo> findPeersByFilename(String filename) throws SQLException {
        String sql = "SELECT peer_id, host, port FROM shared_files WHERE filename = ? ORDER BY peer_id";
        List<PeerInfo> peers = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, filename);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    peers.add(new PeerInfo(
                            resultSet.getString("peer_id"),
                            resultSet.getString("host"),
                            resultSet.getInt("port")
                    ));
                }
            }
        }
        return peers;
    }
}
