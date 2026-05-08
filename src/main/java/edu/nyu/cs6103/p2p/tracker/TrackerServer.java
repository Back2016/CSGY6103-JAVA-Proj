package edu.nyu.cs6103.p2p.tracker;

import edu.nyu.cs6103.p2p.common.AppConfig;
import edu.nyu.cs6103.p2p.common.ProtocolCommands;
import edu.nyu.cs6103.p2p.db.TrackerDatabase;
import edu.nyu.cs6103.p2p.model.ChunkRecord;
import edu.nyu.cs6103.p2p.model.PeerInfo;
import edu.nyu.cs6103.p2p.model.SearchResult;
import edu.nyu.cs6103.p2p.model.SharedFileDescriptor;
import edu.nyu.cs6103.p2p.model.TrackerRecord;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TrackerServer {
    private final int port;
    private final TrackerDatabase database;
    private final String sessionId;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tracker-cleanup");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;
    private volatile ServerSocket serverSocket;

    public TrackerServer(int port, TrackerDatabase database) {
        this.port = port;
        this.database = database;
        this.sessionId = java.util.UUID.randomUUID().toString();
    }

    public void start() throws IOException {
        database.clearPeerSessionsAndFiles();
        running = true;
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                database.purgeExpiredSessions();
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }, AppConfig.HEARTBEAT_INTERVAL_MS, AppConfig.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        try (ServerSocket boundServerSocket = new ServerSocket(port)) {
            serverSocket = boundServerSocket;
            while (running) {
                Socket clientSocket = boundServerSocket.accept();
                executorService.submit(() -> handleClient(clientSocket));
            }
        } catch (SocketException exception) {
            if (running) {
                throw exception;
            }
        } finally {
            running = false;
            serverSocket = null;
            cleanupExecutor.shutdownNow();
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        executorService.shutdownNow();
        cleanupExecutor.shutdownNow();
    }

    private void handleClient(Socket clientSocket) {
        try (clientSocket;
             DataInputStream input = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()))) {
            String command = input.readUTF();
            switch (command) {
                case ProtocolCommands.PING -> handlePing(output);
                case ProtocolCommands.HELLO -> handleHello(input, output);
                case ProtocolCommands.HEARTBEAT -> handleHeartbeat(input, output);
                case ProtocolCommands.REGISTER -> handleRegister(input, output);
                case ProtocolCommands.DISCONNECT -> handleDisconnect(input, output);
                case ProtocolCommands.SEARCH -> handleSearch(input, output);
                case ProtocolCommands.LIST_RECORDS -> handleListRecords(output);
                case ProtocolCommands.PEER_COUNT -> handlePeerCount(output);
                default -> {
                    output.writeBoolean(false);
                    output.writeUTF("Unsupported tracker command: " + command);
                    output.flush();
                }
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private void handlePing(DataOutputStream output) throws IOException {
        output.writeBoolean(true);
        output.writeUTF(ProtocolCommands.PONG);
        output.writeUTF(sessionId);
        output.flush();
    }

    private void handleHello(DataInputStream input, DataOutputStream output) throws IOException {
        String peerId = input.readUTF();
        String host = input.readUTF();
        int peerPort = input.readInt();
        String peerSessionToken = database.openPeerSession(peerId, host, peerPort);
        output.writeBoolean(true);
        output.writeUTF(peerSessionToken);
        output.flush();
    }

    private void handleHeartbeat(DataInputStream input, DataOutputStream output) throws IOException {
        String sessionToken = input.readUTF();
        try {
            database.heartbeat(sessionToken);
            output.writeBoolean(true);
            output.writeUTF("Heartbeat accepted");
        } catch (IllegalStateException exception) {
            output.writeBoolean(false);
            output.writeUTF(exception.getMessage());
        }
        output.flush();
    }

    private void handleRegister(DataInputStream input, DataOutputStream output) throws IOException {
        String sessionToken = input.readUTF();
        String fileId = input.readUTF();
        String contentHash = input.readUTF();
        String filename = input.readUTF();
        long size = input.readLong();
        int chunkSize = input.readInt();
        int chunkCount = input.readInt();
        boolean encrypted = input.readBoolean();

        try {
            database.registerSharedFile(
                    new SharedFileDescriptor(fileId, contentHash, filename, size, chunkSize, chunkCount, encrypted),
                    sessionToken
            );
            output.writeBoolean(true);
            output.writeUTF("Registered " + filename);
        } catch (IllegalStateException exception) {
            output.writeBoolean(false);
            output.writeUTF(exception.getMessage());
        }
        output.flush();
    }

    private void handleDisconnect(DataInputStream input, DataOutputStream output) throws IOException {
        String sessionToken = input.readUTF();
        try {
            database.closePeerSession(sessionToken);
            output.writeBoolean(true);
            output.writeUTF("Disconnected peer session");
        } catch (IllegalStateException exception) {
            output.writeBoolean(false);
            output.writeUTF(exception.getMessage());
        }
        output.flush();
    }

    private void handleSearch(DataInputStream input, DataOutputStream output) throws IOException {
        String query = input.readUTF();
        List<SearchResult> results = database.searchFiles(query);
        output.writeBoolean(true);
        output.writeInt(results.size());
        for (SearchResult result : results) {
            output.writeUTF(result.fileId());
            output.writeUTF(result.contentHash());
            output.writeUTF(result.filename());
            output.writeLong(result.size());
            output.writeInt(result.chunkSize());
            output.writeInt(result.chunkCount());
            output.writeBoolean(result.encrypted());
            output.writeInt(result.peers().size());
            for (PeerInfo peer : result.peers()) {
                output.writeUTF(peer.peerId());
                output.writeUTF(peer.host());
                output.writeInt(peer.port());
            }
        }
        output.flush();
    }

    private void handleListRecords(DataOutputStream output) throws IOException {
        List<TrackerRecord> records = database.listTrackerRecords();
        output.writeBoolean(true);
        output.writeInt(records.size());
        for (TrackerRecord record : records) {
            output.writeUTF(record.fileId());
            output.writeUTF(record.contentHash());
            output.writeUTF(record.filename());
            output.writeLong(record.size());
            output.writeInt(record.chunkSize());
            output.writeInt(record.chunkCount());
            output.writeBoolean(record.encrypted());
            output.writeUTF(record.updatedAt() == null ? "" : record.updatedAt());
            output.writeInt(record.peers().size());
            for (PeerInfo peer : record.peers()) {
                output.writeUTF(peer.peerId());
                output.writeUTF(peer.host());
                output.writeInt(peer.port());
            }
            output.writeInt(record.chunkRecords().size());
            for (ChunkRecord chunkRecord : record.chunkRecords()) {
                output.writeInt(chunkRecord.chunkIndex());
                output.writeLong(chunkRecord.offset());
                output.writeInt(chunkRecord.length());
            }
        }
        output.flush();
    }

    private void handlePeerCount(DataOutputStream output) throws IOException {
        int count = database.countActivePeers();
        output.writeBoolean(true);
        output.writeInt(count);
        output.flush();
    }
}
