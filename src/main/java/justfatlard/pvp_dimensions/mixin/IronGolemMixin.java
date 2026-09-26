package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Horde;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A golem the players built never turns on a player, except one of the horde. */
@Mixin(IronGolem.class)
public class IronGolemMixin {
	@Inject(method = "canAttack", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$hordeIsFair(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
		if (Horde.is(target) && target.isAlive()) cir.setReturnValue(true);
	}
}
