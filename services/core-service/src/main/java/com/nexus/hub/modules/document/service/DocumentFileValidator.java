package com.nexus.hub.modules.document.service;

import com.nexus.hub.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strict upload validation: the file must have an allowed extension AND its leading bytes must be
 * consistent with that extension. The client-supplied Content-Type is only accepted when it is
 * absent, application/octet-stream, or matches the extension — it is never trusted on its own.
 */
public final class DocumentFileValidator {

    /** Number of leading bytes inspected for magic numbers / UTF-8 validity. */
    public static final int HEAD_BYTES = 8192;

    private static final String INVALID_TYPE_MESSAGE = "Invalid file type. Supported formats: PDF, TXT, MD, DOCX";
    private static final String OCTET_STREAM = "application/octet-stream";

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    private static final Map<String, Set<String>> EXTENSION_MIME_TYPES = Map.of(
            ".pdf", Set.of("application/pdf"),
            ".txt", Set.of("text/plain"),
            ".md", Set.of("text/markdown", "text/x-markdown", "text/plain"),
            ".docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    );

    private DocumentFileValidator() {
    }

    /**
     * @param filename     original (cleaned) filename
     * @param contentType  client-declared Content-Type (may be null)
     * @param head         first up-to-{@link #HEAD_BYTES} bytes of the file
     * @param completeFile true when {@code head} contains the entire file
     * @return the normalized extension (e.g. ".pdf")
     * @throws ApiException 400 when the file is not acceptable
     */
    public static String validate(String filename, String contentType, byte[] head, boolean completeFile) {
        String extension = extractExtension(filename);
        Set<String> allowedMimes = extension != null ? EXTENSION_MIME_TYPES.get(extension) : null;
        if (allowedMimes == null) {
            throw badRequest();
        }

        String mime = normalizeMime(contentType);
        if (mime != null && !OCTET_STREAM.equals(mime) && !allowedMimes.contains(mime)) {
            throw badRequest();
        }

        if (head == null || head.length == 0) {
            throw badRequest();
        }

        boolean contentMatches = switch (extension) {
            case ".pdf" -> startsWith(head, PDF_MAGIC);
            case ".docx" -> startsWith(head, ZIP_MAGIC);
            case ".txt", ".md" -> isUtf8Text(head, completeFile);
            default -> false;
        };
        if (!contentMatches) {
            throw new ApiException("File content does not match its declared type. Supported formats: PDF, TXT, MD, DOCX",
                    HttpStatus.BAD_REQUEST);
        }
        return extension;
    }

    static String extractExtension(String filename) {
        if (filename == null) {
            return null;
        }
        String lower = filename.toLowerCase(Locale.ROOT).trim();
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return null;
        }
        return lower.substring(dot);
    }

    private static String normalizeMime(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String mime = contentType.toLowerCase(Locale.ROOT);
        int semicolon = mime.indexOf(';');
        if (semicolon >= 0) {
            mime = mime.substring(0, semicolon);
        }
        return mime.trim();
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Valid UTF-8 without NUL bytes. When only a prefix of the file is inspected, a multi-byte
     * sequence truncated at the end of the buffer is tolerated.
     */
    private static boolean isUtf8Text(byte[] head, boolean completeFile) {
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer out = CharBuffer.allocate(head.length + 1);
        CoderResult result = decoder.decode(ByteBuffer.wrap(head), out, completeFile);
        if (result.isError()) {
            return false;
        }
        if (completeFile) {
            return !decoder.flush(out).isError();
        }
        return true;
    }

    private static ApiException badRequest() {
        return new ApiException(INVALID_TYPE_MESSAGE, HttpStatus.BAD_REQUEST);
    }
}
