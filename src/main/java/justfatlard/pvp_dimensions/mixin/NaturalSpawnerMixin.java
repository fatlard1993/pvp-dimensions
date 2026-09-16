package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Places;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No hostile mob spawns by darkness in an arena: the arena sends its own. Were the game to try,
 * one landing beside the arena's own mobs would pass for something they brought with them.
 */
@Mixin(NaturalSpawner.class)
public class NaturalSpawnerMixin {
	@Inject(method = "spawnCategoryForChunk", at = @At("HEAD"), cancellable = true)
	private static void pvpDimensions$noDarkSpawns(MobCategory category, ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnPredicate predicate, NaturalSpawner.AfterSpawnCallback callback, CallbackInfo ci) {
		if (category == MobCategory.MONSTER && Places.isArena(level.dimension())) ci.cancel();
	}
}
