package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.check;

import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Travel;
import justfatlard.pvp_dimensions.arena.Visit;
import justfatlard.pvp_dimensions.onboarding.Demo;
import justfatlard.pvp_dimensions.onboarding.Demos;
import justfatlard.pvp_dimensions.onboarding.Progress;
import justfatlard.pvp_dimensions.preset.Preset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A demo, played: read out of the mod's own jar and made safe, a copy made for one player that no
 * menu lists, their pack set aside on the way in and back on the way out, stepping in counted, and
 * the copy closed once they are gone.
 */
public final class DemosPlay implements FabricClientGameTest {

	/**
	 * The shape demo files give enchanted boots and potions in, read: written by hand in other mods'
	 * jars, where a wrong shape is read as a plain item and nobody is told.
	 */
	private static void kitComponentsRead() {
		com.google.gson.JsonObject preset = com.google.gson.JsonParser.parseString("""
			{"preset": {"entry_kit": [
			  {"slot": 36, "item": {"id": "minecraft:diamond_boots", "count": 1, "components": {"minecraft:enchantments": {"minecraft:feather_falling": 2}}}},
			  {"slot": 0, "item": {"id": "minecraft:potion", "count": 2, "components": {"minecraft:potion_contents": {"potion": "minecraft:fire_resistance"}}}}
			]}}""").getAsJsonObject();
		java.util.List<ItemStack> kit = justfatlard.pvp_dimensions.preset.Presets.readFile(preset).entryKit.stacks();
		ItemStack boots = kit.stream().filter(stack -> stack.is(Items.DIAMOND_BOOTS)).findFirst().orElse(ItemStack.EMPTY);
		check(!boots.isEmpty() && boots.isEnchanted(), "enchanted boots in a demo kit came out plain: " + kit);
		ItemStack potion = kit.stream().filter(stack -> stack.is(Items.POTION)).findFirst().orElse(ItemStack.EMPTY);
		var contents = potion.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
		check(contents != null && contents.potion().isPresent(), "a potion in a demo kit came out as water: " + potion);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			server.runOnServer(s -> {
				Demo demo = Demos.get(PvpDimensions.MOD_ID);
				check(demo != null, "this mod's own demo was not read: " + Demos.all());
				Preset preset = demo.preset();
				check(!preset.waitingRoom && preset.isolated() && preset.exitCommand && !preset.rewardsOut()
					&& preset.prizes.isEmpty() && preset.entryFee.isEmpty(), "the demo's preset was not made safe");
				check(demo.finishes(), "the demo says it finishes, and that was lost");

				kitComponentsRead();

				ServerPlayer player = connection.getServerPlayer();
				player.getInventory().clearContent();
				player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
				Demos.play(player, demo.id());
			});
			server.waitFor(s -> Visit.of(connection.getServerPlayer()) != null, 2400);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				Arena arena = Arenas.of(player);
				check(arena != null && arena.demo.equals(PvpDimensions.MOD_ID), "the player is not in the demo");
				check(arena.title().equals("PvP Dimensions demo"), "the demo is titled like an arena: " + arena.title());
				check(Arenas.running(s).isEmpty(), "the demo is listed among the arenas anyone can join");
				check(Arenas.demos(s).size() == 1, "the demo is not counted as one");
				check(player.getInventory().countItem(Items.DIAMOND) == 0, "the player's own pack came into the demo");
				Progress progress = Progress.of(player);
				check(progress.entered().contains(arena.demo), "stepping into the demo was not counted");
				check(!progress.played(Demos.get(arena.demo)), "a demo with a finish counted as played on the way in");

				Travel.goHome(player, arena, Travel.Why.COMMAND);
				check(player.getInventory().countItem(Items.DIAMOND) == 3, "the player's own pack did not come back");
			});
			server.waitFor(s -> Arenas.demos(s).isEmpty(), 200);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				Progress.finish(player, PvpDimensions.MOD_ID);
				check(Progress.of(player).played(Demos.get(PvpDimensions.MOD_ID)), "finishing did not count as played");
				Demo next = Demos.next(player, null);
				check(next != null && !next.id().equals(PvpDimensions.MOD_ID), "a played demo was offered as the next: " + next);
			});
		}
	}
}
