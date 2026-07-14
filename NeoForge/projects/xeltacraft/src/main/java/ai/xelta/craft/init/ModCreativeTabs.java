package ai.xelta.craft.init;

import ai.xelta.craft.XeltaCraftMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, XeltaCraftMod.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> XELTACRAFT_TAB =
            CREATIVE_TABS.register("xeltacraft_tab",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.xeltacraft"))
                            .withTabsBefore(CreativeModeTabs.COMBAT)
                            .icon(() -> ModItems.XELTA_TERMINAL.get().getDefaultInstance())
                            .displayItems((params, output) -> {
                                output.accept(ModItems.XELTA_TERMINAL.get());
                                output.accept(ModItems.PHOTO_SLIDE.get());
                                output.accept(ModItems.FILM_ROLL.get());
                                output.accept(ModItems.AVATAR_SCROLL.get());
                                output.accept(ModItems.MIXBOARD.get());
                            })
                            .build()
            );
}
