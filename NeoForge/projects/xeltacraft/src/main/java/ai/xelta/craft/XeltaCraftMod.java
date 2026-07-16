package ai.xelta.craft;

import ai.xelta.craft.config.XeltaCraftConfig;
import ai.xelta.craft.init.*;
import ai.xelta.craft.command.XeltaCraftCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * XeltaCraft — Xelta AI × Minecraft NeoForge Mod
 *
 * Entry point for the mod. Registers all game objects and event listeners.
 * Architecture: Event-driven, async-first, client/server split.
 */
@Mod(XeltaCraftMod.MOD_ID)
public class XeltaCraftMod {

    public static final String MOD_ID = "xeltacraft";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public XeltaCraftMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("XeltaCraft initializing — Xelta AI meets Minecraft!");

        // Register all deferred registries onto the mod event bus
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModMenuTypes.MENU_TYPES.register(modEventBus);
        ModCreativeTabs.CREATIVE_TABS.register(modEventBus);

        // Register common setup
        modEventBus.addListener(this::onCommonSetup);

        // Register game events (commands, etc.)
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        // Register config
        modContainer.registerConfig(ModConfig.Type.COMMON, XeltaCraftConfig.SPEC, "xeltacraft.toml");

        LOGGER.info("XeltaCraft registrations complete.");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("XeltaCraft common setup running.");
        // Network packet registration happens here
        // (ModNetwork.register() will be called once packets are created)
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        XeltaCraftCommand.register(event.getDispatcher());
    }
}
