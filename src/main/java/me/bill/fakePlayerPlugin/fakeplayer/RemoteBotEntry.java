package me.bill.fakePlayerPlugin.fakeplayer;

import java.util.UUID;

public record RemoteBotEntry(
        String serverId,
        UUID uuid,
        String name,
        String displayName,
        String packetProfileName,
        String skinValue,
        String skinSignature,
        int ping) {


}
