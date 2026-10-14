package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.arena;
import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.UUID;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.CopsRobbers;
import justfatlard.pvp_dimensions.preset.Kinds;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Cops and robbers, played: the jail, the vault and the hideout built, a robber jailed and sprung
 * by a free one at the lever, gold taken out a handful at a time with the carrier glowing, gold
 * banked in the hideout counting, and the last free robber jailed - into the cell, unhurt, the gold
 * on them back in the vault - winning it for the cops.
 *
 * <p>One player is a robber; the cop is the test, jailing by the same call a cop's hit makes. A
 * second robber is a name on the arena with nobody behind it, which is what a robber who has
 * logged off is, and so never counts toward everyone being in jail.
 */
public final class CopsRobbersPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			Tables.start(server, connection, Kinds.COPS_AND_ROBBERS, preset -> {});
			server.waitFor(s -> arena(s).bases.size() == 2, 200);

			BlockPos door = server.computeOnServer(s -> CopsRobbers.doorOf(arena(s)));
			Tables.look(context, server, connection, Vec3.atBottomCenterOf(away(server, door, 7)), Vec3.atCenterOf(door).add(0, 1, 0));
			Tables.shoot(context, "cops-and-robbers");

			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				Arena.Member me = arena.member(player.getUUID());
				check(me.team == CopsRobbers.ROBBERS, "the only player is not a robber");
				check(Tables.block(level, door).equals("iron_door") && Tables.block(level, door.above()).equals("iron_door"), "no iron door on the jail");
				check(Tables.block(level, CopsRobbers.leverOf(arena)).equals("lever"), "no lever beside the jail's door");
				int bars = 0;
				for (BlockPos pos : BlockPos.betweenClosed(door.offset(-5, 0, -5), door.offset(5, 2, 5))) {
					if (Tables.block(level, pos).equals("iron_bars")) bars++;
				}
				check(bars >= 20, "the jail has only " + bars + " iron bars");
				int stock = CopsRobbers.stock(arena.preset);
				check(stock >= arena.preset.heistTarget, "the vault holds less than the target");
				check(CopsRobbers.held(level, CopsRobbers.vaultOf(arena), arena.preset) == stock, "the vault isn't full");
				check(CopsRobbers.held(level, CopsRobbers.hideoutOf(arena), arena.preset) == 0, "the hideout isn't empty");

				// A second robber, jailed: one robber still free, so nobody has won.
				Arena.Member other = arena.join(UUID.randomUUID(), "Mate");
				other.team = CopsRobbers.ROBBERS;
				other.inside = true;
				CopsRobbers.jail(s, arena, other, "A cop");
				check(CopsRobbers.isJailed(arena, other.id), "the robber wasn't jailed");
				check(arena.closesAt == 0, "jailing one of two robbers ended it");

				// The free robber springs the jail from outside, and the one let out can't be caught at once.
				InteractionResult pulled = Tables.click(player, level, CopsRobbers.leverOf(arena));
				check(pulled.consumesAction(), "the lever did nothing: " + pulled);
				check(!CopsRobbers.isJailed(arena, other.id), "the jail wasn't sprung");
				check(CopsRobbers.shielded(arena, other.id), "the sprung robber can be caught again at once");
				check(Tables.click(player, level, door) == InteractionResult.FAIL, "an empty jail sprang");

				// Eight taken out of the vault: only five can be carried.
				Container vault = (Container) level.getBlockEntity(CopsRobbers.vaultOf(arena));
				Item gold = CopsRobbers.loot(arena.preset);
				moveOut(vault, gold, 8);
				player.getInventory().placeItemBackInInventory(new ItemStack(gold, 8), net.minecraft.util.Prediction.SERVER_ONLY);
			});
			context.waitTicks(25);
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				int stock = CopsRobbers.stock(arena.preset);
				Item gold = CopsRobbers.loot(arena.preset);
				check(count(player.getInventory(), gold) == CopsRobbers.CARRY, "the robber carries " + count(player.getInventory(), gold));
				check(CopsRobbers.held(level, CopsRobbers.vaultOf(arena), arena.preset) == stock - CopsRobbers.CARRY,
					"the vault holds " + CopsRobbers.held(level, CopsRobbers.vaultOf(arena), arena.preset) + " of " + stock);
				check(player.hasEffect(MobEffects.GLOWING), "a robber carrying gold doesn't glow");

				// Two banked in the hideout.
				moveOut(player.getInventory(), gold, 2);
				Container hideout = (Container) level.getBlockEntity(CopsRobbers.hideoutOf(arena));
				hideout.setItem(0, new ItemStack(gold, 2));
			});
			context.waitTicks(25);
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				int stock = CopsRobbers.stock(arena.preset);
				Item gold = CopsRobbers.loot(arena.preset);
				check(arena.scores.getOrDefault(CopsRobbers.ROBBERS, 0) == 2, "the hideout's gold didn't count: " + arena.scores);

				// Caught carrying three: into the cell, unhurt, and the three back in the vault. The only
				// robber here is behind bars, so the cops have won.
				float health = player.getHealth();
				CopsRobbers.jail(s, arena, arena.member(player.getUUID()), "A cop");
				check(CopsRobbers.inCell(arena, player.position()), "the robber isn't in the cell: " + player.position());
				check(count(player.getInventory(), gold) == 0, "the jailed robber kept their gold");
				check(CopsRobbers.held(level, CopsRobbers.vaultOf(arena), arena.preset) == stock - 2, "the gold didn't go back in the vault");
				check(player.getHealth() == health && !player.isDeadOrDying(), "jailing hurt the robber");
				check(CopsRobbers.jailedCount(arena) == 1 && CopsRobbers.robberCount(arena) == 1, "the board doesn't count the jailed robber here");
				check(arena.closesAt > 0, "every robber in jail didn't win it for the cops");
			});
			// From inside the cell, with the win over it.
			context.waitTicks(20);
			Tables.shoot(context, "cops-and-robbers-jailed");
		}
	}

	/** A spot this far out in front of the jail, for a look at it. */
	private static BlockPos away(TestServerContext server, BlockPos door, int distance) {
		return server.computeOnServer(s -> {
			Arena arena = arena(s);
			var footprint = arena.footprint();
			double dx = footprint.centerX() - door.getX();
			double dz = footprint.centerZ() - door.getZ();
			double length = Math.max(1, Math.sqrt(dx * dx + dz * dz));
			return BlockPos.containing(door.getX() + dx / length * distance, door.getY(), door.getZ() + dz / length * distance);
		});
	}

	private static int count(Container container, Item item) {
		int total = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) if (container.getItem(slot).is(item)) total += container.getItem(slot).getCount();
		return total;
	}

	private static void moveOut(Container container, Item item, int count) {
		for (int slot = 0; slot < container.getContainerSize() && count > 0; slot++) {
			ItemStack stack = container.getItem(slot);
			if (!stack.is(item)) continue;
			int some = Math.min(count, stack.getCount());
			stack.shrink(some);
			count -= some;
		}
		container.setChanged();
	}
}
