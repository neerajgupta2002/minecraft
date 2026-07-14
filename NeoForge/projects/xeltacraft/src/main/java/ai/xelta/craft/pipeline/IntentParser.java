package ai.xelta.craft.pipeline;

import ai.xelta.craft.api.GeminiApiClient;
import java.util.concurrent.CompletableFuture;

public class IntentParser {
    public CompletableFuture<String> parse(String userRequest) {
        return GeminiApiClient.getInstance().parseIntentToJson(userRequest);
    }
}
