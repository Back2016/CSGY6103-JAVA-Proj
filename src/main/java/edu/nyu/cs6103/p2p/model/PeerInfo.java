package edu.nyu.cs6103.p2p.model;

public record PeerInfo(String peerId, String host, int port) {
    @Override
    public String toString() {
        return peerId + "@" + host + ":" + port;
    }
}
