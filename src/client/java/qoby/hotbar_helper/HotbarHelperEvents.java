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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;

/**
 * Registers Fabric events for client-side detection of hotbar emptying.
 * Uses deferred execution (next client tick) since consumption happens after
 * callback returns.
 * USE = any item consumed on use (in-air or on entity) that isn't place.
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
            if (!config.refillOnPlace)
                return InteractionResult.PASS;
            if (hitResult.getType() != HitResult.Type.BLOCK)
                return InteractionResult.PASS;

            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem))
                return InteractionResult.PASS;

            pendingRefills.add(new PendingRefill(player.getInventory().getSelectedSlot(), stack.getItem(),
                    HotbarRefillCause.PLACE, config));
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
            if (stack.isEmpty())
                return InteractionResult.PASS;

            // Using item on entity (feeding animals, etc.) - item may be consumed
            pendingRefills.add(
                    new PendingRefill(player.getInventory().getSelectedSlot(), stack.getItem(), HotbarRefillCause.USE,
                            config));
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

            // BlockItems are handled by UseBlockCallback (PLACE) - avoid double refill
            if (stack.getItem() instanceof BlockItem)
                return InteractionResult.PASS;

            // Any item consumed on use: eating, throwables, ender pearl, feeding, etc.
            if (!config.refillOnUse)
                return InteractionResult.PASS;

            pendingRefills.add(
                    new PendingRefill(player.getInventory().getSelectedSlot(), stack.getItem(), HotbarRefillCause.USE,
                            config));
            return InteractionResult.PASS;
        });

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            Player player = Minecraft.getInstance().player;
            if (player == null || !player.isAlive() || player.isRemoved())
                return;

            boolean keyDropDown = client.options.keyDrop.isDown();
            if (config.refillOnDrop && keyDropDown && !wasKeyDropDownLastTick) {
                var stack = player.getItemInHand(InteractionHand.MAIN_HAND);
                if (!stack.isEmpty()) {
                    pendingRefills.add(new PendingRefill(player.getInventory().getSelectedSlot(), stack.getItem(),
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
                    case DROP -> pr.config.refillOnDrop;
                    case USE -> pr.config.refillOnUse;
                };
                if (!enabled)
                    continue;

                if (HotbarRefill.tryRefill(player, pr.slot, pr.itemType, false) && pr.config.refillSound) {
                    player.level().playSound(player, player.blockPosition(), SoundEvents.CHICKEN_EGG,
                            SoundSource.PLAYERS, 0.4f, 1.2f);
                }
            }
        });
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
