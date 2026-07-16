package ai.xelta.craft.api;

/**
 * XeltaApiResponse — parsed from Xelta's standard response envelope.
 *
 * Xelta's real response format:
 *   { "success": true, "data": { ... }, "message": "..." }
 *
 * After parsing the envelope, the fields from "data" are promoted here.
 */
public class XeltaApiResponse {

    /** true = success, false = API reported an error */
    public boolean success;

    /** Human-readable message from API */
    public String message;

    /** "done" | "processing" | "queued" | "error" */
    public String status;

    /** Image URL (sync image generation) */
    public String image_url;

    /** Video URL (sync or completed async video generation) */
    public String video_url;

    /** Job ID for async generation — poll /api/jobs/{job_id} */
    public String job_id;

    /** Error message from API (when success=false) */
    public String error;

    /** Credits consumed by this request */
    public int credits_used;

    /** Remaining credit balance */
    public int balance;

    /** Returns true if this response represents a completed async job */
    public boolean isAsync() {
        return job_id != null && !job_id.isBlank()
            && (image_url == null || image_url.isBlank())
            && (video_url == null || video_url.isBlank());
    }

    /** Returns true if a usable result URL is present */
    public boolean hasResult() {
        return (image_url != null && !image_url.isBlank())
            || (video_url != null && !video_url.isBlank());
    }
}
