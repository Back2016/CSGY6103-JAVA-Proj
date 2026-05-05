package edu.nyu.cs6103.p2p.peer;

import edu.nyu.cs6103.p2p.common.AppConfig;
import edu.nyu.cs6103.p2p.common.CsvUtils;
import edu.nyu.cs6103.p2p.common.HashingUtils;
import edu.nyu.cs6103.p2p.common.ProtocolCommands;
import edu.nyu.cs6103.p2p.common.ThrowableUtils;
import edu.nyu.cs6103.p2p.db.ClientDatabase;
import edu.nyu.cs6103.p2p.model.DownloadHistoryEntry;
import edu.nyu.cs6103.p2p.model.PeerInfo;
import edu.nyu.cs6103.p2p.model.SearchResult;
import edu.nyu.cs6103.p2p.model.SharedFileDescriptor;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private volatile String peerSessionToken;
    private volatile ScheduledExecutorService heartbeatExecutor;

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

    public synchronized void startServer() throws IOException {
        peerServer.start();
        try {
            peerSessionToken = openPeerSession();
            startHeartbeatLoop();
        } catch (IOException exception) {
            stopServer();
            throw exception;
        }
    }

    public synchronized void stopServer() {
        stopHeartbeatLoop();
        peerSessionToken = null;
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
        Path servedPath;
        boolean encrypted;
        if (normalizedPassword.isEmpty()) {
            servedPath = filePath;
            encrypted = false;
        } else {
            servedPath = buildEncryptedCopy(filePath, normalizedPassword);
            encrypted = true;
        }

        SharedFileDescriptor descriptor = buildDescriptor(filePath.getFileName().toString(), servedPath, encrypted);
        SharedFileEntry entry = new SharedFileEntry(descriptor, servedPath);
        sharedFiles.put(descriptor.fileId(), entry);
        registerFileWithTracker(entry);
    }

    public List<String> getSharedFileNames() {
        return sharedFiles.values().stream()
                .map(entry -> entry.descriptor().filename())
                .sorted()
                .toList();
    }

    public Path getSharedFile(String fileId) {
        SharedFileEntry entry = sharedFiles.get(fileId);
        return entry == null ? null : entry.servedPath();
    }

    public List<SearchResult> search(String query) throws IOException {
        try (Socket socket = new Socket(trackerHost, trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.SEARCH);
            output.writeUTF(query);
            output.flush();

            boolean success = input.readBoolean();
            if (!success) {
                throw new IOException(input.readUTF());
            }

            int resultCount = input.readInt();
            List<SearchResult> results = new ArrayList<>();
            for (int i = 0; i < resultCount; i++) {
                String fileId = input.readUTF();
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
                results.add(new SearchResult(filename, fileId, size, chunkSize, chunkCount, encrypted, peers));
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

        try {
            verifyDownloadedFileId(result, workingTarget);

            if (result.encrypted()) {
                if (normalizedPassword.isBlank()) {
                    Files.deleteIfExists(workingTarget);
                    throw new IOException("Download failed: this file is encrypted and requires a password");
                }

                try {
                    decryptFile(workingTarget, destination, normalizedPassword);
                    Files.deleteIfExists(workingTarget);
                } catch (IOException exception) {
                    Files.deleteIfExists(workingTarget);
                    Files.deleteIfExists(destination);
                    throw new IOException("Download failed: unable to decrypt file with the provided password", exception);
                }
            }
        } catch (IOException exception) {
            Files.deleteIfExists(workingTarget);
            Files.deleteIfExists(destination);
            clientDatabase.recordDownload(result.filename(), peerSummary, destination.toAbsolutePath().toString(), "FAILED");
            throw exception;
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
            output.writeUTF(ProtocolCommands.LIST_RECORDS);
            output.flush();

            boolean success = input.readBoolean();
            if (!success) {
                throw new IOException(input.readUTF());
            }

            int recordCount = input.readInt();
            List<TrackerRecord> records = new ArrayList<>();
            for (int index = 0; index < recordCount; index++) {
                String fileId = input.readUTF();
                String filename = input.readUTF();
                long size = input.readLong();
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
                records.add(new TrackerRecord(filename, fileId, size, chunkSize, chunkCount, encrypted, updatedAt, peers, chunkRecords));
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
        String sessionToken = peerSessionToken;
        if (sessionToken == null) {
            clearSharedFiles();
            return;
        }
        try (Socket socket = new Socket(trackerHost, trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.DISCONNECT);
            output.writeUTF(sessionToken);
            output.flush();

            boolean success = input.readBoolean();
            if (!success) {
                throw new IOException(input.readUTF());
            }
            input.readUTF();
        }
        peerSessionToken = null;
        clearSharedFiles();
    }

    public void clearSharedFiles() {
        for (SharedFileEntry entry : sharedFiles.values()) {
            if (entry.descriptor().encrypted()) {
                try {
                    Files.deleteIfExists(entry.servedPath());
                } catch (IOException ignored) {
                }
            }
        }
        sharedFiles.clear();
    }

    private void registerFileWithTracker(SharedFileEntry entry) throws IOException {
        String sessionToken = peerSessionToken;
        if (sessionToken == null) {
            throw new IOException("Peer session is not established");
        }
        try (Socket socket = new Socket(trackerHost, trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.REGISTER);
            output.writeUTF(sessionToken);
            output.writeUTF(entry.descriptor().fileId());
            output.writeUTF(entry.descriptor().filename());
            output.writeLong(entry.descriptor().size());
            output.writeInt(entry.descriptor().chunkSize());
            output.writeInt(entry.descriptor().chunkCount());
            output.writeBoolean(entry.descriptor().encrypted());
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
                return fetchChunk(peer, result.fileId(), result.filename(), chunkIndex);
            } catch (IOException exception) {
                lastException = exception;
                if (attempt == orderedPeers.size() - 1) {
                    throw new IllegalStateException("Unable to fetch chunk " + chunkIndex + " from peers " +
                            orderedPeers.stream().map(PeerInfo::toString).reduce((a, b) -> a + ", " + b).orElse("none") +
                            ". Last error: " + ThrowableUtils.rootCauseMessage(exception), exception);
                }
            }
        }
        throw new IllegalStateException("No peers available for chunk " + chunkIndex +
                (lastException == null ? "" : ". Last error: " + ThrowableUtils.rootCauseMessage(lastException)));
    }

    private byte[] fetchChunk(PeerInfo peer, String fileId, String filename, int chunkIndex) throws IOException {
        try (Socket socket = new Socket()) {
            SocketAddress socketAddress = new InetSocketAddress(peer.host(), peer.port());
            socket.connect(socketAddress, SOCKET_CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);

            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                output.writeUTF(ProtocolCommands.CHUNK);
                output.writeUTF(fileId);
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
        lines.add("file_id,filename,size,chunk_size,chunk_count,encrypted,updated_at,peers,chunk_records");
        for (TrackerRecord record : records) {
            lines.add(String.join(",",
                    CsvUtils.quote(record.fileId()),
                    CsvUtils.quote(record.filename()),
                    CsvUtils.quote(String.valueOf(record.size())),
                    CsvUtils.quote(String.valueOf(record.chunkSize())),
                    CsvUtils.quote(String.valueOf(record.chunkCount())),
                    CsvUtils.quote(String.valueOf(record.encrypted())),
                    CsvUtils.quote(record.updatedAt()),
                    CsvUtils.quote(record.peers().stream().map(PeerInfo::toString).reduce((a, b) -> a + " | " + b).orElse("")),
                    CsvUtils.quote(record.chunkRecords().stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""))
            ));
        }
        Files.write(csvPath, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static String resolveTrackerSessionId(String host, int port) throws IOException {
        try (Socket socket = new Socket(host, port);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.PING);
            output.flush();
            boolean success = input.readBoolean();
            if (!success || !ProtocolCommands.PONG.equals(input.readUTF())) {
                throw new IOException("Tracker did not return a healthy session handshake");
            }
            return input.readUTF();
        }
    }

    private static String sanitizeForPath(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private SharedFileDescriptor buildDescriptor(String filename, Path servedPath, boolean encrypted) throws IOException {
        long size = Files.size(servedPath);
        int chunkCount = (int) Math.ceil((double) size / AppConfig.DEFAULT_CHUNK_SIZE);
        return new SharedFileDescriptor(
                HashingUtils.sha256(servedPath),
                filename,
                size,
                AppConfig.DEFAULT_CHUNK_SIZE,
                chunkCount,
                encrypted
        );
    }

    private void verifyDownloadedFileId(SearchResult result, Path downloadedFile) throws IOException {
        String actualFileId = HashingUtils.sha256(downloadedFile);
        if (!result.fileId().equals(actualFileId)) {
            Files.deleteIfExists(downloadedFile);
            throw new IOException("Download failed: file content did not match expected fileId for " + result.filename());
        }
    }

    private String openPeerSession() throws IOException {
        try (Socket socket = new Socket(trackerHost, trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.HELLO);
            output.writeUTF(peerId);
            output.writeUTF(advertisedHost);
            output.writeInt(peerPort);
            output.flush();

            boolean success = input.readBoolean();
            if (!success) {
                throw new IOException(input.readUTF());
            }
            return input.readUTF();
        }
    }

    private void startHeartbeatLoop() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "peer-heartbeat-" + peerPort);
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                sendHeartbeat();
            } catch (IOException ignored) {
            }
        }, AppConfig.HEARTBEAT_INTERVAL_MS, AppConfig.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        heartbeatExecutor = executor;
    }

    private void stopHeartbeatLoop() {
        ScheduledExecutorService executor = heartbeatExecutor;
        if (executor != null) {
            executor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    private void sendHeartbeat() throws IOException {
        String sessionToken = peerSessionToken;
        if (sessionToken == null) {
            return;
        }
        try (Socket socket = new Socket(trackerHost, trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.HEARTBEAT);
            output.writeUTF(sessionToken);
            output.flush();

            boolean success = input.readBoolean();
            if (!success) {
                throw new IOException(input.readUTF());
            }
            input.readUTF();
        }
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

    private record SharedFileEntry(SharedFileDescriptor descriptor, Path servedPath) {
    }
}
