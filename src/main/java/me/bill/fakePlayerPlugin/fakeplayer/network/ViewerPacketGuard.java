package me.bill.fakePlayerPlugin.fakeplayer.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import me.bill.fakePlayerPlugin.util.FppLogger;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Outbound guard on a real viewer's connection that keeps the client's scoreboard-team state
 * consistent with the packets actually sent to it.
 *
 * <p>FPP pushes its own "hidden name" team to clients by hand (it is never registered on the real
 * server scoreboard). A bot can therefore be on team A according to the server (another plugin's
 * prefix/AFK/nametag team) while the client has moved it onto FPP's team. When that other plugin
 * later sends "remove entry from team A", the vanilla client's
 * {@code Scoreboard#removePlayerFromTeam} throws {@code IllegalStateException} and the client is
 * disconnected with a Network Protocol Error. The same happens in reverse for FPP's own REMOVE, and
 * for a duplicate "create team" packet.
 *
 * <p>This handler mirrors, per connection, which team each entry is on from the client's point of
 * view and rewrites/drops any team packet the client would reject. It never alters packets the
 * client would accept, so it is transparent for everything else.
 *
 * <p>All state is touched only from the channel's event loop, so no synchronisation is required.
 */
public final class ViewerPacketGuard extends ChannelOutboundHandlerAdapter {

    private static final String HANDLER_NAME = "fpp_team_guard";

    /** Teams the client currently knows (only those created through this connection after install). */
    private final Set<String> knownTeams = new HashSet<>();

    /** Entry (player name) -> team it is on, as far as packets sent after install tell us. */
    private final Map<String, String> entryTeam = new HashMap<>();

    private final Scoreboard dummyBoard = new Scoreboard();

    private static volatile BiFunction<UUID, String, String> botNameResolver = (uuid, name) -> null;
    private static volatile BooleanSupplier tabListEnabled = () -> false;
    private static volatile UnaryOperator<String> tabListNamer = n -> n;

    /**
     * Wires the tab-list policy. {@code botNameResolver} maps (profile uuid, profile name) to the
     * bot's name, or {@code null} for a real player. Every player-info entry of a bot is rewritten on
     * its way to a viewer: never listed unless {@code tabListEnabled}, and then only under
     * {@code tabListNamer(botName)} - so no code path (the server's own join sync, another plugin, a
     * late packet) can put a bot in the tab list under a bare, player-looking name.
     */
    public static void configureTab(
            BiFunction<UUID, String, String> botNameResolver,
            BooleanSupplier tabListEnabled,
            UnaryOperator<String> tabListNamer) {
        ViewerPacketGuard.botNameResolver = botNameResolver;
        ViewerPacketGuard.tabListEnabled = tabListEnabled;
        ViewerPacketGuard.tabListNamer = tabListNamer;
    }

    private static volatile PlayerTeam protectedTeam;
    private static volatile Set<String> protectedEntries = Set.of();

    /**
     * Registers the hide-name team and the live set of bot names that must stay on it. Any packet
     * that would move one of those entries onto another team is followed by a re-add, so the
     * client's vanilla name tag for a bot can never become visible - no matter which plugin
     * (TAB, LuckPerms prefixes, AFK displays, ...) manages scoreboard teams.
     */
    public static void protect(PlayerTeam team, Set<String> entries) {
        protectedTeam = team;
        protectedEntries = entries;
    }

    /** Installs the guard on a real player's channel; safe to call repeatedly. */
    public static boolean install(Channel channel) {
        if (channel == null || channel instanceof FakeChannel) return false;
        try {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) return true;
            ViewerPacketGuard guard = new ViewerPacketGuard();
            if (pipeline.get("unbundler") != null) {
                // Placed after the unbundler (in outbound order) so bundles are already split into
                // individual packets by the time we see them.
                pipeline.addBefore("unbundler", HANDLER_NAME, guard);
            } else if (pipeline.get("encoder") != null) {
                pipeline.addBefore("encoder", HANDLER_NAME, guard);
            } else {
                return false;
            }
            return true;
        } catch (Throwable t) {
            FppLogger.debug("ViewerPacketGuard.install failed: " + t);
            return false;
        }
    }

    private static ClientboundPlayerInfoUpdatePacket rewriteInfo(ClientboundPlayerInfoUpdatePacket p) {
        List<ClientboundPlayerInfoUpdatePacket.Entry> in = p.entries();
        List<ClientboundPlayerInfoUpdatePacket.Entry> out = null;
        boolean show = tabListEnabled.getAsBoolean();
        for (int i = 0; i < in.size(); i++) {
            ClientboundPlayerInfoUpdatePacket.Entry e = in.get(i);
            String pname = e.profile() != null ? e.profile().name() : null;
            String bot = botNameResolver.apply(e.profileId(), pname);
            if (bot == null) continue;
            Component display = show ? Component.literal(tabListNamer.apply(bot)) : e.displayName();
            if (e.listed() == show && java.util.Objects.equals(display, e.displayName())) continue;
            if (out == null) out = new ArrayList<>(in);
            out.set(
                    i,
                    new ClientboundPlayerInfoUpdatePacket.Entry(
                            e.profileId(),
                            e.profile(),
                            show,
                            e.latency(),
                            e.gameMode(),
                            display,
                            e.showHat(),
                            e.listOrder(),
                            e.chatSession()));
        }
        return out == null ? p : new ClientboundPlayerInfoUpdatePacket(p.actions(), out);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ClientboundPlayerInfoUpdatePacket info) {
            try {
                msg = rewriteInfo(info);
            } catch (Throwable t) {
                FppLogger.debug("ViewerPacketGuard info rewrite failed: " + t);
            }
            ctx.write(msg, promise);
            return;
        }
        if (msg instanceof ClientboundSetPlayerTeamPacket packet) {
            Object out;
            List<ClientboundSetPlayerTeamPacket> followUps = new ArrayList<>();
            try {
                out = filter(packet, followUps);
            } catch (Throwable t) {
                out = packet;
            }
            if (out == null) {
                promise.trySuccess();
            } else {
                ctx.write(out, promise);
            }
            // Written right behind the packet, so the client never renders a frame in between.
            for (ClientboundSetPlayerTeamPacket f : followUps) ctx.write(f);
            return;
        }
        ctx.write(msg, promise);
    }

    /**
     * Queues a re-add to the hide team for every protected entry among {@code entries} that the
     * packet being forwarded is about to move onto {@code team}.
     */
    private void protect(String team, java.util.Collection<String> entries, List<ClientboundSetPlayerTeamPacket> out) {
        PlayerTeam hidden = protectedTeam;
        // Only once this viewer has actually been sent the hide team; otherwise the client would
        // ignore the re-add and our map would wrongly claim the entry is on it.
        if (hidden == null || team.equals(hidden.getName()) || !knownTeams.contains(hidden.getName())) return;
        Set<String> guarded = protectedEntries;
        for (String e : entries) {
            if (!guarded.contains(e)) continue;
            out.add(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                    hidden, e, ClientboundSetPlayerTeamPacket.Action.ADD));
            entryTeam.put(e, hidden.getName());
        }
    }

    /** @return the packet to forward, a replacement, or {@code null} to drop it. */
    private Object filter(ClientboundSetPlayerTeamPacket p, List<ClientboundSetPlayerTeamPacket> followUps) {
        String team = p.getName();

        ClientboundSetPlayerTeamPacket.Action teamAction = p.getTeamAction();
        if (teamAction == ClientboundSetPlayerTeamPacket.Action.ADD) {
            // Creating a team the client already has makes it throw ("team already exists").
            if (!knownTeams.add(team)) return null;
            for (String e : p.getPlayers()) entryTeam.put(e, team);
            protect(team, p.getPlayers(), followUps);
            return p;
        }
        if (teamAction == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            knownTeams.remove(team);
            entryTeam.values().removeIf(team::equals);
            return p;
        }

        ClientboundSetPlayerTeamPacket.Action playerAction = p.getPlayerAction();
        if (playerAction == ClientboundSetPlayerTeamPacket.Action.ADD) {
            // FPP re-asserts the hide membership on a steady cadence; skip the no-op repeats.
            boolean allAlready = !p.getPlayers().isEmpty();
            for (String e : p.getPlayers()) {
                if (!team.equals(entryTeam.get(e))) {
                    allAlready = false;
                    break;
                }
            }
            if (allAlready) return null;
            for (String e : p.getPlayers()) entryTeam.put(e, team);
            protect(team, p.getPlayers(), followUps);
            return p;
        }
        if (playerAction == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            List<String> valid = new ArrayList<>();
            for (String e : p.getPlayers()) {
                String current = entryTeam.get(e);
                // Unknown entry: it was placed by a pre-install server packet, so the client agrees
                // with the server and the removal is legitimate. Known to be elsewhere: illegal.
                boolean ours = protectedTeam != null && team.equals(protectedTeam.getName());
                // FPP's own team is tracked exactly (every add passed through here), so an unknown
                // entry there is simply not a member and must not be removed.
                if (current == null ? !ours : current.equals(team)) valid.add(e);
            }
            if (valid.size() == p.getPlayers().size()) {
                for (String e : valid) entryTeam.remove(e);
                return p;
            }
            if (valid.isEmpty()) return null;
            for (String e : valid) entryTeam.remove(e);
            return ClientboundSetPlayerTeamPacket.createMultiplePlayerPacket(
                    new PlayerTeam(dummyBoard, team), valid, ClientboundSetPlayerTeamPacket.Action.REMOVE);
        }
        return p;
    }
}
