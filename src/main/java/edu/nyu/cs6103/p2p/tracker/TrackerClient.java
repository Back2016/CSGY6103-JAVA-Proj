package edu.nyu.cs6103.p2p.tracker;

import edu.nyu.cs6103.p2p.common.ProtocolCommands;
import edu.nyu.cs6103.p2p.model.ChunkRecord;
import edu.nyu.cs6103.p2p.model.PeerInfo;
import edu.nyu.cs6103.p2p.model.SearchResult;
import edu.nyu.cs6103.p2p.model.SharedFileDescriptor;
import edu.nyu.cs6103.p2p.model.TrackerRecord;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public final class TrackerClient {
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 3000;
    private static final int SOCKET_READ_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;

    public TrackerClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String ping() throws IOException {
        try (Socket socket = openSocket();
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.PING);
            output.flush();

            readSuccess(input);
            String response = input.readUTF();
            if (!ProtocolCommands.PONG.equals(response)) {
                throw new IOException("Unexpected tracker ping response: " + response);
            }
            return input.readUTF();
        }
    }

    public String hello(String peerId, String advertisedHost, int peerPort) throws IOException {
        try (Socket socket = openSocket();
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.HELLO);
            output.writeUTF(peerId);
            output.writeUTF(advertisedHost);
            output.writeInt(peerPort);
            output.flush();
            readSuccess(input);
            return input.readUTF();
        }
    }

    public void heartbeat(String sessionToken) throws IOException {
        requestWithAcknowledgement(ProtocolCommands.HEARTBEAT, output -> output.writeUTF(sessionToken));
    }

    public void register(String sessionToken, SharedFileDescriptor descriptor) throws IOException {
        requestWithAcknowledgement(ProtocolCommands.REGISTER, output -> {
            output.writeUTF(sessionToken);
            output.writeUTF(descriptor.fileId());
            output.writeUTF(descriptor.filename());
            output.writeLong(descriptor.size());
            output.writeInt(descriptor.chunkSize());
            output.writeInt(descriptor.chunkCount());
            output.writeBoolean(descriptor.encrypted());
        });
    }

    public List<SearchResult> search(String query) throws IOException {
        try (Socket socket = openSocket();
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.SEARCH);
            output.writeUTF(query);
            output.flush();

            readSuccess(input);
            int resultCount = input.readInt();
            List<SearchResult> results = new ArrayList<>();
            for (int resultIndex = 0; resultIndex < resultCount; resultIndex++) {
                String fileId = input.readUTF();
                String filename = input.readUTF();
                long size = input.readLong();
                int chunkSize = input.readInt();
                int chunkCount = input.readInt();
                boolean encrypted = input.readBoolean();
                int peerCount = input.readInt();
                List<PeerInfo> peers = new ArrayList<>();
                for (int peerIndex = 0; peerIndex < peerCount; peerIndex++) {
                    peers.add(new PeerInfo(input.readUTF(), input.readUTF(), input.readInt()));
                }
                results.add(new SearchResult(filename, fileId, size, chunkSize, chunkCount, encrypted, peers));
            }
            return results;
        }
    }

    public List<TrackerRecord> listRecords() throws IOException {
        try (Socket socket = openSocket();
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.LIST_RECORDS);
            output.flush();

            readSuccess(input);
            int recordCount = input.readInt();
            List<TrackerRecord> records = new ArrayList<>();
            for (int recordIndex = 0; recordIndex < recordCount; recordIndex++) {
                String fileId = input.readUTF();
                String filename = input.readUTF();
                long size = input.readLong();
                int chunkSize = input.readInt();
                int chunkCount = input.readInt();
                boolean encrypted = input.readBoolean();
                String updatedAt = input.readUTF();
                int peerCount = input.readInt();
                List<PeerInfo> peers = new ArrayList<>();
                for (int peerIndex = 0; peerIndex < peerCount; peerIndex++) {
                    peers.add(new PeerInfo(input.readUTF(), input.readUTF(), input.readInt()));
                }
                int chunkRecordCount = input.readInt();
                List<ChunkRecord> chunkRecords = new ArrayList<>();
                for (int chunkIndex = 0; chunkIndex < chunkRecordCount; chunkIndex++) {
                    chunkRecords.add(new ChunkRecord(input.readInt(), input.readLong(), input.readInt()));
                }
                records.add(new TrackerRecord(filename, fileId, size, chunkSize, chunkCount, encrypted, updatedAt, peers, chunkRecords));
            }
            return records;
        }
    }

    public int peerCount() throws IOException {
        try (Socket socket = openSocket();
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(ProtocolCommands.PEER_COUNT);
            output.flush();
            readSuccess(input);
            return input.readInt();
        }
    }

    public void disconnect(String sessionToken) throws IOException {
        requestWithAcknowledgement(ProtocolCommands.DISCONNECT, output -> output.writeUTF(sessionToken));
    }

    private void requestWithAcknowledgement(String command, RequestWriter requestWriter) throws IOException {
        try (Socket socket = openSocket();
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF(command);
            requestWriter.write(output);
            output.flush();
            readSuccess(input);
            input.readUTF();
        }
    }

    private static void readSuccess(DataInputStream input) throws IOException {
        boolean success = input.readBoolean();
        if (!success) {
            throw new IOException(input.readUTF());
        }
    }

    private Socket openSocket() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
        return socket;
    }

    @FunctionalInterface
    private interface RequestWriter {
        void write(DataOutputStream output) throws IOException;
    }
}
