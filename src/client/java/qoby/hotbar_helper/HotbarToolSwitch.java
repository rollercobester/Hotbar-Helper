package qoby.hotbar_helper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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

            // Tie-breaking: when scores equal, prefer switching to empty/non-damageable to
            // save durability
            var currentStack = player.getInventory().getItem(currentSlot);
            var bestStack = player.getInventory().getItem(bestSlot);
            int currentScore = scoreItem(currentStack, state, player.level());
            int bestScore = scoreItem(bestStack, state, player.level());
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
        EnchantBlockRegistry.loadFromTagsIfEmpty(player.level().registryAccess());

        int bestSlot = -1;
        int bestScore = -1_000_000;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            int score = scoreItem(stack, blockState, player.level());
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static int scoreItem(ItemStack stack, BlockState blockState, Level level) {
        // Empty hand: prefer over useless/almost-broken tools when nothing helps
        if (stack.isEmpty())
            return 0;

        // Durability: almost broken ranks lowest (prefer empty hand over
        // switching to it)
        if (stack.isDamageableItem()) {
            int maxDamage = stack.getMaxDamage();
            int damage = stack.getDamageValue();
            if (maxDamage > 0 && damage >= maxDamage - 5)
                return -2;
        }

        // Use destroy speed - works with any tool (vanilla and modded)
        float miningSpeed = new ItemStack(stack.getItem()).getDestroySpeed(blockState);
        int toolScore = miningSpeed > 1.0f ? (int) (miningSpeed * 2) : 0;

        int enchantScore = getEnchantScore(stack, blockState, level);

        // Deprioritize useless tools to save durability
        if (toolScore == 0 && enchantScore < 1000) {
            return stack.isDamageableItem() ? -1 : 0;
        }

        // Prioritize tool type over enchants
        return toolScore * 10000 + enchantScore;
    }

    private static int getEnchantScore(ItemStack stack, BlockState blockState, Level level) {
        int score = 0;
        var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);

        // Fortune: for blocks that benefit (fortune_blocks tag - ores, wheat, grass,
        // crops)
        if (EnchantBlockRegistry.isFortuneBlock(blockState)) {
            Holder<Enchantment> fortune = enchantments.getOrThrow(Enchantments.FORTUNE);
            score += EnchantmentHelper.getItemEnchantmentLevel(fortune, stack) * 1000;
        }

        // Silk Touch: for blocks that benefit (silk_touch_blocks tag)
        if (EnchantBlockRegistry.isSilkTouchBlock(blockState)) {
            Holder<Enchantment> silkTouch = enchantments.getOrThrow(Enchantments.SILK_TOUCH);
            score += EnchantmentHelper.getItemEnchantmentLevel(silkTouch, stack) * 1000;
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
