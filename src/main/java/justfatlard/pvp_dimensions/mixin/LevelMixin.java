package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Places;
import justfatlard.pvp_dimensions.arena.Weather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rain in an arena world falls where that arena's own weather says it does. */
@Mixin(Level.class)
public class LevelMixin {
	@Inject(method = "precipitationAt", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$arenaRain(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
		if ((Object) this instanceof ServerLevel level && Places.isArena(level.dimension())) cir.setReturnValue(Weather.at(level, pos));
	}
}
