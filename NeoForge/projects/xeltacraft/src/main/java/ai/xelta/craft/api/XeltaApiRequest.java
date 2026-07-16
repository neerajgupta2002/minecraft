package ai.xelta.craft.api;

/** HTTP request body sent to Xelta's image generation endpoint. */
public class XeltaApiRequest {
    public String prompt;
    public String model;
    public String size;   // e.g. "1024x1024"

    public XeltaApiRequest(String prompt, String model, String size) {
        this.prompt = prompt;
        this.model  = model;
        this.size   = size;
    }
}
