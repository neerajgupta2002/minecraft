package ai.xelta.craft.pipeline;

import java.util.concurrent.CompletableFuture;
import ai.xelta.craft.api.GeminiApiClient;

public class TestEnv {
    public static void main(String[] args) {
        System.out.println("=== STARTING GEMINI API TEST ===");
        try {
            GeminiApiClient client = GeminiApiClient.getInstance();
            System.out.println("Sending test prompt to Gemini...");
            
            String result = client.parseIntentToJson("Generate a table and a pile of yellow blocks.").join();
            
            System.out.println("✅ Gemini API Call Successful!");
            System.out.println("Output from Gemini:");
            System.out.println(result);
            
        } catch (Exception e) {
            System.out.println("❌ Gemini API Call Failed!");
            e.printStackTrace();
        }
        System.out.println("=== GEMINI API TEST FINISHED ===");
        System.exit(0);
    }
}
