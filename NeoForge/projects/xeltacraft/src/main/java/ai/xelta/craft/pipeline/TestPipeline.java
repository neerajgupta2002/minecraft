package ai.xelta.craft.pipeline;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public class TestPipeline {
    public static void main(String[] args) {
        System.out.println("=== STARTING PIPELINE TEST ===");
        String testJson = "{\"objects\": [{\"type\": \"table\", \"material\": \"oak_planks\"}, {\"type\": \"block_pile\", \"block\": \"yellow_wool\"}]}";
        
        System.out.println("Simulating Gemini output: " + testJson);
        
        ScenePlanner scenePlanner = new ScenePlanner();
        ObjectPlanner objectPlanner = new ObjectPlanner();
        BlueprintGenerator blueprintGenerator = new BlueprintGenerator();
        
        try {
            scenePlanner.planScene(testJson)
                .thenCompose(scenePlan -> objectPlanner.planObjects(scenePlan))
                .thenCompose(objectsPlan -> blueprintGenerator.generateBlueprint(objectsPlan))
                .thenAccept(blueprintNbt -> {
                    System.out.println("✅ Blueprint Generated Successfully!");
                    System.out.println("NBT Output: " + blueprintNbt.toString());
                }).join();
        } catch (Exception e) {
            System.err.println("❌ Pipeline failed: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("=== PIPELINE TEST FINISHED ===");
        System.exit(0);
    }
}
