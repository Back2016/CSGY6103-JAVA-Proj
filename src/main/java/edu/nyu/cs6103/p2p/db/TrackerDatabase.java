package edu.nyu.cs6103.p2p.db;

import edu.nyu.cs6103.p2p.model.ChunkRecord;
import edu.nyu.cs6103.p2p.model.PeerInfo;
import edu.nyu.cs6103.p2p.model.SearchResult;
import edu.nyu.cs6103.p2p.model.SharedFileDescriptor;
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
        String recreateSql = """
                DROP TABLE IF EXISTS shared_files;
                """;
        String ddl = """
                CREATE TABLE IF NOT EXISTS shared_files (
                    file_id TEXT NOT NULL,
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
                    PRIMARY KEY (file_id, peer_id)
                );
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            if (needsSchemaRebuild(connection)) {
                statement.execute(recreateSql);
            }
            statement.execute(ddl);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize tracker database", exception);
        }
    }

    private boolean needsSchemaRebuild(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(shared_files)")) {
            if (!resultSet.next()) {
                return false;
            }
            boolean hasFileId = false;
            do {
                if ("file_id".equalsIgnoreCase(resultSet.getString("name"))) {
                    hasFileId = true;
                    break;
                }
            } while (resultSet.next());
            return !hasFileId;
        }
    }

    public synchronized void registerSharedFile(SharedFileDescriptor descriptor,
                                                String originalPath,
                                                String peerId,
                                                String host,
                                                int port) {
        String sql = """
                INSERT INTO shared_files (file_id, filename, size, chunk_size, chunk_count, original_path, encrypted, peer_id, host, port, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(file_id, peer_id)
                DO UPDATE SET
                    filename = excluded.filename,
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
            statement.setString(1, descriptor.fileId());
            statement.setString(2, descriptor.filename());
            statement.setLong(3, descriptor.size());
            statement.setInt(4, descriptor.chunkSize());
            statement.setInt(5, descriptor.chunkCount());
            statement.setString(6, originalPath);
            statement.setInt(7, descriptor.encrypted() ? 1 : 0);
            statement.setString(8, peerId);
            statement.setString(9, host);
            statement.setInt(10, port);
            statement.setString(11, LocalDateTime.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to register file " + descriptor.filename(), exception);
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
                SELECT file_id, filename, size, chunk_size, chunk_count, MAX(encrypted) AS encrypted
                FROM shared_files
                WHERE filename LIKE ?
                GROUP BY file_id, filename, size, chunk_size, chunk_count
                ORDER BY filename, file_id;
                """;
        List<SearchResult> results = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "%" + query + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String fileId = resultSet.getString("file_id");
                    String filename = resultSet.getString("filename");
                    long size = resultSet.getLong("size");
                    int chunkSize = resultSet.getInt("chunk_size");
                    int chunkCount = resultSet.getInt("chunk_count");
                    results.add(new SearchResult(
                            filename,
                            fileId,
                            size,
                            chunkSize,
                            chunkCount,
                            resultSet.getInt("encrypted") == 1,
                            findPeersByFileId(fileId, filename)
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
                SELECT file_id, filename, size, chunk_size, chunk_count, MIN(original_path) AS original_path,
                       MAX(encrypted) AS encrypted, MAX(updated_at) AS updated_at
                FROM shared_files
                GROUP BY file_id, filename, size, chunk_size, chunk_count
                ORDER BY filename, file_id;
                """;
        List<TrackerRecord> records = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String fileId = resultSet.getString("file_id");
                String filename = resultSet.getString("filename");
                long size = resultSet.getLong("size");
                int chunkSize = resultSet.getInt("chunk_size");
                int chunkCount = resultSet.getInt("chunk_count");
                records.add(new TrackerRecord(
                        filename,
                        fileId,
                        size,
                        resultSet.getString("original_path"),
                        chunkSize,
                        chunkCount,
                        resultSet.getInt("encrypted") == 1,
                        resultSet.getString("updated_at"),
                        findPeersByFileId(fileId, filename),
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

    private List<PeerInfo> findPeersByFileId(String fileId, String filename) throws SQLException {
        String sql = "SELECT peer_id, host, port FROM shared_files WHERE file_id = ? AND filename = ? ORDER BY peer_id";
        List<PeerInfo> peers = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileId);
            statement.setString(2, filename);
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
