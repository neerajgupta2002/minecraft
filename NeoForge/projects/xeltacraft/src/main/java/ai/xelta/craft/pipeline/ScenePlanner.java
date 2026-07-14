package ai.xelta.craft.pipeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import java.util.concurrent.CompletableFuture;

public class ScenePlanner {
    public CompletableFuture<JsonObject> planScene(String jsonIntent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                com.google.gson.JsonElement element = JsonParser.parseString(jsonIntent);
                if (element.isJsonArray()) {
                    JsonObject root = new JsonObject();
                    root.add("objects", element.getAsJsonArray());
                    return root;
                } else if (element.isJsonObject()) {
                    JsonObject obj = element.getAsJsonObject();
                    if (!obj.has("objects")) {
                        // wrap single object in objects array
                        JsonArray arr = new JsonArray();
                        arr.add(obj);
                        JsonObject root = new JsonObject();
                        root.add("objects", arr);
                        return root;
                    }
                    return obj;
                }
                throw new RuntimeException("Expected JSON array or object");
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse intent JSON: " + e.getMessage() + " | Input: " + jsonIntent);
            }
        });
    }
}
