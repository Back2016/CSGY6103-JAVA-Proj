package edu.neu.cs6103.p2p.tracker;

import edu.neu.cs6103.p2p.db.TrackerDatabase;
import edu.neu.cs6103.p2p.model.PeerInfo;
import edu.neu.cs6103.p2p.model.SearchResult;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrackerServer {
    private final int port;
    private final TrackerDatabase database;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public TrackerServer(int port, TrackerDatabase database) {
        this.port = port;
        this.database = database;
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                executorService.submit(() -> handleClient(clientSocket));
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try (clientSocket;
             DataInputStream input = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()))) {
            String command = input.readUTF();
            switch (command) {
                case "PING" -> handlePing(output);
                case "REGISTER" -> handleRegister(input, output);
                case "SEARCH" -> handleSearch(input, output);
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

        database.registerSharedFile(filename, size, chunkSize, chunkCount, peerId, host, peerPort);

        output.writeBoolean(true);
        output.writeUTF("Registered " + filename);
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
            output.writeInt(result.peers().size());
            for (PeerInfo peer : result.peers()) {
                output.writeUTF(peer.peerId());
                output.writeUTF(peer.host());
                output.writeInt(peer.port());
            }
        }
        output.flush();
    }
}
