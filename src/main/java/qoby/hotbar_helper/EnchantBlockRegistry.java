package qoby.hotbar_helper;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side registry that builds merged sets of blocks benefiting from
 * Fortune
 * and Silk Touch. Combines our datapack tags with optional convention tags
 * (#c:ores, #c:clusters, etc.) and with blocks detected from loot table
 * analysis.
 */
public final class EnchantBlockRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnchantBlockRegistry.class);

    private static final TagKey<Block> FORTUNE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("hotbar_helper", "fortune_blocks"));
    private static final TagKey<Block> SILK_TOUCH_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("hotbar_helper", "silk_touch_blocks"));

    /**
     * Optional tags from Fabric/mod conventions – if present, blocks are merged in.
     */
    private static final TagKey<Block> C_ORES = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "ores"));
    private static final TagKey<Block> C_CLUSTERS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "clusters"));
    private static final TagKey<Block> C_GLASS_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "glass_blocks"));
    private static final TagKey<Block> C_GLASS_PANES = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "glass_panes"));
    private static final TagKey<Block> C_BUDDING_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "budding_blocks"));

    /**
     * Merged block sets: our tags + optional convention tags + loot-detected
     * blocks.
     */
    private static final Set<Block> FORTUNE_BLOCKS_SET = ConcurrentHashMap.newKeySet();
    private static final Set<Block> SILK_TOUCH_BLOCKS_SET = ConcurrentHashMap.newKeySet();

    /** Block IDs for syncing to client (updated by server after rebuild). */
    private static volatile List<ResourceLocation> syncedFortuneBlocks = List.of();
    private static volatile List<ResourceLocation> syncedSilkTouchBlocks = List.of();

    public static void register() {
        ServerWorldEvents.LOAD.register((server, level) -> {
            if (level.dimension() != Level.OVERWORLD) return;
            rebuild(server);
        });
    }

    /** Rebuilds block lists. Call before sending sync to ensure data is ready. */
    public static void ensureBuilt(MinecraftServer server) {
        rebuild(server);
    }

    /**
     * Rebuilds the fortune and silk touch block sets from tags and loot table
     * analysis. Safe to call from server thread.
     */
    public static void rebuild(MinecraftServer server) {
        var blockReg = server.registryAccess().registryOrThrow(Registries.BLOCK);

        Set<Block> fortune = new HashSet<>();
        Set<Block> silkTouch = new HashSet<>();

        addBlocksFromTag(blockReg, FORTUNE_BLOCKS, fortune);
        addBlocksFromTag(blockReg, C_ORES, fortune);
        addBlocksFromTag(blockReg, SILK_TOUCH_BLOCKS, silkTouch);
        addBlocksFromTag(blockReg, C_CLUSTERS, silkTouch);
        addBlocksFromTag(blockReg, C_GLASS_BLOCKS, silkTouch);
        addBlocksFromTag(blockReg, C_GLASS_PANES, silkTouch);
        addBlocksFromTag(blockReg, C_BUDDING_BLOCKS, silkTouch);

        ServerLevel overworld = server.overworld();
        if (overworld != null) {
            int fortuneAdded = scanLootForMissingBlocks(overworld, fortune, silkTouch);
            if (fortuneAdded > 0) {
                LOGGER.info("EnchantBlockRegistry: Added {} blocks from loot analysis to fortune/silk touch lists",
                        fortuneAdded);
            }
        }

        FORTUNE_BLOCKS_SET.clear();
        FORTUNE_BLOCKS_SET.addAll(fortune);
        SILK_TOUCH_BLOCKS_SET.clear();
        SILK_TOUCH_BLOCKS_SET.addAll(silkTouch);

        syncedFortuneBlocks = fortune.stream()
                .map(blockReg::getResourceKey)
                .filter(java.util.Optional::isPresent)
                .map(o -> o.orElseThrow().location())
                .toList();
        syncedSilkTouchBlocks = silkTouch.stream()
                .map(blockReg::getResourceKey)
                .filter(java.util.Optional::isPresent)
                .map(o -> o.orElseThrow().location())
                .toList();
    }

    /** Returns the block ID lists for syncing to clients. Called on server. */
    public static List<ResourceLocation> getSyncedFortuneBlocks() {
        return syncedFortuneBlocks;
    }

    public static List<ResourceLocation> getSyncedSilkTouchBlocks() {
        return syncedSilkTouchBlocks;
    }

    /**
     * Applies synced block lists from the server. Called on client when packet
     * is received. If payload is empty, skip to avoid clearing valid data.
     */
    public static void applySyncedBlocks(net.minecraft.core.RegistryAccess registryAccess,
            List<ResourceLocation> fortuneIds, List<ResourceLocation> silkTouchIds) {
        if (fortuneIds.isEmpty() && silkTouchIds.isEmpty()) return;
        var blockReg = registryAccess.registryOrThrow(Registries.BLOCK);
        FORTUNE_BLOCKS_SET.clear();
        SILK_TOUCH_BLOCKS_SET.clear();
        for (ResourceLocation id : fortuneIds) {
            blockReg.getOptional(net.minecraft.resources.ResourceKey.create(Registries.BLOCK, id))
                    .ifPresent(FORTUNE_BLOCKS_SET::add);
        }
        for (ResourceLocation id : silkTouchIds) {
            blockReg.getOptional(net.minecraft.resources.ResourceKey.create(Registries.BLOCK, id))
                    .ifPresent(SILK_TOUCH_BLOCKS_SET::add);
        }
    }

    /**
     * Client-side fallback: load block lists from tags when sets are empty.
     * Ensures fortune/silk-touch detection works before server sync arrives.
     */
    public static void loadFromTagsIfEmpty(net.minecraft.core.RegistryAccess registryAccess) {
        if (!FORTUNE_BLOCKS_SET.isEmpty() && !SILK_TOUCH_BLOCKS_SET.isEmpty()) return;
        var blockReg = registryAccess.registryOrThrow(Registries.BLOCK);
        if (FORTUNE_BLOCKS_SET.isEmpty()) {
            addBlocksFromTag(blockReg, FORTUNE_BLOCKS, FORTUNE_BLOCKS_SET);
            addBlocksFromTag(blockReg, C_ORES, FORTUNE_BLOCKS_SET);
        }
        if (SILK_TOUCH_BLOCKS_SET.isEmpty()) {
            addBlocksFromTag(blockReg, SILK_TOUCH_BLOCKS, SILK_TOUCH_BLOCKS_SET);
            addBlocksFromTag(blockReg, C_CLUSTERS, SILK_TOUCH_BLOCKS_SET);
            addBlocksFromTag(blockReg, C_GLASS_BLOCKS, SILK_TOUCH_BLOCKS_SET);
            addBlocksFromTag(blockReg, C_GLASS_PANES, SILK_TOUCH_BLOCKS_SET);
            addBlocksFromTag(blockReg, C_BUDDING_BLOCKS, SILK_TOUCH_BLOCKS_SET);
        }
    }

    private static void addBlocksFromTag(net.minecraft.core.Registry<Block> blockReg, TagKey<Block> tag,
            Set<Block> out) {
        blockReg.getTagOrEmpty(tag).forEach(holder -> out.add(holder.value()));
    }

    /**
     * Scans block loot tables and adds blocks whose drops change with Fortune or
     * Silk Touch but are not already in the tag-based sets.
     */
    private static int scanLootForMissingBlocks(ServerLevel level, Set<Block> fortune, Set<Block> silkTouch) {
        int added = 0;
        var blockReg = level.registryAccess().registryOrThrow(Registries.BLOCK);

        ItemStack plainTool = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack fortuneTool = new ItemStack(Items.DIAMOND_PICKAXE);
        fortuneTool.enchant(
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE), 3);
        ItemStack silkTouchTool = new ItemStack(Items.DIAMOND_PICKAXE);
        silkTouchTool.enchant(
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1);

        var blockPos = level.getSharedSpawnPos();

        for (var entry : blockReg.entrySet()) {
            Block block = entry.getValue();
            BlockState state = block.defaultBlockState();

            if (state.isAir())
                continue;

            var lootKey = block.getLootTable();
            if (lootKey == null || lootKey.location().getPath().equals("empty"))
                continue;

            try {
                List<ItemStack> plain = getDrops(level, state, blockPos, plainTool);
                List<ItemStack> withFortune = getDrops(level, state, blockPos, fortuneTool);
                List<ItemStack> withSilkTouch = getDrops(level, state, blockPos, silkTouchTool);

                if (!fortune.contains(block) && dropsDiffer(plain, withFortune)) {
                    fortune.add(block);
                    added++;
                }
                if (!silkTouch.contains(block) && dropsDiffer(plain, withSilkTouch)) {
                    silkTouch.add(block);
                    added++;
                }
            } catch (Exception e) {
                // Skip blocks that fail (e.g. need block entity data)
            }
        }
        return added;
    }

    private static List<ItemStack> getDrops(ServerLevel level, BlockState state, net.minecraft.core.BlockPos pos,
            ItemStack tool) {
        LootParams.Builder builder = new LootParams.Builder(level)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                        Vec3.atCenterOf(pos))
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_STATE, state)
                .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL, tool);
        return state.getDrops(builder);
    }

    private static boolean dropsDiffer(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size())
            return true;
        int totalA = a.stream().mapToInt(ItemStack::getCount).sum();
        int totalB = b.stream().mapToInt(ItemStack::getCount).sum();
        if (totalA != totalB)
            return true;
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.isSameItemSameComponents(a.get(i), b.get(i)))
                return true;
        }
        return false;
    }

    public static boolean isFortuneBlock(BlockState state) {
        Block block = state.getBlock();
        if (FORTUNE_BLOCKS_SET.contains(block))
            return true;
        return state.is(FORTUNE_BLOCKS) || state.is(C_ORES);
    }

    public static boolean isSilkTouchBlock(BlockState state) {
        Block block = state.getBlock();
        if (SILK_TOUCH_BLOCKS_SET.contains(block))
            return true;
        return state.is(SILK_TOUCH_BLOCKS) || state.is(C_CLUSTERS) || state.is(C_GLASS_BLOCKS)
                || state.is(C_GLASS_PANES) || state.is(C_BUDDING_BLOCKS);
    }
}
