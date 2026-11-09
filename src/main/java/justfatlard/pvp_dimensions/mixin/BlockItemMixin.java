package justfatlard.pvp_dimensions.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import justfatlard.pvp_dimensions.arena.Powers;
import justfatlard.pvp_dimensions.arena.Traps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Who put each block down in an arena, so a trap's kill is its maker's; and custom's endless blocks. */
@Mixin(BlockItem.class)
public class BlockItemMixin {
	@Inject(method = "place", at = @At("RETURN"))
	private void pvpDimensions$remember(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (!cir.getReturnValue().consumesAction()) return;
		if (context.getLevel() instanceof ServerLevel level && context.getPlayer() instanceof ServerPlayer player) {
			Traps.placed(level, context.getClickedPos(), player);
		}
	}

	/** The client took one off the stack when it put the block down; the whole pack is sent again to put it back. */
	@WrapOperation(method = "place", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/item/ItemStack;consume(ILnet/minecraft/world/entity/LivingEntity;)V"))
	private void pvpDimensions$endless(ItemStack stack, int amount, LivingEntity owner, Operation<Void> original) {
		if (owner instanceof ServerPlayer player && Powers.endless(player, stack)) {
			player.containerMenu.sendAllDataToRemote();
			return;
		}
		original.call(stack, amount, owner);
	}
}
