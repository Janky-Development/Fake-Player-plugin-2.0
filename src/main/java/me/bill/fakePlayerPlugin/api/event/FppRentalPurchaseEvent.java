package me.bill.fakePlayerPlugin.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.api.FppBot;

/**
 * Fired on the main thread just before a rental purchase is charged - from {@code /fpp rent}, the rent
 * GUI, a package, or auto-renew by an online owner. Shop/economy plugins can veto it or change the
 * price (e.g. a sale, a custom currency check, a per-rank surcharge). Cancelling charges nothing and
 * spawns nothing.
 */
public class FppRentalPurchaseEvent extends Event implements Cancellable {

    public enum Type {
        /** A brand-new rented bot. */
        NEW,
        /** More time on an existing rented bot. */
        EXTEND
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Type type;
    private final FppBot bot;
    private final int hours;
    private final String source;
    private double price;
    private boolean cancelled;

    public FppRentalPurchaseEvent(
            @NotNull Player player,
            @NotNull Type type,
            @Nullable FppBot bot,
            int hours,
            double price,
            @NotNull String source) {
        this.player = player;
        this.type = type;
        this.bot = bot;
        this.hours = hours;
        this.price = price;
        this.source = source;
    }

    public @NotNull Player getPlayer() {
        return player;
    }

    public @NotNull Type getType() {
        return type;
    }

    /** The bot being extended; {@code null} for {@link Type#NEW}. */
    public @Nullable FppBot getBot() {
        return bot;
    }

    public int getHours() {
        return hours;
    }

    /** Where the purchase came from: {@code command}, {@code gui}, {@code package} or {@code auto-renew}. */
    public @NotNull String getSource() {
        return source;
    }

    public double getPrice() {
        return price;
    }

    /** Overrides what the buyer will be charged (0 = free, never negative). */
    public void setPrice(double price) {
        this.price = Math.max(0.0, price);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
