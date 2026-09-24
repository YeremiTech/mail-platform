package com.yeremitech.mailplatform.api.service;

import com.yeremitech.mailplatform.application.usecase.CreateBatchUseCase;
import com.yeremitech.mailplatform.domain.EmailAddress;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded RFC-4180 CSV import; no CSV expressions are evaluated, and no unbounded reads are performed. */
public final class CampaignCsvParser {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final int MAX_RECIPIENTS = 10_000;
    private static final int MAX_COLUMNS = 24;
    private static final int MAX_FIELD_CHARS = 4_096;
    private CampaignCsvParser() {}

    public static List<CreateBatchUseCase.Recipient> parse(InputStream input, boolean marketing) throws IOException {
        if (input == null) throw new IllegalArgumentException("CSV file is required");
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader r = new InputStreamReader(new BoundedStream(input, MAX_BYTES), decoder);
             PushbackReader reader = new PushbackReader(r, 2)) {
            List<String> header = readRow(reader);
            if (header == null) throw new IllegalArgumentException("CSV file is empty");
            if (!header.isEmpty() && header.getFirst().startsWith("\uFEFF")) {
                header.set(0, header.getFirst().substring(1));
            }
            if (header.size() < 2 || header.size() > MAX_COLUMNS
                    || !"email".equals(header.get(0)) || !"display_name".equals(header.get(1))) {
                throw new IllegalArgumentException("CSV columns must begin with email,display_name");
            }
            Set<String> columns = new HashSet<>();
            int consentColumn = -1;
            for (int i = 0; i < header.size(); i++) {
                String col = header.get(i);
                if (!columns.add(col)) throw new IllegalArgumentException("duplicate CSV column: " + col);
                if ("consent".equals(col)) consentColumn = i;
                else if (i >= 2 && !col.matches("var_[A-Za-z][A-Za-z0-9_]{0,62}")) {
                    throw new IllegalArgumentException("unexpected CSV column: " + col);
                }
            }
            if (marketing && consentColumn == -1) throw new IllegalArgumentException("marketing campaigns require a consent column");
            List<CreateBatchUseCase.Recipient> recipients = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            List<String> row;
            while ((row = readRow(reader)) != null) {
                if (row.size() == 1 && row.getFirst().isBlank()) continue;
                if (row.size() != header.size()) throw new IllegalArgumentException("CSV row has incorrect column count");
                if (recipients.size() >= MAX_RECIPIENTS) throw new IllegalArgumentException("CSV exceeds 10000 recipients");
                String email = row.get(0).trim();
                new EmailAddress(email);
                if (!seen.add(email.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("CSV includes a repeated email address");
                }
                String name = row.get(1).trim();
                if (name.length() > 200) throw new IllegalArgumentException("recipient name exceeds 200 characters");
                if (marketing && !"true".equalsIgnoreCase(row.get(consentColumn).trim())) {
                    throw new IllegalArgumentException("each marketing recipient must have consent=true");
                }
                Map<String, Object> variables = new HashMap<>();
                for (int i = 2; i < header.size(); i++) {
                    if (i != consentColumn) variables.put(header.get(i).substring(4), row.get(i));
                }
                recipients.add(new CreateBatchUseCase.Recipient(email, name.isEmpty() ? null : name, variables));
            }
            if (recipients.isEmpty()) throw new IllegalArgumentException("CSV has no recipients");
            return List.copyOf(recipients);
        }
    }

    /** Reads one record, including quoted commas and newlines, without retaining the complete file. */
    private static List<String> readRow(PushbackReader in) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, afterQuote = false, started = false;
        int current;
        while ((current = in.read()) != -1) {
            started = true;
            char c = (char) current;
            if (quoted) {
                if (c == '"') {
                    int next = in.read();
                    if (next == '"') field.append('"');
                    else { quoted = false; afterQuote = true; if (next != -1) in.unread(next); }
                } else field.append(c);
            } else if (afterQuote) {
                if (c == ',') { fields.add(field.toString()); field.setLength(0); afterQuote = false; }
                else if (c == '\n' || c == '\r') { eatLf(in, c); fields.add(field.toString()); return fields; }
                else throw new IllegalArgumentException("unexpected character after a quoted CSV field");
            } else if (c == '"') {
                if (!field.isEmpty()) throw new IllegalArgumentException("quote inside unquoted CSV field");
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString()); field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                eatLf(in, c); fields.add(field.toString()); return fields;
            } else field.append(c);
            if (field.length() > MAX_FIELD_CHARS) throw new IllegalArgumentException("CSV field exceeds 4096 characters");
            if (fields.size() >= MAX_COLUMNS) throw new IllegalArgumentException("CSV has too many columns");
        }
        if (quoted) throw new IllegalArgumentException("unterminated quoted CSV field");
        if (!started) return null;
        fields.add(field.toString());
        return fields;
    }

    private static void eatLf(PushbackReader in, char c) throws IOException {
        if (c == '\r') { int next = in.read(); if (next != '\n' && next != -1) in.unread(next); }
    }

    private static final class BoundedStream extends java.io.FilterInputStream {
        private final long limit;
        private long consumed;
        private BoundedStream(InputStream in, long limit) { super(in); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++consumed > limit) throw new IllegalArgumentException("CSV exceeds 5 MB");
            return value;
        }
        @Override public int read(byte[] dest, int offset, int len) throws IOException {
            int count = in.read(dest, offset, Math.min(len, (int) Math.min(Integer.MAX_VALUE, limit - consumed + 1)));
            if (count > 0 && (consumed += count) > limit) throw new IllegalArgumentException("CSV exceeds 5 MB");
            return count;
        }
    }
}
