package qoby.hotbar_helper.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import qoby.hotbar_helper.HotbarHelper;
import qoby.hotbar_helper.HotbarHelperConfig;
import qoby.hotbar_helper.HotbarRefillCause;

@Mixin(Player.class)
public abstract class PlayerDropMixin {

    private static final ThreadLocal<ItemStack> mainHandAtHead = new ThreadLocal<>();

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"))
    private void hotbarHelper$onDropHead(ItemStack droppedStack, boolean dropStack, CallbackInfoReturnable<ItemEntity> cir) {
        Player self = (Player) (Object) this;
        mainHandAtHead.set(self.getItemInHand(InteractionHand.MAIN_HAND).copy());
    }

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("RETURN"))
    private void hotbarHelper$onDropReturn(ItemStack droppedStack, boolean dropStack, CallbackInfoReturnable<ItemEntity> cir) {
        try {
            ItemEntity ret = cir.getReturnValue();
            if (ret == null) return;
            Player self = (Player) (Object) this;
            HotbarHelperConfig config = HotbarHelper.getConfig();
            if (config == null || !config.refillOnDrop) return;

            if (droppedStack == null || droppedStack.isEmpty()) return;

            ItemStack mainHand = mainHandAtHead.get();
            if (mainHand == null || mainHand.isEmpty()) return;
            // Only schedule refill when the drop came from the main hand (Q drop), not from a GUI click
            if (!ItemStack.isSameItem(droppedStack, mainHand)) return;
            if (droppedStack.getCount() > mainHand.getCount()) return;

            Item itemType = droppedStack.getItem();
            int slot = self.getInventory().getSelectedSlot();
            if (slot < 0 || slot >= 9) return;

            HotbarHelper.scheduleRefill(slot, itemType, HotbarRefillCause.DROP, config);
        } finally {
            mainHandAtHead.remove();
        }
    }
}
