package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Gateways;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A lit gate goes into its arena, an exit goes home, and in a sealed arena no other portal goes
 * anywhere: a frame somebody builds in the middle of a fight is not a way out.
 *
 * <p>Here rather than on the portal block, because every kind of portal is asked through this
 * one call, and another mod answering for the nether portal block, such as Player Portals'
 * struck frames, would otherwise get its answer in first.
 */
@Mixin(PortalProcessor.class)
public class PortalProcessorMixin {
	@Shadow
	private BlockPos entryPosition;

	@Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$arenaPortals(ServerLevel level, Entity entity, CallbackInfoReturnable<TeleportTransition> cir) {
		Gateways.Answer answer = Gateways.destination(level, entity, entryPosition);
		if (answer.decided()) cir.setReturnValue(answer.trip());
	}
}
