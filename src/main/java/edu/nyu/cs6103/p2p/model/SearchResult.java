package edu.nyu.cs6103.p2p.model;

import java.util.List;

public record SearchResult(String filename,
                           String fileId,
                           String contentHash,
                           long size,
                           int chunkSize,
                           int chunkCount,
                           boolean encrypted,
                           List<PeerInfo> peers) {
    @Override
    public String toString() {
        return filename + " [" + fileId + "] (" + size + " bytes, " + peers.size() + " peer(s), " + chunkCount + " chunks" +
                (encrypted ? ", encrypted" : "") + ")";
    }
}
