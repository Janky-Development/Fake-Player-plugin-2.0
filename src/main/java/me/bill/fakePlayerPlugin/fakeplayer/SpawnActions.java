package me.bill.fakePlayerPlugin.fakeplayer;

import java.util.List;
import java.util.UUID;

/**
 * What {@code /fpp spawn <options> <action…>} queued on a bot: timed command lines that run once, as
 * the person who spawned it, after the bot's body is online. {@code senderUuid} is ignored for console.
 */
public record SpawnActions(UUID senderUuid, boolean console, long initialDelayTicks, List<Step> steps) {

    /** Metadata key under which a bot carries its pending actions. */
    public static final String KEY = "fpp.spawn-actions";

    /** One command line and how many ticks after the previous step it should run. */
    public record Step(long delayTicks, String commandLine) {}
}
