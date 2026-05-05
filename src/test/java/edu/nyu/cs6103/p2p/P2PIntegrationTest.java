package edu.nyu.cs6103.p2p;

import edu.nyu.cs6103.p2p.common.AppConfig;
import edu.nyu.cs6103.p2p.common.HashingUtils;
import edu.nyu.cs6103.p2p.common.ProtocolCommands;
import edu.nyu.cs6103.p2p.db.TrackerDatabase;
import edu.nyu.cs6103.p2p.model.PeerInfo;
import edu.nyu.cs6103.p2p.model.SearchResult;
import edu.nyu.cs6103.p2p.model.SharedFileDescriptor;
import edu.nyu.cs6103.p2p.peer.PeerNode;
import edu.nyu.cs6103.p2p.tracker.TrackerServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P2PIntegrationTest {
    @TempDir
    Path tempDir;
    private final List<TrackerServer> trackers = new ArrayList<>();

    @AfterEach
    void stopTrackers() {
        for (TrackerServer tracker : trackers) {
            tracker.stop();
        }
        trackers.clear();
    }

    @Test
    void peerCanRegisterSearchAndDownloadAFile() throws Exception {
        int trackerPort = findFreePort();
        int peerOnePort = findFreePort();
        int peerTwoPort = findFreePort();
        Path trackerDbPath = tempDir.resolve("tracker-test.db");
        TrackerDatabase trackerDatabase = new TrackerDatabase("jdbc:sqlite:" + trackerDbPath.toAbsolutePath());
        startTrackerInBackground(trackerPort, trackerDatabase);
        waitForTrackerStartup(trackerPort);

        PeerNode peerOne = new PeerNode(
                "peer-one",
                "localhost",
                trackerPort,
                peerOnePort,
                tempDir.resolve("peer-one-tracker-records"),
                tempDir.resolve("peer-one-downloads")
        );
        PeerNode peerTwo = new PeerNode(
                "peer-two",
                "localhost",
                trackerPort,
                peerTwoPort,
                tempDir.resolve("peer-two-tracker-records"),
                tempDir.resolve("peer-two-downloads")
        );

        peerOne.startServer();
        peerTwo.startServer();

        Path sharedFile = tempDir.resolve("sample.txt");
        String content = "chunked-p2p-transfer".repeat(50_000);
        Files.writeString(sharedFile, content);

        peerOne.shareFile(sharedFile);

        SearchResult result = waitForSearchResult(peerTwo, "sample.txt");
        assertNotNull(result);
        assertEquals("sample.txt", result.filename());
        assertEquals(HashingUtils.sha256(sharedFile), result.fileId());
        assertFalse(result.peers().isEmpty());

        AtomicReference<String> lastStatus = new AtomicReference<>("pending");
        Path downloaded = peerTwo.download(result, progress -> { }, lastStatus::set);

        assertTrue(Files.exists(downloaded));
        assertEquals(content, Files.readString(downloaded));
        assertEquals("Download complete", lastStatus.get());
        assertFalse(peerTwo.getDownloadHistory().isEmpty());
    }

    @Test
    void sameFilenameDifferentContent_shouldReturnSeparateSearchResults() throws Exception {
        int trackerPort = findFreePort();
        int peerOnePort = findFreePort();
        int peerTwoPort = findFreePort();
        Path trackerDbPath = tempDir.resolve("same-name-test.db");
        TrackerDatabase trackerDatabase = new TrackerDatabase("jdbc:sqlite:" + trackerDbPath.toAbsolutePath());
        startTrackerInBackground(trackerPort, trackerDatabase);
        waitForTrackerStartup(trackerPort);

        PeerNode peerOne = new PeerNode(
                "peer-one",
                "localhost",
                trackerPort,
                peerOnePort,
                tempDir.resolve("same-name-peer-one-records"),
                tempDir.resolve("same-name-peer-one-downloads")
        );
        PeerNode peerTwo = new PeerNode(
                "peer-two",
                "localhost",
                trackerPort,
                peerTwoPort,
                tempDir.resolve("same-name-peer-two-records"),
                tempDir.resolve("same-name-peer-two-downloads")
        );

        peerOne.startServer();
        peerTwo.startServer();

        Path peerOneDir = tempDir.resolve("same-name-peer-one");
        Path peerTwoDir = tempDir.resolve("same-name-peer-two");
        Files.createDirectories(peerOneDir);
        Files.createDirectories(peerTwoDir);
        Path peerOneFile = peerOneDir.resolve("shared.txt");
        Path peerTwoFile = peerTwoDir.resolve("shared.txt");
        Files.writeString(peerOneFile, "peer-one-content".repeat(10_000));
        Files.writeString(peerTwoFile, "peer-two-content".repeat(10_000));

        peerOne.shareFile(peerOneFile);
        peerTwo.shareFile(peerTwoFile);

        List<SearchResult> results = waitForSearchResults(peerOne, "shared.txt", 2);
        assertEquals(2, results.size());
        assertNotEquals(results.get(0).fileId(), results.get(1).fileId());
        assertEquals(1, results.get(0).peers().size());
        assertEquals(1, results.get(1).peers().size());
        assertNotEquals(results.get(0).peers().get(0).peerId(), results.get(1).peers().get(0).peerId());
    }

    @Test
    void downloadRetriesAnotherPeerWhenOneEndpointIsUnavailable() throws Exception {
        int downloaderPort = findFreePort();
        int healthyPeerPort = findFreePort();
        int deadPeerPort = findFreePort();

        PeerNode downloader = new PeerNode(
                "downloader",
                "localhost",
                findFreePort(),
                "test-session-retry",
                downloaderPort,
                "127.0.0.1",
                tempDir.resolve("retry-tracker-records"),
                tempDir.resolve("retry-downloads")
        );

        byte[] content = buildBytes(AppConfig.DEFAULT_CHUNK_SIZE * 2 + 1024);
        String fileId = HashingUtils.sha256Hex(content);
        CountingChunkServer healthyPeer = new CountingChunkServer(healthyPeerPort, fileId, content, false);
        healthyPeer.start();

        SearchResult result = new SearchResult(
                "retry.bin",
                fileId,
                content.length,
                AppConfig.DEFAULT_CHUNK_SIZE,
                (int) Math.ceil((double) content.length / AppConfig.DEFAULT_CHUNK_SIZE),
                false,
                List.of(
                        new PeerInfo("dead-peer", "localhost", deadPeerPort),
                        new PeerInfo("healthy-peer", "localhost", healthyPeerPort)
                )
        );

        Path downloaded = downloader.download(result, progress -> { }, status -> { });

        assertEquals(content.length, Files.size(downloaded));
        assertArrayEquals(content, Files.readAllBytes(downloaded));
        assertTrue(healthyPeer.totalRequests() > 0);
    }

    @Test
    void chunksAreDistributedAcrossMultiplePeers() throws Exception {
        int downloaderPort = findFreePort();
        int peerOnePort = findFreePort();
        int peerTwoPort = findFreePort();

        PeerNode downloader = new PeerNode(
                "downloader",
                "localhost",
                findFreePort(),
                "test-session-multi",
                downloaderPort,
                "127.0.0.1",
                tempDir.resolve("multi-tracker-records"),
                tempDir.resolve("multi-downloads")
        );

        byte[] content = buildBytes(AppConfig.DEFAULT_CHUNK_SIZE * 4 + 4096);
        String fileId = HashingUtils.sha256Hex(content);
        CountingChunkServer peerOne = new CountingChunkServer(peerOnePort, fileId, content, false);
        CountingChunkServer peerTwo = new CountingChunkServer(peerTwoPort, fileId, content, false);
        peerOne.start();
        peerTwo.start();

        SearchResult result = new SearchResult(
                "multi.bin",
                fileId,
                content.length,
                AppConfig.DEFAULT_CHUNK_SIZE,
                (int) Math.ceil((double) content.length / AppConfig.DEFAULT_CHUNK_SIZE),
                false,
                List.of(
                        new PeerInfo("peer-a", "localhost", peerOnePort),
                        new PeerInfo("peer-b", "localhost", peerTwoPort)
                )
        );

        Path downloaded = downloader.download(result, progress -> { }, status -> { });

        assertEquals(content.length, Files.size(downloaded));
        assertArrayEquals(content, Files.readAllBytes(downloaded));
        assertFalse(peerOne.servedChunks().isEmpty());
        assertFalse(peerTwo.servedChunks().isEmpty());
    }

    @Test
    void trackerStartupClearsPreviouslyRegisteredFiles() throws Exception {
        Path trackerDbPath = tempDir.resolve("tracker-restart.db");
        TrackerDatabase staleDatabase = new TrackerDatabase("jdbc:sqlite:" + trackerDbPath.toAbsolutePath());
        String staleSessionToken = staleDatabase.openPeerSession("old-peer", "localhost", 6060);
        staleDatabase.registerSharedFile(
                new SharedFileDescriptor("stale-file-id", "stale.txt", 128, AppConfig.DEFAULT_CHUNK_SIZE, 1, false),
                tempDir.resolve("stale.txt").toString(),
                staleSessionToken);
        assertEquals(1, staleDatabase.searchFiles("stale").size());

        int trackerPort = findFreePort();
        TrackerDatabase restartedDatabase = new TrackerDatabase("jdbc:sqlite:" + trackerDbPath.toAbsolutePath());
        startTrackerInBackground(trackerPort, restartedDatabase);
        waitForTrackerStartup(trackerPort);

        PeerNode peer = new PeerNode(
                "searcher",
                "localhost",
                trackerPort,
                "test-session-restart",
                findFreePort(),
                "127.0.0.1",
                tempDir.resolve("restart-tracker-records"),
                tempDir.resolve("restart-downloads")
        );

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                assertTrue(peer.search("stale").isEmpty());
                return;
            } catch (IOException ignored) {
                Thread.sleep(50);
            }
        }
        assertTrue(peer.search("stale").isEmpty());
    }

    @Test
    void hashMismatchFromPeerContent_shouldFailDownload() throws Exception {
        int downloaderPort = findFreePort();
        int badPeerPort = findFreePort();
        int goodPeerPort = findFreePort();

        PeerNode downloader = new PeerNode(
                "downloader",
                "localhost",
                findFreePort(),
                "test-session-hash-mismatch",
                downloaderPort,
                "127.0.0.1",
                tempDir.resolve("hash-mismatch-records"),
                tempDir.resolve("hash-mismatch-downloads")
        );

        byte[] expectedContent = buildBytes(AppConfig.DEFAULT_CHUNK_SIZE * 2);
        byte[] corruptedContent = buildCorruptedBytes(expectedContent);
        String expectedFileId = HashingUtils.sha256Hex(expectedContent);

        CountingChunkServer badPeer = new CountingChunkServer(badPeerPort, expectedFileId, corruptedContent, false);
        CountingChunkServer goodPeer = new CountingChunkServer(goodPeerPort, expectedFileId, expectedContent, false);
        badPeer.start();
        goodPeer.start();

        SearchResult result = new SearchResult(
                "corrupt.bin",
                expectedFileId,
                expectedContent.length,
                AppConfig.DEFAULT_CHUNK_SIZE,
                (int) Math.ceil((double) expectedContent.length / AppConfig.DEFAULT_CHUNK_SIZE),
                false,
                List.of(
                        new PeerInfo("peer-a", "localhost", badPeerPort),
                        new PeerInfo("peer-b", "localhost", goodPeerPort)
                )
        );

        IOException exception = assertThrows(IOException.class, () -> downloader.download(result, progress -> { }, status -> { }));
        assertTrue(exception.getMessage().contains("fileId"));
    }

    @Test
    void registerWithoutValidSessionToken_shouldBeRejected() throws Exception {
        int trackerPort = findFreePort();
        Path trackerDbPath = tempDir.resolve("invalid-register.db");
        TrackerDatabase trackerDatabase = new TrackerDatabase("jdbc:sqlite:" + trackerDbPath.toAbsolutePath());
        startTrackerInBackground(trackerPort, trackerDatabase);
        waitForTrackerStartup(trackerPort);

        TrackerResponse response = sendRegisterRequest(
                trackerPort,
                "invalid-session-token",
                new SharedFileDescriptor("invalid-file-id", "invalid.txt", 64, AppConfig.DEFAULT_CHUNK_SIZE, 1, false),
                tempDir.resolve("invalid.txt").toString()
        );

        assertFalse(response.success());
        assertTrue(response.message().contains("session"));
    }

    @Test
    void disconnectWithoutValidSessionToken_shouldBeRejected() throws Exception {
        int trackerPort = findFreePort();
        Path trackerDbPath = tempDir.resolve("invalid-disconnect.db");
        TrackerDatabase trackerDatabase = new TrackerDatabase("jdbc:sqlite:" + trackerDbPath.toAbsolutePath());
        startTrackerInBackground(trackerPort, trackerDatabase);
        waitForTrackerStartup(trackerPort);

        TrackerResponse response = sendDisconnectRequest(trackerPort, "invalid-session-token");

        assertFalse(response.success());
        assertTrue(response.message().contains("session"));
    }

    @Test
    void expiredPeerSession_shouldDisappearFromSearchResults() throws Exception {
        int trackerPort = findFreePort();
        Path trackerDbPath = tempDir.resolve("expired-session.db");
        TrackerDatabase trackerDatabase = new TrackerDatabase("jdbc:sqlite:" + trackerDbPath.toAbsolutePath());
        startTrackerInBackground(trackerPort, trackerDatabase);
        waitForTrackerStartup(trackerPort);

        String sessionToken = openPeerSession(trackerPort, "peer-expired", "127.0.0.1", 6060);
        byte[] content = buildBytes(AppConfig.DEFAULT_CHUNK_SIZE + 512);
        SharedFileDescriptor descriptor = new SharedFileDescriptor(
                HashingUtils.sha256Hex(content),
                "expiring.txt",
                content.length,
                AppConfig.DEFAULT_CHUNK_SIZE,
                (int) Math.ceil((double) content.length / AppConfig.DEFAULT_CHUNK_SIZE),
                false
        );
        TrackerResponse registerResponse = sendRegisterRequest(trackerPort, sessionToken, descriptor, tempDir.resolve("expiring.txt").toString());
        assertTrue(registerResponse.success());

        PeerNode searcher = new PeerNode(
                "searcher",
                "localhost",
                trackerPort,
                "test-expired-search",
                findFreePort(),
                "127.0.0.1",
                tempDir.resolve("expired-search-records"),
                tempDir.resolve("expired-search-downloads")
        );

        List<SearchResult> initialResults = waitForSearchResults(searcher, "expiring.txt", 1);
        assertEquals(1, initialResults.size());

        Thread.sleep(AppConfig.PEER_SESSION_TTL_MS + 750L);

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (searcher.search("expiring.txt").isEmpty()) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(searcher.search("expiring.txt").isEmpty());
    }

    private void startTrackerInBackground(int trackerPort, TrackerDatabase trackerDatabase) {
        TrackerServer trackerServer = new TrackerServer(trackerPort, trackerDatabase);
        trackers.add(trackerServer);
        Thread thread = new Thread(() -> {
            try {
                trackerServer.start();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }, "tracker-test-server");
        thread.setDaemon(true);
        thread.start();
    }

    private static SearchResult waitForSearchResult(PeerNode peerNode, String query) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<SearchResult> results = peerNode.search(query);
            if (!results.isEmpty()) {
                return results.get(0);
            }
            Thread.sleep(100);
        }
        return null;
    }

    private static List<SearchResult> waitForSearchResults(PeerNode peerNode, String query, int expectedCount) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<SearchResult> results = peerNode.search(query);
            if (results.size() >= expectedCount) {
                return results;
            }
            Thread.sleep(100);
        }
        return peerNode.search(query);
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void waitForTrackerStartup(int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket("localhost", port);
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                output.writeUTF(ProtocolCommands.PING);
                output.flush();
                if (input.readBoolean() && ProtocolCommands.PONG.equals(input.readUTF())) {
                    input.readUTF();
                    return;
                }
            } catch (IOException ignored) {
                Thread.sleep(50);
            }
        }
        throw new IOException("Tracker did not start listening on port " + port);
    }

    private static String openPeerSession(int trackerPort, String peerId, String host, int port) throws IOException {
        try (Socket socket = new Socket("localhost", trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.HELLO);
            output.writeUTF(peerId);
            output.writeUTF(host);
            output.writeInt(port);
            output.flush();

            if (!input.readBoolean()) {
                throw new IOException(input.readUTF());
            }
            return input.readUTF();
        }
    }

    private static TrackerResponse sendRegisterRequest(int trackerPort,
                                                       String sessionToken,
                                                       SharedFileDescriptor descriptor,
                                                       String originalPath) throws IOException {
        try (Socket socket = new Socket("localhost", trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.REGISTER);
            output.writeUTF(sessionToken);
            output.writeUTF(descriptor.fileId());
            output.writeUTF(descriptor.filename());
            output.writeLong(descriptor.size());
            output.writeInt(descriptor.chunkSize());
            output.writeInt(descriptor.chunkCount());
            output.writeUTF(originalPath);
            output.writeBoolean(descriptor.encrypted());
            output.flush();

            return new TrackerResponse(input.readBoolean(), input.readUTF());
        }
    }

    private static TrackerResponse sendDisconnectRequest(int trackerPort, String sessionToken) throws IOException {
        try (Socket socket = new Socket("localhost", trackerPort);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.DISCONNECT);
            output.writeUTF(sessionToken);
            output.flush();

            return new TrackerResponse(input.readBoolean(), input.readUTF());
        }
    }

    private static byte[] buildBytes(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) ('A' + (index % 26));
        }
        return bytes;
    }

    private static byte[] buildCorruptedBytes(byte[] source) {
        byte[] corrupted = source.clone();
        for (int index = 0; index < corrupted.length; index += 2) {
            corrupted[index] = (byte) (corrupted[index] ^ 0x0F);
        }
        return corrupted;
    }

    private static final class CountingChunkServer {
        private final int port;
        private final String fileId;
        private final byte[] content;
        private final boolean failAllRequests;
        private final Map<Integer, Integer> servedChunks = new ConcurrentHashMap<>();

        private CountingChunkServer(int port, String fileId, byte[] content, boolean failAllRequests) {
            this.port = port;
            this.fileId = fileId;
            this.content = content;
            this.failAllRequests = failAllRequests;
        }

        private void start() {
            Thread thread = new Thread(() -> {
                try (ServerSocket serverSocket = new ServerSocket(port)) {
                    while (true) {
                        Socket socket = serverSocket.accept();
                        handle(socket);
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }, "counting-peer-" + port);
            thread.setDaemon(true);
            thread.start();
        }

        private void handle(Socket socket) {
            try (socket;
                 DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
                String command = input.readUTF();
                if (!ProtocolCommands.CHUNK.equals(command)) {
                    output.writeBoolean(false);
                    output.writeUTF("Unsupported command");
                    output.flush();
                    return;
                }

                String requestedFileId = input.readUTF();
                int chunkIndex = input.readInt();
                if (failAllRequests || !fileId.equals(requestedFileId)) {
                    output.writeBoolean(false);
                    output.writeUTF("Chunk unavailable");
                    output.flush();
                    return;
                }

                int offset = chunkIndex * AppConfig.DEFAULT_CHUNK_SIZE;
                int length = Math.min(AppConfig.DEFAULT_CHUNK_SIZE, content.length - offset);
                if (length <= 0) {
                    output.writeBoolean(false);
                    output.writeUTF("Chunk index out of range");
                    output.flush();
                    return;
                }

                servedChunks.merge(chunkIndex, 1, Integer::sum);
                output.writeBoolean(true);
                output.writeInt(chunkIndex);
                output.writeInt(length);
                output.write(content, offset, length);
                output.flush();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private int totalRequests() {
            return servedChunks.values().stream().mapToInt(Integer::intValue).sum();
        }

        private List<Integer> servedChunks() {
            return new ArrayList<>(servedChunks.keySet());
        }
    }

    private record TrackerResponse(boolean success, String message) {
    }
}
