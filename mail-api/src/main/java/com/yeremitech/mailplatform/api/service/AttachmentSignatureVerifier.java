package com.yeremitech.mailplatform.api.service;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Rejects MIME/content mismatches without retaining entire uploads in application heap. */
public final class AttachmentSignatureVerifier {
    private static final int MAX_ARCHIVE_ENTRIES = 500;
    private static final long MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_FILE_SIZE = 15L * 1024 * 1024;
    private AttachmentSignatureVerifier() {}

    public static void verify(String mime, byte[] data) {
        if (data == null) throw new IllegalArgumentException("attachment is empty");
        try { verify(mime, new ByteArrayInputStream(data), data.length); }
        catch (IOException ex) { throw new IllegalArgumentException("unable to verify attachment", ex); }
    }

    public static void verify(String mime, InputStream source, long declaredSize) throws IOException {
        if (source == null || declaredSize < 1 || declaredSize > MAX_FILE_SIZE) reject();
        BufferedInputStream input = new BufferedInputStream(source, 8192);
        input.mark(16);
        byte[] header = input.readNBytes(12);
        input.reset();
        switch (mime) {
            case "application/pdf" -> { if (!startsWith(header, "%PDF-".getBytes(StandardCharsets.US_ASCII))) reject(); }
            case "image/png" -> { if (!startsWith(header, new byte[]{(byte)137,80,78,71,13,10,26,10})) reject(); }
            case "image/jpeg" -> { if (header.length < 3 || (header[0]&255)!=255 || (header[1]&255)!=216 || (header[2]&255)!=255) reject(); }
            case "image/webp" -> { if (header.length < 12 || !rangeEquals(header,0,"RIFF") || !rangeEquals(header,8,"WEBP")) reject(); }
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> verifyOffice(input, "word/document.xml");
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> verifyOffice(input, "xl/workbook.xml");
            case "text/plain", "text/csv", "application/xml", "text/xml" -> verifyUtf8Text(input);
            default -> throw new IllegalArgumentException("attachment content type is not allowed");
        }
    }

    private static void verifyUtf8Text(InputStream input) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new InputStreamReader(input, decoder)) {
            char[] chars = new char[8192];
            int count;
            while ((count = reader.read(chars)) != -1) {
                for (int i=0; i<count; i++) if (chars[i] == '\0') reject();
            }
        } catch (java.nio.charset.CharacterCodingException ex) {
            throw new IllegalArgumentException("text attachment must be UTF-8", ex);
        }
    }

    private static void verifyOffice(InputStream input, String requiredPart) throws IOException {
        input.mark(4);
        byte[] magic = input.readNBytes(4);
        if (magic.length < 4 || magic[0]!='P' || magic[1]!='K' || magic[2]!=3 || magic[3]!=4) reject();
        input.reset();
        Set<String> entries = new HashSet<>();
        long expanded = 0;
        try (ZipInputStream archive = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = archive.getNextEntry()) != null) {
                if (entries.size() >= MAX_ARCHIVE_ENTRIES || !entries.add(entry.getName())) reject();
                String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("vbaproject.bin") || name.contains("../") || name.startsWith("/")
                        || name.indexOf('\\') >= 0) reject();
                int read;
                while ((read = archive.read(buffer)) > 0) {
                    expanded += read;
                    if (expanded > MAX_UNCOMPRESSED_BYTES) reject();
                }
                archive.closeEntry();
            }
        } catch (IOException ex) { throw new IllegalArgumentException("attachment is not a valid Office package", ex); }
        if (!entries.contains("[Content_Types].xml") || !entries.contains(requiredPart)) reject();
    }

    private static boolean startsWith(byte[] bytes, byte[] signature) {
        if (bytes.length < signature.length) return false;
        for (int i=0;i<signature.length;i++) if (bytes[i]!=signature[i]) return false;
        return true;
    }
    private static boolean rangeEquals(byte[] bytes, int offset, String value) {
        for (int i=0;i<value.length();i++) if (bytes[offset+i]!=value.charAt(i)) return false;
        return true;
    }
    private static void reject() { throw new IllegalArgumentException("attachment signature or content does not match declared type"); }
}
