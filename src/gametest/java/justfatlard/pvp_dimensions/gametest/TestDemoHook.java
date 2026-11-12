package justfatlard.pvp_dimensions.gametest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import justfatlard.pvp_dimensions.api.DemoHook;
import justfatlard.pvp_dimensions.api.DemoRun;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** The test mod's demo hook: a gold block put down on arrival, and the demo finished by standing on it. */
public final class TestDemoHook implements DemoHook {
	static int arrived;
	static int left;
	static final Map<UUID, BlockPos> gold = new HashMap<>();

	@Override
	public void arrived(DemoRun run) {
		arrived++;
		int middle = run.width() / 2;
		BlockPos top = run.at(middle, 0, middle);
		int y = run.level().getHeight(Heightmap.Types.WORLD_SURFACE, top.getX(), top.getZ());
		BlockPos spot = run.at(middle, y, middle);
		run.level().setBlockAndUpdate(spot, Blocks.GOLD_BLOCK.defaultBlockState());
		gold.put(run.player().getUUID(), spot);
	}

	@Override
	public void tick(DemoRun run) {
		BlockPos spot = gold.get(run.player().getUUID());
		if (spot != null && run.player().blockPosition().below().equals(spot)) run.finish();
	}

	@Override
	public void left(DemoRun run) {
		left++;
	}
}
