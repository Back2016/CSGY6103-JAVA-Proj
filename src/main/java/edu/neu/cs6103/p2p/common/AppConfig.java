package edu.neu.cs6103.p2p.common;

public final class AppConfig {
    public static final int DEFAULT_TRACKER_PORT = 5050;
    public static final int DEFAULT_PEER_PORT = 6060;
    public static final int DEFAULT_CHUNK_SIZE = 256 * 1024;
    public static final int DOWNLOAD_THREADS = 6;

    private AppConfig() {
    }
}
