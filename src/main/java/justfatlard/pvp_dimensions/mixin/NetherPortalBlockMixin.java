package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Gateways;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A lit gate goes into its arena, an exit goes home, and no other portal inside an arena goes
 * anywhere: a frame somebody builds in the middle of a fight is not a way out to the nether.
 */
@Mixin(NetherPortalBlock.class)
public class NetherPortalBlockMixin {
	@Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$arenaPortals(ServerLevel level, Entity entity, BlockPos pos, CallbackInfoReturnable<TeleportTransition> cir) {
		Gateways.Answer answer = Gateways.destination(level, entity, pos);
		if (answer.decided()) cir.setReturnValue(answer.trip());
	}
}
