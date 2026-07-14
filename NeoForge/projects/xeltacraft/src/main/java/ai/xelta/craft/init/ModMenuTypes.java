package ai.xelta.craft.init;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.menu.XeltaTerminalMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, XeltaCraftMod.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<XeltaTerminalMenu>> XELTA_TERMINAL =
            MENU_TYPES.register("xelta_terminal",
                    () -> IMenuTypeExtension.create(XeltaTerminalMenu::new)
            );
}
