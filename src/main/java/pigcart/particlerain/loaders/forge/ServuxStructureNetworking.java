//? if forge {
/*package pigcart.particlerain.loaders.forge;

import fi.dy.masa.malilib.network.ClientPacketChannelHandler;
import fi.dy.masa.malilib.network.IPluginChannelHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import pigcart.particlerain.ParticleRain;
import pigcart.particlerain.VersionUtil;
import pigcart.particlerain.config.ServuxStructureCache;

import java.util.List;

public final class ServuxStructureNetworking implements IPluginChannelHandler {
    private static final int STRUCTURE_PACKET_TYPE_METADATA = 1;
    private static final int STRUCTURE_PACKET_TYPE_DATA = 2;
    private static final ResourceLocation CHANNEL = VersionUtil.parseId("servux:structures");
    private static final ServuxStructureNetworking INSTANCE = new ServuxStructureNetworking();

    private static boolean registered;

    private ServuxStructureNetworking() {
    }

    public static void init() {
        registered = false;
    }

    public static void tick(Minecraft client) {
        if (client.level == null || client.getConnection() == null) {
            if (registered) {
                registered = false;
            }
            ServuxStructureCache.clear();
            return;
        }

        if (!registered) {
            ClientPacketChannelHandler.getInstance().unregisterClientChannelHandler(INSTANCE);
            ClientPacketChannelHandler.getInstance().registerClientChannelHandler(INSTANCE);
            registered = true;
            trace("Registered Servux channel handler for " + CHANNEL);
        }

        ServuxStructureCache.prune(client.level.getGameTime());
    }

    @Override
    public List<ResourceLocation> getChannels() {
        ParticleRain.LOGGER.info("ServuxStructureNetworking.getChannels() called. Returning: {}", CHANNEL);
        return List.of(CHANNEL);
    }

    @Override
    public void onPacketReceived(FriendlyByteBuf buf) {
        int packetType = buf.readVarInt();
        int payloadBytes = buf.readableBytes();

        trace(String.format("Servux packet received: channel=%s, type=%s, bytes=%d", CHANNEL, packetTypeName(packetType), payloadBytes));

        if (packetType != STRUCTURE_PACKET_TYPE_METADATA && packetType != STRUCTURE_PACKET_TYPE_DATA) {
            trace("Servux packet ignored: unknown type " + packetType);
            return;
        }

        CompoundTag tag = buf.readNbt();

        if (tag == null) {
            trace("Servux packet " + packetTypeName(packetType) + " did not contain NBT payload");
            return;
        }

        trace(String.format("Servux packet decoded: type=%s, tagKeys=%s", packetTypeName(packetType), tag.getAllKeys()));

        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.level != null) {
                ServuxStructureCache.ingest(tag, client.level.getGameTime());
            }
        });
    }

    @Override
    public boolean usePacketSplitter() {
        return true;
    }

    @Override
    public boolean registerToServer() {
        return true;
    }

    private static String packetTypeName(int packetType) {
        return switch (packetType) {
            case STRUCTURE_PACKET_TYPE_METADATA -> "metadata";
            case STRUCTURE_PACKET_TYPE_DATA -> "data";
            default -> "unknown(" + packetType + ")";
        };
    }

    private static void trace(String message) {
        ParticleRain.LOGGER.warn(message);
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) {
            client.gui.getChat().addMessage(Component.literal(message));
        }
    }
}
*///?}