package com.nexus.hub.modules.document;

import com.nexus.hub.common.exception.ApiException;
import com.nexus.hub.modules.document.service.DocumentFileValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentFileValidatorTest {

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOCX = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00};
    private static final byte[] TEXT = "# Title\nCafé déjà vu — ünïcödé".getBytes(StandardCharsets.UTF_8);
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static void assertRejected(String name, String mime, byte[] head, boolean complete) {
        assertThatThrownBy(() -> DocumentFileValidator.validate(name, mime, head, complete))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("Accepts matching extension + magic bytes, including application/octet-stream and missing mime")
    void acceptsConsistentFiles() {
        assertThat(DocumentFileValidator.validate("a.pdf", "application/pdf", PDF, true)).isEqualTo(".pdf");
        assertThat(DocumentFileValidator.validate("A.PDF", "application/octet-stream", PDF, true)).isEqualTo(".pdf");
        assertThat(DocumentFileValidator.validate("a.docx", DOCX_MIME, DOCX, true)).isEqualTo(".docx");
        assertThat(DocumentFileValidator.validate("a.docx", "application/octet-stream", DOCX, true)).isEqualTo(".docx");
        assertThat(DocumentFileValidator.validate("a.md", "text/markdown", TEXT, true)).isEqualTo(".md");
        assertThat(DocumentFileValidator.validate("a.txt", "text/plain; charset=utf-8", TEXT, true)).isEqualTo(".txt");
        assertThat(DocumentFileValidator.validate("a.txt", null, TEXT, true)).isEqualTo(".txt");
    }

    @Test
    @DisplayName("Rejects disallowed or missing extensions even with an allowed mime type")
    void rejectsBadExtensions() {
        assertRejected("evil.exe", "application/pdf", PDF, true);
        assertRejected("noextension", "text/plain", TEXT, true);
        assertRejected("archive.zip", "application/octet-stream", DOCX, true);
        assertRejected("page.html", "text/plain", TEXT, true);
    }

    @Test
    @DisplayName("Rejects content that does not match the extension (magic bytes)")
    void rejectsMagicByteMismatch() {
        assertRejected("fake.pdf", "application/pdf", "<html>not a pdf</html>".getBytes(StandardCharsets.US_ASCII), true);
        assertRejected("fake.pdf", "application/octet-stream", DOCX, true);
        assertRejected("fake.docx", DOCX_MIME, PDF, true);
        assertRejected("fake.docx", "application/octet-stream", TEXT, true);
    }

    @Test
    @DisplayName("Rejects a mime type that contradicts the extension")
    void rejectsContradictingMime() {
        assertRejected("a.txt", "text/html", TEXT, true);
        assertRejected("a.pdf", "application/x-msdownload", PDF, true);
    }

    @Test
    @DisplayName("TXT/MD must be valid UTF-8 without NUL bytes")
    void rejectsBinaryText() {
        byte[] withNul = Arrays.copyOf(TEXT, TEXT.length + 1);
        assertRejected("a.txt", "text/plain", withNul, true);
        assertRejected("a.md", "application/octet-stream", new byte[]{(byte) 0xC3, (byte) 0x28, 0x41}, true);
        assertRejected("a.txt", "text/plain", PDF_WITH_BINARY, true);
        assertRejected("a.txt", "text/plain", new byte[0], true);
    }

    private static final byte[] PDF_WITH_BINARY = {0x25, 0x50, 0x44, 0x46, (byte) 0xFF, (byte) 0xFE, 0x00};

    @Test
    @DisplayName("A multi-byte UTF-8 sequence cut at the 8KB inspection boundary is tolerated")
    void toleratesTruncatedTrailingSequenceWhenPartial() {
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8); // 3 bytes
        byte[] head = Arrays.copyOf(euro, 2);                 // truncated sequence
        assertThat(DocumentFileValidator.validate("a.txt", "text/plain", head, false)).isEqualTo(".txt");
        // ...but not when the buffer is the complete file
        assertRejected("a.txt", "text/plain", head, true);
    }
}
