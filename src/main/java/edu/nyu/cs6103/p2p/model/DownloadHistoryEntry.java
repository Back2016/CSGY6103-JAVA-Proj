package edu.nyu.cs6103.p2p.model;

public record DownloadHistoryEntry(String filename, String sourcePeers, String destinationPath, String status, String createdAt) {
    @Override
    public String toString() {
        return createdAt + " | " + filename + " | " + status + " | " + destinationPath;
    }
}
