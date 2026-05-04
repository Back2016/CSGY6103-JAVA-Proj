package edu.neu.cs6103.p2p.peer;

import edu.neu.cs6103.p2p.common.AppConfig;
import edu.neu.cs6103.p2p.db.ClientDatabase;
import edu.neu.cs6103.p2p.model.DownloadHistoryEntry;
import edu.neu.cs6103.p2p.model.PeerInfo;
import edu.neu.cs6103.p2p.model.SearchResult;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

public class PeerNode {
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 3000;
    private static final int SOCKET_READ_TIMEOUT_MS = 5000;

    private final String peerId;
    private final String trackerHost;
    private final int trackerPort;
    private final int peerPort;
    private final String advertisedHost;
    private final Path sharedDirectory;
    private final Path downloadsDirectory;
    private final ClientDatabase clientDatabase;
    private final Map<String, Path> sharedFiles = new ConcurrentHashMap<>();
    private final PeerServer peerServer;

    public PeerNode(String peerId,
                    String trackerHost,
                    int trackerPort,
                    int peerPort,
                    Path sharedDirectory,
                    Path downloadsDirectory) throws IOException {
        this(peerId, trackerHost, trackerPort, peerPort, suggestAdvertisedHost(), sharedDirectory, downloadsDirectory,
                downloadsDirectory.resolve("peer-client.db"));
    }

    public PeerNode(String peerId,
                    String trackerHost,
                    int trackerPort,
                    int peerPort,
                    String advertisedHost,
                    Path sharedDirectory,
                    Path downloadsDirectory) throws IOException {
        this(peerId, trackerHost, trackerPort, peerPort, advertisedHost, sharedDirectory, downloadsDirectory,
                downloadsDirectory.resolve("peer-client.db"));
    }

    public PeerNode(String peerId,
                    String trackerHost,
                    int trackerPort,
                    int peerPort,
                    Path sharedDirectory,
                    Path downloadsDirectory,
                    Path clientDatabasePath) throws IOException {
        this(peerId, trackerHost, trackerPort, peerPort, suggestAdvertisedHost(), sharedDirectory, downloadsDirectory, clientDatabasePath);
    }

    public PeerNode(String peerId,
                    String trackerHost,
                    int trackerPort,
                    int peerPort,
                    String advertisedHost,
                    Path sharedDirectory,
                    Path downloadsDirectory,
                    Path clientDatabasePath) throws IOException {
        this.peerId = peerId;
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
        this.peerPort = peerPort;
        this.advertisedHost = advertisedHost;
        this.sharedDirectory = sharedDirectory;
        this.downloadsDirectory = downloadsDirectory;
        Files.createDirectories(sharedDirectory);
        Files.createDirectories(downloadsDirectory);
        this.clientDatabase = new ClientDatabase("jdbc:sqlite:" + clientDatabasePath.toAbsolutePath());
        this.peerServer = new PeerServer(peerPort, this);
    }

    public void startServer() {
        peerServer.start();
    }

    public void stopServer() {
        peerServer.stop();
    }

    public void shareFile(Path filePath) throws IOException {
        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            throw new IllegalArgumentException("Selected path is not a file");
        }

        sharedFiles.put(filePath.getFileName().toString(), filePath);
        registerFileWithTracker(filePath);
    }

    public List<String> getSharedFileNames() {
        return sharedFiles.keySet().stream().sorted().toList();
    }

    public Path getSharedFile(String filename) {
        return sharedFiles.get(filename);
    }

    public List<SearchResult> search(String query) throws IOException {
        try (Socket socket = new Socket(trackerHost, trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF("SEARCH");
            output.writeUTF(query);
            output.flush();

            boolean success = input.readBoolean();
            if (!success) {
                throw new IOException(input.readUTF());
            }

            int resultCount = input.readInt();
            List<SearchResult> results = new ArrayList<>();
            for (int i = 0; i < resultCount; i++) {
                String filename = input.readUTF();
                long size = input.readLong();
                int chunkSize = input.readInt();
                int chunkCount = input.readInt();
                int peerCount = input.readInt();
                List<PeerInfo> peers = new ArrayList<>();
                for (int peerIndex = 0; peerIndex < peerCount; peerIndex++) {
                    peers.add(new PeerInfo(input.readUTF(), input.readUTF(), input.readInt()));
                }
                results.add(new SearchResult(filename, size, chunkSize, chunkCount, peers));
            }
            return results;
        }
    }

    public Path download(SearchResult result, DoubleConsumer progressCallback, Consumer<String> statusCallback) throws IOException {
        List<PeerInfo> candidatePeers = result.peers().stream()
                .filter(peer -> !(peer.peerId().equals(peerId) && peer.port() == peerPort))
                .toList();
        if (candidatePeers.isEmpty()) {
            throw new IOException("No remote peers are available for " + result.filename());
        }

        Path destination = resolveDownloadTarget(result.filename());
        Files.createDirectories(downloadsDirectory);
        String peerSummary = candidatePeers.stream().map(PeerInfo::toString).reduce((a, b) -> a + ", " + b).orElse("none");

        statusCallback.accept("Preparing " + result.chunkCount() + " chunk(s)");
        try (FileChannel channel = FileChannel.open(destination,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            channel.truncate(result.size());
            AtomicInteger completedChunks = new AtomicInteger();
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(AppConfig.DOWNLOAD_THREADS, result.chunkCount()));
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int chunkIndex = 0; chunkIndex < result.chunkCount(); chunkIndex++) {
                    final int currentChunk = chunkIndex;
                    futures.add(executor.submit(() -> {
                        byte[] bytes = fetchChunkWithRetry(result, currentChunk, candidatePeers);
                        try {
                            channel.write(ByteBuffer.wrap(bytes), (long) currentChunk * result.chunkSize());
                        } catch (IOException exception) {
                            throw new IllegalStateException("Failed to write chunk " + currentChunk, exception);
                        }
                        int done = completedChunks.incrementAndGet();
                        progressCallback.accept(done / (double) result.chunkCount());
                        statusCallback.accept("Downloaded chunk " + done + " of " + result.chunkCount());
                    }));
                }

                for (Future<?> future : futures) {
                    future.get();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", exception);
            } catch (ExecutionException exception) {
                Files.deleteIfExists(destination);
                clientDatabase.recordDownload(result.filename(), peerSummary, destination.toAbsolutePath().toString(), "FAILED");
                throw new IOException("Download failed: " + exception.getCause().getMessage(), exception.getCause());
            } finally {
                executor.shutdownNow();
            }
        }

        clientDatabase.recordDownload(result.filename(), peerSummary, destination.toAbsolutePath().toString(), "COMPLETED");
        progressCallback.accept(1.0);
        statusCallback.accept("Download complete");
        return destination;
    }

    public List<DownloadHistoryEntry> getDownloadHistory() {
        return clientDatabase.listHistory();
    }

    public String describeRemotePeers(SearchResult result) {
        return result.peers().stream()
                .filter(peer -> !(peer.peerId().equals(peerId) && peer.port() == peerPort))
                .map(PeerInfo::toString)
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }

    private void registerFileWithTracker(Path filePath) throws IOException {
        long size = Files.size(filePath);
        int chunkCount = (int) Math.ceil((double) size / AppConfig.DEFAULT_CHUNK_SIZE);
        try (Socket socket = new Socket(trackerHost, trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF("REGISTER");
            output.writeUTF(peerId);
            output.writeUTF(advertisedHost);
            output.writeInt(peerPort);
            output.writeUTF(filePath.getFileName().toString());
            output.writeLong(size);
            output.writeInt(AppConfig.DEFAULT_CHUNK_SIZE);
            output.writeInt(chunkCount);
            output.flush();

            boolean success = input.readBoolean();
            if (!success) {
                throw new IOException(input.readUTF());
            }
            input.readUTF();
        }
    }

    public static String suggestAdvertisedHost() throws IOException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                continue;
            }

            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && address.isSiteLocalAddress()) {
                    return address.getHostAddress();
                }
            }
        }

        return InetAddress.getLocalHost().getHostAddress();
    }

    private Path resolveDownloadTarget(String filename) {
        Path candidate = downloadsDirectory.resolve(filename);
        if (!Files.exists(candidate)) {
            return candidate;
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex <= 0) {
            return downloadsDirectory.resolve(filename + "_" + timestamp);
        }
        String stem = filename.substring(0, dotIndex);
        String extension = filename.substring(dotIndex);
        return downloadsDirectory.resolve(stem + "_" + timestamp + extension);
    }

    private byte[] fetchChunkWithRetry(SearchResult result, int chunkIndex, List<PeerInfo> peers) {
        List<PeerInfo> orderedPeers = new ArrayList<>(peers);
        orderedPeers.sort(Comparator.comparing(PeerInfo::peerId));
        int startIndex = chunkIndex % orderedPeers.size();
        IOException lastException = null;

        for (int attempt = 0; attempt < orderedPeers.size(); attempt++) {
            PeerInfo peer = orderedPeers.get((startIndex + attempt) % orderedPeers.size());
            try {
                return fetchChunk(peer, result.filename(), chunkIndex);
            } catch (IOException exception) {
                lastException = exception;
                if (attempt == orderedPeers.size() - 1) {
                    throw new IllegalStateException("Unable to fetch chunk " + chunkIndex + " from peers " +
                            orderedPeers.stream().map(PeerInfo::toString).reduce((a, b) -> a + ", " + b).orElse("none") +
                            ". Last error: " + rootCauseMessage(exception), exception);
                }
            }
        }
        throw new IllegalStateException("No peers available for chunk " + chunkIndex +
                (lastException == null ? "" : ". Last error: " + rootCauseMessage(lastException)));
    }

    private byte[] fetchChunk(PeerInfo peer, String filename, int chunkIndex) throws IOException {
        try (Socket socket = new Socket()) {
            SocketAddress socketAddress = new InetSocketAddress(peer.host(), peer.port());
            socket.connect(socketAddress, SOCKET_CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);

            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF("CHUNK");
            output.writeUTF(filename);
            output.writeInt(chunkIndex);
            output.flush();

            boolean success = input.readBoolean();
            if (!success) {
                throw new IOException(input.readUTF());
            }

            int returnedChunkIndex = input.readInt();
            int length = input.readInt();
            if (returnedChunkIndex != chunkIndex) {
                throw new IOException("Chunk mismatch for " + filename + ": expected " + chunkIndex + " but received " + returnedChunkIndex);
            }

            byte[] buffer = new byte[length];
            input.readFully(buffer);
            return buffer;
            }
        }
    }

    public static String generatePeerId() {
        return "peer-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
