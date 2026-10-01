package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Balls;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Who moved a ball last: whoever hit it, by hand or with anything shot or thrown, and whoever
 * walked into it. A goal, a hit or a stroke is counted for them.
 *
 * <p>On the cube itself because its own hurt skips everything Fabric's damage events watch: with a
 * block inside, a blow it is immune to is turned straight into knockback and never reaches them.
 * The knockback names the hitter whatever the blow was, so this sees every kick.
 *
 * <p>Walking into a golf ball is refused, since there every move of it is a stroke. Otherwise
 * nothing is changed; this only watches.
 */
@Mixin(SulfurCube.class)
public abstract class SulfurCubeMixin {

	@Inject(method = "knockback", at = @At("HEAD"))
	private void pvpDimensions$kicked(double power, double xd, double zd, DamageSource source, float damage, boolean comesFromEffect, CallbackInfo callback) {
		Balls.touched((SulfurCube) (Object) this, source.getEntity(), true);
	}

	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$pushed(Player player, CallbackInfo callback) {
		SulfurCube self = (SulfurCube) (Object) this;
		if (!Balls.pushable(self)) {
			callback.cancel();
			return;
		}
		Balls.touched(self, player, false);
	}
}
