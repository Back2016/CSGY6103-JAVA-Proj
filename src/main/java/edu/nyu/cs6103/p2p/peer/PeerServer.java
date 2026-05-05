package edu.nyu.cs6103.p2p.peer;

import edu.nyu.cs6103.p2p.common.AppConfig;
import edu.nyu.cs6103.p2p.common.ProtocolCommands;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PeerServer {
    private final int port;
    private final PeerNode peerNode;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile Thread serverThread;

    public PeerServer(int port, PeerNode peerNode) {
        this.port = port;
        this.peerNode = peerNode;
    }

    public void start() {
        if (running) {
            return;
        }

        running = true;
        serverThread = new Thread(() -> {
            try (ServerSocket boundServerSocket = new ServerSocket(port)) {
                serverSocket = boundServerSocket;
                while (running) {
                    Socket socket = serverSocket.accept();
                    executorService.submit(() -> handleClient(socket));
                }
            } catch (SocketException exception) {
                if (running) {
                    throw new IllegalStateException("Peer server stopped unexpectedly", exception);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Peer server stopped unexpectedly", exception);
            } finally {
                running = false;
                serverSocket = null;
            }
        }, "peer-server-" + port);
        serverThread.setDaemon(true);
        serverThread.start();
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

    private void handleClient(Socket socket) {
        try (socket;
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            String command = input.readUTF();
            switch (command) {
                case ProtocolCommands.MANIFEST -> handleManifest(input, output);
                case ProtocolCommands.CHUNK -> handleChunk(input, output);
                default -> {
                    output.writeBoolean(false);
                    output.writeUTF("Unsupported peer command: " + command);
                    output.flush();
                }
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private void handleManifest(DataInputStream input, DataOutputStream output) throws IOException {
        String filename = input.readUTF();
        Path path = peerNode.getSharedFile(filename);
        if (path == null || !Files.exists(path)) {
            output.writeBoolean(false);
            output.writeUTF("File not found: " + filename);
            output.flush();
            return;
        }

        long size = Files.size(path);
        int chunkCount = (int) Math.ceil((double) size / AppConfig.DEFAULT_CHUNK_SIZE);
        output.writeBoolean(true);
        output.writeLong(size);
        output.writeInt(AppConfig.DEFAULT_CHUNK_SIZE);
        output.writeInt(chunkCount);
        output.flush();
    }

    private void handleChunk(DataInputStream input, DataOutputStream output) throws IOException {
        String filename = input.readUTF();
        int chunkIndex = input.readInt();
        Path path = peerNode.getSharedFile(filename);
        if (path == null || !Files.exists(path)) {
            output.writeBoolean(false);
            output.writeUTF("File not found: " + filename);
            output.flush();
            return;
        }

        long offset = (long) chunkIndex * AppConfig.DEFAULT_CHUNK_SIZE;
        long fileSize = Files.size(path);
        int length = (int) Math.min(AppConfig.DEFAULT_CHUNK_SIZE, fileSize - offset);
        if (length <= 0) {
            output.writeBoolean(false);
            output.writeUTF("Chunk index out of range");
            output.flush();
            return;
        }

        byte[] buffer = new byte[length];
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(path.toFile(), "r")) {
            randomAccessFile.seek(offset);
            randomAccessFile.readFully(buffer);
        }

        output.writeBoolean(true);
        output.writeInt(chunkIndex);
        output.writeInt(length);
        output.write(buffer);
        output.flush();
    }
}
