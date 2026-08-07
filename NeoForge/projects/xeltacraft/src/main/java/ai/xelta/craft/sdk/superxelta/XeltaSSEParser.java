package ai.xelta.craft.sdk.superxelta;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XeltaSSEParser — parses the Nexus Deep Agent backend's chat/stream SSE wire format
 * (see API.md § "POST /sessions/{session_id}/chat/stream").
 *
 * Frame shapes, in the order they can appear on the wire:
 *   1. Keep-alive comment           — ": keep-alive"                      (no data: prefix)
 *   2. approval_required            — {"type":"approval_required","approval_id":...,"credits":...,"balance":...,"resource_type":...}
 *   3. Text delta                   — {"choices":[{"delta":{"content":"..."}}]}
 *   4. retract                      — {"type":"retract","chars":42}
 *   5. Final skill tag              — {"choices":[{"delta":{"content":""}}],"skill":"..."}
 *      (or, on error: content carries the full user-facing error message, "skill":null)
 *   6. Trailing image frame         — {"choices":[{"delta":{"content":"\n![result](url)"}}]}
 *   7. Billing error                — {"error": "..."}
 *   8. Terminator                   — "data: [DONE]"
 *
 * Frames 3, 5 and 6 share the exact same JSON shape on the wire — they are told apart only
 * by the presence of the "skill" key (→ SKILL_TAG) or a markdown image link in the content
 * (→ IMAGE_FRAME); anything else is a plain TEXT_DELTA.
 */
public final class XeltaSSEParser {

    private XeltaSSEParser() {}

    public enum FrameKind {
        KEEP_ALIVE, APPROVAL_REQUIRED, TEXT_DELTA, RETRACT, SKILL_TAG, IMAGE_FRAME, BILLING_ERROR, DONE
    }

    public record XeltaSSEFrame(
            FrameKind kind,
            String text,                  // TEXT_DELTA content / IMAGE_FRAME markdown / SKILL_TAG error text
            int retractChars,             // RETRACT
            String approvalId,            // APPROVAL_REQUIRED
            int approvalCredits,
            int approvalBalance,
            String approvalResourceType,
            String skill,                 // SKILL_TAG — null when the turn errored
            boolean skillTagIsError,      // SKILL_TAG — true when `text` is the final error message
            String resultUrl,             // IMAGE_FRAME — the extracted URL
            String billingError) {        // BILLING_ERROR

        public boolean isApprovalRequired() { return kind == FrameKind.APPROVAL_REQUIRED; }
        public boolean isDone()             { return kind == FrameKind.DONE; }
    }

    /** Matches the markdown image link the backend appends as its "refresh credits" signal. */
    private static final Pattern IMAGE_MD = Pattern.compile("!\\[[^\\]]*]\\(([^)]+)\\)");

    /**
     * Parses one raw line already read from the stream (no trailing newline required).
     * Returns null for lines that carry nothing actionable (blank lines, malformed JSON, etc).
     */
    public static XeltaSSEFrame parseLine(String rawLine) {
        if (rawLine == null) return null;
        String line = rawLine.stripTrailing();
        if (line.isBlank()) return null;

        // SSE comment frames (keep-alive) have no "data:" prefix at all.
        if (line.startsWith(":")) {
            return frame(FrameKind.KEEP_ALIVE, "", 0, "", 0, 0, "", null, false, "", "");
        }

        if (!line.startsWith("data:")) return null;
        String data = line.substring(5).trim();

        if (data.equals("[DONE]")) {
            return frame(FrameKind.DONE, "", 0, "", 0, 0, "", null, false, "", "");
        }

        try {
            JsonObject obj = JsonParser.parseString(data).getAsJsonObject();

            if (obj.has("error")) {
                return frame(FrameKind.BILLING_ERROR, "", 0, "", 0, 0, "", null, false, "",
                    str(obj, "error", ""));
            }

            String type = str(obj, "type", "");

            if ("approval_required".equals(type)) {
                return frame(FrameKind.APPROVAL_REQUIRED, "", 0,
                    str(obj, "approval_id", ""),
                    intOf(obj, "credits", 0), intOf(obj, "balance", 0),
                    str(obj, "resource_type", ""), null, false, "", "");
            }

            if ("retract".equals(type)) {
                return frame(FrameKind.RETRACT, "", intOf(obj, "chars", 0),
                    "", 0, 0, "", null, false, "", "");
            }

            String content = extractDeltaContent(obj);

            if (obj.has("skill")) {
                String skill = obj.get("skill").isJsonNull() ? null : obj.get("skill").getAsString();
                boolean isError = skill == null && !content.isBlank();
                return frame(FrameKind.SKILL_TAG, content, 0, "", 0, 0, "", skill, isError, "", "");
            }

            Matcher m = IMAGE_MD.matcher(content);
            if (m.find()) {
                return frame(FrameKind.IMAGE_FRAME, content, 0, "", 0, 0, "", null, false, m.group(1), "");
            }

            return frame(FrameKind.TEXT_DELTA, content, 0, "", 0, 0, "", null, false, "", "");

        } catch (Exception e) {
            return null;
        }
    }

    private static String extractDeltaContent(JsonObject obj) {
        if (!obj.has("choices") || !obj.get("choices").isJsonArray()) return "";
        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) return "";
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (!choice0.has("delta") || !choice0.get("delta").isJsonObject()) return "";
        return str(choice0.getAsJsonObject("delta"), "content", "");
    }

    private static XeltaSSEFrame frame(FrameKind kind, String text, int retractChars,
                                        String approvalId, int approvalCredits, int approvalBalance,
                                        String approvalResourceType, String skill, boolean skillTagIsError,
                                        String resultUrl, String billingError) {
        return new XeltaSSEFrame(kind, text, retractChars, approvalId, approvalCredits, approvalBalance,
            approvalResourceType, skill, skillTagIsError, resultUrl, billingError);
    }

    private static String str(JsonObject obj, String key, String def) {
        return obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()
            ? obj.get(key).getAsString() : def;
    }

    private static int intOf(JsonObject obj, String key, int def) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()
                ? obj.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }
}
