package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.arena;
import static justfatlard.pvp_dimensions.gametest.Tables.check;

import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Travel;
import justfatlard.pvp_dimensions.preset.ItemList;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Custom, played: survival with flight, a listed block that never runs out and an unlisted one
 * that does, nothing allowed out, and the flight taken away again on the way home.
 */
public final class CustomPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runOnServer(s -> savedAndKeptApart());
			Tables.start(server, connection, Kinds.DEATHMATCH, CustomPlays::custom);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(player.gameMode() == GameType.SURVIVAL, "custom did not play as survival: " + player.gameMode());
				check(player.getAbilities().mayfly, "custom with flying on gave no flight");

				ServerLevel level = Arenas.level(s, arena(s));
				ItemStack planks = new ItemStack(Items.OAK_PLANKS, 3);
				player.setItemInHand(InteractionHand.MAIN_HAND, planks);
				check(place(player, level, player.blockPosition().offset(3, 4, 0)), "the oak planks were not put down");
				check(player.getMainHandItem().is(Items.OAK_PLANKS) && player.getMainHandItem().getCount() == 3,
					"an endless block was used up: " + player.getMainHandItem());

				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 3));
				check(place(player, level, player.blockPosition().offset(-3, 4, 0)), "the cobblestone was not put down");
				check(player.getMainHandItem().getCount() == 2, "an unlisted block was not used up: " + player.getMainHandItem());

				Travel.goHome(player, arena(s), Travel.Why.COMMAND);
				check(!player.getAbilities().mayfly && !player.getAbilities().flying, "flight came home from custom");
			});
		}
	}

	private static void custom(Preset preset) {
		preset.gameMode = Preset.GameRule.CUSTOM;
		preset.flight = true;
		preset.endlessBlocks = ItemList.of("minecraft:oak_planks", 1);
		preset.traversal = Preset.Traversal.FREE;
	}

	/** Written and read back, and kept apart however its inventories are set, since endless blocks can be broken for more. */
	private static void savedAndKeptApart() {
		Preset preset = Kinds.DEATHMATCH.make();
		custom(preset);
		preset.flight = false;
		Preset back = Presets.readFile(Presets.writeFile(preset));
		check(back.gameMode == Preset.GameRule.CUSTOM && !back.flight && back.endlessBlocks.allows(Items.OAK_PLANKS),
			"custom did not survive a save");
		check(back.isolated() && !back.rewardsOut(), "endless blocks let things out");
		back.endlessBlocks = new ItemList();
		check(!back.isolated(), "custom with no endless blocks kept things apart anyway");
	}

	/** Onto the top of a stone block put there for it, through the game's own use of the held item. */
	private static boolean place(ServerPlayer player, ServerLevel level, BlockPos under) {
		level.setBlockAndUpdate(under, Blocks.STONE.defaultBlockState());
		level.setBlockAndUpdate(under.above(), Blocks.AIR.defaultBlockState());
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(under).add(0, 0.5, 0), Direction.UP, under, false);
		player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
		return !level.getBlockState(under.above()).isAir();
	}
}
