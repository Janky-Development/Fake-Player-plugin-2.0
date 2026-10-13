package me.bill.fakePlayerPlugin.economy;

import java.util.List;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * A named rental offer from {@code economy.rental.packages}. It is the unit every shop surface shares:
 * the built-in rent GUI shows these, and any other shop GUI plugin buys one with the single command
 * {@code /fpp rent package <id>}.
 *
 * @param fixedPrice when set, replaces the computed (slot + hourly) subtotal; discounts still apply
 * @param permission optional extra node required to buy this package (null = none)
 */
public record RentalPackage(
        String id,
        String label,
        int hours,
        Material icon,
        @Nullable Double fixedPrice,
        @Nullable String permission,
        List<String> description) {}
