package edu.nyu.cs6103.p2p.model;

public record ChunkRecord(int chunkIndex, long offset, int length) {
    @Override
    public String toString() {
        return "chunk " + chunkIndex + " @ " + offset + " (" + length + " bytes)";
    }
}
