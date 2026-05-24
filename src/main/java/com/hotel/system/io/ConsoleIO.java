package com.hotel.system.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ConsoleIO {
    private final BufferedReader reader;

    public ConsoleIO(BufferedReader reader) {
        this.reader = reader;
    }

    public String readKeyword(String prompt, String... allowed) throws IOException {
        Map<String, String> set = new LinkedHashMap<>();
        for (String a : allowed) set.put(a.toLowerCase(Locale.ROOT), a);

        while (true) {
            System.out.print(prompt);
            String line = reader.readLine();
            if (line == null) return allowed[0];
            String v = line.trim().toLowerCase(Locale.ROOT);
            if (set.containsKey(v)) return v;
            System.out.println("Invalid input. Allowed: " + String.join("/", allowed));
        }
    }
}
