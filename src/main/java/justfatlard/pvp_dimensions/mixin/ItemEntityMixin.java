package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Horde;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One of the horde picks nothing up: what the fallen dropped is for the living. */
@Mixin(ItemEntity.class)
public class ItemEntityMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$hordeTakesNothing(Player player, CallbackInfo ci) {
		if (Horde.is(player)) ci.cancel();
	}
}
