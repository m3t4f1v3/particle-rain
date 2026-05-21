//? if forge {
/*package pigcart.particlerain.loaders.forge;

import fi.dy.masa.malilib.network.ClientPacketChannelHandler;
import fi.dy.masa.malilib.network.IPluginChannelHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
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
            registered = false;
            ServuxStructureCache.clear();
            return;
        }

        if (client.hasSingleplayerServer()) {
            registered = false;
            ServuxStructureCache.updateFromIntegratedServer(client);
            return;
        }

        if (!registered) {
            ClientPacketChannelHandler.getInstance().registerClientChannelHandler(INSTANCE);
            registered = true;
        }

        ServuxStructureCache.prune(client.level.getGameTime());
    }

    @Override
    public List<ResourceLocation> getChannels() {
        return List.of(CHANNEL);
    }

    @Override
    public void onPacketReceived(FriendlyByteBuf buf) {
        int packetType = buf.readVarInt();

        if (packetType != STRUCTURE_PACKET_TYPE_METADATA && packetType != STRUCTURE_PACKET_TYPE_DATA) {
            return;
        }

        CompoundTag tag = buf.readNbt();

        if (tag == null) {
            return;
        }

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

}
*///?}