package edu.nyu.cs6103.p2p.common;

public final class AppConfig {
    public static final int DEFAULT_TRACKER_PORT = 5050;
    public static final int DEFAULT_PEER_PORT = 6060;
    public static final int DEFAULT_CHUNK_SIZE = 256 * 1024;
    public static final int DOWNLOAD_THREADS = 6;
    public static final int HEARTBEAT_INTERVAL_MS = 1_000;
    public static final int PEER_SESSION_TTL_MS = 3_000;

    private AppConfig() {
    }
}
