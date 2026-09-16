package justfatlard.pvp_dimensions.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A mob's goals, which the game keeps to itself: how an arena gives a giant a mind. */
@Mixin(Mob.class)
public interface MobAccessor {
	@Accessor("goalSelector")
	GoalSelector pvpDimensions$goals();

	@Accessor("targetSelector")
	GoalSelector pvpDimensions$targets();
}
