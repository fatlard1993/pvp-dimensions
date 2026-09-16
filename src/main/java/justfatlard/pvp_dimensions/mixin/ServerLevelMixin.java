package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Places;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An arena world has no weather of its own: every arena in it has its own instead, which it tells
 * its players about itself. Left running, the world's would follow the overworld's and tell every
 * player in every arena.
 */
@Mixin(ServerLevel.class)
public class ServerLevelMixin {
	@Inject(method = "advanceWeatherCycle", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$noWorldWeather(CallbackInfo ci) {
		ServerLevel level = (ServerLevel) (Object) this;
		if (!Places.isArena(level.dimension())) return;
		level.setRainLevel(0F);
		level.setThunderLevel(0F);
		ci.cancel();
	}
}
