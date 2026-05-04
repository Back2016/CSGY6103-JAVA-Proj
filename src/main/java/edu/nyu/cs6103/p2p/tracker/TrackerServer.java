package edu.nyu.cs6103.p2p.tracker;

import edu.nyu.cs6103.p2p.db.TrackerDatabase;
import edu.nyu.cs6103.p2p.model.ChunkRecord;
import edu.nyu.cs6103.p2p.model.PeerInfo;
import edu.nyu.cs6103.p2p.model.SearchResult;
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

public class TrackerServer {
    private final int port;
    private final TrackerDatabase database;
    private final String sessionId;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private volatile boolean running;
    private volatile ServerSocket serverSocket;

    public TrackerServer(int port, TrackerDatabase database) {
        this.port = port;
        this.database = database;
        this.sessionId = java.util.UUID.randomUUID().toString();
    }

    public void start() throws IOException {
        database.clearSharedFiles();
        running = true;
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
    }

    private void handleClient(Socket clientSocket) {
        try (clientSocket;
             DataInputStream input = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()))) {
            String command = input.readUTF();
            switch (command) {
                case "PING" -> handlePing(output);
                case "REGISTER" -> handleRegister(input, output);
                case "UNREGISTER_PEER" -> handleUnregisterPeer(input, output);
                case "SEARCH" -> handleSearch(input, output);
                case "LIST_RECORDS" -> handleListRecords(output);
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
        output.writeUTF("PONG");
        output.writeUTF(sessionId);
        output.flush();
    }

    private void handleRegister(DataInputStream input, DataOutputStream output) throws IOException {
        String peerId = input.readUTF();
        String host = input.readUTF();
        int peerPort = input.readInt();
        String filename = input.readUTF();
        long size = input.readLong();
        int chunkSize = input.readInt();
        int chunkCount = input.readInt();
        String originalPath = input.readUTF();
        boolean encrypted = input.readBoolean();

        database.registerSharedFile(filename, size, chunkSize, chunkCount, originalPath, encrypted, peerId, host, peerPort);

        output.writeBoolean(true);
        output.writeUTF("Registered " + filename);
        output.flush();
    }

    private void handleUnregisterPeer(DataInputStream input, DataOutputStream output) throws IOException {
        String peerId = input.readUTF();
        database.unregisterPeer(peerId);
        output.writeBoolean(true);
        output.writeUTF("Unregistered " + peerId);
        output.flush();
    }

    private void handleSearch(DataInputStream input, DataOutputStream output) throws IOException {
        String query = input.readUTF();
        List<SearchResult> results = database.searchFiles(query);
        output.writeBoolean(true);
        output.writeInt(results.size());
        for (SearchResult result : results) {
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
            output.writeUTF(record.filename());
            output.writeLong(record.size());
            output.writeUTF(record.originalPath() == null ? "" : record.originalPath());
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
}
