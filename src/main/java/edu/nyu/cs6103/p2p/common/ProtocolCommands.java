package edu.nyu.cs6103.p2p.common;

public final class ProtocolCommands {
    public static final String CHUNK = "CHUNK";
    public static final String DISCONNECT = "DISCONNECT";
    public static final String HEARTBEAT = "HEARTBEAT";
    public static final String HELLO = "HELLO";
    public static final String LIST_RECORDS = "LIST_RECORDS";
    public static final String MANIFEST = "MANIFEST";
    public static final String PING = "PING";
    public static final String PONG = "PONG";
    public static final String REGISTER = "REGISTER";
    public static final String SEARCH = "SEARCH";
    public static final String UNREGISTER_PEER = "UNREGISTER_PEER";

    private ProtocolCommands() {
    }
}
