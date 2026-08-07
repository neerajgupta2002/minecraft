package ai.xelta.craft.event;

import ai.xelta.craft.XeltaCraftMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.List;

@EventBusSubscriber(modid = XeltaCraftMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class SitEventHandler {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);

        // Check if it's a stair block (a common chair representation)
        if (state.getBlock() instanceof StairBlock) {
            Player player = event.getEntity();
            
            // Check if someone is already sitting here
            List<ArmorStand> existing = level.getEntitiesOfClass(ArmorStand.class, new AABB(pos));
            for (ArmorStand stand : existing) {
                if (stand.getTags().contains("xelta_chair")) {
                    return; // Already occupied
                }
            }

            // Spawn invisible armor stand to sit on
            ArmorStand seat = new ArmorStand(level, pos.getX() + 0.5D, pos.getY() - 0.2D, pos.getZ() + 0.5D);
            seat.setInvisible(true);
            seat.setInvulnerable(true);
            seat.setNoGravity(true);
            seat.addTag("xelta_chair");
            
            level.addFreshEntity(seat);
            player.startRiding(seat);
            
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onEntityMount(EntityMountEvent event) {
        if (event.isDismounting() && event.getEntityBeingMounted() instanceof ArmorStand stand) {
            if (stand.getTags().contains("xelta_chair") && !stand.isRemoved()) {
                stand.discard(); // Remove the invisible seat when player stands up
            }
        }
    }
}
