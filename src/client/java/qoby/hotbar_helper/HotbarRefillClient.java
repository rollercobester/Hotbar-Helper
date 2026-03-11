package qoby.hotbar_helper;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Client-side implementation of hotbar refill. Sends SWAP packet to server.
 * Built for the MC version in gradle.properties – rebuild for each target version.
 */
public final class HotbarRefillClient implements HotbarRefill.RefillHandler {
    private static final int HOTBAR_SIZE = 9;
    private static final int CONTAINER_HOTBAR_START = 36;

    @Override
    public boolean tryRefill(Player player, int emptySlot, Item itemToMatch, boolean isContinuation) {
        if (emptySlot < 0 || emptySlot >= HOTBAR_SIZE || itemToMatch == null) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player != player) return false;
        if (!(player.containerMenu instanceof InventoryMenu menu)) return false;

        Inventory inv = player.getInventory();

        int bestSlot = -1;
        int bestCount = 0;

        for (int i = HOTBAR_SIZE; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(itemToMatch) && stack.getCount() > bestCount) {
                bestSlot = i;
                bestCount = stack.getCount();
            }
        }

        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (i == emptySlot) continue;
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(itemToMatch) && stack.getCount() > bestCount) {
                bestSlot = i;
                bestCount = stack.getCount();
            }
        }

        if (bestSlot < 0) return false;

        // Optimistically update client inventory so display is correct immediately.
        // The server processes our SWAP packet but may not sync back in time (especially for size-1 stacks).
        ItemStack toMove = inv.getItem(bestSlot).copy();
        inv.setItem(emptySlot, toMove);
        inv.setItem(bestSlot, ItemStack.EMPTY);

        short containerSlot = (short) (bestSlot < 9 ? CONTAINER_HOTBAR_START + bestSlot : bestSlot);
        var packet = new ServerboundContainerClickPacket(
                menu.containerId,
                menu.getStateId(),
                containerSlot,
                (byte) emptySlot,
                ClickType.SWAP,
                new Int2ObjectArrayMap<HashedStack>(0),
                HashedStack.EMPTY
        );
        mc.getConnection().send(packet);
        return true;
    }
}
