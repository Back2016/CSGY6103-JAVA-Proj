package edu.nyu.cs6103.p2p.model;

public record SharedFileDescriptor(String fileId,
                                   String contentHash,
                                   String filename,
                                   long size,
                                   int chunkSize,
                                   int chunkCount,
                                   boolean encrypted) {
}
