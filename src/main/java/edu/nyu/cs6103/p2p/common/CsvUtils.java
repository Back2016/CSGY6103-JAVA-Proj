package edu.nyu.cs6103.p2p.common;

import java.util.ArrayList;
import java.util.List;

public final class CsvUtils {
    private CsvUtils() {
    }

    public static String quote(String value) {
        String normalized = value == null ? "" : value;
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    public static List<String> parseLine(String line) {
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
