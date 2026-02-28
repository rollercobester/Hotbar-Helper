package qoby.hotbar_helper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.BlockEvents;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ProjectileItem;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;

/**
 * Registers Fabric events for client-side detection of hotbar emptying.
 * Uses deferred execution (next client tick) since consumption happens after callback returns.
 */
public final class HotbarHelperEvents {
    private static final Queue<PendingRefill> pendingRefills = new ArrayDeque<>();
    private static boolean wasKeyDropDownLastTick;

    public static void register(HotbarHelperConfig config) {
        BlockEvents.USE_ITEM_ON.register((itemStack, blockState, level, blockPos, player, hand, hitResult) -> {
            if (hand != InteractionHand.MAIN_HAND) return null;
            if (!config.refillOnPlace) return null;

            int slot = player.getInventory().getSelectedSlot();
            Item itemType = itemStack.getItem();
            pendingRefills.add(new PendingRefill(slot, itemType, HotbarRefillCause.PLACE, config));
            return null;
        });

        ItemEvents.USE.register((level, player, hand) -> {
            if (hand != InteractionHand.MAIN_HAND) return null;

            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty()) return null;

            Item item = stack.getItem();
            HotbarRefillCause cause;
            if (item instanceof ProjectileItem) {
                if (!config.refillOnThrow) return null;
                cause = HotbarRefillCause.THROW;
            } else if (stack.getComponents().has(DataComponents.FOOD)) {
                if (!config.refillOnEat) return null;
                cause = HotbarRefillCause.EAT;
            } else {
                return null;
            }

            int slot = player.getInventory().getSelectedSlot();
            pendingRefills.add(new PendingRefill(slot, item, cause, config));
            return null;
        });

        // START: detect drop key before game processes it (item still in hand)
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            Player player = Minecraft.getInstance().player;
            if (player == null || !player.isAlive() || player.isRemoved()) return;

            boolean keyDropDown = client.options.keyDrop.isDown();
            if (config.refillOnDrop && keyDropDown && !wasKeyDropDownLastTick) {
                var stack = player.getItemInHand(InteractionHand.MAIN_HAND);
                if (!stack.isEmpty()) {
                    int slot = player.getInventory().getSelectedSlot();
                    pendingRefills.add(new PendingRefill(slot, stack.getItem(), HotbarRefillCause.DROP, config));
                }
            }
            wasKeyDropDownLastTick = keyDropDown;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Player player = Minecraft.getInstance().player;
            if (player == null || !player.isAlive() || player.isRemoved()) return;

            // Process refills scheduled by server (e.g. drop mixin in singleplayer)
            for (HotbarHelper.PendingRefillRequest pr; (pr = HotbarHelper.pollPendingRefill()) != null; ) {
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
                boolean slotEmpty = stack.isEmpty();
                // Only refill when slot is empty - no multi-stack continuation.
                // QUICK_MOVE spills to other slots; SWAP would alternate. One refill per trigger.
                if (!slotEmpty) continue;

                it.remove();
                boolean enabled = switch (pr.cause) {
                    case PLACE -> pr.config.refillOnPlace;
                    case EAT -> pr.config.refillOnEat;
                    case DROP -> pr.config.refillOnDrop;
                    case THROW -> pr.config.refillOnThrow;
                };
                if (!enabled) continue;

                HotbarRefill.tryRefill(player, pr.slot, pr.itemType, false);
            }
        });
    }

    private record PendingRefill(int slot, Item itemType, HotbarRefillCause cause, HotbarHelperConfig config) {}
}
