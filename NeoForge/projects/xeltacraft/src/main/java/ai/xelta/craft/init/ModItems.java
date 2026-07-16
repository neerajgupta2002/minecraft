package ai.xelta.craft.init;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.item.AvatarScrollItem;
import ai.xelta.craft.item.FilmRollItem;
import ai.xelta.craft.item.MixboardItem;
import ai.xelta.craft.item.PhotoSlideItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(XeltaCraftMod.MOD_ID);

    // Block item for the Xelta Terminal
    public static final DeferredItem<BlockItem> XELTA_TERMINAL =
            ITEMS.registerSimpleBlockItem("xelta_terminal", ModBlocks.XELTA_TERMINAL);

    // Photo Slide — holds a generated AI image URL in NBT
    public static final DeferredItem<PhotoSlideItem> PHOTO_SLIDE =
            ITEMS.registerItem("photo_slide",
                    props -> new PhotoSlideItem(props.stacksTo(1)),
                    new Item.Properties().stacksTo(1)
            );

    // Film Roll — holds a generated AI video URL in NBT
    public static final DeferredItem<FilmRollItem> FILM_ROLL =
            ITEMS.registerItem("film_roll",
                    props -> new FilmRollItem(props.stacksTo(1)),
                    new Item.Properties().stacksTo(1)
            );

    // Avatar Scroll — holds a GenAvatar talking-avatar video URL
    public static final DeferredItem<AvatarScrollItem> AVATAR_SCROLL =
            ITEMS.registerItem("avatar_scroll",
                    props -> new AvatarScrollItem(props.stacksTo(1)),
                    new Item.Properties().stacksTo(1)
            );

    // Mixboard — holds up to 8 Xelta Mix style-transfer image variations
    public static final DeferredItem<MixboardItem> MIXBOARD =
            ITEMS.registerItem("mixboard",
                    props -> new MixboardItem(props.stacksTo(1)),
                    new Item.Properties().stacksTo(1)
            );
}
