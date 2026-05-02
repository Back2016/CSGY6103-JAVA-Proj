package edu.neu.cs6103.p2p;

import edu.neu.cs6103.p2p.common.AppConfig;
import edu.neu.cs6103.p2p.db.TrackerDatabase;
import edu.neu.cs6103.p2p.model.PeerInfo;
import edu.neu.cs6103.p2p.model.SearchResult;
import edu.neu.cs6103.p2p.peer.PeerNode;
import edu.neu.cs6103.p2p.tracker.TrackerServer;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P2PIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void peerCanRegisterSearchAndDownloadAFile() throws Exception {
        int trackerPort = findFreePort();
        int peerOnePort = findFreePort();
        int peerTwoPort = findFreePort();
        Path trackerDbPath = tempDir.resolve("tracker-test.db");
        TrackerDatabase trackerDatabase = new TrackerDatabase("jdbc:sqlite:" + trackerDbPath.toAbsolutePath());
        startTrackerInBackground(trackerPort, trackerDatabase);

        PeerNode peerOne = new PeerNode(
                "peer-one",
                "localhost",
                trackerPort,
                peerOnePort,
                tempDir.resolve("peer-one-shared"),
                tempDir.resolve("peer-one-downloads")
        );
        PeerNode peerTwo = new PeerNode(
                "peer-two",
                "localhost",
                trackerPort,
                peerTwoPort,
                tempDir.resolve("peer-two-shared"),
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
        assertFalse(result.peers().isEmpty());

        AtomicReference<String> lastStatus = new AtomicReference<>("pending");
        Path downloaded = peerTwo.download(result, progress -> { }, lastStatus::set);

        assertTrue(Files.exists(downloaded));
        assertEquals(content, Files.readString(downloaded));
        assertEquals("Download complete", lastStatus.get());
        assertFalse(peerTwo.getDownloadHistory().isEmpty());
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
                downloaderPort,
                tempDir.resolve("retry-shared"),
                tempDir.resolve("retry-downloads")
        );

        byte[] content = buildBytes(AppConfig.DEFAULT_CHUNK_SIZE * 2 + 1024);
        CountingChunkServer healthyPeer = new CountingChunkServer(healthyPeerPort, "retry.bin", content, false);
        healthyPeer.start();

        SearchResult result = new SearchResult(
                "retry.bin",
                content.length,
                AppConfig.DEFAULT_CHUNK_SIZE,
                (int) Math.ceil((double) content.length / AppConfig.DEFAULT_CHUNK_SIZE),
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
                downloaderPort,
                tempDir.resolve("multi-shared"),
                tempDir.resolve("multi-downloads")
        );

        byte[] content = buildBytes(AppConfig.DEFAULT_CHUNK_SIZE * 4 + 4096);
        CountingChunkServer peerOne = new CountingChunkServer(peerOnePort, "multi.bin", content, false);
        CountingChunkServer peerTwo = new CountingChunkServer(peerTwoPort, "multi.bin", content, false);
        peerOne.start();
        peerTwo.start();

        SearchResult result = new SearchResult(
                "multi.bin",
                content.length,
                AppConfig.DEFAULT_CHUNK_SIZE,
                (int) Math.ceil((double) content.length / AppConfig.DEFAULT_CHUNK_SIZE),
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

    private static void startTrackerInBackground(int trackerPort, TrackerDatabase trackerDatabase) {
        Thread thread = new Thread(() -> {
            try {
                new TrackerServer(trackerPort, trackerDatabase).start();
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

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] buildBytes(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) ('A' + (index % 26));
        }
        return bytes;
    }

    private static final class CountingChunkServer {
        private final int port;
        private final String filename;
        private final byte[] content;
        private final boolean failAllRequests;
        private final Map<Integer, Integer> servedChunks = new ConcurrentHashMap<>();

        private CountingChunkServer(int port, String filename, byte[] content, boolean failAllRequests) {
            this.port = port;
            this.filename = filename;
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
                if (!"CHUNK".equals(command)) {
                    output.writeBoolean(false);
                    output.writeUTF("Unsupported command");
                    output.flush();
                    return;
                }

                String requestedFilename = input.readUTF();
                int chunkIndex = input.readInt();
                if (failAllRequests || !filename.equals(requestedFilename)) {
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
}
