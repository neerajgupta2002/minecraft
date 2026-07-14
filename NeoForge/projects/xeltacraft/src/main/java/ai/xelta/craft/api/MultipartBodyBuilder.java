package ai.xelta.craft.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MultipartBodyBuilder
 *
 * Constructs a multipart/form-data request body for Java 11+ HttpClient,
 * which has no built-in multipart support.
 *
 * Usage:
 *   var builder = new MultipartBodyBuilder();
 *   builder.addField("prompt", "A cyberpunk forest");
 *   builder.addFile("file", "image.png", pngBytes, "image/png");
 *   byte[] body = builder.build();
 *   String contentType = "multipart/form-data; boundary=" + builder.getBoundary();
 */
public class MultipartBodyBuilder {

    private static final byte[] CRLF     = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DASH_DASH = "--".getBytes(StandardCharsets.UTF_8);

    private final String boundary;
    private final List<byte[]> parts = new ArrayList<>();

    public MultipartBodyBuilder() {
        this.boundary = "XeltaBoundary" + Long.toHexString(System.nanoTime());
    }

    /** Add a plain text field. */
    public MultipartBodyBuilder addField(String name, String value) {
        if (value == null || value.isBlank()) return this;
        try {
            var out = new ByteArrayOutputStream();
            out.write(DASH_DASH);
            out.write(boundary.getBytes(StandardCharsets.UTF_8));
            out.write(CRLF);
            out.write(("Content-Disposition: form-data; name=\"" + name + "\"").getBytes(StandardCharsets.UTF_8));
            out.write(CRLF);
            out.write(CRLF);
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.write(CRLF);
            parts.add(out.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("MultipartBodyBuilder.addField failed", e);
        }
        return this;
    }

    /** Add a binary file part. */
    public MultipartBodyBuilder addFile(String fieldName, String filename, byte[] data, String mimeType) {
        if (data == null) return this;
        try {
            var out = new ByteArrayOutputStream();
            out.write(DASH_DASH);
            out.write(boundary.getBytes(StandardCharsets.UTF_8));
            out.write(CRLF);
            out.write(("Content-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\"" + filename + "\"").getBytes(StandardCharsets.UTF_8));
            out.write(CRLF);
            out.write(("Content-Type: " + mimeType).getBytes(StandardCharsets.UTF_8));
            out.write(CRLF);
            out.write(CRLF);
            out.write(data);
            out.write(CRLF);
            parts.add(out.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("MultipartBodyBuilder.addFile failed", e);
        }
        return this;
    }

    /** Build the final body bytes. */
    public byte[] build() {
        try {
            var out = new ByteArrayOutputStream();
            for (byte[] part : parts) {
                out.write(part);
            }
            // Final boundary
            out.write(DASH_DASH);
            out.write(boundary.getBytes(StandardCharsets.UTF_8));
            out.write(DASH_DASH);
            out.write(CRLF);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("MultipartBodyBuilder.build failed", e);
        }
    }

    public String getBoundary() { return boundary; }

    public String getContentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    // =========================================================
    // Placeholder image — minimal 1×1 transparent PNG
    // Used when no source image is available for Photo Lab
    // =========================================================

    /**
     * A valid 1×1 transparent PNG in raw bytes.
     * Sent as the "file" field when generating AI backgrounds without a source image.
     */
    public static final byte[] TRANSPARENT_1X1_PNG = new byte[] {
        (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,         // IHDR chunk length + type
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,         // Width=1, Height=1
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte)0xC4,   // BitDepth=8, ColorType=6 (RGBA)
        (byte)0x89,                                               // CRC
        0x00, 0x00, 0x00, 0x0B, 0x49, 0x44, 0x41, 0x54,         // IDAT chunk
        0x08, (byte)0xD7, 0x63, 0x60, 0x60, 0x60, 0x60, 0x00,
        0x00, 0x00, 0x05, 0x00, 0x01, (byte)0xA5, (byte)0xF6, 0x45,
        0x40,                                                     // IDAT data + CRC
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,         // IEND chunk
        (byte)0xAE, 0x42, 0x60, (byte)0x82                       // IEND CRC
    };
}
