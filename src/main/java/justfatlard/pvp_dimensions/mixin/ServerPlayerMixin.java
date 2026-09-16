package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Combat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {
	/** A death in an arena that keeps its dead comes back inside it, not at a bed. */
	@Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$respawnInside(boolean consumeSpawnBlock, TeleportTransition.PostTeleportTransition after,
			CallbackInfoReturnable<TeleportTransition> cir) {
		TeleportTransition inside = Combat.respawn((ServerPlayer) (Object) this, after);
		if (inside != null) cir.setReturnValue(inside);
	}

	/** One of the horde and one of the living can always fight, and two of the horde never. */
	@Inject(method = "canHarmPlayer", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$horde(Player other, CallbackInfoReturnable<Boolean> cir) {
		Boolean allowed = Combat.canHarm((ServerPlayer) (Object) this, other);
		if (allowed != null) cir.setReturnValue(allowed);
	}

	/** In an arena the arena says who may fight, whatever the server's pvp rule says elsewhere. */
	@Inject(method = "isPvpAllowed", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$arenaRules(CallbackInfoReturnable<Boolean> cir) {
		Boolean allowed = Combat.pvpAllowed((ServerPlayer) (Object) this);
		if (allowed != null) cir.setReturnValue(allowed);
	}
}
