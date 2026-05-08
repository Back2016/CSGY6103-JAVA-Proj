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
import edu.nyu.cs6103.p2p.tracker.TrackerClient;

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
    private final TrackerClient trackerClient;
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
        this.peerPort = peerPort;
        this.advertisedHost = advertisedHost;
        this.trackerRecordsDirectory = trackerRecordsDirectory;
        this.downloadsDirectory = downloadsDirectory;
        this.trackerClient = new TrackerClient(trackerHost, trackerPort);
        this.trackerSessionId = trackerSessionId;
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
        Path servedPath = filePath;
        boolean encrypted = false;
        Path temporaryEncryptedCopy = null;
        try {
            if (!normalizedPassword.isEmpty()) {
                servedPath = buildEncryptedCopy(filePath, normalizedPassword);
                temporaryEncryptedCopy = servedPath;
                encrypted = true;
            }

            SharedFileDescriptor descriptor = buildDescriptor(filePath.getFileName().toString(), servedPath, encrypted);
            SharedFileEntry entry = new SharedFileEntry(descriptor, servedPath);
            registerFileWithTracker(entry);
            sharedFiles.put(descriptor.fileId(), entry);
        } catch (IOException exception) {
            if (temporaryEncryptedCopy != null) {
                Files.deleteIfExists(temporaryEncryptedCopy);
            }
            throw exception;
        }
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
        return trackerClient.search(query);
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

        try {
            if (result.chunkCount() == 0) {
                Files.write(workingTarget, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } else {
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
                        throw new IOException("Download failed: " + exception.getCause().getMessage(), exception.getCause());
                    } finally {
                        executor.shutdownNow();
                    }
                }
            }

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
            cleanupDownloadArtifacts(workingTarget, destination);
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
        List<TrackerRecord> records = trackerClient.listRecords();
        writeTrackerRecordsCsv(records);
        return records;
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
        trackerClient.disconnect(sessionToken);
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
        trackerClient.register(sessionToken, entry.descriptor());
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
        String fileName = originalFile.getFileName().toString();
        String tempPrefix = sanitizeForPath(fileName);
        if (tempPrefix.length() < 3) {
            tempPrefix = (tempPrefix + "enc").substring(0, 3);
        }
        Path encryptedTarget = Files.createTempFile(encryptedFilesDirectory, tempPrefix + "-", ".p2penc");
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
                return fetchChunk(peer, result, chunkIndex);
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

    private byte[] fetchChunk(PeerInfo peer, SearchResult result, int chunkIndex) throws IOException {
        try (Socket socket = new Socket()) {
            SocketAddress socketAddress = new InetSocketAddress(peer.host(), peer.port());
            socket.connect(socketAddress, SOCKET_CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);

            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                output.writeUTF(ProtocolCommands.CHUNK);
                output.writeUTF(result.fileId());
                output.writeInt(chunkIndex);
                output.flush();

                boolean success = input.readBoolean();
                if (!success) {
                    throw new IOException(input.readUTF());
                }

                int returnedChunkIndex = input.readInt();
                int length = input.readInt();
                if (returnedChunkIndex != chunkIndex) {
                    throw new IOException("Chunk mismatch for " + result.filename() + ": expected " + chunkIndex + " but received " + returnedChunkIndex);
                }
                int expectedChunkLength = expectedChunkLength(result, chunkIndex);
                if (expectedChunkLength <= 0) {
                    throw new IOException("Chunk index out of range for " + result.filename() + ": " + chunkIndex);
                }
                if (length <= 0 || length > expectedChunkLength) {
                    throw new IOException("Invalid chunk length for " + result.filename() + ": expected 1.." +
                            expectedChunkLength + " but received " + length);
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
        return new TrackerClient(host, port).ping();
    }

    private static int expectedChunkLength(SearchResult result, int chunkIndex) {
        long chunkOffset = (long) chunkIndex * result.chunkSize();
        long remaining = result.size() - chunkOffset;
        return (int) Math.min(result.chunkSize(), Math.max(0L, remaining));
    }

    private static void cleanupDownloadArtifacts(Path workingTarget, Path destination) {
        try {
            Files.deleteIfExists(workingTarget);
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(destination);
        } catch (IOException ignored) {
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
        return trackerClient.hello(peerId, advertisedHost, peerPort);
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
        trackerClient.heartbeat(sessionToken);
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
