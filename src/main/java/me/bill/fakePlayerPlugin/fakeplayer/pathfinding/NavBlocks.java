package me.bill.fakePlayerPlugin.fakeplayer.pathfinding;

import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.BotClickDispatcher;
import me.bill.fakePlayerPlugin.fakeplayer.NmsPlayerSpawner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Block placement for pathing/clearing, done the way a client does it: pick a block from the
 * inventory into the hand, look at a face of a supporting block, and send the real use-item-on packet.
 * The server runs reach/collision checks, fires the place events, plays the sound and consumes the
 * item - nothing sets blocks directly.
 */
public final class NavBlocks {

    private NavBlocks() {}

    /** Preferred cheap filler, best first, after the configured {@code pathfinding.place-material}. */
    private static final List<Material> PREFERRED = List.of(
            Material.COBBLESTONE,
            Material.COBBLED_DEEPSLATE,
            Material.DIRT,
            Material.NETHERRACK,
            Material.STONE,
            Material.DEEPSLATE,
            Material.ANDESITE,
            Material.DIORITE,
            Material.GRANITE,
            Material.TUFF,
            Material.BLACKSTONE,
            Material.END_STONE);

    /** A block the bot may use as scaffolding: solid, opaque, not gravity-affected, not interactive. */
    public static boolean isScaffold(Material m) {
        return m != null
                && m.isBlock()
                && m.isSolid()
                && m.isOccluding()
                && !m.hasGravity()
                && !(m.name().contains("CHEST") || m.name().contains("FURNACE") || m.name().contains("TABLE")
                        || m.name().contains("DOOR") || m.name().contains("BED") || m.name().contains("ANVIL"))
                && !m.name().contains("SHULKER")
                && !m.name().contains("TNT")
                && !m.name().contains("ORE")
                && m != Material.BEDROCK
                && m != Material.OBSIDIAN
                && m != Material.SPAWNER;
    }

    private static int rank(Material m) {
        Material configured = configured();
        if (configured != null && configured == m) return -1;
        int i = PREFERRED.indexOf(m);
        return i >= 0 ? i : PREFERRED.size();
    }

    private static Material configured() {
        try {
            return Material.valueOf(Config.pathfindingPlaceMaterial().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Slot (0-35) holding the best scaffold block, or -1 when the inventory has none. */
    public static int bestSlot(Player bot) {
        PlayerInventory inv = bot.getInventory();
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getAmount() <= 0 || !isScaffold(it.getType())) continue;
            int r = rank(it.getType());
            if (r < bestRank || (r == bestRank && slot == inv.getHeldItemSlot())) {
                best = slot;
                bestRank = r;
            }
        }
        return best;
    }

    public static boolean hasPlaceable(Player bot) {
        return bestSlot(bot) >= 0;
    }

    /** Puts the best scaffold block in the main hand (hotbar select, or swap from the inventory). */
    public static boolean equipPlaceable(Player bot) {
        PlayerInventory inv = bot.getInventory();
        int held = inv.getHeldItemSlot();
        ItemStack heldItem = inv.getItem(held);
        if (heldItem != null && heldItem.getAmount() > 0 && isScaffold(heldItem.getType())) return true;
        int slot = bestSlot(bot);
        if (slot < 0) return false;
        if (slot <= 8) {
            inv.setHeldItemSlot(slot);
        } else {
            ItemStack other = inv.getItem(slot);
            inv.setItem(slot, heldItem);
            inv.setItem(held, other);
        }
        return true;
    }

    /**
     * Places a block into {@code cell} against the best adjacent support. Returns true when the cell
     * holds a block afterwards. Does nothing (false) when no support is in reach or nothing can be placed.
     */
    public static boolean place(Player bot, BlockPos cell) {
        ServerPlayer nms = ((CraftPlayer) bot).getHandle();
        BlockState existing = nms.level().getBlockState(cell);
        if (!existing.isAir() && !existing.canBeReplaced()) return true; // already filled
        if (!equipPlaceable(bot)) return false;

        Vec3 eye = nms.getEyePosition(1.0F);
        double reach = nms.blockInteractionRange();
        Direction[] order = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
        };
        for (Direction side : order) {
            BlockPos support = cell.relative(side);
            BlockState st = nms.level().getBlockState(support);
            Direction face = side.getOpposite(); // face of the support that touches the cell
            if (st.isAir() || !st.isFaceSturdy(nms.level(), support, face)) continue;

            Vec3 hitLoc = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (eye.distanceTo(hitLoc) > reach) continue;

            aim(bot, nms, hitLoc);
            BotClickDispatcher.useItemOn(nms, InteractionHand.MAIN_HAND, new BlockHitResult(hitLoc, face, support, false));
            BotClickDispatcher.swing(nms, InteractionHand.MAIN_HAND);
            if (!nms.level().getBlockState(cell).isAir()) return true;
        }
        return false;
    }

    private static void aim(Player bot, ServerPlayer nms, Vec3 target) {
        Vec3 eye = nms.getEyePosition(1.0F);
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        nms.absSnapRotationTo(yaw, pitch);
        NmsPlayerSpawner.setHeadYaw(bot, yaw);
    }
}
