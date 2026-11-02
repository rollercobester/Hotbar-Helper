package qoby.hotbar_helper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;

/**
 * Registers Fabric events for client-side detection of hotbar emptying.
 * Uses deferred execution (next client tick) since consumption happens after
 * callback returns.
 * PLACE = block items. ENTITY_PLACE = items that spawn an entity, such as minecarts.
 * USE = any other item consumed on use (in-air or on entity).
 * Covers eating, throwables, ender pearl, eye of ender, feeding animals, etc.
 */
public final class HotbarHelperEvents {
    private static final Queue<PendingRefill> pendingRefills = new ArrayDeque<>();
    private static boolean wasKeyDropDownLastTick;

    public static void register(HotbarHelperConfig config) {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!level.isClientSide())
                return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND)
                return InteractionResult.PASS;
            if (hitResult.getType() != HitResult.Type.BLOCK)
                return InteractionResult.PASS;

            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty())
                return InteractionResult.PASS;
            Item item = stack.getItem();
            if (!config.allowsRefill(item))
                return InteractionResult.PASS;

            HotbarRefillCause cause;
            if (item instanceof BlockItem) {
                if (!config.refillOnPlace)
                    return InteractionResult.PASS;
                cause = HotbarRefillCause.PLACE;
            } else if (placesEntity(item)) {
                if (!config.refillOnEntityPlace)
                    return InteractionResult.PASS;
                cause = HotbarRefillCause.ENTITY_PLACE;
            } else {
                return InteractionResult.PASS;
            }

            pendingRefills.add(new PendingRefill(player.getInventory().selected, item, cause, config));
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!level.isClientSide())
                return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND)
                return InteractionResult.PASS;
            if (!config.refillOnUse)
                return InteractionResult.PASS;

            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty() || !config.allowsRefill(stack.getItem()))
                return InteractionResult.PASS;

            // Using item on entity (feeding animals, etc.) - item may be consumed
            pendingRefills.add(
                    new PendingRefill(player.getInventory().selected, stack.getItem(), HotbarRefillCause.USE, config));
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!level.isClientSide())
                return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND)
                return InteractionResult.PASS;

            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty())
                return InteractionResult.PASS;

            // Blocks and entity-placing items are handled by UseBlockCallback.
            if (stack.getItem() instanceof BlockItem || placesEntity(stack.getItem()))
                return InteractionResult.PASS;

            // Any item consumed on use: eating, throwables, ender pearl, feeding, etc.
            if (!config.refillOnUse || !config.allowsRefill(stack.getItem()))
                return InteractionResult.PASS;

            pendingRefills.add(
                    new PendingRefill(player.getInventory().selected, stack.getItem(), HotbarRefillCause.USE, config));
            return InteractionResult.PASS;
        });

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            Player player = Minecraft.getInstance().player;
            if (player == null || !player.isAlive() || player.isRemoved())
                return;

            boolean keyDropDown = client.options.keyDrop.isDown();
            if (config.refillOnDrop && keyDropDown && !wasKeyDropDownLastTick) {
                var stack = player.getItemInHand(InteractionHand.MAIN_HAND);
                if (!stack.isEmpty() && config.allowsRefill(stack.getItem())) {
                    pendingRefills.add(new PendingRefill(player.getInventory().selected, stack.getItem(),
                            HotbarRefillCause.DROP, config));
                }
            }
            wasKeyDropDownLastTick = keyDropDown;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Clear pending refills when any screen is open - prevents desync if the
            // player opens inventory and manually moves items before our refill fires
            if (client.screen != null) {
                pendingRefills.clear();
                return;
            }

            Player player = Minecraft.getInstance().player;
            if (player == null || !player.isAlive() || player.isRemoved())
                return;

            for (HotbarHelper.PendingRefillRequest pr; (pr = HotbarHelper.pollPendingRefill()) != null;) {
                if (pr.slot() >= 0 && pr.slot() < 9) {
                    pendingRefills.add(new PendingRefill(pr.slot(), pr.itemType(), pr.cause(), pr.config()));
                }
            }

            Iterator<PendingRefill> it = pendingRefills.iterator();
            while (it.hasNext()) {
                PendingRefill pr = it.next();
                if (pr.slot < 0 || pr.slot >= 9) {
                    it.remove();
                    continue;
                }
                var stack = player.getInventory().getItem(pr.slot);
                if (!stack.isEmpty()) {
                    pr.ticksWaiting++;
                    if (pr.ticksWaiting > 40)
                        it.remove();
                    continue;
                }

                it.remove();
                boolean enabled = switch (pr.cause) {
                    case PLACE -> pr.config.refillOnPlace;
                    case ENTITY_PLACE -> pr.config.refillOnEntityPlace;
                    case DROP -> pr.config.refillOnDrop;
                    case USE -> pr.config.refillOnUse;
                };
                if (!enabled || !pr.config.allowsRefill(pr.itemType))
                    continue;

                if (HotbarRefill.tryRefill(player, pr.slot, pr.itemType, false) && pr.config.refillSound) {
                    player.level().playSound(player, player.blockPosition(), SoundEvents.CHICKEN_EGG,
                            SoundSource.PLAYERS, 0.5f, 1f);
                }
            }
        });
    }

    /** Items that spawn an entity when used on a block, rather than placing a block. */
    private static boolean placesEntity(Item item) {
        return item instanceof MinecartItem
                || item instanceof BoatItem
                || item instanceof ArmorStandItem
                || item instanceof HangingEntityItem
                || item instanceof SpawnEggItem
                || item instanceof EndCrystalItem
                || item instanceof MobBucketItem;
    }

    private static final class PendingRefill {
        final int slot;
        final Item itemType;
        final HotbarRefillCause cause;
        final HotbarHelperConfig config;
        int ticksWaiting;

        PendingRefill(int slot, Item itemType, HotbarRefillCause cause, HotbarHelperConfig config) {
            this.slot = slot;
            this.itemType = itemType;
            this.cause = cause;
            this.config = config;
        }
    }
}
