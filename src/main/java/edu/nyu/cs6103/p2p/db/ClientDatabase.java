package edu.nyu.cs6103.p2p.db;

import edu.nyu.cs6103.p2p.model.DownloadHistoryEntry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ClientDatabase {
    private static final String HEADER = "created_at,filename,source_peers,destination_path,status";

    private final Path csvPath;

    public ClientDatabase(Path csvPath) {
        this.csvPath = csvPath;
        initialize();
    }

    private void initialize() {
        try {
            Files.createDirectories(csvPath.getParent());
            if (!Files.exists(csvPath)) {
                Files.writeString(csvPath, HEADER + System.lineSeparator(), StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize client history csv", exception);
        }
    }

    public synchronized void recordDownload(String filename, String sourcePeers, String destinationPath, String status) {
        String createdAt = LocalDateTime.now().toString();
        String row = String.join(",",
                escape(createdAt),
                escape(filename),
                escape(sourcePeers),
                escape(destinationPath),
                escape(status));
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardOpenOption.APPEND)) {
            writer.write(row);
            writer.newLine();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to record download history", exception);
        }
    }

    public synchronized List<DownloadHistoryEntry> listHistory() {
        try {
            if (!Files.exists(csvPath)) {
                return List.of();
            }
            List<String> lines = Files.readAllLines(csvPath);
            List<DownloadHistoryEntry> entries = new ArrayList<>();
            for (int index = 1; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) {
                    continue;
                }
                List<String> values = parseCsvLine(line);
                if (values.size() < 5) {
                    continue;
                }
                entries.add(new DownloadHistoryEntry(
                        values.get(1),
                        values.get(2),
                        values.get(3),
                        values.get(4),
                        values.get(0)
                ));
            }
            entries.sort(Comparator.comparing(DownloadHistoryEntry::createdAt).reversed());
            return entries;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load download history", exception);
        }
    }

    private String escape(String value) {
        String normalized = value == null ? "" : value;
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '"') {
                if (inQuotes && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }
}
