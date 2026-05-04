package edu.nyu.cs6103.p2p.peer;

import edu.nyu.cs6103.p2p.common.AppConfig;
import edu.nyu.cs6103.p2p.db.ClientDatabase;
import edu.nyu.cs6103.p2p.model.DownloadHistoryEntry;
import edu.nyu.cs6103.p2p.model.PeerInfo;
import edu.nyu.cs6103.p2p.model.SearchResult;
import edu.nyu.cs6103.p2p.model.TrackerRecord;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
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

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class PeerNode {
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 3000;
    private static final int SOCKET_READ_TIMEOUT_MS = 5000;
    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final byte[] ENCRYPTED_MAGIC = "P2PENC1".getBytes(StandardCharsets.US_ASCII);

    private final String peerId;
    private final String trackerHost;
    private final int trackerPort;
    private final String trackerSessionId;
    private final int peerPort;
    private final String advertisedHost;
    private final Path trackerRecordsDirectory;
    private final Path sessionDirectory;
    private final Path encryptedFilesDirectory;
    private final Path downloadsDirectory;
    private final ClientDatabase clientDatabase;
    private final Map<String, SharedFileEntry> sharedFiles = new ConcurrentHashMap<>();
    private final PeerServer peerServer;

    public PeerNode(String peerId,
                    String trackerHost,
                    int trackerPort,
                    int peerPort,
                    Path trackerRecordsDirectory,
                    Path downloadsDirectory) throws IOException {
        this(peerId, trackerHost, trackerPort, resolveTrackerSessionId(trackerHost, trackerPort), peerPort,
                suggestAdvertisedHost(), trackerRecordsDirectory, downloadsDirectory);
    }

    public PeerNode(String peerId,
                    String trackerHost,
                    int trackerPort,
                    int peerPort,
                    String advertisedHost,
                    Path trackerRecordsDirectory,
                    Path downloadsDirectory) throws IOException {
        this(peerId, trackerHost, trackerPort, resolveTrackerSessionId(trackerHost, trackerPort), peerPort,
                advertisedHost, trackerRecordsDirectory, downloadsDirectory);
    }

    public PeerNode(String peerId,
                    String trackerHost,
                    int trackerPort,
                    String trackerSessionId,
                    int peerPort,
                    String advertisedHost,
                    Path trackerRecordsDirectory,
                    Path downloadsDirectory) throws IOException {
        this.peerId = peerId;
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
        this.trackerSessionId = trackerSessionId;
        this.peerPort = peerPort;
        this.advertisedHost = advertisedHost;
        this.trackerRecordsDirectory = trackerRecordsDirectory;
        this.downloadsDirectory = downloadsDirectory;
        String sessionSlug = sanitizeForPath(trackerHost + "_" + trackerPort + "_" + trackerSessionId);
        this.sessionDirectory = trackerRecordsDirectory.resolve(sessionSlug);
        this.encryptedFilesDirectory = sessionDirectory.resolve("shared_encrypted");
        Files.createDirectories(trackerRecordsDirectory);
        Files.createDirectories(sessionDirectory);
        Files.createDirectories(encryptedFilesDirectory);
        Files.createDirectories(downloadsDirectory);
        this.clientDatabase = new ClientDatabase(sessionDirectory.resolve("download_history.csv"));
        this.peerServer = new PeerServer(peerPort, this);
    }

    public void startServer() {
        peerServer.start();
    }

    public void stopServer() {
        peerServer.stop();
    }

    public void shareFile(Path filePath) throws IOException {
        shareFile(filePath, "");
    }

    public void shareFile(Path filePath, String password) throws IOException {
        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            throw new IllegalArgumentException("Selected path is not a file");
        }

        String normalizedPassword = password == null ? "" : password.trim();
        SharedFileEntry entry;
        if (normalizedPassword.isEmpty()) {
            entry = new SharedFileEntry(filePath.getFileName().toString(), filePath, filePath.toAbsolutePath().toString(), false);
        } else {
            Path encryptedCopy = buildEncryptedCopy(filePath, normalizedPassword);
            entry = new SharedFileEntry(filePath.getFileName().toString(), encryptedCopy, filePath.toAbsolutePath().toString(), true);
        }

        sharedFiles.put(entry.displayName(), entry);
        registerFileWithTracker(entry);
    }

    public List<String> getSharedFileNames() {
        return sharedFiles.keySet().stream().sorted().toList();
    }

    public Path getSharedFile(String filename) {
        SharedFileEntry entry = sharedFiles.get(filename);
        return entry == null ? null : entry.servedPath();
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
                boolean encrypted = input.readBoolean();
                int peerCount = input.readInt();
                List<PeerInfo> peers = new ArrayList<>();
                for (int peerIndex = 0; peerIndex < peerCount; peerIndex++) {
                    peers.add(new PeerInfo(input.readUTF(), input.readUTF(), input.readInt()));
                }
                results.add(new SearchResult(filename, size, chunkSize, chunkCount, encrypted, peers));
            }
            return results;
        }
    }

    public Path download(SearchResult result, DoubleConsumer progressCallback, Consumer<String> statusCallback) throws IOException {
        return download(result, "", progressCallback, statusCallback);
    }

    public Path download(SearchResult result, String password, DoubleConsumer progressCallback, Consumer<String> statusCallback) throws IOException {
        List<PeerInfo> candidatePeers = result.peers().stream()
                .filter(peer -> !(peer.peerId().equals(peerId) && peer.port() == peerPort))
                .toList();
        if (candidatePeers.isEmpty()) {
            throw new IOException("No remote peers are available for " + result.filename());
        }

        String normalizedPassword = password == null ? "" : password;
        Path destination = resolveDownloadTarget(result.filename());
        Path workingTarget = result.encrypted() ? buildEncryptedDownloadTarget(destination) : destination;
        Files.createDirectories(downloadsDirectory);
        String peerSummary = candidatePeers.stream().map(PeerInfo::toString).reduce((a, b) -> a + ", " + b).orElse("none");

        statusCallback.accept("Preparing " + result.chunkCount() + " chunk(s)");
        try (FileChannel channel = FileChannel.open(workingTarget,
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
                Files.deleteIfExists(workingTarget);
                clientDatabase.recordDownload(result.filename(), peerSummary, destination.toAbsolutePath().toString(), "FAILED");
                throw new IOException("Download failed: " + exception.getCause().getMessage(), exception.getCause());
            } finally {
                executor.shutdownNow();
            }
        }

        if (result.encrypted()) {
            if (normalizedPassword.isBlank()) {
                Files.deleteIfExists(workingTarget);
                clientDatabase.recordDownload(result.filename(), peerSummary, destination.toAbsolutePath().toString(), "FAILED");
                throw new IOException("Download failed: this file is encrypted and requires a password");
            }

            try {
                decryptFile(workingTarget, destination, normalizedPassword);
                Files.deleteIfExists(workingTarget);
            } catch (IOException exception) {
                Files.deleteIfExists(workingTarget);
                Files.deleteIfExists(destination);
                clientDatabase.recordDownload(result.filename(), peerSummary, destination.toAbsolutePath().toString(), "FAILED");
                throw new IOException("Download failed: unable to decrypt file with the provided password", exception);
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

    public List<TrackerRecord> fetchTrackerRecords() throws IOException {
        try (Socket socket = new Socket(trackerHost, trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF("LIST_RECORDS");
            output.flush();

            boolean success = input.readBoolean();
            if (!success) {
                throw new IOException(input.readUTF());
            }

            int recordCount = input.readInt();
            List<TrackerRecord> records = new ArrayList<>();
            for (int index = 0; index < recordCount; index++) {
                String filename = input.readUTF();
                long size = input.readLong();
                String originalPath = input.readUTF();
                int chunkSize = input.readInt();
                int chunkCount = input.readInt();
                boolean encrypted = input.readBoolean();
                String updatedAt = input.readUTF();
                int peerCount = input.readInt();
                List<PeerInfo> peers = new ArrayList<>();
                for (int peerIndex = 0; peerIndex < peerCount; peerIndex++) {
                    peers.add(new PeerInfo(input.readUTF(), input.readUTF(), input.readInt()));
                }
                int chunkRecordCount = input.readInt();
                List<edu.nyu.cs6103.p2p.model.ChunkRecord> chunkRecords = new ArrayList<>();
                for (int chunkIndex = 0; chunkIndex < chunkRecordCount; chunkIndex++) {
                    chunkRecords.add(new edu.nyu.cs6103.p2p.model.ChunkRecord(
                            input.readInt(),
                            input.readLong(),
                            input.readInt()
                    ));
                }
                records.add(new TrackerRecord(filename, size, originalPath, chunkSize, chunkCount, encrypted, updatedAt, peers, chunkRecords));
            }
            writeTrackerRecordsCsv(records);
            return records;
        }
    }

    public String describeRemotePeers(SearchResult result) {
        return result.peers().stream()
                .filter(peer -> !(peer.peerId().equals(peerId) && peer.port() == peerPort))
                .map(PeerInfo::toString)
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }

    public void unregisterFromTracker() throws IOException {
        try (Socket socket = new Socket(trackerHost, trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF("UNREGISTER_PEER");
            output.writeUTF(peerId);
            output.flush();

            boolean success = input.readBoolean();
            if (!success) {
                throw new IOException(input.readUTF());
            }
            input.readUTF();
        }
        clearSharedFiles();
    }

    public void clearSharedFiles() {
        for (SharedFileEntry entry : sharedFiles.values()) {
            if (entry.encrypted()) {
                try {
                    Files.deleteIfExists(entry.servedPath());
                } catch (IOException ignored) {
                }
            }
        }
        sharedFiles.clear();
    }

    private void registerFileWithTracker(SharedFileEntry entry) throws IOException {
        long size = Files.size(entry.servedPath());
        int chunkCount = (int) Math.ceil((double) size / AppConfig.DEFAULT_CHUNK_SIZE);
        try (Socket socket = new Socket(trackerHost, trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF("REGISTER");
            output.writeUTF(peerId);
            output.writeUTF(advertisedHost);
            output.writeInt(peerPort);
            output.writeUTF(entry.displayName());
            output.writeLong(size);
            output.writeInt(AppConfig.DEFAULT_CHUNK_SIZE);
            output.writeInt(chunkCount);
            output.writeUTF(entry.originalPath());
            output.writeBoolean(entry.encrypted());
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

    private Path buildEncryptedCopy(Path originalFile, String password) throws IOException {
        String safeName = originalFile.getFileName().toString() + ".p2penc";
        Path encryptedTarget = encryptedFilesDirectory.resolve(safeName);
        encryptFile(originalFile, encryptedTarget, password);
        return encryptedTarget;
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

    private Path buildEncryptedDownloadTarget(Path destination) {
        return destination.resolveSibling(destination.getFileName() + ".p2pdownload");
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

    private void writeTrackerRecordsCsv(List<TrackerRecord> records) throws IOException {
        Path csvPath = sessionDirectory.resolve("tracker_records.csv");
        List<String> lines = new ArrayList<>();
        lines.add("filename,size,original_path,chunk_size,chunk_count,encrypted,updated_at,peers,chunk_records");
        for (TrackerRecord record : records) {
            lines.add(String.join(",",
                    csv(record.filename()),
                    csv(String.valueOf(record.size())),
                    csv(record.originalPath()),
                    csv(String.valueOf(record.chunkSize())),
                    csv(String.valueOf(record.chunkCount())),
                    csv(String.valueOf(record.encrypted())),
                    csv(record.updatedAt()),
                    csv(record.peers().stream().map(PeerInfo::toString).reduce((a, b) -> a + " | " + b).orElse("")),
                    csv(record.chunkRecords().stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""))
            ));
        }
        Files.write(csvPath, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static String csv(String value) {
        String normalized = value == null ? "" : value;
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    private static String resolveTrackerSessionId(String host, int port) throws IOException {
        try (Socket socket = new Socket(host, port);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF("PING");
            output.flush();
            boolean success = input.readBoolean();
            if (!success || !"PONG".equals(input.readUTF())) {
                throw new IOException("Tracker did not return a healthy session handshake");
            }
            return input.readUTF();
        }
    }

    private static String sanitizeForPath(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private void encryptFile(Path inputFile, Path outputFile, String password) throws IOException {
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(salt);
        secureRandom.nextBytes(iv);

        try (OutputStream rawOutput = Files.newOutputStream(outputFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            rawOutput.write(ENCRYPTED_MAGIC);
            rawOutput.write(salt);
            rawOutput.write(iv);
            Cipher cipher = initCipher(Cipher.ENCRYPT_MODE, password, salt, iv);
            try (var cipherOutput = new javax.crypto.CipherOutputStream(rawOutput, cipher);
                 InputStream input = Files.newInputStream(inputFile)) {
                input.transferTo(cipherOutput);
            }
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to encrypt shared file", exception);
        }
    }

    private void decryptFile(Path inputFile, Path outputFile, String password) throws IOException {
        try (InputStream rawInput = Files.newInputStream(inputFile);
             OutputStream output = Files.newOutputStream(outputFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] magic = rawInput.readNBytes(ENCRYPTED_MAGIC.length);
            if (!java.util.Arrays.equals(magic, ENCRYPTED_MAGIC)) {
                throw new IOException("Encrypted file header is invalid");
            }
            byte[] salt = rawInput.readNBytes(SALT_LENGTH);
            byte[] iv = rawInput.readNBytes(IV_LENGTH);
            if (salt.length != SALT_LENGTH || iv.length != IV_LENGTH) {
                throw new IOException("Encrypted file header is incomplete");
            }
            Cipher cipher = initCipher(Cipher.DECRYPT_MODE, password, salt, iv);
            try (var cipherInput = new javax.crypto.CipherInputStream(rawInput, cipher)) {
                cipherInput.transferTo(output);
            }
        } catch (GeneralSecurityException exception) {
            throw new IOException("Wrong password or corrupted encrypted file", exception);
        }
    }

    private Cipher initCipher(int mode, String password, byte[] salt, byte[] iv) throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        SecretKey secretKey = factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS));
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getEncoded(), "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher;
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private record SharedFileEntry(String displayName, Path servedPath, String originalPath, boolean encrypted) {
    }
}
