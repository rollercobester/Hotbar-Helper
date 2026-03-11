package qoby.hotbar_helper.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import qoby.hotbar_helper.HotbarHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client payload that syncs the computed fortune and silk touch
 * block lists (from loot table analysis) to the client for multiplayer.
 */
public record EnchantBlocksSyncPayload(List<ResourceLocation> fortuneBlocks, List<ResourceLocation> silkTouchBlocks)
                implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<EnchantBlocksSyncPayload> TYPE = new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath(HotbarHelper.MOD_ID, "enchant_blocks_sync"));

        public static final StreamCodec<FriendlyByteBuf, EnchantBlocksSyncPayload> STREAM_CODEC = StreamCodec.composite(
                        ByteBufCodecs.collection(ArrayList::new, ResourceLocation.STREAM_CODEC, 4096),
                        EnchantBlocksSyncPayload::fortuneBlocks,
                        ByteBufCodecs.collection(ArrayList::new, ResourceLocation.STREAM_CODEC, 4096),
                        EnchantBlocksSyncPayload::silkTouchBlocks,
                        EnchantBlocksSyncPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
                return TYPE;
        }
}
