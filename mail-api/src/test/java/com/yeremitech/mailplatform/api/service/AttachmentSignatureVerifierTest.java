package com.yeremitech.mailplatform.api.service;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class AttachmentSignatureVerifierTest {
    @Test void checksPdfAndRejectsSpoofing() {
        assertDoesNotThrow(() -> AttachmentSignatureVerifier.verify("application/pdf", "%PDF-1.7\nminimal".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> AttachmentSignatureVerifier.verify("application/pdf", "<script>".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> AttachmentSignatureVerifier.verify("image/png", "%PDF-1.7".getBytes()));
    }

    @Test void rejectsBrokenAndMacroOfficePackages() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AttachmentSignatureVerifier.verify(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "PKNO".getBytes()));
        byte[] cleanDoc = zip("[Content_Types].xml", "word/document.xml");
        assertDoesNotThrow(() -> AttachmentSignatureVerifier.verify(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", cleanDoc));
        byte[] macroDoc = zip("[Content_Types].xml", "word/document.xml", "word/vbaProject.bin");
        assertThrows(IllegalArgumentException.class, () -> AttachmentSignatureVerifier.verify(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", macroDoc));
    }

    @Test void requiresUtf8Text() {
        assertThrows(IllegalArgumentException.class, () -> AttachmentSignatureVerifier.verify("text/plain", new byte[]{(byte)0xff}));
        assertDoesNotThrow(() -> AttachmentSignatureVerifier.verify("text/csv", "name,age\nAna,20".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void validatesStreamsWithoutMaterializingUpload() throws Exception {
        byte[] pdf="%PDF-1.7\nstreamed".getBytes(StandardCharsets.US_ASCII);
        assertDoesNotThrow(() -> AttachmentSignatureVerifier.verify("application/pdf",
                new ByteArrayInputStream(pdf), pdf.length));
        assertThrows(IllegalArgumentException.class, () -> AttachmentSignatureVerifier.verify(
                "application/pdf", new ByteArrayInputStream(pdf), 16L*1024*1024));
        byte[] invalidUtf8={(byte)0xc3,(byte)0x28};
        assertThrows(IllegalArgumentException.class, () -> AttachmentSignatureVerifier.verify(
                "text/plain", new ByteArrayInputStream(invalidUtf8), invalidUtf8.length));
    }

    private static byte[] zip(String... paths) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String path : paths) {
                out.putNextEntry(new ZipEntry(path));
                out.write("x".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
