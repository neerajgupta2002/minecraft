package ai.xelta.craft.event;

import ai.xelta.craft.pipeline.BlockValidator;
import ai.xelta.craft.pipeline.BlueprintGenerator;
import ai.xelta.craft.pipeline.IntentParser;
import ai.xelta.craft.pipeline.ObjectPlanner;
import ai.xelta.craft.pipeline.ScenePlanner;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

public class ChatListener {

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        String message = event.getMessage().getString().trim();
        if (message.toLowerCase().startsWith("generate ")) {
            ServerPlayer player = event.getPlayer();
            
            // Cancel the chat message so it doesn't just broadcast normally
            event.setCanceled(true);
            
            player.sendSystemMessage(Component.literal("§b[Gemini] §fParsing intent: " + message));

            IntentParser parser = new IntentParser();
            ScenePlanner scenePlanner = new ScenePlanner();
            ObjectPlanner objectPlanner = new ObjectPlanner();
            BlueprintGenerator blueprintGenerator = new BlueprintGenerator();
            BlockValidator blockValidator = new BlockValidator();

            parser.parse(message)
                .thenCompose(jsonIntent -> {
                    player.server.execute(() -> player.sendSystemMessage(Component.literal("§b[Gemini] §ePlanning Scene...")));
                    return scenePlanner.planScene(jsonIntent);
                })
                .thenCompose(scenePlan -> {
                    player.server.execute(() -> player.sendSystemMessage(Component.literal("§b[Gemini] §ePlanning Objects...")));
                    return objectPlanner.planObjects(scenePlan);
                })
                .thenCompose(objectsPlan -> {
                    player.server.execute(() -> player.sendSystemMessage(Component.literal("§b[Gemini] §eGenerating Blueprint...")));
                    return blueprintGenerator.generateBlueprint(objectsPlan);
                })
                .thenCompose(blueprintNbt -> {
                    player.server.execute(() -> player.sendSystemMessage(Component.literal("§b[Gemini] §eValidating Blocks...")));
                    return blockValidator.validate(blueprintNbt);
                })
                .thenAccept(validatedNbt -> {
                    player.server.execute(() -> {
                        net.minecraft.world.level.Level level = player.serverLevel();
                        net.minecraft.core.BlockPos origin = player.blockPosition().relative(player.getDirection(), 2);
                        
                        net.minecraft.nbt.ListTag blocks = validatedNbt.getList("Blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
                        for (int i = 0; i < blocks.size(); i++) {
                            net.minecraft.nbt.CompoundTag blockTag = blocks.getCompound(i);
                            int dx = blockTag.getInt("x");
                            int dy = blockTag.getInt("y");
                            int dz = blockTag.getInt("z");
                            String stateStr = blockTag.getString("state");

                            net.minecraft.resources.ResourceLocation res = net.minecraft.resources.ResourceLocation.tryParse(stateStr);
                            if (res != null) {
                                var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(res);
                                if (block != null) {
                                    net.minecraft.core.BlockPos targetPos = origin.offset(dx, dy, dz);
                                    level.setBlock(targetPos, block.defaultBlockState(), 3);
                                }
                            }
                        }
                        
                        ai.xelta.craft.XeltaCraftMod.LOGGER.info("Successfully generated {} blocks at {}", blocks.size(), origin);
                        player.sendSystemMessage(Component.literal("§b[Gemini] §a✅ Structure generated (" + blocks.size() + " blocks) at " + origin.toShortString()));

                    });
                }).exceptionally(ex -> {
                    player.server.execute(() -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        player.sendSystemMessage(Component.literal("§c❌ Gemini Error: " + cause.getMessage()));
                    });
                    return null;
                });
        }
    }
}
