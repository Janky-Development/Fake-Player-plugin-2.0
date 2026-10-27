package me.bill.fakePlayerPlugin.database;

import java.time.Instant;
import java.util.UUID;

import me.bill.fakePlayerPlugin.config.Config;

public class BotRecord {

    private final long id;
    private final String botName;
    private final UUID botUuid;
    private final String spawnedBy;
    private final UUID spawnedByUuid;
    private final String worldName;
    private final double spawnX;
    private final double spawnY;
    private final double spawnZ;
    private final float spawnYaw;
    private final float spawnPitch;
    private String lastWorld;
    private double lastX, lastY, lastZ;
    private float lastYaw, lastPitch;
    private final Instant spawnedAt;
    private Instant removedAt;
    private String removeReason;

    private final String serverId;

    public BotRecord(
            long id,
            String botName,
            UUID botUuid,
            String spawnedBy,
            UUID spawnedByUuid,
            String worldName,
            double spawnX,
            double spawnY,
            double spawnZ,
            float spawnYaw,
            float spawnPitch,
            String lastWorld,
            double lastX,
            double lastY,
            double lastZ,
            float lastYaw,
            float lastPitch,
            Instant spawnedAt,
            Instant removedAt,
            String removeReason,
            String serverId) {
        this.id = id;
        this.botName = botName;
        this.botUuid = botUuid;
        this.spawnedBy = spawnedBy;
        this.spawnedByUuid = spawnedByUuid;
        this.worldName = worldName;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.spawnYaw = spawnYaw;
        this.spawnPitch = spawnPitch;
        this.lastWorld = lastWorld != null ? lastWorld : worldName;
        this.lastX = lastX;
        this.lastY = lastY;
        this.lastZ = lastZ;
        this.lastYaw = lastYaw;
        this.lastPitch = lastPitch;
        this.spawnedAt = spawnedAt;
        this.removedAt = removedAt;
        this.removeReason = removeReason;
        this.serverId = (serverId != null && !serverId.isBlank()) ? serverId : Config.serverId();
    }

    public BotRecord(
            long id,
            String botName,
            UUID botUuid,
            String spawnedBy,
            UUID spawnedByUuid,
            String worldName,
            double spawnX,
            double spawnY,
            double spawnZ,
            float spawnYaw,
            float spawnPitch,
            String lastWorld,
            double lastX,
            double lastY,
            double lastZ,
            float lastYaw,
            float lastPitch,
            Instant spawnedAt,
            Instant removedAt,
            String removeReason) {
        this(
                id,
                botName,
                botUuid,
                spawnedBy,
                spawnedByUuid,
                worldName,
                spawnX,
                spawnY,
                spawnZ,
                spawnYaw,
                spawnPitch,
                lastWorld,
                lastX,
                lastY,
                lastZ,
                lastYaw,
                lastPitch,
                spawnedAt,
                removedAt,
                removeReason,
                Config.serverId());
    }

    public BotRecord(
            long id,
            String botName,
            UUID botUuid,
            String spawnedBy,
            UUID spawnedByUuid,
            String worldName,
            double spawnX,
            double spawnY,
            double spawnZ,
            float spawnYaw,
            float spawnPitch,
            Instant spawnedAt,
            Instant removedAt,
            String removeReason) {
        this(
                id,
                botName,
                botUuid,
                spawnedBy,
                spawnedByUuid,
                worldName,
                spawnX,
                spawnY,
                spawnZ,
                spawnYaw,
                spawnPitch,
                worldName,
                spawnX,
                spawnY,
                spawnZ,
                spawnYaw,
                spawnPitch,
                spawnedAt,
                removedAt,
                removeReason,
                Config.serverId());
    }

    public long getId() {
        return id;
    }

    public String getBotName() {
        return botName;
    }

    public UUID getBotUuid() {
        return botUuid;
    }

    public String getSpawnedBy() {
        return spawnedBy;
    }

    public UUID getSpawnedByUuid() {
        return spawnedByUuid;
    }

    public String getWorldName() {
        return worldName;
    }

    public double getSpawnX() {
        return spawnX;
    }

    public double getSpawnY() {
        return spawnY;
    }

    public double getSpawnZ() {
        return spawnZ;
    }

    public float getSpawnYaw() {
        return spawnYaw;
    }

    public float getSpawnPitch() {
        return spawnPitch;
    }

    public Instant getSpawnedAt() {
        return spawnedAt;
    }

    public String getRemoveReason() {
        return removeReason;
    }

    public boolean isActive() {
        return removedAt == null;
    }

    public String getServerId() {
        return serverId;
    }

    public void setLastLocation(String world, double x, double y, double z, float yaw, float pitch) {
        this.lastWorld = world;
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.lastYaw = yaw;
        this.lastPitch = pitch;
    }

    @Deprecated
    public void setLastLocation(String world, double x, double y, double z) {
        setLastLocation(world, x, y, z, this.lastYaw, this.lastPitch);
    }

    public void setRemovedAt(Instant ts) {
        this.removedAt = ts;
    }

    public void setRemoveReason(String reason) {
        this.removeReason = reason;
    }

    @Override
    public String toString() {
        return "BotRecord{id="
                + id
                + ", bot="
                + botName
                + ", by="
                + spawnedBy
                + ", world="
                + worldName
                + ", server="
                + serverId
                + ", active="
                + isActive()
                + "}";
    }
}
