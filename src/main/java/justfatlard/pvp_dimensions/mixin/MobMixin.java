package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Mobs;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The arena's mobs are all on one side: an iron golem sent in angry doesn't turn on the zombies
 * beside it, and a skeleton's stray arrow doesn't start a fight between them.
 */
@Mixin(Mob.class)
public class MobMixin {
	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$oneSide(LivingEntity target, CallbackInfo ci) {
		if (target != null && Mobs.sameSide((Mob) (Object) this, target)) ci.cancel();
	}
}
