package qoby.hotbar_helper;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

/**
 * Client-side refill is implemented in the client source set.
 * This class provides a callback that the client registers.
 */
public final class HotbarRefill {
    /**
     * Called by the client to perform refill. Set by HotbarHelperClient.
     * Parameters: (player, emptySlot, itemToMatch) -> boolean (true if refill was done)
     */
    public static volatile RefillHandler refillHandler;

    public interface RefillHandler {
        boolean tryRefill(Player player, int emptySlot, Item itemToMatch, boolean isContinuation);
    }

    public static boolean tryRefill(Player player, int emptySlot, Item itemToMatch, boolean isContinuation) {
        RefillHandler handler = refillHandler;
        return handler != null && handler.tryRefill(player, emptySlot, itemToMatch, isContinuation);
    }
}
