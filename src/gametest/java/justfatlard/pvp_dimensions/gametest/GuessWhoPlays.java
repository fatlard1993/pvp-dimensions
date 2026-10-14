package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.arena;
import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.HashSet;
import java.util.Set;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.GuessWho;
import justfatlard.pvp_dimensions.preset.Kinds;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Guess who, played: two galleries of the same eight mobs and eight blocks either side of a wall,
 * a lever flipping a mob or a block down and back up, the other gallery's lever refused, a first
 * right-click on a suspect that only asks, and a second on the right one that wins it.
 */
public final class GuessWhoPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			Tables.start(server, connection, Kinds.GUESS_WHO, preset -> {});
			server.waitFor(s -> arena(s).bases.size() == 2 && GuessWho.side(arena(s)) >= 0, 200);

			int[] team = new int[1];
			server.runOnServer(s -> team[0] = arena(s).member(connection.getServerPlayer().getUUID()).team);
			BlockPos corner = server.computeOnServer(s -> arena(s).bases.get(team[0]));
			Tables.look(context, server, connection, Vec3.atBottomCenterOf(corner).add(6, 0, -5), Vec3.atCenterOf(corner).add(6, 0, 8));
			Tables.shoot(context, "guess-who");
			// A few seconds on, it is hidden again.
			server.waitFor(s -> !GuessWho.shown(arena(s), team[0]), 200);
			server.runOnServer(s -> check(Tables.block(Arenas.level(s, arena(s)), GuessWho.pedestalOf(arena(s), team[0]).above()).equals("air")
				&& Arenas.level(s, arena(s)).getEntitiesOfClass(Mob.class, new AABB(GuessWho.pedestalOf(arena(s), team[0]).above()).inflate(1),
					mob -> mob.entityTags().contains(GuessWho.CARD)).isEmpty(), "the suspect stayed on the pedestal"));
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				team[0] = arena.member(player.getUUID()).team;
				int enemy = 1 - team[0];

				var footprint = arena.footprint();
				AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
				int mobs = level.getEntitiesOfClass(Mob.class, area, mob -> mob.entityTags().contains(GuessWho.TAG)).size();
				check(mobs == 16, mobs + " suspect mobs, not eight in each gallery");
				Set<String> names = new HashSet<>();
				int things = 0;
				for (int place = 0; place < GuessWho.SUSPECT_COUNT; place++) {
					names.add(GuessWho.nameOf(arena, place));
					if (GuessWho.isItem(arena, place)) {
						things++;
						for (int side = 0; side < 2; side++) {
							var shown = GuessWho.floatingAt(arena, side, place);
							check(shown != null && !shown.getItemStack().isEmpty() && GuessWho.clickableAt(arena, side, place) != null,
								"the " + GuessWho.nameOf(arena, place) + " is not floating over its pen");
						}
						continue;
					}
					if (!GuessWho.isBlock(arena, place)) continue;
					things++;
					for (int side = 0; side < 2; side++) {
						check(!Tables.block(level, GuessWho.spotOf(arena, side, place)).equals("air"), "the " + GuessWho.nameOf(arena, place) + " is not standing in its pen");
					}
				}
				check(things == 8, things + " blocks and items in the lineup, not eight");
				check(names.size() == GuessWho.SUSPECT_COUNT, "the lineup has the same suspect twice: " + names);

				// The pedestal stands empty until its button is pressed, then shows the team's own suspect.
				int mineSecret = GuessWho.secretOf(arena, team[0]);
				BlockPos top = GuessWho.pedestalOf(arena, team[0]).above();
				check(Tables.block(level, top).equals("air") && !GuessWho.shown(arena, team[0]), "the pedestal shows the suspect before anyone asks");
				Tables.click(player, level, GuessWho.buttonOf(arena, team[0]));
				check(GuessWho.shown(arena, team[0]), "the button showed nothing");
				if (GuessWho.isBlock(arena, mineSecret)) {
					check(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(top).getBlock()).toString().equals(GuessWho.blockOf(arena, mineSecret)),
						"the pedestal shows " + Tables.block(level, top) + ", not the " + GuessWho.nameOf(arena, mineSecret));
				} else if (GuessWho.isItem(arena, mineSecret)) {
					var shown = level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class, new AABB(top).inflate(0.5),
						display -> display.entityTags().contains(GuessWho.CARD));
					check(shown.size() == 1 && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(shown.get(0).getItemStack().getItem()).toString()
						.equals(GuessWho.itemOf(arena, mineSecret)), "the pedestal does not show the " + GuessWho.nameOf(arena, mineSecret));
				} else {
					var shown = level.getEntitiesOfClass(Mob.class, new AABB(top).inflate(0.5), mob -> mob.entityTags().contains(GuessWho.CARD));
					check(shown.size() == 1 && shown.get(0).getType() == GuessWho.typeOf(arena, mineSecret),
						"the pedestal does not show the " + GuessWho.nameOf(arena, mineSecret));
				}

				// The wall between the galleries.
				int middle = (int) Math.floor(footprint.centerX());
				BlockPos mine = arena.bases.get(team[0]);
				check(Tables.block(level, new BlockPos(middle, mine.getY() + 8, mine.getZ())).equals("polished_deepslate"), "no wall between the galleries");

				// A block flipped down goes, and comes back; so does an item. The draw may hold none of one.
				int block = 0;
				while (block < GuessWho.SUSPECT_COUNT && !GuessWho.isBlock(arena, block)) block++;
				if (block < GuessWho.SUSPECT_COUNT) {
					check(Tables.click(player, level, GuessWho.leverOf(arena, team[0], block)).consumesAction(), "our lever did nothing");
					check(GuessWho.isDown(arena, team[0], block) && Tables.block(level, GuessWho.spotOf(arena, team[0], block)).equals("air"), "the block was not flipped down");
					Tables.click(player, level, GuessWho.leverOf(arena, team[0], block));
					check(!Tables.block(level, GuessWho.spotOf(arena, team[0], block)).equals("air"), "the block did not come back");
				}
				int item = 0;
				while (item < GuessWho.SUSPECT_COUNT && !GuessWho.isItem(arena, item)) item++;
				if (item < GuessWho.SUSPECT_COUNT) {
					Tables.click(player, level, GuessWho.leverOf(arena, team[0], item));
					check(GuessWho.floatingAt(arena, team[0], item).getItemStack().isEmpty(), "the item was not flipped down");
					check(GuessWho.useEntity(player, GuessWho.clickableAt(arena, team[0], item), InteractionHand.MAIN_HAND) == InteractionResult.FAIL,
						"a flipped-down item could be accused");
					Tables.click(player, level, GuessWho.leverOf(arena, team[0], item));
					check(!GuessWho.floatingAt(arena, team[0], item).getItemStack().isEmpty(), "the item did not come back");
				}
				// A mob flipped down goes invisible.
				int mob = 0;
				while (GuessWho.isBlock(arena, mob) || GuessWho.isItem(arena, mob)) mob++;
				Tables.click(player, level, GuessWho.leverOf(arena, team[0], mob));
				check(GuessWho.suspectAt(arena, team[0], mob).isInvisible(), "the mob was not flipped down");
				Tables.click(player, level, GuessWho.leverOf(arena, team[0], mob));
				check(!GuessWho.suspectAt(arena, team[0], mob).isInvisible(), "the mob did not come back");

				// Theirs does nothing.
				check(Tables.click(player, level, GuessWho.leverOf(arena, enemy, mob)) == InteractionResult.FAIL, "their lever answered us");

				// The one they are hiding: a mob, or the box round an item, is right-clicked; a block is right-clicked on.
				int secret = GuessWho.secretOf(arena, enemy);
				InteractionResult first = GuessWho.isBlock(arena, secret)
					? Tables.click(player, level, GuessWho.spotOf(arena, team[0], secret))
					: GuessWho.useEntity(player, GuessWho.clickableAt(arena, team[0], secret), InteractionHand.MAIN_HAND);
				check(first.consumesAction(), "a click on a suspect did nothing");
				check(arena.closesAt == 0, "one right-click accused");
			});
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				int secret = GuessWho.secretOf(arena, 1 - team[0]);
				if (GuessWho.isBlock(arena, secret)) Tables.click(player, level, GuessWho.spotOf(arena, team[0], secret));
				else GuessWho.useEntity(player, GuessWho.clickableAt(arena, team[0], secret), InteractionHand.MAIN_HAND);
				check(arena.closesAt > 0 && arena.winners.contains(player.getUUID()), "naming the right suspect did not win it");
			});
		}
	}
}
