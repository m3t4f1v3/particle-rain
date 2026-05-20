package pigcart.particlerain.config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import pigcart.particlerain.VersionUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ServuxStructureCache {
    private static final long STALE_AFTER_TICKS = 20L * 60L * 5L;
    private static final long DEBUG_PRINT_INTERVAL = 20L;
    private static final Map<ResourceLocation, StructureRecord> structures = new HashMap<>();
    private static String lastDebugKey = "";
    private static long lastDebugTick = Long.MIN_VALUE;

    private ServuxStructureCache() {
    }

    public static synchronized void clear() {
        structures.clear();
    }

    public static synchronized void prune(long gameTime) {
        structures.values().removeIf(record -> gameTime - record.lastSeenTick > STALE_AFTER_TICKS);
    }

    public static synchronized void ingest(CompoundTag tag, long gameTime) {
        ListTag list = tag.getList("Structures", Tag.TAG_COMPOUND);
        int ingested = 0;
        StructureRecord firstRecord = null;

        for (Tag element : list) {
            if (element instanceof CompoundTag structureTag) {
                StructureRecord record = readStructure(structureTag, gameTime);

                if (record != null) {
                    structures.put(record.id, record);
                    ingested++;

                    if (firstRecord == null) {
                        firstRecord = record;
                    }
                }
            }
        }

        String message = firstRecord == null
                ? "ParticleRain Servux cache: loaded 0 structures"
                : String.format("ParticleRain Servux cache: loaded %d structures, first=%s %s", ingested, firstRecord.id, firstRecord.box);
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal(message));
        prune(gameTime);
    }

    public static synchronized boolean allows(BlockPos pos, Whitelist.StructureList list, net.minecraft.client.multiplayer.ClientLevel level) {
        if (list.getEntries().isEmpty()) {
            return true;
        }

        List<ResourceLocation> matchingStructures = getMatchingStructures(pos);

        if (matchingStructures.isEmpty()) {
            return !list.isWhitelist;
        }

        for (ResourceLocation id : matchingStructures) {
            Holder<Structure> holder = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                    .getHolder(ResourceKey.create(Registries.STRUCTURE, id))
                    .orElse(null);

            if (holder != null && list.contains(holder)) {
                return list.isWhitelist;
            }
        }

        return !list.isWhitelist;
    }

    public static synchronized void debugCurrentStructure(ClientLevel level, BlockPos pos) {
        List<ResourceLocation> matchingStructures = getMatchingStructures(pos);
        String debugKey = matchingStructures.isEmpty()
                ? "none"
                : String.join(", ", matchingStructures.stream().map(ResourceLocation::toString).toList());

        long gameTime = level.getGameTime();
        if (debugKey.equals(lastDebugKey) && gameTime - lastDebugTick < DEBUG_PRINT_INTERVAL) {
            return;
        }

        lastDebugKey = debugKey;
        lastDebugTick = gameTime;

        String message = matchingStructures.isEmpty()
                ? String.format("ParticleRain structure debug: no tracked structure at %s", pos.toShortString())
                : String.format("ParticleRain structure debug: %s at %s", debugKey, pos.toShortString());
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal(message));
    }

    private static List<ResourceLocation> getMatchingStructures(BlockPos pos) {
        List<ResourceLocation> matchingStructures = new ArrayList<>();

        for (StructureRecord record : structures.values()) {
            if (record.box.isInside(pos)) {
                matchingStructures.add(record.id);
            }
        }

        matchingStructures.sort(Comparator.comparing(ResourceLocation::toString));
        return matchingStructures;
    }

    private static StructureRecord readStructure(CompoundTag tag, long gameTime) {
        ResourceLocation id = readId(tag);

        if (id == null) {
            return null;
        }

        BoundingBox box = readBox(tag);

        if (box == null) {
            return null;
        }

        return new StructureRecord(id, box, gameTime);
    }

    private static ResourceLocation readId(CompoundTag tag) {
        List<String> keys = List.of("id", "structure", "structure_id", "structureId", "name", "Name");

        for (String key : keys) {
            String value = tag.getString(key);

            if (value.isEmpty() == false) {
                ResourceLocation id = VersionUtil.parseId(value);

                if (id != null) {
                    return id;
                }
            }
        }

        return null;
    }

    private static BoundingBox readBox(CompoundTag tag) {
        BoundingBox direct = readBoxFromCompound(tag);

        if (direct != null) {
            return direct;
        }

        for (String key : tag.getAllKeys()) {
            Tag value = tag.get(key);
            BoundingBox nested = readBox(value);

            if (nested != null) {
                return nested;
            }
        }

        return null;
    }

    private static BoundingBox readBox(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            return readBoxFromCompound(compound);
        }

        if (tag instanceof ListTag list) {
            for (Tag element : list) {
                BoundingBox box = readBox(element);

                if (box != null) {
                    return box;
                }
            }
        }

        return null;
    }

    private static BoundingBox readBoxFromCompound(CompoundTag tag) {
        if (tag.contains("BB", Tag.TAG_INT_ARRAY)) {
            int[] box = tag.getIntArray("BB");

            if (box.length == 6) {
                return new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5]);
            }
        }

        if (tag.contains("bounding_box", Tag.TAG_INT_ARRAY)) {
            int[] box = tag.getIntArray("bounding_box");

            if (box.length == 6) {
                return new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5]);
            }
        }

        if (tag.contains("box", Tag.TAG_INT_ARRAY)) {
            int[] box = tag.getIntArray("box");

            if (box.length == 6) {
                return new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5]);
            }
        }

        return null;
    }

    private record StructureRecord(ResourceLocation id, BoundingBox box, long lastSeenTick) {
    }
}