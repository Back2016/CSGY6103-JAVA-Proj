package edu.nyu.cs6103.p2p.db;

import edu.nyu.cs6103.p2p.model.ChunkRecord;
import edu.nyu.cs6103.p2p.model.PeerInfo;
import edu.nyu.cs6103.p2p.model.SearchResult;
import edu.nyu.cs6103.p2p.model.TrackerRecord;

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
                    original_path TEXT NOT NULL DEFAULT '',
                    encrypted INTEGER NOT NULL DEFAULT 0,
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
            ensureColumn(statement, "ALTER TABLE shared_files ADD COLUMN original_path TEXT NOT NULL DEFAULT ''");
            ensureColumn(statement, "ALTER TABLE shared_files ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize tracker database", exception);
        }
    }

    private void ensureColumn(Statement statement, String sql) throws SQLException {
        try {
            statement.execute(sql);
        } catch (SQLException exception) {
            if (!exception.getMessage().contains("duplicate column name")) {
                throw exception;
            }
        }
    }

    public synchronized void registerSharedFile(String filename,
                                                long size,
                                                int chunkSize,
                                                int chunkCount,
                                                String originalPath,
                                                boolean encrypted,
                                                String peerId,
                                                String host,
                                                int port) {
        String sql = """
                INSERT INTO shared_files (filename, size, chunk_size, chunk_count, original_path, encrypted, peer_id, host, port, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(filename, peer_id)
                DO UPDATE SET
                    size = excluded.size,
                    chunk_size = excluded.chunk_size,
                    chunk_count = excluded.chunk_count,
                    original_path = excluded.original_path,
                    encrypted = excluded.encrypted,
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
            statement.setString(5, originalPath);
            statement.setInt(6, encrypted ? 1 : 0);
            statement.setString(7, peerId);
            statement.setString(8, host);
            statement.setInt(9, port);
            statement.setString(10, LocalDateTime.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to register file " + filename, exception);
        }
    }

    public synchronized void unregisterPeer(String peerId) {
        String sql = "DELETE FROM shared_files WHERE peer_id = ?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, peerId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to unregister peer " + peerId, exception);
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
                SELECT filename, size, chunk_size, chunk_count, MAX(encrypted) AS encrypted
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
                            resultSet.getInt("encrypted") == 1,
                            findPeersByFilename(filename)
                    ));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to search tracker database", exception);
        }
        return results;
    }

    public synchronized List<TrackerRecord> listTrackerRecords() {
        String sql = """
                SELECT filename, size, chunk_size, chunk_count, original_path, MAX(encrypted) AS encrypted, MAX(updated_at) AS updated_at
                FROM shared_files
                GROUP BY filename, size, chunk_size, chunk_count, original_path
                ORDER BY filename;
                """;
        List<TrackerRecord> records = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String filename = resultSet.getString("filename");
                long size = resultSet.getLong("size");
                int chunkSize = resultSet.getInt("chunk_size");
                int chunkCount = resultSet.getInt("chunk_count");
                records.add(new TrackerRecord(
                        filename,
                        size,
                        resultSet.getString("original_path"),
                        chunkSize,
                        chunkCount,
                        resultSet.getInt("encrypted") == 1,
                        resultSet.getString("updated_at"),
                        findPeersByFilename(filename),
                        buildChunkRecords(size, chunkSize, chunkCount)
                ));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to list tracker records", exception);
        }
        return records;
    }

    private List<ChunkRecord> buildChunkRecords(long size, int chunkSize, int chunkCount) {
        List<ChunkRecord> chunkRecords = new ArrayList<>();
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            long offset = (long) chunkIndex * chunkSize;
            int length = (int) Math.min(chunkSize, size - offset);
            chunkRecords.add(new ChunkRecord(chunkIndex, offset, Math.max(length, 0)));
        }
        return chunkRecords;
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
