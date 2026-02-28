package qoby.hotbar_helper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

/**
 * When a block is attacked (left-clicked), switches to the "best" tool in the
 * hotbar.
 * Once per mouse click - does not react to manual tool changes during mining.
 */
public final class HotbarToolSwitch {

    /**
     * Tag for blocks that benefit from Silk Touch. Mods can add blocks via
     * datapacks.
     */
    private static final TagKey<Block> SILK_TOUCH_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("hotbar_helper", "silk_touch_blocks"));

    private static int storedSlotBeforeMining = -1;
    private static boolean wasAttackKeyDownLastTick;

    public static void register(HotbarHelperConfig config) {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!config.autoToolSwitch)
                return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND)
                return InteractionResult.PASS;
            if (player.isSpectator())
                return InteractionResult.PASS;
            if (player.isCreative())
                return InteractionResult.PASS;

            BlockState state = level.getBlockState(pos);
            if (state.isAir())
                return InteractionResult.PASS;

            int bestSlot = findBestToolSlot(player, state);
            if (bestSlot < 0)
                return InteractionResult.PASS;

            int currentSlot = player.getInventory().selected;
            if (bestSlot == currentSlot)
                return InteractionResult.PASS;

            // Check tie-breaking: if current item has same score as best, don't swap
            var currentStack = player.getInventory().getItem(currentSlot);
            var bestStack = player.getInventory().getItem(bestSlot);
            int currentScore = scoreTool(currentStack, state, player.level());
            int bestScore = scoreTool(bestStack, state, player.level());
            if (currentScore == bestScore) {
                return InteractionResult.PASS;
            }

            var mc = Minecraft.getInstance();
            if (player.level().isClientSide() && mc.getConnection() != null) {
                if (storedSlotBeforeMining < 0)
                    storedSlotBeforeMining = currentSlot; // Only store on first switch
                player.getInventory().selected = bestSlot; // Update client immediately so slot display matches
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(bestSlot));
            }
            return InteractionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!config.autoToolSwitch)
                return;
            var player = Minecraft.getInstance().player;
            var connection = Minecraft.getInstance().getConnection();
            if (player == null || connection == null)
                return;

            boolean attackKeyDown = client.options.keyAttack.isDown();
            if (storedSlotBeforeMining >= 0 && wasAttackKeyDownLastTick && !attackKeyDown) {
                player.getInventory().selected = storedSlotBeforeMining; // Restore client slot
                connection.send(new ServerboundSetCarriedItemPacket(storedSlotBeforeMining));
                storedSlotBeforeMining = -1;
            }
            wasAttackKeyDownLastTick = attackKeyDown;
        });
    }

    /**
     * Returns the leftmost hotbar slot with the highest score, or -1 if no valid
     * tool.
     */
    private static int findBestToolSlot(Player player, BlockState blockState) {
        int bestSlot = -1;
        int bestScore = -1_000_000;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            int score = scoreTool(stack, blockState, player.level());
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /**
     * Scores a tool for mining the given block. Higher is better.
     * Returns DEFAULT_SCORE for non-tools or almost-broken tools.
     */
    private static int scoreTool(ItemStack stack, BlockState blockState, Level level) {
        // Empty hand: prefer over useless/almost-broken tools when nothing helps
        if (stack.isEmpty())
            return 0;

        // Durability: almost broken ranks lowest (prefer empty hand over
        // switching to it)
        if (stack.isDamageableItem()) {
            int maxDamage = stack.getMaxDamage();
            int damage = stack.getDamageValue();
            if (maxDamage > 0 && damage >= maxDamage - 5)
                return -3;
        }

        Item item = stack.getItem();
        int toolTypeScore = getToolTypeScore(item, blockState);

        if (toolTypeScore <= 0) {
            // Silk touch on silk-touch block with wrong tool: score above default, below
            // correct tool
            if (blockState.is(SILK_TOUCH_BLOCKS)) {
                var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
                int silkTouch = EnchantmentHelper
                        .getItemEnchantmentLevel(enchantments.getOrThrow(Enchantments.SILK_TOUCH), stack);
                if (silkTouch > 0) {
                    int enchantScore = getEnchantScore(stack, blockState, level);
                    return enchantScore + 1; // Beats default (0), below correct tool (toolTypeScore * 10000)
                }
            }
            // Tool doesn't help and block doesn't need enchant
            if (!blockState.is(ConventionalBlockTags.ORES) && !blockState.is(SILK_TOUCH_BLOCKS)) {
                if (stack.isDamageableItem()) {
                    return -2; // Deprioritize damageable to preserve durability
                }
                return 0; // Non-tools (e.g. dirt block) equal to empty - no reason to switch
            }
            return 0;
        }

        int enchantScore = getEnchantScore(stack, blockState, level);
        return toolTypeScore * 10_000 + enchantScore;
    }

    private static int getToolTypeScore(Item item, BlockState blockState) {
        // Use destroy speed - works with any tool (vanilla and modded) without
        // instanceof checks
        float speed = new ItemStack(item).getDestroySpeed(blockState);
        return speed > 1.0f ? (int) (speed * 2) : 0;
    }

    private static int getEnchantScore(ItemStack stack, BlockState blockState, Level level) {
        int score = 0;
        var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);

        // Fortune: only for ores (ConventionalBlockTags.ORES - mods add their ores
        // here)
        if (blockState.is(ConventionalBlockTags.ORES)) {
            Holder<Enchantment> fortune = enchantments.getOrThrow(Enchantments.FORTUNE);
            score += EnchantmentHelper.getItemEnchantmentLevel(fortune, stack) * 10000;
        }

        // Silk Touch: only for blocks that benefit (tag extensible via datapacks)
        if (blockState.is(SILK_TOUCH_BLOCKS)) {
            Holder<Enchantment> silkTouch = enchantments.getOrThrow(Enchantments.SILK_TOUCH);
            score += EnchantmentHelper.getItemEnchantmentLevel(silkTouch, stack) * 10000;
        }

        Holder<Enchantment> efficiency = enchantments.getOrThrow(Enchantments.EFFICIENCY);
        score += EnchantmentHelper.getItemEnchantmentLevel(efficiency, stack) * 100;

        Holder<Enchantment> mending = enchantments.getOrThrow(Enchantments.MENDING);
        score += EnchantmentHelper.getItemEnchantmentLevel(mending, stack) * 10;

        Holder<Enchantment> unbreaking = enchantments.getOrThrow(Enchantments.UNBREAKING);
        score += EnchantmentHelper.getItemEnchantmentLevel(unbreaking, stack) * 1;

        return score;
    }
}
