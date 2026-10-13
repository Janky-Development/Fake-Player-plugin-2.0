package me.bill.fakePlayerPlugin.fakeplayer;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.util.FppLogger;

import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;

/**
 * A bot's "options menu". A real client tells the server about itself with a
 * {@code ServerboundClientInformationPacket} - language, render distance, chat/particle settings, skin
 * layers, main hand - at login and again whenever the player changes a setting. A bot does exactly the
 * same, and nothing more: the server's own chunk system then decides what to load, generate, tick and
 * send for that "client" the way it would for any AFK player. The plugin never touches tickets,
 * view/simulation distances or any other server-side state.
 */
public final class BotClientOptions {

    /** The lowest render distance a vanilla client can select. */
    public static final int MIN_VIEW_DISTANCE = 2;

    private BotClientOptions() {}

    /**
     * The render distance this bot should report: its own override, else the configured global value,
     * lowered to the mass-spawn value when many bots are online, and the vanilla minimum when chunk
     * loading is switched off or the bot's radius is 0.
     */
    public static int effectiveViewDistance(FakePlayer fp, int activeBots) {
        if (!Config.chunkLoadingEnabled()) return MIN_VIEW_DISTANCE;

        int own = fp != null ? fp.getChunkLoadRadius() : -1;
        int distance = own >= 0 ? own : Config.chunkLoadingRadius();
        if (distance == 0) return MIN_VIEW_DISTANCE;

        int threshold = Config.chunkLoadingMassDisableThreshold();
        if (threshold > 0 && activeBots >= threshold) {
            distance = Math.min(distance, Config.chunkLoadingMassViewDistance());
        }
        return Math.max(MIN_VIEW_DISTANCE, Math.min(32, distance));
    }

    /** Builds the information a bot reports, with the given render distance. */
    public static ClientInformation build(int viewDistance) {
        return new ClientInformation(
                Config.clientLanguage(),
                Math.max(MIN_VIEW_DISTANCE, Math.min(32, viewDistance)),
                chatVisibility(),
                true,
                Config.clientSkinParts(),
                Config.clientLeftHanded() ? HumanoidArm.LEFT : HumanoidArm.RIGHT,
                false,
                Config.clientAllowServerListings(),
                particles());
    }

    /** What a bot reports at login (before its own override is known). */
    public static ClientInformation initial() {
        return build(effectiveViewDistance(null, 0));
    }

    private static ChatVisiblity chatVisibility() {
        return switch (Config.clientChatVisibility()) {
            case "system" -> ChatVisiblity.SYSTEM;
            case "hidden" -> ChatVisiblity.HIDDEN;
            default -> ChatVisiblity.FULL;
        };
    }

    private static ParticleStatus particles() {
        return switch (Config.clientParticles().toLowerCase(Locale.ROOT)) {
            case "decreased" -> ParticleStatus.DECREASED;
            case "minimal" -> ParticleStatus.MINIMAL;
            default -> ParticleStatus.ALL;
        };
    }

    /**
     * Sends the bot's options to the server the way its client would, if they differ from what the
     * server currently has.
     *
     * @return true when a packet was sent
     */
    public static boolean sync(ServerPlayer nms, int viewDistance) {
        ClientInformation wanted = build(viewDistance);
        @Nullable ClientInformation current = nms.clientInformation();
        if (current != null && current.equals(wanted)) return false;
        try {
            new ServerboundClientInformationPacket(wanted).handle(nms.connection);
            return true;
        } catch (Throwable t) {
            FppLogger.debug("BotClientOptions.sync failed for " + nms.getScoreboardName() + ": " + t.getMessage());
            return false;
        }
    }
}
