package me.bill.fakePlayerPlugin.fakeplayer;

import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Picks the best melee weapon the way a player scanning the hotbar would: by what the item actually
 * does to the wielder's {@code attack_damage} / {@code attack_speed} attributes (the same item
 * component vanilla reads), so modded/datapack weapons and renamed items are judged by stats, not by
 * name. Score = expected damage per second of a full-charge swing (Sharpness included).
 */
public final class BotWeaponSelector {

    /** An empty hand: 1 damage, 4 attacks/second. */
    private static final double FIST_SCORE = (1.0) * 4.0;

    private BotWeaponSelector() {}

    /** Damage per second of this stack held in the main hand. */
    public static double score(ItemStack bukkit) {
        if (bukkit == null || bukkit.getType().isAir()) return FIST_SCORE;
        net.minecraft.world.item.ItemStack stack = CraftItemStack.asNMSCopy(bukkit);
        ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);

        double damage = 1.0; // player base
        double speed = 4.0; // player base
        boolean hasWeaponStats = false;
        for (ItemAttributeModifiers.Entry entry : mods.modifiers()) {
            if (entry.slot() != EquipmentSlotGroup.MAINHAND && entry.slot() != EquipmentSlotGroup.ANY) continue;
            AttributeModifier m = entry.modifier();
            if (m.operation() != AttributeModifier.Operation.ADD_VALUE) continue;
            if (entry.attribute().value() == Attributes.ATTACK_DAMAGE.value()) {
                damage += m.amount();
                hasWeaponStats = true;
            } else if (entry.attribute().value() == Attributes.ATTACK_SPEED.value()) {
                speed += m.amount();
            }
        }
        if (!hasWeaponStats) return FIST_SCORE;

        int sharpness = bukkit.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharpness > 0) damage += 0.5 * sharpness + 0.5;
        return Math.max(0.0, damage) * Math.max(0.1, speed);
    }

    /**
     * Moves the best weapon into the main hand (hotbar slot select, or a swap for inventory slots).
     *
     * @return true if the held item changed
     */
    public static boolean equipBestWeapon(Player bot) {
        PlayerInventory inv = bot.getInventory();
        int held = inv.getHeldItemSlot();
        double bestScore = score(inv.getItem(held));
        int bestSlot = held;
        for (int slot = 0; slot < 36; slot++) {
            if (slot == held) continue;
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType().isAir()) continue;
            double s = score(it);
            if (s > bestScore + 0.01) {
                bestScore = s;
                bestSlot = slot;
            }
        }
        if (bestSlot == held) return false;

        if (bestSlot <= 8) {
            inv.setHeldItemSlot(bestSlot);
        } else {
            ItemStack heldItem = inv.getItem(held);
            inv.setItem(held, inv.getItem(bestSlot));
            inv.setItem(bestSlot, heldItem);
        }
        return true;
    }
}
