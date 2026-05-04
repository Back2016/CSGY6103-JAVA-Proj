package edu.nyu.cs6103.p2p.model;

import java.util.List;

public record TrackerRecord(String filename,
                            long size,
                            String originalPath,
                            int chunkSize,
                            int chunkCount,
                            boolean encrypted,
                            String updatedAt,
                            List<PeerInfo> peers,
                            List<ChunkRecord> chunkRecords) {
    @Override
    public String toString() {
        return filename + " | " + size + " bytes | " + chunkCount + " chunks | " +
                peers.size() + " peer(s)" + (encrypted ? " | encrypted" : "") +
                (originalPath == null || originalPath.isBlank() ? "" : " | " + originalPath);
    }
}
