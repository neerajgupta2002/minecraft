package ai.xelta.craft.generation;

import ai.xelta.craft.XeltaCraftMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FeedbackEngine {

    private static final String FEEDBACK_FILE = "xeltacraft_feedback.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Store the last build job ID for each player so they can rate it
    private static final Map<UUID, String> lastBuildIds = new HashMap<>();

    public static void promptForRating(ServerPlayer player, String jobId, JsonArray blueprint) {
        lastBuildIds.put(player.getUUID(), jobId);

        // Send interactive rating message
        player.sendSystemMessage(Component.literal("§aBuild completed successfully! How did the AI do?"));
        
        Component star1 = Component.literal(" [★] ").withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/xeltacraft rate 1")));
        Component star2 = Component.literal(" [★★] ").withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/xeltacraft rate 2")));
        Component star3 = Component.literal(" [★★★] ").withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/xeltacraft rate 3")));
        Component star4 = Component.literal(" [★★★★] ").withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/xeltacraft rate 4")));
        Component star5 = Component.literal(" [★★★★★] ").withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GREEN).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/xeltacraft rate 5")));
        
        player.sendSystemMessage(Component.literal("Rate: ").append(star1).append(star2).append(star3).append(star4).append(star5));
    }

    public static void saveRating(ServerPlayer player, int stars) {
        String jobId = lastBuildIds.remove(player.getUUID());
        if (jobId == null) {
            player.sendSystemMessage(Component.literal("§cNo recent build found to rate."));
            return;
        }

        try {
            File file = new File(FEEDBACK_FILE);
            JsonArray feedbackList = new JsonArray();
            if (file.exists()) {
                try (FileReader reader = new FileReader(file)) {
                    feedbackList = GSON.fromJson(reader, JsonArray.class);
                }
            }

            JsonObject feedback = new JsonObject();
            feedback.addProperty("jobId", jobId);
            feedback.addProperty("player", player.getName().getString());
            feedback.addProperty("stars", stars);
            feedback.addProperty("timestamp", System.currentTimeMillis());

            feedbackList.add(feedback);

            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(feedbackList, writer);
            }

            player.sendSystemMessage(Component.literal("§aThank you! Rating saved. X-DAVE will learn from this."));
        } catch (Exception e) {
            XeltaCraftMod.LOGGER.error("Failed to save feedback", e);
            player.sendSystemMessage(Component.literal("§cError saving feedback."));
        }
    }
}
