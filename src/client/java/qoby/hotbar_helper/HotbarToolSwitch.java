package qoby.hotbar_helper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

/**
 * When a block is attacked (left-clicked), switches to the "best" tool in the
 * hotbar.
 * Once per mouse click - does not react to manual tool changes during mining.
 */
public final class HotbarToolSwitch {

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

            // Tie-breaking: when scores equal, prefer switching to empty/non-damageable to save durability
            var currentStack = player.getInventory().getItem(currentSlot);
            var bestStack = player.getInventory().getItem(bestSlot);
            int currentScore = scoreTool(currentStack, state, player.level());
            int bestScore = scoreTool(bestStack, state, player.level());
            if (currentScore == bestScore) {
                // Only stay if switching wouldn't save durability
                boolean bestSavesDurability = bestStack.isEmpty() || !bestStack.isDamageableItem();
                boolean currentConsumesDurability = currentStack.isDamageableItem();
                if (!(bestSavesDurability && currentConsumesDurability))
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
            if (EnchantBlockRegistry.isSilkTouchBlock(blockState)) {
                var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
                int silkTouch = EnchantmentHelper
                        .getItemEnchantmentLevel(enchantments.getOrThrow(Enchantments.SILK_TOUCH), stack);
                if (silkTouch > 0) {
                    int enchantScore = getEnchantScore(stack, blockState, level);
                    return enchantScore + 1; // Beats default (0), below correct tool (toolTypeScore * 10000)
                }
            }
            // Fortune on fortune block (e.g. crops) with instant-break: getDestroySpeed <= 1 so
            // toolTypeScore is 0, but fortune hoe should still beat empty hand
            if (EnchantBlockRegistry.isFortuneBlock(blockState)) {
                var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
                int fortune = EnchantmentHelper
                        .getItemEnchantmentLevel(enchantments.getOrThrow(Enchantments.FORTUNE), stack);
                if (fortune > 0) {
                    int enchantScore = getEnchantScore(stack, blockState, level);
                    return enchantScore + 1; // Beats default (0)
                }
            }
            // Tool doesn't help: block needs no enchant, or needs enchant we don't have
            boolean blockNeedsFortune = EnchantBlockRegistry.isFortuneBlock(blockState);
            boolean blockNeedsSilkTouch = EnchantBlockRegistry.isSilkTouchBlock(blockState);
            boolean hasRelevantEnchant = (blockNeedsFortune && EnchantmentHelper.getItemEnchantmentLevel(
                    level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE), stack) > 0)
                    || (blockNeedsSilkTouch && EnchantmentHelper.getItemEnchantmentLevel(
                    level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), stack) > 0);
            if ((!blockNeedsFortune && !blockNeedsSilkTouch) || !hasRelevantEnchant) {
                if (stack.isDamageableItem()) {
                    return -2; // Deprioritize damageable to preserve durability
                }
                return 0;
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

        // Fortune: only for blocks in fortune_blocks tag (extensible via datapacks)
        if (EnchantBlockRegistry.isFortuneBlock(blockState)) {
            Holder<Enchantment> fortune = enchantments.getOrThrow(Enchantments.FORTUNE);
            score += EnchantmentHelper.getItemEnchantmentLevel(fortune, stack) * 10000;
        }

        // Silk Touch: only for blocks that benefit (tag extensible via datapacks)
        if (EnchantBlockRegistry.isSilkTouchBlock(blockState)) {
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
