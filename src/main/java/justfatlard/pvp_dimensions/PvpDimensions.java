package justfatlard.pvp_dimensions;

import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Combat;
import justfatlard.pvp_dimensions.arena.DeathCompasses;
import justfatlard.pvp_dimensions.arena.Goals;
import justfatlard.pvp_dimensions.arena.Horde;
import justfatlard.pvp_dimensions.arena.Mobs;
import justfatlard.pvp_dimensions.arena.Places;
import justfatlard.pvp_dimensions.arena.Portals;
import justfatlard.pvp_dimensions.arena.TeamChests;
import justfatlard.pvp_dimensions.arena.Travel;
import justfatlard.pvp_dimensions.arena.Visit;
import justfatlard.pvp_dimensions.command.PvpCommands;
import justfatlard.pvp_dimensions.integration.ChestUtils;
import justfatlard.pvp_dimensions.integration.DeadHeads;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.ui.ItemSessions;
import justfatlard.pvp_dimensions.ui.Screens;
import justfatlard.pvp_dimensions.world.ArenaBiomes;
import justfatlard.pvp_dimensions.world.ArenaGenerator;
import justfatlard.pvp_dimensions.world.Footprints;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Arenas on demand. An admin, or a user from an admin's preset, starts one from a menu; it is
 * made on the spot as a plot of its own in one of three arena dimensions, entered by invitation
 * or through an empty obsidian frame lit beside whoever started it, and gone when its time is up.
 */
public class PvpDimensions implements ModInitializer {
	public static final String MOD_ID = "pvp-dimensions-justfatlard";
	public static final Logger LOGGER = LoggerFactory.getLogger("pvp-dimensions");

	private static volatile @Nullable MinecraftServer server;

	public static @Nullable MinecraftServer server() {
		return server;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("arena"), ArenaGenerator.CODEC);
		Registry.register(BuiltInRegistries.BIOME_SOURCE, id("arena"), ArenaBiomes.CODEC);
		Access.init();
		Visit.init();
		ItemSessions.init();
		Arenas.init();
		ServerConfig.load();

		ServerLifecycleEvents.SERVER_STARTING.register(starting -> {
			server = starting;
			Arenas.beforeLevels(starting);
		});
		ServerLevelEvents.LOAD.register((loaded, level) -> {
			if (level.getChunkSource().getGenerator() instanceof ArenaGenerator generator) generator.bind(level.dimension());
		});
		ServerLifecycleEvents.SERVER_STARTED.register(Arenas::afterLevels);
		ServerLifecycleEvents.SERVER_STOPPING.register(Arenas::stopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
			Footprints.clear();
			server = null;
		});
		ServerTickEvents.END_SERVER_TICK.register(Arenas::tick);

		ServerPlayerEvents.JOIN.register(player -> {
			Travel.loggedIn(player);
			ItemSessions.loggedIn(player);
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((old, fresh, alive) -> {
			if (!alive) Combat.respawned(fresh);
		});
		DeathCompasses.register();
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) Combat.died(player, source);
			else Combat.mobDied(entity);
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> Combat.allowDamage(entity));
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (taken > 0) justfatlard.pvp_dimensions.arena.Moments.hurt(entity, source);
		});
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player) Goals.carrierDying(player);
			return true;
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(Travel::changedLevel);
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (!Places.isArena(level.dimension())) return;
			if (entity instanceof net.minecraft.world.entity.item.PrimedTnt tnt) justfatlard.pvp_dimensions.arena.Traps.primed(tnt, level);
			if (Mobs.allowed(entity, level)) Mobs.loaded(entity);
			else entity.discard();
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (!(level instanceof ServerLevel serverLevel) || !Places.isArena(level.dimension())) return true;
			Arena arena = Arenas.at(serverLevel, pos.getX(), pos.getZ());
			if (arena == null || !Goals.protectedBlock(arena, pos, player instanceof ServerPlayer breaker ? breaker : null)) return true;
			if (player instanceof ServerPlayer told) Say.bar(told, Goals.wallHolds(arena, pos) ? "The wall holds until it falls" : "That can't be broken");
			return false;
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, disconnecting) -> {
			Screens.forget(handler.getPlayer().getUUID());
			justfatlard.pvp_dimensions.ui.GameHud.forget(handler.getPlayer().getUUID());
			justfatlard.pvp_dimensions.arena.Loadouts.forget(handler.getPlayer().getUUID());
			justfatlard.pvp_dimensions.arena.Moments.forget(handler.getPlayer().getUUID());
		});

		// An ender chest is the one pack that is the same everywhere: in an arena that keeps
		// inventories apart it would carry the arena's winnings out and anybody's gear in.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(player instanceof ServerPlayer serverPlayer) || !Places.isArena(level.dimension())) return InteractionResult.PASS;
			if (Horde.is(serverPlayer) && level.getBlockEntity(hit.getBlockPos()) != null) {
				Say.bar(serverPlayer, "The horde doesn't open things");
				return InteractionResult.FAIL;
			}
			InteractionResult chest = TeamChests.use(serverPlayer, (ServerLevel) level, hit.getBlockPos());
			if (chest != InteractionResult.PASS) return chest;
			if (!level.getBlockState(hit.getBlockPos()).is(Blocks.ENDER_CHEST)) return InteractionResult.PASS;
			Arena arena = Arenas.of(serverPlayer);
			if (arena == null || !arena.preset.isolated() && arena.preset.activeGoal() != Preset.Goal.CTF) return InteractionResult.PASS;
			Say.bar(serverPlayer, "Ender chests are shut in here");
			return InteractionResult.FAIL;
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> PvpCommands.register(dispatcher));

		PandoricalApi.portals().keepOutOfPairing(Portals::ours);
		// Taking from another team's chest is half of some games; nobody's lock gets in the way.
		ChestUtils.refuseLocking((level, pos) -> Places.isArena(level.dimension()));
		DeadHeads.lockTimeAt((level, pos) -> {
			if (!Places.isArena(level.dimension())) return null;
			Arena arena = Arenas.at(level, pos.getX(), pos.getZ());
			return arena == null || arena.preset.headLock < 0 ? null : arena.preset.headLock;
		});
		Screens.register();
		ServerConfig.menu();

		LOGGER.info("PvP Dimensions loaded");
	}
}
