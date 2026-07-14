package ai.xelta.craft.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.concurrent.CompletableFuture;

public class BlueprintGenerator {

    public CompletableFuture<CompoundTag> generateBlueprint(JsonObject objectsPlan) {
        return ai.xelta.craft.api.GeminiApiClient.getInstance().generateBlueprintBlocks(objectsPlan)
            .thenApply(jsonResponse -> {
                CompoundTag blueprintNbt = new CompoundTag();
                ListTag blocksList = new ListTag();

                try {
                    JsonElement rootElem = com.google.gson.JsonParser.parseString(jsonResponse);
                    JsonArray blocksArray;
                    
                    if (rootElem.isJsonObject() && rootElem.getAsJsonObject().has("blocks")) {
                        blocksArray = rootElem.getAsJsonObject().getAsJsonArray("blocks");
                    } else if (rootElem.isJsonArray()) {
                        blocksArray = rootElem.getAsJsonArray();
                    } else {
                        throw new RuntimeException("Unexpected JSON format from Gemini: " + jsonResponse);
                    }

                    for (JsonElement blockElem : blocksArray) {
                        if (!blockElem.isJsonObject()) continue;
                        JsonObject blockObj = blockElem.getAsJsonObject();
                        
                        int x = blockObj.has("x") ? blockObj.get("x").getAsInt() : 0;
                        int y = blockObj.has("y") ? blockObj.get("y").getAsInt() : 0;
                        int z = blockObj.has("z") ? blockObj.get("z").getAsInt() : 0;
                        
                        String state = blockObj.has("state") ? blockObj.get("state").getAsString() : "minecraft:stone";
                        
                        // basic validation/formatting
                        state = state.toLowerCase().replace(" ", "_");
                        if (!state.contains(":")) state = "minecraft:" + state;
                        
                        blocksList.add(createBlockTag(x, y, z, state));
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse AI blueprint: " + e.getMessage() + " | Input: " + jsonResponse);
                }

                blueprintNbt.put("Blocks", blocksList);
                return blueprintNbt;
            });
    }

    private CompoundTag createBlockTag(int x, int y, int z, String state) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        tag.putString("state", state);
        return tag;
    }
}
