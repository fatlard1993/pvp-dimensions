package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Traps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Who put each block down in an arena, so a trap's kill is its maker's. */
@Mixin(BlockItem.class)
public class BlockItemMixin {
	@Inject(method = "place", at = @At("RETURN"))
	private void pvpDimensions$remember(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (!cir.getReturnValue().consumesAction()) return;
		if (context.getLevel() instanceof ServerLevel level && context.getPlayer() instanceof ServerPlayer player) {
			Traps.placed(level, context.getClickedPos(), player);
		}
	}
}
