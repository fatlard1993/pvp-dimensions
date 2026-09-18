package justfatlard.pvp_dimensions.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import justfatlard.pvp_dimensions.Access;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Spies;
import justfatlard.pvp_dimensions.arena.Places;
import justfatlard.pvp_dimensions.arena.Portals;
import justfatlard.pvp_dimensions.arena.Teams;
import justfatlard.pvp_dimensions.arena.Travel;
import justfatlard.pvp_dimensions.arena.Visit;
import justfatlard.pvp_dimensions.preset.Field;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.ui.ItemSessions;
import justfatlard.pvp_dimensions.ui.Menus;
import justfatlard.pvp_dimensions.world.SavedTerrains;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * {@code /pvp}: everything the menus do, as commands, so a vanilla client can do all of it and the
 * menus' buttons are nothing but these run for you.
 */
public final class PvpCommands {
	private PvpCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("pvp")
			.executes(context -> run(context, player -> Menus.open(player)));

		root.then(Commands.literal("start").requires(source -> Access.of(source).atLeast(Access.Tier.USER))
			.then(Commands.argument("preset", StringArgumentType.word()).suggests(PvpCommands::startablePresets)
				.executes(context -> start(context, Arenas.Start.standard()))
				.then(Commands.literal("private").executes(context -> start(context, new Arenas.Start(Arenas.Invite.NOBODY, List.of(), true, true))))
				.then(Commands.literal("invite").then(Commands.argument("who", StringArgumentType.greedyString()).suggests(PvpCommands::playerWords)
					.executes(context -> {
						Invitees who = invitees(context.getSource().getServer(), StringArgumentType.getString(context, "who"));
						for (String name : who.unknown()) context.getSource().sendFailure(Say.line("Nobody called " + name + " is online"));
						return start(context, who.everyone() ? Arenas.Start.standard()
							: new Arenas.Start(Arenas.Invite.CHOSEN, ids(who.players()), true, true));
					})))));

		root.then(Commands.literal("join")
			.executes(context -> run(context, player -> joinAny(player)))
			.then(Commands.argument("arena", StringArgumentType.word()).suggests(PvpCommands::arenas)
				.executes(context -> run(context, player -> {
					Arena arena = arena(context, player);
					if (arena != null) Travel.join(player, arena);
				}))));

		root.then(Commands.literal("leave").executes(context -> run(context, PvpCommands::leave)));

		// Naming somebody and agreeing to a name: the whole of the odd one out that is not talking.
		root.then(Commands.literal("accuse").then(Commands.argument("player", EntityArgument.player())
			.executes(context -> run(context, player -> {
				Arena arena = Arenas.of(player);
				if (arena == null) {
					Say.to(player, "You are not in an arena");
					return;
				}
				try {
					Spies.accuse(player.level().getServer(), arena, player,
						EntityArgument.getPlayer(context, "player"));
				} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
					Say.to(player, "No such player here");
				}
			}))));
		root.then(Commands.literal("agree").executes(context -> run(context, player -> {
			Arena arena = Arenas.of(player);
			if (arena == null) {
				Say.to(player, "You are not in an arena");
				return;
			}
			Spies.agree(player.level().getServer(), arena, player);
		})));

		root.then(Commands.literal("loadout")
			.executes(context -> run(context, player -> {
				Arena arena = Arenas.of(player);
				if (arena == null || !arena.preset.picksLoadout()) Say.to(player, "There are no loadouts to pick from here");
				else justfatlard.pvp_dimensions.arena.Loadouts.offer(player, arena);
			}))
			.then(Commands.argument("number", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 20))
				.executes(context -> run(context, player -> {
					Arena arena = Arenas.of(player);
					Arena.Member member = arena == null ? null : arena.member(player.getUUID());
					if (member == null || !arena.preset.picksLoadout()) {
						Say.to(player, "There are no loadouts to pick from here");
						return;
					}
					int number = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "number");
					if (number > arena.preset.loadoutCount()) Say.to(player, "There are only " + arena.preset.loadoutCount() + " loadouts");
					else justfatlard.pvp_dimensions.arena.Loadouts.pick(player, arena, member, number - 1);
				}))));

		root.then(Commands.literal("begin")
			.executes(context -> run(context, player -> begin(player, Arenas.of(player))))
			.then(Commands.argument("arena", StringArgumentType.word()).suggests(PvpCommands::arenas)
				.executes(context -> run(context, player -> begin(player, arena(context, player))))));

		root.then(Commands.literal("team").then(Commands.argument("colour", StringArgumentType.word())
			.suggests((context, builder) -> SharedSuggestionProvider.suggest(TeamColors.ALL.stream().map(c -> c.name().toLowerCase(Locale.ROOT)), builder))
			.executes(context -> run(context, player -> team(player, StringArgumentType.getString(context, "colour"))))));

		root.then(Commands.literal("end")
			.executes(context -> run(context, player -> end(player, Arenas.of(player))))
			.then(Commands.argument("arena", StringArgumentType.word()).suggests(PvpCommands::arenas)
				.executes(context -> {
					Arena arena = Arenas.get(context.getSource().getServer(), StringArgumentType.getString(context, "arena"));
					ServerPlayer player = context.getSource().getPlayer();
					if (player == null) {
						if (arena == null) return fail(context, "No arena called that");
						Arenas.end(context.getSource().getServer(), arena, "ended by the server");
						return 1;
					}
					end(player, arena);
					return 1;
				})));

		// Plain words rather than a player selector: the game only lets operators use @a and the
		// like, and inviting everyone online is no more than starting an arena already does.
		root.then(Commands.literal("invite").then(Commands.argument("who", StringArgumentType.greedyString()).suggests(PvpCommands::inviteWords)
			.executes(context -> run(context, player -> invite(player, invitees(player.level().getServer(), StringArgumentType.getString(context, "who")))))));

		root.then(Commands.literal("light").then(Commands.argument("arena", StringArgumentType.word()).suggests(PvpCommands::arenas)
			.executes(context -> run(context, player -> light(player, arena(context, player))))));

		root.then(Commands.literal("list").executes(context -> {
			List<Arena> running = Arenas.running(context.getSource().getServer());
			if (running.isEmpty()) context.getSource().sendSuccess(() -> Say.line("No arenas running"), false);
			for (Arena arena : running) context.getSource().sendSuccess(() -> Arenas.summary(arena), false);
			return running.size();
		}));

		root.then(Commands.literal("done").executes(context -> run(context, ItemSessions::done)));
		root.then(Commands.literal("cancel").executes(context -> run(context, ItemSessions::cancel)));

		root.then(presets());
		root.then(terrain());

		root.then(Commands.literal("grant").requires(source -> Access.of(source) == Access.Tier.ADMIN)
			.then(Commands.argument("player", EntityArgument.player())
				.then(Commands.literal("admin").executes(context -> grant(context, Access.Tier.ADMIN)))
				.then(Commands.literal("user").executes(context -> grant(context, Access.Tier.USER)))));
		root.then(Commands.literal("revoke").requires(source -> Access.of(source) == Access.Tier.ADMIN)
			.then(Commands.argument("player", EntityArgument.player()).executes(context -> grant(context, Access.Tier.NONE))));

		if (DebugPictures.ON) {
			root.then(Commands.literal("debug").requires(source -> Access.of(source) == Access.Tier.ADMIN)
				.then(Commands.argument("arena", StringArgumentType.word()).suggests(PvpCommands::arenas).executes(context -> {
					Arena arena = Arenas.get(context.getSource().getServer(), StringArgumentType.getString(context, "arena"));
					if (arena == null) return fail(context, "No arena");
					var level = Arenas.level(context.getSource().getServer(), arena);
					try {
						var folder = DebugPictures.draw(level, arena);
						context.getSource().sendSuccess(() -> Component.literal("Drawn to " + folder + "; center "
							+ arena.footprint().center(arena.surfaceY).toShortString() + " phase " + arena.phase
							+ " bases " + arena.bases.stream().map(net.minecraft.core.BlockPos::toShortString).toList()
							+ " exits " + arena.exits.stream().limit(1).map(net.minecraft.core.BlockPos::toShortString).toList()), false);
					} catch (java.io.IOException e) {
						return fail(context, e.toString());
					}
					return 1;
				})));
		}

		dispatcher.register(root);
	}

	// --- Arenas ---

	private static int start(CommandContext<CommandSourceStack> context, Arenas.Start options) {
		CommandSourceStack source = context.getSource();
		String id = StringArgumentType.getString(context, "preset");
		Preset preset = Presets.get(id);
		if (preset == null) return fail(context, "No preset called " + id);
		ServerPlayer player = source.getPlayer();
		if (Access.of(source) != Access.Tier.ADMIN && !preset.forUsers) return fail(context, "That preset is kept for admins");
		if (player != null && Visit.of(player) != null) return fail(context, "Leave your arena before starting another");
		Arenas.Start actual = player == null ? new Arenas.Start(options.invite(), options.chosen(), false, false) : options;
		Arena arena = Arenas.start(source.getServer(), player, id, preset, actual, words -> source.sendFailure(Component.literal(words)));
		if (arena == null) return 0;
		source.sendSuccess(() -> Say.line("Making " + arena.title() + "; it opens in a moment"), true);
		return 1;
	}

	private static void joinAny(ServerPlayer player) {
		for (Arena arena : Arenas.running(player.level().getServer())) {
			if (arena.invitedOrOpen(player.getUUID())) {
				Travel.join(player, arena);
				return;
			}
		}
		Say.to(player, "No arena you're invited to is running");
	}

	private static void leave(ServerPlayer player) {
		Visit visit = Visit.of(player);
		if (visit == null) {
			Say.to(player, "You aren't in an arena");
			return;
		}
		Arena arena = Arenas.get(player.level().getServer(), visit.arena());
		if (arena != null && arena.phase != Arena.Phase.ENDED && !arena.preset.exitCommand && !Access.admin(player)) {
			Say.to(player, arena.title() + " has no leave command" + (arena.preset.exitPortal ? "; find an exit portal" : ""));
			return;
		}
		Travel.goHome(player, arena, Travel.Why.COMMAND);
	}

	private static void begin(ServerPlayer player, @Nullable Arena arena) {
		if (arena == null) {
			Say.to(player, "You aren't in an arena");
			return;
		}
		if (!arena.host.equals(player.getUUID()) && !Access.admin(player)) {
			Say.to(player, "Only " + arena.hostName + " can start the fight");
			return;
		}
		if (arena.phase != Arena.Phase.LOBBY) {
			Say.to(player, arena.phase == Arena.Phase.LIVE ? "The fight has already started" : arena.title() + " isn't ready yet");
			return;
		}
		Arenas.goLive(player.level().getServer(), arena);
	}

	private static void team(ServerPlayer player, String colour) {
		Arena arena = Arenas.of(player);
		if (arena == null || !arena.preset.teamsOn() || arena.phase != Arena.Phase.LOBBY && arena.phase != Arena.Phase.LIVE) {
			Say.to(player, "Teams are picked in a waiting room, or just after arriving");
			return;
		}
		int team = TeamColors.byName(colour);
		if (team < 0 || team >= arena.preset.teams) {
			Say.to(player, "No team called " + colour);
			return;
		}
		Arena.Member member = arena.member(player.getUUID());
		if (member == null) return;
		if (arena.phase == Arena.Phase.LIVE) {
			String problem = justfatlard.pvp_dimensions.arena.Travel.changeTeam(player, arena, member, team);
			if (problem != null) Say.to(player, problem);
			return;
		}
		Teams.put(player, arena, member, team);
		Say.to(player, "You're on " + TeamColors.of(team).name());
	}

	private static void end(ServerPlayer player, @Nullable Arena arena) {
		if (arena == null) {
			Say.to(player, "No arena to end");
			return;
		}
		if (!arena.host.equals(player.getUUID()) && !Access.admin(player)) {
			Say.to(player, "Only " + arena.hostName + " or an admin can end " + arena.title());
			return;
		}
		Arenas.end(player.level().getServer(), arena, "ended by " + player.getGameProfile().name());
	}

	/** Who {@code /pvp invite} names: players, everyone, and perhaps which arena, in any order. */
	private record Invitees(List<ServerPlayer> players, List<String> unknown, boolean everyone, @Nullable Arena arena) {}

	private static final java.util.Set<String> EVERYONE = java.util.Set.of("everyone", "all", "@a");

	private static Invitees invitees(MinecraftServer server, String words) {
		List<ServerPlayer> players = new ArrayList<>();
		List<String> unknown = new ArrayList<>();
		boolean everyone = false;
		Arena arena = null;
		for (String word : words.split("[\\s,]+")) {
			if (word.isEmpty()) continue;
			Arena named = Arenas.get(server, word);
			ServerPlayer player = server.getPlayerList().getPlayerByName(word);
			if (EVERYONE.contains(word.toLowerCase(Locale.ROOT))) {
				everyone = true;
				for (ServerPlayer online : server.getPlayerList().getPlayers()) if (!players.contains(online)) players.add(online);
			} else if (named != null && named.phase != Arena.Phase.ENDED) {
				arena = named;
			} else if (player != null) {
				if (!players.contains(player)) players.add(player);
			} else {
				unknown.add(word);
			}
		}
		return new Invitees(players, unknown, everyone, arena);
	}

	/**
	 * Into the arena named, or the one the inviter is in, or else the newest one they host: whoever
	 * started an arena from out in the world can still invite people to it from there.
	 */
	private static void invite(ServerPlayer player, Invitees who) {
		MinecraftServer server = player.level().getServer();
		Arena arena = who.arena() != null ? who.arena() : Arenas.of(player);
		if (arena == null) {
			for (Arena running : Arenas.running(server)) if (running.host.equals(player.getUUID())) arena = running;
		}
		for (String name : who.unknown()) Say.to(player, "Nobody called " + name + " is online");
		if (arena == null) {
			Say.to(player, "No arena to invite anyone to: start one, or name it, like /pvp invite Steve a3");
			return;
		}
		if (!arena.host.equals(player.getUUID()) && !Access.admin(player) && !arena.members.containsKey(player.getUUID())) {
			Say.to(player, "Only players in " + arena.title() + " can invite");
			return;
		}
		List<ServerPlayer> invited = who.players().stream().filter(target -> target != player).toList();
		if (invited.isEmpty()) {
			if (who.everyone()) Say.to(player, "Nobody else is online");
			else if (!who.players().isEmpty()) Say.to(player, "You're already in " + arena.title());
			else if (who.unknown().isEmpty()) Say.to(player, "Name who to invite, or everyone");
			return;
		}
		for (ServerPlayer target : invited) Arenas.invite(server, arena, target.getUUID(), player);
		Say.to(player, "Invited " + invited.size() + (invited.size() == 1 ? " player" : " players") + " to " + arena.title());
	}

	private static void light(ServerPlayer player, @Nullable Arena arena) {
		if (arena == null || !arena.open() && arena.phase != Arena.Phase.GENERATING) {
			Say.to(player, "No arena like that is running");
			return;
		}
		if (!arena.host.equals(player.getUUID()) && !Access.admin(player)) {
			Say.to(player, "Only " + arena.hostName + " or an admin can light a way in");
			return;
		}
		if (Places.isArena(player.level().dimension())) {
			Say.to(player, "Frames are lit out in the world, not inside an arena");
			return;
		}
		int lit = Portals.lightNear(player.level(), player.position(), arena);
		Arenas.vault(player.level().getServer()).touch();
		Say.to(player, lit > 0 ? "Lit; it leads into " + arena.title() : "No empty obsidian frame within a few blocks");
	}

	// --- Presets ---

	private static LiteralArgumentBuilder<CommandSourceStack> presets() {
		return Commands.literal("preset").requires(source -> Access.of(source) == Access.Tier.ADMIN)
			.then(Commands.literal("list").executes(context -> {
				for (var entry : Presets.all()) {
					Preset preset = entry.getValue();
					context.getSource().sendSuccess(() -> Component.literal(entry.getKey() + ": " + preset.name
						+ (preset.forUsers ? "" : " (admins only)")), false);
				}
				return Presets.all().size();
			}))
			.then(Commands.literal("show").then(presetArgument()
				.executes(context -> run(context, player -> Menus.edit(player, presetId(context), Field.Section.GAME)))
				.then(Commands.argument("section", StringArgumentType.word())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						java.util.Arrays.stream(Field.Section.values()).map(s -> s.name().toLowerCase(Locale.ROOT)), builder))
					.executes(context -> run(context, player -> {
						Field.Section section;
						try {
							section = Field.Section.valueOf(StringArgumentType.getString(context, "section").toUpperCase(Locale.ROOT));
						} catch (IllegalArgumentException e) {
							section = Field.Section.GAME;
						}
						Menus.edit(player, presetId(context), section);
					})))))
			.then(Commands.literal("new")
				.executes(context -> {
					ServerPlayer player = context.getSource().getPlayer();
					if (player == null) return fail(context, "Pick a kind from a player");
					Menus.newPreset(player);
					return 1;
				})
				.then(Commands.literal("kind").then(Commands.argument("kind", StringArgumentType.word())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						java.util.Arrays.stream(Kinds.values()).filter(Kinds::here).map(Enum::name), builder))
					.executes(context -> {
						Kinds kind;
						try {
							kind = Kinds.valueOf(StringArgumentType.getString(context, "kind").toUpperCase(Locale.ROOT));
						} catch (IllegalArgumentException e) {
							return fail(context, "No kind called " + StringArgumentType.getString(context, "kind"));
						}
						String id = Presets.save(null, kind.make());
						context.getSource().sendSuccess(() -> Say.line("Made " + kind.label + " (" + id + ")"), false);
						ServerPlayer player = context.getSource().getPlayer();
						if (player != null) Menus.edit(player, id, Field.Section.GAME);
						return 1;
					})))
				.then(Commands.argument("name", StringArgumentType.greedyString())
				.executes(context -> {
					Preset preset = new Preset();
					preset.name = trimName(StringArgumentType.getString(context, "name"));
					String id = Presets.save(null, preset);
					context.getSource().sendSuccess(() -> Say.line("Made " + preset.name + " (" + id + ")"), false);
					ServerPlayer player = context.getSource().getPlayer();
					if (player != null) Menus.edit(player, id, Field.Section.GAME);
					return 1;
				})))
			.then(Commands.literal("copy").then(presetArgument().then(Commands.argument("name", StringArgumentType.greedyString())
				.executes(context -> {
					Preset original = Presets.get(presetId(context));
					if (original == null) return fail(context, "No preset called " + presetId(context));
					Preset copy = Presets.copy(original);
					copy.name = trimName(StringArgumentType.getString(context, "name"));
					String id = Presets.save(null, copy);
					context.getSource().sendSuccess(() -> Say.line("Copied to " + copy.name + " (" + id + ")"), false);
					ServerPlayer player = context.getSource().getPlayer();
					if (player != null) Menus.edit(player, id, Field.Section.GAME);
					return 1;
				}))))
			.then(Commands.literal("delete").then(presetArgument().executes(context -> {
				if (!Presets.delete(presetId(context))) return fail(context, "No preset called " + presetId(context));
				context.getSource().sendSuccess(() -> Say.line("Deleted " + presetId(context)), false);
				return 1;
			})))
			.then(Commands.literal("export").then(presetArgument().executes(context -> {
				Preset preset = Presets.get(presetId(context));
				if (preset == null) return fail(context, "No preset called " + presetId(context));
				String said = justfatlard.pvp_dimensions.preset.Sharing.export(presetId(context), preset);
				context.getSource().sendSuccess(() -> Say.line(said), false);
				return 1;
			})))
			.then(Commands.literal("import").then(Commands.argument("file", StringArgumentType.word())
				.suggests((context, builder) -> SharedSuggestionProvider.suggest(
					justfatlard.pvp_dimensions.preset.Sharing.offers(context.getSource().getServer()).stream().map(offer -> offer.file()), builder))
				.executes(context -> {
					List<String> told = new java.util.ArrayList<>();
					String id = justfatlard.pvp_dimensions.preset.Sharing.importFile(context.getSource().getServer(),
						StringArgumentType.getString(context, "file"), told);
					for (String line : told) context.getSource().sendSuccess(() -> Say.line(line), false);
					if (id == null) return 0;
					ServerPlayer player = context.getSource().getPlayer();
					if (player != null) Menus.edit(player, id, Field.Section.GAME);
					return 1;
				})))
			.then(Commands.literal("set").then(presetArgument().then(fieldArgument()
				.then(Commands.argument("value", StringArgumentType.greedyString()).suggests(PvpCommands::values)
					.executes(context -> edit(context, (preset, field) -> field.set(preset, StringArgumentType.getString(context, "value"))))))))
			.then(Commands.literal("step").then(presetArgument().then(fieldArgument()
				.then(Commands.argument("by", IntegerArgumentType.integer(-100, 100))
					.executes(context -> edit(context, (preset, field) -> {
						int by = IntegerArgumentType.getInteger(context, "by");
						if (field instanceof Field.Choice choice) choice.step(preset, by);
						else if (field instanceof Field.Number number) number.step(preset, by);
						else if (field instanceof Field.Toggle toggle) toggle.flip(preset);
						else return "Nothing to step";
						return null;
					}))))))
			.then(Commands.literal("held").then(presetArgument().then(fieldArgument()
				.executes(context -> edit(context, (preset, field) -> held(context.getSource().getPlayer(), preset, field))))))
			.then(Commands.literal("do").then(presetArgument().then(fieldArgument()
				.executes(context -> edit(context, (preset, field) -> {
					if (field.key.equals("swap.add")) return held(context.getSource().getPlayer(), preset, field);
					if (!(field instanceof Field.Action action)) return "That isn't a button";
					action.run(preset);
					return null;
				})))))
			.then(Commands.literal("shuffle").then(presetArgument().then(fieldArgument()
				.executes(context -> edit(context, (preset, field) -> {
					if (!(field instanceof Field.Items items) || !items.canShuffle()) return "That isn't a list that can be shuffled";
					items.shuffle(preset);
					return null;
				})))))
			.then(Commands.literal("items").then(presetArgument().then(fieldArgument()
				.executes(context -> run(context, player -> ItemSessions.start(player, presetId(context), fieldKey(context)))))));
	}

	@FunctionalInterface
	private interface Edit {
		@Nullable String apply(Preset preset, Field field);
	}

	/** Change one setting of a saved preset, save it, and show the section it is in. */
	private static int edit(CommandContext<CommandSourceStack> context, Edit edit) {
		String id = presetId(context);
		Preset preset = Presets.get(id);
		if (preset == null) return fail(context, "No preset called " + id);
		Field field = Fields.find(preset, fieldKey(context));
		if (field == null) return fail(context, "No setting called " + fieldKey(context) + " here");
		String problem = edit.apply(preset, field);
		if (problem != null) return fail(context, problem);
		Presets.save(id, preset);
		ServerPlayer player = context.getSource().getPlayer();
		if (player != null) Menus.edit(player, id, field.section);
		else context.getSource().sendSuccess(() -> Component.literal(field.label + ": " + field.display(Presets.get(id))), false);
		return 1;
	}

	/** A block setting from the block in hand; the picture from any item in hand; a new swap row. */
	private static @Nullable String held(@Nullable ServerPlayer player, Preset preset, Field field) {
		if (player == null) return "Only a player holds anything";
		ItemStack stack = player.getMainHandItem();
		if (stack.isEmpty()) return "Hold something first";
		String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		if (field.key.equals("icon")) {
			preset.icon = item;
			return null;
		}
		if (!(stack.getItem() instanceof BlockItem blockItem)) return "That isn't a block";
		String block = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
		if (field.key.equals("swap.add")) {
			preset.swaps.putIfAbsent(block, block);
			return null;
		}
		return field.set(preset, block);
	}

	// --- Saved terrain ---

	private static LiteralArgumentBuilder<CommandSourceStack> terrain() {
		return Commands.literal("terrain").requires(source -> Access.of(source) == Access.Tier.ADMIN)
			.then(Commands.literal("list").executes(context -> {
				List<String> names = SavedTerrains.names();
				context.getSource().sendSuccess(() -> Say.line(names.isEmpty() ? "No saved terrains" : "Saved: " + String.join(", ", names)), false);
				return names.size();
			}))
			.then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.word())
				.executes(context -> run(context, player -> saveTerrain(player, StringArgumentType.getString(context, "name"))))))
			.then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word())
				.suggests((context, builder) -> SharedSuggestionProvider.suggest(SavedTerrains.names(), builder))
				.executes(context -> {
					String name = StringArgumentType.getString(context, "name");
					if (!SavedTerrains.delete(name)) return fail(context, "No saved terrain called " + name);
					context.getSource().sendSuccess(() -> Say.line("Deleted " + name), false);
					return 1;
				})));
	}

	private static void saveTerrain(ServerPlayer player, String name) {
		Arena arena = Arenas.of(player);
		if (arena == null) {
			Say.to(player, "Stand in the arena you want to keep");
			return;
		}
		if (arena.lobbyStanding) {
			Say.to(player, "The waiting room is still up; start the fight first so it isn't saved with the ground");
			return;
		}
		var level = Arenas.level(player.level().getServer(), arena);
		if (level == null) return;
		String biome = arena.terrain().biome().unwrapKey().map(key -> key.identifier().toString()).orElse("minecraft:plains");
		Say.to(player, "Saving " + arena.chunks * arena.chunks + " chunks as " + SavedTerrains.clean(name) + "...");
		SavedTerrains.save(level, arena.footprint(), name, biome, arena.surfaceY, arena.highestY, arena.wallTop, written -> {
			if (written < 0) Say.to(player, "Saving failed; the server log says why");
			else Say.to(player, "Saved " + SavedTerrains.clean(name) + ". A preset with Ground: Saved can use it now");
		});
	}

	// --- Access ---

	private static int grant(CommandContext<CommandSourceStack> context, Access.Tier tier) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(context, "player");
		Access.grant(target, tier);
		context.getSource().getServer().getCommands().sendCommands(target);
		String words = tier == Access.Tier.NONE ? "no longer starts arenas" : "is now a PvP Dimensions " + tier.name().toLowerCase(Locale.ROOT);
		context.getSource().sendSuccess(() -> Say.line(target.getGameProfile().name() + " " + words), true);
		return 1;
	}

	// --- Arguments ---

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> presetArgument() {
		return Commands.argument("preset", StringArgumentType.word())
			.suggests((context, builder) -> SharedSuggestionProvider.suggest(Presets.ids(), builder));
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> fieldArgument() {
		return Commands.argument("field", StringArgumentType.word()).suggests((context, builder) -> {
			Preset preset = Presets.get(presetId(context));
			if (preset == null) return builder.buildFuture();
			return SharedSuggestionProvider.suggest(Fields.of(preset).stream().map(field -> field.key), builder);
		});
	}

	private static String presetId(CommandContext<CommandSourceStack> context) {
		return StringArgumentType.getString(context, "preset");
	}

	private static String fieldKey(CommandContext<CommandSourceStack> context) {
		return StringArgumentType.getString(context, "field");
	}

	private static CompletableFuture<Suggestions> values(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		Preset preset = Presets.get(presetId(context));
		if (preset == null) return builder.buildFuture();
		Field field = Fields.find(preset, fieldKey(context));
		if (field == null) return builder.buildFuture();
		return SharedSuggestionProvider.suggest(field.suggestions(preset), builder);
	}

	private static CompletableFuture<Suggestions> startablePresets(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		boolean admin = Access.of(context.getSource()) == Access.Tier.ADMIN;
		List<String> ids = new ArrayList<>();
		for (var entry : Presets.all()) {
			if (admin || entry.getValue().forUsers) ids.add(entry.getKey());
		}
		return SharedSuggestionProvider.suggest(ids, builder);
	}

	/** Online names and everyone, for the word being typed. */
	private static CompletableFuture<Suggestions> playerWords(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		return lastWord(builder, words(context.getSource().getServer(), false));
	}

	/** The same, and the running arenas, which an invite may name. */
	private static CompletableFuture<Suggestions> inviteWords(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		return lastWord(builder, words(context.getSource().getServer(), true));
	}

	private static List<String> words(MinecraftServer server, boolean arenas) {
		List<String> words = new ArrayList<>(List.of("everyone"));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) words.add(player.getGameProfile().name());
		if (arenas) for (Arena arena : Arenas.running(server)) words.add(arena.id);
		return words;
	}

	private static CompletableFuture<Suggestions> lastWord(SuggestionsBuilder builder, List<String> words) {
		String typed = builder.getRemaining();
		int cut = Math.max(typed.lastIndexOf(' '), typed.lastIndexOf(',')) + 1;
		return SharedSuggestionProvider.suggest(words, builder.createOffset(builder.getStart() + cut));
	}

	private static CompletableFuture<Suggestions> arenas(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(Arenas.running(context.getSource().getServer()).stream().map(arena -> arena.id), builder);
	}

	private static @Nullable Arena arena(CommandContext<CommandSourceStack> context, ServerPlayer player) {
		String id = StringArgumentType.getString(context, "arena");
		Arena arena = Arenas.get(player.level().getServer(), id);
		if (arena == null || arena.phase == Arena.Phase.ENDED) {
			Say.to(player, "No arena called " + id + " is running");
			return null;
		}
		return arena;
	}

	private static List<UUID> ids(Collection<ServerPlayer> players) {
		return players.stream().map(ServerPlayer::getUUID).toList();
	}

	private static String trimName(String name) {
		String trimmed = name.strip();
		return trimmed.length() > 24 ? trimmed.substring(0, 24) : trimmed;
	}

	@FunctionalInterface
	private interface PlayerAction {
		void accept(ServerPlayer player) throws CommandSyntaxException;
	}

	private static int run(CommandContext<CommandSourceStack> context, PlayerAction action) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) return fail(context, "Only a player can do that");
		action.accept(player);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> context, String words) {
		context.getSource().sendFailure(Component.literal(words));
		return 0;
	}

	static MinecraftServer server(CommandContext<CommandSourceStack> context) {
		return context.getSource().getServer();
	}
}
