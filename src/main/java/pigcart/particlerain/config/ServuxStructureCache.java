package pigcart.particlerain.config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.StructureAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import pigcart.particlerain.VersionUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ServuxStructureCache {
    private static final long STALE_AFTER_TICKS = 20L * 60L * 5L;
    private static final long DEBUG_PRINT_INTERVAL = 20L;
    private static final Map<ResourceLocation, StructureRecord> structures = new HashMap<>();
    private static String lastDebugKey = "";
    private static long lastDebugTick = Long.MIN_VALUE;
    private static BlockPos lastIntegratedUpdatePos = null;

    private ServuxStructureCache() {
    }

    public static synchronized void clear() {
        structures.clear();
    }

    public static synchronized void prune(long gameTime) {
        structures.values().removeIf(record -> gameTime - record.lastSeenTick > STALE_AFTER_TICKS);
    }

    public static synchronized void updateFromIntegratedServer(Minecraft client) {
        if (client.level == null || client.player == null || !client.hasSingleplayerServer()) {
            return;
        }

        long gameTime = client.level.getGameTime();
        if ((gameTime % 20L) != 0L) {
            return;
        }

        BlockPos playerPos = BlockPos.containing(client.player.position());

        if (!needsIntegratedRefresh(playerPos)) {
            return;
        }

        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            clear();
            lastIntegratedUpdatePos = null;
            return;
        }

        ServerLevel world = server.getLevel(client.level.dimension());
        if (world == null) {
            clear();
            lastIntegratedUpdatePos = null;
            return;
        }

        int maxRange = client.options.getEffectiveRenderDistance() + 2;
        refreshFromIntegratedServer(world, playerPos, maxRange, gameTime);
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
            : String.format("ParticleRain Servux cache: loaded %d structures, first=%s %s (%d pieces)", ingested, firstRecord.id, firstRecord.box(), firstRecord.boxes.size());
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal(message));
        prune(gameTime);
    }

    private static synchronized void refreshFromIntegratedServer(ServerLevel world, BlockPos playerPos, int maxRange, long gameTime) {
        structures.clear();

        int minCX = (playerPos.getX() >> 4) - maxRange;
        int minCZ = (playerPos.getZ() >> 4) - maxRange;
        int maxCX = (playerPos.getX() >> 4) + maxRange;
        int maxCZ = (playerPos.getZ() >> 4) + maxRange;

        for (int cz = minCZ; cz <= maxCZ; ++cz) {
            for (int cx = minCX; cx <= maxCX; ++cx) {
                ChunkAccess chunk;
                try {
                    chunk = world.getChunk(cx, cz, ChunkStatus.STRUCTURE_REFERENCES, false);
                } catch (Exception ignored) {
                    continue;
                }

                if (chunk == null) {
                    continue;
                }

                Map<Structure, ?> references = ((StructureAccess) chunk).getAllReferences();
                for (Structure structure : references.keySet()) {
                    ResourceLocation id = world.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(structure);
                    StructureStart start = ((StructureAccess) chunk).getStartForStructure(structure);
                    StructureRecord record = readStructure(id, start, gameTime);

                    if (record != null) {
                        structures.put(record.id, record);
                    }
                }
            }
        }

        lastIntegratedUpdatePos = playerPos;
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
            // Check direct ResourceLocation IDs first. 
            // This is safer on the client where the structure registry is often missing.
            if (list.ids.contains(id)) {
                return list.isWhitelist;
            }

            // Safe registry lookup for tag support (#tag). 
            // Using .registry() avoids IllegalStateException if the registry is missing.
            Optional<? extends Registry<Structure>> registryOpt = level.registryAccess().registry(Registries.STRUCTURE);
            if (registryOpt.isPresent()) {
                Holder<Structure> holder = registryOpt.get().getHolder(ResourceKey.create(Registries.STRUCTURE, id)).orElse(null);
                if (holder != null && list.contains(holder)) {
                    return list.isWhitelist;
                }
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
            if (record.contains(pos)) {
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

        List<BoundingBox> boxes = readBoxes(tag);

        if (boxes.isEmpty()) {
            return null;
        }

        return new StructureRecord(id, boxes, gameTime);
    }

    private static StructureRecord readStructure(ResourceLocation id, StructureStart start, long gameTime) {
        if (id == null || start == null) {
            return null;
        }

        List<BoundingBox> boxes = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox box = piece.getBoundingBox();
            if (box != null) {
                boxes.add(box);
            }
        }

        if (boxes.isEmpty()) {
            return null;
        }

        return new StructureRecord(id, boxes, gameTime);
    }

    private static List<BoundingBox> readBoxes(CompoundTag tag) {
        List<BoundingBox> boxes = new ArrayList<>();

        ListTag children = tag.getList("Children", Tag.TAG_COMPOUND);
        for (Tag element : children) {
            if (element instanceof CompoundTag pieceTag) {
                BoundingBox box = readBoxFromCompound(pieceTag);
                if (box != null) {
                    boxes.add(box);
                }
            }
        }

        if (boxes.isEmpty()) {
            BoundingBox box = readBox(tag);
            if (box != null) {
                boxes.add(box);
            }
        }

        return boxes;
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

    private record StructureRecord(ResourceLocation id, List<BoundingBox> boxes, long lastSeenTick) {
        private BoundingBox box() {
            BoundingBox first = boxes.get(0);
            int minX = first.minX();
            int minY = first.minY();
            int minZ = first.minZ();
            int maxX = first.maxX();
            int maxY = first.maxY();
            int maxZ = first.maxZ();

            for (int i = 1; i < boxes.size(); ++i) {
                BoundingBox box = boxes.get(i);
                minX = Math.min(minX, box.minX());
                minY = Math.min(minY, box.minY());
                minZ = Math.min(minZ, box.minZ());
                maxX = Math.max(maxX, box.maxX());
                maxY = Math.max(maxY, box.maxY());
                maxZ = Math.max(maxZ, box.maxZ());
            }

            return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private boolean contains(BlockPos pos) {
            for (BoundingBox box : boxes) {
                if (box.isInside(pos)) {
                    return true;
                }
            }

            return false;
        }
    }

    private static boolean needsIntegratedRefresh(BlockPos playerPos) {
        if (lastIntegratedUpdatePos == null) {
            return true;
        }

        return Math.abs(playerPos.getX() - lastIntegratedUpdatePos.getX()) >= 16
                || Math.abs(playerPos.getY() - lastIntegratedUpdatePos.getY()) >= 16
                || Math.abs(playerPos.getZ() - lastIntegratedUpdatePos.getZ()) >= 16;
    }
}