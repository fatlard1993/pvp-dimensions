package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Gateways;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Whenever the game tells a player what their dimension's border is, an arena player is told
 * their arena's instead, straight after: on joining, on respawning, on every change of dimension.
 */
@Mixin(PlayerList.class)
public class PlayerListMixin {
	@Inject(method = "sendLevelInfo", at = @At("TAIL"))
	private void pvpDimensions$arenaBorder(ServerPlayer player, ServerLevel level, CallbackInfo ci) {
		Gateways.borderFor(player, level);
	}
}
