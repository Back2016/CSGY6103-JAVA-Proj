package edu.nyu.cs6103.p2p.db;

import edu.nyu.cs6103.p2p.model.ChunkRecord;
import edu.nyu.cs6103.p2p.model.PeerInfo;
import edu.nyu.cs6103.p2p.model.SearchResult;
import edu.nyu.cs6103.p2p.model.SharedFileDescriptor;
import edu.nyu.cs6103.p2p.model.TrackerRecord;
import edu.nyu.cs6103.p2p.common.AppConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TrackerDatabase {
    private final String jdbcUrl;

    public TrackerDatabase(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        initialize();
    }

    private void initialize() {
        String peerSessionsDdl = """
                CREATE TABLE IF NOT EXISTS peer_sessions (
                    session_token TEXT NOT NULL PRIMARY KEY,
                    peer_id TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    last_seen_at INTEGER NOT NULL
                );
                """;
        String sharedFilesDdl = """
                CREATE TABLE IF NOT EXISTS shared_files (
                    file_id TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    filename TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    chunk_size INTEGER NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    encrypted INTEGER NOT NULL DEFAULT 0,
                    session_token TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (file_id, session_token)
                );
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            if (needsSchemaRebuild(connection)) {
                statement.execute("DROP TABLE IF EXISTS shared_files");
                statement.execute("DROP TABLE IF EXISTS peer_sessions");
            }
            statement.execute(peerSessionsDdl);
            statement.execute(sharedFilesDdl);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize tracker database", exception);
        }
    }

    private boolean needsSchemaRebuild(Connection connection) throws SQLException {
        return !hasColumn(connection, "shared_files", "session_token")
                || !hasColumn(connection, "shared_files", "file_id")
                || !hasColumn(connection, "shared_files", "content_hash")
                || hasColumn(connection, "shared_files", "original_path")
                || !hasTable(connection, "peer_sessions");
    }

    private boolean hasColumn(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean hasTable(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public synchronized String openPeerSession(String peerId, String host, int port) {
        String sessionToken = UUID.randomUUID().toString();
        String sql = """
                INSERT INTO peer_sessions (session_token, peer_id, host, port, created_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        long now = System.currentTimeMillis();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionToken);
            statement.setString(2, peerId);
            statement.setString(3, host);
            statement.setInt(4, port);
            statement.setLong(5, now);
            statement.setLong(6, now);
            statement.executeUpdate();
            return sessionToken;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to open peer session for " + peerId, exception);
        }
    }

    public synchronized void heartbeat(String sessionToken) {
        String sql = "UPDATE peer_sessions SET last_seen_at = ? WHERE session_token = ? AND last_seen_at >= ?";
        long now = System.currentTimeMillis();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setString(2, sessionToken);
            statement.setLong(3, activeCutoff());
            if (statement.executeUpdate() == 0) {
                throw new IllegalStateException("Unknown peer session");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to update peer session heartbeat", exception);
        }
    }

    public synchronized void registerSharedFile(SharedFileDescriptor descriptor,
                                                String sessionToken) {
        String sql = """
                INSERT INTO shared_files (file_id, content_hash, filename, size, chunk_size, chunk_count, encrypted, session_token, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(file_id, session_token)
                DO UPDATE SET
                    content_hash = excluded.content_hash,
                    filename = excluded.filename,
                    size = excluded.size,
                    chunk_size = excluded.chunk_size,
                    chunk_count = excluded.chunk_count,
                    encrypted = excluded.encrypted,
                    updated_at = excluded.updated_at;
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            requireActiveSession(connection, sessionToken);
            statement.setString(1, descriptor.fileId());
            statement.setString(2, descriptor.contentHash());
            statement.setString(3, descriptor.filename());
            statement.setLong(4, descriptor.size());
            statement.setInt(5, descriptor.chunkSize());
            statement.setInt(6, descriptor.chunkCount());
            statement.setInt(7, descriptor.encrypted() ? 1 : 0);
            statement.setString(8, sessionToken);
            statement.setString(9, LocalDateTime.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to register file " + descriptor.filename(), exception);
        }
    }

    public synchronized void closePeerSession(String sessionToken) {
        String deleteFilesSql = "DELETE FROM shared_files WHERE session_token = ?";
        String deleteSessionSql = "DELETE FROM peer_sessions WHERE session_token = ?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement deleteFiles = connection.prepareStatement(deleteFilesSql);
             PreparedStatement deleteSession = connection.prepareStatement(deleteSessionSql)) {
            requireActiveSession(connection, sessionToken);
            deleteFiles.setString(1, sessionToken);
            deleteFiles.executeUpdate();
            deleteSession.setString(1, sessionToken);
            if (deleteSession.executeUpdate() == 0) {
                throw new IllegalStateException("Unknown peer session");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to close peer session", exception);
        }
    }

    public synchronized int countActivePeers() {
        String sql = "SELECT COUNT(*) FROM peer_sessions WHERE last_seen_at >= ?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, activeCutoff());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to count active peers", exception);
        }
    }

    public synchronized void clearPeerSessionsAndFiles() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM shared_files");
            statement.executeUpdate("DELETE FROM peer_sessions");
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to clear tracker database", exception);
        }
    }

    public synchronized void purgeExpiredSessions() {
        long cutoff = System.currentTimeMillis() - AppConfig.PEER_SESSION_TTL_MS;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement deleteFiles = connection.prepareStatement(
                     "DELETE FROM shared_files WHERE session_token IN (SELECT session_token FROM peer_sessions WHERE last_seen_at < ?)");
             PreparedStatement deleteSessions = connection.prepareStatement(
                     "DELETE FROM peer_sessions WHERE last_seen_at < ?")) {
            deleteFiles.setLong(1, cutoff);
            deleteFiles.executeUpdate();
            deleteSessions.setLong(1, cutoff);
            deleteSessions.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to purge expired peer sessions", exception);
        }
    }

    public synchronized List<SearchResult> searchFiles(String query) {
        String sql = """
                SELECT sf.file_id, sf.content_hash, sf.filename, sf.size, sf.chunk_size, sf.chunk_count, MAX(sf.encrypted) AS encrypted
                FROM shared_files sf
                JOIN peer_sessions ps ON ps.session_token = sf.session_token
                WHERE filename LIKE ?
                  AND ps.last_seen_at >= ?
                GROUP BY sf.file_id, sf.filename, sf.size, sf.chunk_size, sf.chunk_count
                ORDER BY sf.filename, sf.file_id;
                """;
        List<SearchResult> results = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "%" + query + "%");
            statement.setLong(2, activeCutoff());
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
                            resultSet.getString("content_hash"),
                            size,
                            chunkSize,
                            chunkCount,
                            resultSet.getInt("encrypted") == 1,
                            findPeersByFileId(connection, fileId, filename)
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
                SELECT sf.file_id, sf.content_hash, sf.filename, sf.size, sf.chunk_size, sf.chunk_count,
                       MAX(sf.encrypted) AS encrypted, MAX(sf.updated_at) AS updated_at
                FROM shared_files sf
                JOIN peer_sessions ps ON ps.session_token = sf.session_token
                WHERE ps.last_seen_at >= ?
                GROUP BY sf.file_id, sf.filename, sf.size, sf.chunk_size, sf.chunk_count
                ORDER BY sf.filename, sf.file_id;
                """;
        List<TrackerRecord> records = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, activeCutoff());
            try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String fileId = resultSet.getString("file_id");
                String filename = resultSet.getString("filename");
                long size = resultSet.getLong("size");
                int chunkSize = resultSet.getInt("chunk_size");
                int chunkCount = resultSet.getInt("chunk_count");
                records.add(new TrackerRecord(
                        filename,
                        fileId,
                        resultSet.getString("content_hash"),
                        size,
                        chunkSize,
                        chunkCount,
                        resultSet.getInt("encrypted") == 1,
                        resultSet.getString("updated_at"),
                        findPeersByFileId(connection, fileId, filename),
                        buildChunkRecords(size, chunkSize, chunkCount)
                ));
            }
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

    private List<PeerInfo> findPeersByFileId(Connection connection, String fileId, String filename) throws SQLException {
        String sql = """
                SELECT ps.peer_id, ps.host, ps.port
                FROM shared_files sf
                JOIN peer_sessions ps ON ps.session_token = sf.session_token
                WHERE sf.file_id = ?
                  AND sf.filename = ?
                  AND ps.last_seen_at >= ?
                ORDER BY ps.peer_id
                """;
        List<PeerInfo> peers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileId);
            statement.setString(2, filename);
            statement.setLong(3, activeCutoff());
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

    private void requireActiveSession(Connection connection, String sessionToken) throws SQLException {
        String sql = "SELECT peer_id FROM peer_sessions WHERE session_token = ? AND last_seen_at >= ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionToken);
            statement.setLong(2, activeCutoff());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Unknown or expired peer session");
                }
            }
        }
    }

    private long activeCutoff() {
        return System.currentTimeMillis() - AppConfig.PEER_SESSION_TTL_MS;
    }
}
