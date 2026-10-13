package me.bill.fakePlayerPlugin.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;

import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;

/**
 * Prevents bots from earning advancements. Cancelling the criterion grant blocks all progress at
 * the source, so a bot never completes an advancement - no toast, no chat announcement, and no
 * progress accumulating in its {@code world/advancements/<uuid>.json}.
 */
public final class BotAdvancementBlocker implements Listener {

    private final FakePlayerManager manager;

    public BotAdvancementBlocker(FakePlayerManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCriterionGrant(PlayerAdvancementCriterionGrantEvent event) {
        if (manager.getByUuid(event.getPlayer().getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    /** Defence in depth: should a bot ever complete one anyway, it is never announced in chat. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        if (manager.getByUuid(event.getPlayer().getUniqueId()) != null) {
            event.message(null);
        }
    }
}
