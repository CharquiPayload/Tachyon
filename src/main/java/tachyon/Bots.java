package tachyon;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import tachyon.path.Route;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.ScoreHolderArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.ScoreHolder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bots that live on the server: players nobody plays, with a player's body, hands and
 * physics, and a brain that is a model behind an API (see {@link Brain}).
 *
 * <p>A bot is a {@link BotPlayer} let in through the door a joining player uses
 * (it is in TAB, it is seen, it loads chunks), with a connection that goes nowhere. Its
 * body is a player's, with a player's physics, and it walks by pressing a player's keys
 * along the routes of its path finder ({@link Route}), the way Masurium's client bots
 * walk them. The route is searched on threads of their own over the loaded
 * chunks, so that what the tick pays is the body and a look at where it goes.
 * {@code /tachyon stats} says what that costs, next to {@code /tick query}.
 *
 * <pre>
 *   /tachyon spawn &lt;name&gt; [count]   a bot where you stand; with a count,
 *                                          that many: name1, name2...
 *   /tachyon goto &lt;who&gt; &lt;x y z&gt;     walks there
 *   /tachyon follow &lt;who&gt; &lt;player&gt;  walks after them
 *   /tachyon stop &lt;who&gt;             stands still
 *   /tachyon remove &lt;who&gt;           leaves
 *   /tachyon hunt &lt;who&gt; &lt;mob&gt; [count] kills that many each (every one around)
 *   /tachyon clear &lt;who&gt; &lt;from&gt; &lt;to&gt; breaks every block in the box
 *   /tachyon tell &lt;who&gt; &lt;words&gt;   as if said to it in the chat
 *   /tachyon brain [reload]         how they think (tachyon.properties)
 *   /tachyon owner &lt;who&gt; [player]   whose it is, or give it to them
 *   /tachyon list | stats [reset]
 * </pre>
 *
 * <p>Operators order every bot; any other player, the bots that are theirs (who
 * brought it in, or whom an operator gave it to). Bringing them in, handing them over,
 * the brain and the figures are the operators'.
 *
 * <p>{@code <who>} is a bot's name, or a pattern where {@code *} stands for any run
 * of characters ({@code *} is every bot, {@code Bot*} every one whose name starts so),
 * or a selector ({@code @e[distance=..10]}): an order to many, for stress tests.
 */
public final class Bots {

    private static final Logger LOG = LogUtils.getLogger();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** At most this many bots from one spawn. */
    private static final int SPAWN_MAX = 100;
    /** At most this many blocks in a box to clear. */
    private static final long CLEAR_MAX = 100_000;

    // The numbers of Masurium's client Walker: tuned against a player's physics, which is
    // what a bot has.
    /** An intermediate point counts as reached within this; the last one, within more. */
    private static final double NEAR = 0.85, NEAR_END = 1.4;
    /** Every this many ticks, less progress than this is being stuck: it jumps. */
    private static final int STUCK_TICKS = 20;
    private static final double MIN_PROGRESS = 0.35;
    /** After this many jumps without progress it searches again, this many times. */
    private static final int JUMPS_MAX = 6, REPLANS_MAX = 3;
    private static final int TICKS_MAX = 20 * 90;
    /** A door is pressed from this close, and closed when this far past it. */
    private static final double PRESS_DOOR = 2.3, DOOR_PASS = 0.9;

    /** A follower plans again this often, and stops this close to whom it follows. */
    static final int REPLAN_TICKS = 20;
    private static final double FOLLOW_GAP = 2.5;
    /** Standing, a follower this close does not set off again: the hysteresis of Masurium's client Follower. */
    private static final double FOLLOW_FAR = 5.0;
    /** A follower searches again only once whom it follows, or itself, moved this much (flat). */
    static final double MOVED = 2.0;
    /** A route that comes back is joined at its point nearest the body, among its first these many. */
    private static final int JOIN_WITHIN = 16;
    /** A follower's goal: any tile this close to the ground under whom it follows. */
    private static final double FOLLOW_NEAR = 2.0;
    /** Half a player's box across: it is 0.6 wide. */
    private static final double HALF_WIDTH = 0.3;
    /** The chunks read around a search: this margin, at most these many. */
    private static final int MARGIN = 24, MAX_CHUNKS = 400;
    /** A search's budget on its own thread; past it, the stretch found is walked. */
    private static final long SEARCH_MS = 2000;

    private static final Map<String, Bot> ALL = new LinkedHashMap<>();
    /**
     * The searches' threads: a few, a quarter of the cores and at most 4. Each search
     * reads a snapshot of its own, so they share nothing but the stats.
     */
    private static final int ROUTE_THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
    private static final AtomicInteger ROUTE_THREAD_N = new AtomicInteger();
    private static final ExecutorService ROUTES = Executors.newFixedThreadPool(ROUTE_THREADS, r -> {
        Thread t = new Thread(r, "tachyon-routes-" + ROUTE_THREAD_N.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    /** What the bots cost the server's thread, per tick, and what their searches took. */
    private static final Stats STATS = new Stats();

    static final class Bot {
        final BotPlayer body;
        String doing = "standing";

        // What it walks, and what for.
        List<Route.Point> path;
        int next;
        boolean partial;
        Future<Route.Result> pending;
        /** Where a goto goes: a partial route is walked and searched again from its end. */
        BlockPos target;
        ServerPlayer following;
        long plannedAt = -REPLAN_TICKS;
        /** Where whom it follows, and itself, stood at its last search (NaN: search anew). */
        double leaderX = Double.NaN, leaderZ, selfX = Double.NaN, selfZ;

        // How the walk goes.
        int ticks, jumps, replans;
        Vec3 lastPos;
        /** The door it opened and has not closed yet, and which side of it it was on. */
        BlockPos doorOpen;
        double doorSide;

        /** What it does beyond walking (hunting, clearing), or null. */
        Job job;
        /**
         * Whose it is: who brought it in, or whom an operator gave it to (null: nobody's).
         * They give it orders, as operators do; and its brain hears them, and only them.
         */
        UUID owner;
        String ownerName;
        /** Its brain, made the first time it is spoken to. */
        Brain brain;
        /** Its last search found no way at all. */
        boolean searchFailed;

        Bot(BotPlayer body) {
            this.body = body;
        }

        String name() {
            return body.getGameProfile().getName();
        }
    }

    private static final class Stats {
        long ticks, nanos, maxNanos, bodyTicks;
        long snapshots, snapshotNanos, maxSnapshotNanos;
        long searches, searchMs, maxSearchMs, nodes;
        /** What the bots' bodies took in the tick running now. */
        long thisTick;

        synchronized void search(long ms, int looked) {
            searches++;
            searchMs += ms;
            maxSearchMs = Math.max(maxSearchMs, ms);
            nodes += looked;
        }

        synchronized String text(int bots) {
            return String.format(Locale.ROOT,
                    "%d bot(s). On the server's thread: %.3f ms a tick for all of them on average,"
                            + " %.3f at most, over %d ticks (%.3f ms a bot); of that, %d route snapshots,"
                            + " %.3f ms each, %.3f at most. Off it: %d searches, %.1f ms each, %d at most,"
                            + " %d tiles looked at.",
                    bots, ticks == 0 ? 0 : nanos / 1e6 / ticks, maxNanos / 1e6, ticks,
                    bodyTicks == 0 ? 0 : nanos / 1e6 / bodyTicks,
                    snapshots, snapshots == 0 ? 0 : snapshotNanos / 1e6 / snapshots, maxSnapshotNanos / 1e6,
                    searches, searches == 0 ? 0 : (double) searchMs / searches, maxSearchMs, nodes);
        }

        synchronized void reset() {
            ticks = nanos = maxNanos = bodyTicks = 0;
            snapshots = snapshotNanos = maxSnapshotNanos = 0;
            searches = searchMs = maxSearchMs = nodes = 0;
        }
    }

    // --- commands ---------------------------------------------------------------------

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tachyon")
                // Orders are anyone's to give, to the bots they own (see find);
                // bringing bots in, handing them over and the server's figures
                // are the operators'.
                .then(Commands.literal("spawn")
                        .requires(Bots::operator)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(c -> spawn(c, 0))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, SPAWN_MAX))
                                        .executes(c -> spawn(c, IntegerArgumentType.getInteger(c, "count"))))))
                .then(Commands.literal("remove")
                        .then(who().executes(Bots::remove)))
                .then(Commands.literal("goto")
                        .then(who()
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(Bots::goTo))))
                .then(Commands.literal("follow")
                        .then(who()
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(Bots::follow))))
                .then(Commands.literal("stop")
                        .then(who().executes(Bots::stop)))
                .then(Commands.literal("hunt")
                        .then(who()
                                .then(Commands.argument("mob", ResourceArgument.resource(event.getBuildContext(), Registries.ENTITY_TYPE))
                                        .executes(c -> hunt(c, 0))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 10_000))
                                                .executes(c -> hunt(c, IntegerArgumentType.getInteger(c, "count")))))))
                .then(Commands.literal("clear")
                        .then(who()
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .executes(Bots::clear)))))
                .then(Commands.literal("tell")
                        .then(who()
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(Bots::tell))))
                .then(Commands.literal("owner")
                        .requires(Bots::operator)
                        .then(who()
                                .executes(Bots::owner)
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(Bots::owner))))
                .then(Commands.literal("brain")
                        .requires(Bots::operator)
                        .executes(c -> say(c.getSource(), "brain: " + Brain.config().describe(null)))
                        .then(Commands.literal("reload").executes(c -> {
                            Brain.reload();
                            return say(c.getSource(), "brain reloaded: " + Brain.config().describe(null));
                        })))
                .then(Commands.literal("list").executes(Bots::list))
                .then(Commands.literal("stats")
                        .requires(Bots::operator)
                        .executes(c -> say(c.getSource(), STATS.text(ALL.size())))
                        .then(Commands.literal("reset").executes(c -> {
                            STATS.reset();
                            return say(c.getSource(), "bot stats reset");
                        }))));
    }

    /**
     * The bots an order is for. Vanilla's score holder argument reads it, the one
     * {@code /scoreboard} takes {@code *} with: it reads any word up to a space (a word
     * argument refuses {@code *}) or a selector, and a client without the mod knows it.
     */
    private static RequiredArgumentBuilder<CommandSourceStack, ScoreHolderArgument.Result> who() {
        return Commands.argument(WHO, ScoreHolderArgument.scoreHolders())
                .suggests((c, b) -> {
                    List<String> names = new ArrayList<>(List.of("*"));
                    for (Bot p : ALL.values()) names.add(p.name());
                    return SharedSuggestionProvider.suggest(names, b);
                });
    }

    private static final String WHO = "who";

    /** @param count 0: one bot, named {@code name}; else that many, name1 to nameN */
    private static int spawn(CommandContext<CommandSourceStack> c, int count) {
        CommandSourceStack source = c.getSource();
        String name = StringArgumentType.getString(c, "name");
        if (count == 0) {
            if (!spawn(source, name)) return 0;
            return say(source, "bot " + name + " is in, at " + ALL.get(key(name)).body.blockPosition().toShortString());
        }
        if (!NAME.matcher(name + count).matches()) {
            return fail(source, name + count + " is no player name: 3 to 16 letters, digits or _");
        }
        // Each on a tile of its own around the spot: a batch on one tile crams, and past
        // the maxEntityCramming game rule (24) the crowd squishes itself to death.
        List<Vec3> spots = spots(source.getLevel(), source.getPosition(), count);
        int in = 0;
        for (int i = 1; i <= count; i++) {
            if (spawn(source, name + i, spots.get(i - 1))) in++;
        }
        return say(source, in + " of " + count + " bots are in: " + name + "1 to " + name + count);
    }

    /**
     * {@code n} places around {@code at}, a tile each, in rings outwards: free for a body,
     * over solid ground, at its height or a block or two up or down. Short of free tiles,
     * the rest stand at {@code at} itself.
     */
    private static List<Vec3> spots(ServerLevel level, Vec3 at, int n) {
        List<Vec3> out = new ArrayList<>();
        BlockPos c = BlockPos.containing(at);
        for (int ring = 0; ring <= 16 && out.size() < n; ring++) {
            for (int dx = -ring; dx <= ring && out.size() < n; dx++) {
                for (int dz = -ring; dz <= ring && out.size() < n; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (int dy : new int[]{0, 1, -1, 2, -2}) {
                        BlockPos feet = c.offset(dx, dy, dz);
                        if (roomFor(level, feet)) {
                            out.add(new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5));
                            break;
                        }
                    }
                }
            }
        }
        while (out.size() < n) out.add(at);
        return out;
    }

    private static boolean roomFor(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    /** One bot where the source stands; false, saying why, if it cannot come in. */
    private static boolean spawn(CommandSourceStack source, String name) {
        return spawn(source, name, source.getPosition());
    }

    private static boolean spawn(CommandSourceStack source, String name, Vec3 at) {
        if (!NAME.matcher(name).matches()) {
            fail(source, name + " is no player name: 3 to 16 letters, digits or _");
            return false;
        }
        MinecraftServer server = source.getServer();
        if (server.getPlayerList().getPlayerByName(name) != null) {
            fail(source, name + " is already in the game");
            return false;
        }
        ServerLevel level = source.getLevel();
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        BotPlayer body = new BotPlayer(server, level, profile);
        server.getPlayerList().placeNewPlayer(new BotConnection(), body, CommonListenerCookie.createInitial(profile, false));
        // A name that died before comes back with the body it saved when it left: dead,
        // and a bot has no death screen to press "respawn" on. It comes back as a
        // respawn would bring it: whole, not burning, not falling over.
        if (body.isDeadOrDying()) {
            body.setHealth(body.getMaxHealth());
            body.deathTime = 0;
            body.clearFire();
        }
        body.teleportTo(level, at.x, at.y, at.z, source.getRotation().y, 0);
        Bot p = new Bot(body);
        if (source.getPlayer() != null) {
            p.owner = source.getPlayer().getUUID();
            p.ownerName = source.getPlayer().getGameProfile().getName();
        }
        body.pilot = () -> pilot(p);
        ALL.put(key(name), p);
        LOG.info("[tachyon] bot {} spawned at {}", name, body.blockPosition());
        return true;
    }

    private static int remove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bot> them = find(c);
        for (Bot p : them) {
            ALL.remove(key(p.name()));
            leave(p, "removed");
        }
        return told(c, them, "left");
    }

    private static int goTo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bot> them = find(c);
        BlockPos to = BlockPosArgument.getBlockPos(c, "pos");
        for (Bot p : them) orderGoto(p, to);
        return told(c, them, "searching a way to " + to.toShortString());
    }

    private static int follow(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer leader = EntityArgument.getPlayer(c, "player");
        String name = leader.getGameProfile().getName();
        // A bot the pattern also takes in does not follow itself.
        List<Bot> them = find(c).stream().filter(p -> p.body != leader).toList();
        for (Bot p : them) orderFollow(p, leader);
        return told(c, them, "following " + name);
    }

    /** @param count how many each is to kill; 0: every one around */
    private static int hunt(CommandContext<CommandSourceStack> c, int count) throws CommandSyntaxException {
        List<Bot> them = find(c);
        Holder.Reference<EntityType<?>> mob = ResourceArgument.getEntityType(c, "mob");
        String name = mob.key().location().getPath();
        if (mob.value() == EntityType.PLAYER) return fail(c.getSource(), "players are never prey");
        for (Bot p : them) orderHunt(p, mob.value(), name, count);
        return told(c, them, "hunting " + name + (count > 0 ? ", " + count + " each" : ", every one around"));
    }

    private static int clear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bot> them = find(c);
        BlockPos a = BlockPosArgument.getLoadedBlockPos(c, "from"), b = BlockPosArgument.getLoadedBlockPos(c, "to");
        String refused = tooBig(a, b);
        if (refused != null) return fail(c.getSource(), refused);
        Clear.Area area = new Clear.Area(c.getSource().getLevel(), a, b);
        for (Bot p : them) clearWith(p, area);
        return told(c, them, "clearing " + area.box() + " (" + volume(a, b) + " blocks)");
    }

    private static int stop(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bot> them = find(c);
        for (Bot p : them) orderStop(p);
        return told(c, them, "standing still");
    }

    /** Words to a bot from the console or a command, as if said to it in the chat. */
    private static int tell(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bot> them = find(c);
        String text = StringArgumentType.getString(c, "text");
        ServerPlayer from = c.getSource().getPlayer();
        for (Bot p : them) {
            brain(p).hear(from == null ? null : from.getUUID(), c.getSource().getTextName(), text);
        }
        return told(c, them, "heard it");
    }

    // --- orders: from the commands and from the brain's tools -----------------------------

    static void orderGoto(Bot p, BlockPos to) {
        endJob(p);
        p.following = null;
        p.target = to;
        p.replans = 0;
        plan(p, to, "going to " + to.toShortString());
    }

    static void orderFollow(Bot p, ServerPlayer leader) {
        endJob(p);
        p.following = leader;
        p.target = null;
        p.plannedAt = -REPLAN_TICKS;
        p.leaderX = Double.NaN;
        p.doing = "following " + leader.getGameProfile().getName();
    }

    static void orderStop(Bot p) {
        endJob(p);
        p.following = null;
        p.target = null;
        halt(p, "standing");
    }

    static void orderHunt(Bot p, EntityType<?> prey, String name, int count) {
        endJob(p);
        p.following = null;
        p.target = null;
        halt(p, "hunting " + name);
        p.job = new Hunt(prey, name, count);
    }

    /** @return why not (a box too big), or null once the order is given */
    static String orderClear(Bot p, BlockPos a, BlockPos b) {
        String refused = tooBig(a, b);
        if (refused != null) return refused;
        clearWith(p, new Clear.Area(p.body.serverLevel(), a, b));
        return null;
    }

    private static void clearWith(Bot p, Clear.Area area) {
        endJob(p);
        p.following = null;
        p.target = null;
        halt(p, "clearing " + area.box());
        p.job = new Clear(area);
    }

    private static long volume(BlockPos a, BlockPos b) {
        return (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1)
                * (Math.abs(a.getZ() - b.getZ()) + 1);
    }

    private static String tooBig(BlockPos a, BlockPos b) {
        long v = volume(a, b);
        return v > CLEAR_MAX ? "a box of " + v + " blocks: " + CLEAR_MAX + " at most" : null;
    }

    // --- words: the chat, to a bot's brain ---------------------------------------------

    static Brain brain(Bot p) {
        if (p.brain == null) p.brain = new Brain(p);
        return p.brain;
    }

    /** When each player last spoke to bots, for {@code per_minute}. */
    private static final Map<UUID, ArrayDeque<Long>> SPOKE = new HashMap<>();

    /**
     * A bot named in the chat hears it: by its name as a word, in any case. Only its
     * owner is heard, operators not included: the rest cost nothing, since every answer
     * is a call to a model someone pays for. And no more than {@code per_minute} times a
     * minute a player.
     */
    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        if (ALL.isEmpty()) return;
        ServerPlayer from = event.getPlayer();
        String text = event.getRawText();
        List<Bot> named = new ArrayList<>();
        for (Bot p : ALL.values()) {
            if (p.body != from && NAMED.apply(p.name()).matcher(text).find()) named.add(p);
        }
        if (named.isEmpty()) return;
        List<Bot> heard = named.stream().filter(p -> from.getUUID().equals(p.owner)).toList();
        if (heard.isEmpty()) return;
        long now = System.currentTimeMillis();
        ArrayDeque<Long> times = SPOKE.computeIfAbsent(from.getUUID(), k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 60_000) times.removeFirst();
        if (times.size() >= Brain.config().perMinute()) {
            from.sendSystemMessage(Component.literal("[tachyon] slow down: " + Brain.config().perMinute()
                    + " messages to bots a minute"));
            return;
        }
        times.addLast(now);
        for (Bot p : heard) brain(p).hear(from.getUUID(), from.getGameProfile().getName(), text);
    }

    private static final Map<String, Pattern> NAMED_CACHE = new HashMap<>();
    private static final java.util.function.Function<String, Pattern> NAMED = name -> NAMED_CACHE.computeIfAbsent(name,
            n -> Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(n) + "(?![A-Za-z0-9_])"));

    private static int list(CommandContext<CommandSourceStack> c) {
        List<String> lines = new ArrayList<>();
        for (Bot p : ALL.values()) {
            if (!mayOrder(c.getSource(), p)) continue;
            lines.add(p.name() + " at " + p.body.blockPosition().toShortString() + ": " + p.doing);
        }
        if (lines.isEmpty()) return say(c.getSource(), operator(c.getSource()) ? "no bots" : "no bots of yours");
        return say(c.getSource(), String.join("\n", lines));
    }

    /**
     * The bots {@code <who>} names: a selector's, or those whose name the pattern
     * matches, {@code *} being any run of characters, in any case. None is a failure,
     * said.
     */
    private static List<Bot> find(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String typed = typed(c);
        List<Bot> them;
        if (typed.startsWith("@")) {
            Collection<ScoreHolder> chosen = ScoreHolderArgument.getNames(c, WHO, List::of);
            them = ALL.values().stream().filter(p -> chosen.contains(p.body)).toList();
        } else {
            String regex = Arrays.stream(typed.split("\\*", -1)).map(Pattern::quote).collect(Collectors.joining(".*"));
            Pattern glob = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            them = ALL.values().stream().filter(p -> glob.matcher(p.name()).matches()).toList();
        }
        if (them.isEmpty()) {
            fail(c.getSource(), "no bot " + (typed.equals("*") ? "in the game" : typed));
            return them;
        }
        // An operator orders any; anyone else, the ones they own.
        List<Bot> theirs = them.stream().filter(p -> mayOrder(c.getSource(), p)).toList();
        if (theirs.isEmpty()) fail(c.getSource(), "no bot of yours " + (typed.equals("*") ? "in the game" : "matches " + typed));
        return theirs;
    }

    static boolean operator(CommandSourceStack s) {
        return s.hasPermission(2);
    }

    /** Operators order every bot; a player, the ones that are theirs. */
    static boolean mayOrder(CommandSourceStack s, Bot p) {
        if (operator(s)) return true;
        ServerPlayer pl = s.getPlayer();
        return pl != null && pl.getUUID().equals(p.owner);
    }

    /** Whose it is, or (with a player) it is theirs from now on. */
    private static int owner(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bot> them = find(c);
        boolean give = c.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("player"));
        if (!give) {
            List<String> lines = new ArrayList<>();
            for (Bot p : them) lines.add(p.name() + ": " + (p.ownerName == null ? "nobody's (only operators order it)" : p.ownerName + "'s"));
            return say(c.getSource(), String.join("\n", lines));
        }
        ServerPlayer to = EntityArgument.getPlayer(c, "player");
        for (Bot p : them) {
            p.owner = to.getUUID();
            p.ownerName = to.getGameProfile().getName();
        }
        return told(c, them, "now " + to.getGameProfile().getName() + "'s");
    }

    /** The words given for {@code <who>}, as typed. */
    private static String typed(CommandContext<CommandSourceStack> c) {
        for (ParsedCommandNode<CommandSourceStack> n : c.getNodes()) {
            if (n.getNode().getName().equals(WHO)) return n.getRange().get(c.getInput());
        }
        return "";
    }

    /** What some bots were told, in a line: by name when one, by count when more. */
    private static int told(CommandContext<CommandSourceStack> c, List<Bot> them, String doing) {
        if (them.isEmpty()) return 0;
        say(c.getSource(), (them.size() == 1 ? them.get(0).name() : them.size() + " bots") + ": " + doing);
        return them.size();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static int say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("[tachyon] " + text), false);
        return 1;
    }

    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal("[tachyon] " + text));
        return 0;
    }

    // --- coming and going ---------------------------------------------------------------

    /** Its death, from the body: it leaves, as a disconnected player would. */
    static void died(BotPlayer body) {
        for (Bot p : ALL.values()) {
            if (p.body != body) continue;
            endJob(p);
            if (p.brain != null) p.brain.stop();
        }
        ALL.values().removeIf(p -> p.body == body);
        body.pilot = null;
        body.getServer().execute(() -> body.connection.onDisconnect(
                new DisconnectionDetails(Component.literal("died"), Optional.empty(), Optional.empty())));
    }

    private static void leave(Bot p, String why) {
        endJob(p);
        if (p.brain != null) p.brain.stop();
        if (p.pending != null) p.pending.cancel(true);
        p.body.pilot = null;
        p.body.connection.onDisconnect(new DisconnectionDetails(Component.literal(why), Optional.empty(), Optional.empty()));
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent event) {
        for (Bot p : List.copyOf(ALL.values())) leave(p, "the server stops");
        ALL.clear();
    }

    // --- what a tick costs --------------------------------------------------------------

    /** A bot's body ticked, keys and physics: this long. */
    static void ticked(long nanos) {
        synchronized (STATS) {
            STATS.thisTick += nanos;
            STATS.bodyTicks++;
        }
    }

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        if (ALL.isEmpty()) return;
        synchronized (STATS) {
            STATS.ticks++;
            STATS.nanos += STATS.thisTick;
            STATS.maxNanos = Math.max(STATS.maxNanos, STATS.thisTick);
            STATS.thisTick = 0;
        }
    }

    // --- the keys, a tick at a time -----------------------------------------------------

    /** Before each tick of its body: what it is doing, turned into keys. */
    private static void pilot(Bot p) {
        if (!p.body.isAlive()) return;
        long now = p.body.getServer().getTickCount();
        follow(p, now);
        if (p.job != null && !p.job.think(p, now)) endJob(p);
        adopt(p);
        steer(p);
        if (p.job != null) {
            p.job.act(p);
            p.doing = p.job.status();
        }
    }

    /** Its job, over: what its hands held is let go. */
    static void endJob(Bot p) {
        if (p.job == null) return;
        Job j = p.job;
        p.job = null;
        j.end(p);
    }

    /** Every bot in the game, for a job that looks at what the others do. */
    static Collection<Bot> all() {
        return ALL.values();
    }

    /** A search from where the body stands to {@code to}, on a routes thread. */
    private static void plan(Bot p, BlockPos to, String doing) {
        plan(p, to, 0, doing);
    }

    /**
     * With {@code near} over 0 the goal is a ring and not a tile: within {@code near} of
     * the ground under {@code to}, as Masurium's client Follower aims. Whom it follows may be
     * in the air (jumping, flying), where nobody can stand.
     */
    static void plan(Bot p, BlockPos to, double near, String doing) {
        Route.Point b = new Route.Point(to.getX(), to.getY(), to.getZ());
        plan(p, to, near > 0 ? world -> Route.Meta.near(ground(world, b), near) : null, doing);
    }

    /**
     * A search toward a goal of a job's own, made with the snapshot the search reads
     * (null: the tile {@code to} itself). {@code to} bounds the chunks read.
     */
    static void plan(Bot p, BlockPos to, Function<SnapshotWorld, Route.Meta> goal, String doing) {
        if (p.pending != null) p.pending.cancel(true);
        long started = System.nanoTime();
        BlockPos from = new BlockPos(p.body.getBlockX(), floorY(p), p.body.getBlockZ());
        SnapshotWorld world = SnapshotWorld.around(p.body.serverLevel(), from, to, MARGIN, MAX_CHUNKS);
        long took = System.nanoTime() - started;
        synchronized (STATS) {
            STATS.snapshots++;
            STATS.snapshotNanos += took;
            STATS.maxSnapshotNanos = Math.max(STATS.maxSnapshotNanos, took);
        }
        Vec3 at = p.body.position();
        Route.Point b = new Route.Point(to.getX(), to.getY(), to.getZ());
        // Partial routes: a stretch that gets closer is walked and searched on from its
        // end, which is what a follower needs and a goto does too (see steer).
        Route.Options options = new Route.Options(3, Route.Options.byDefault().maxNodes(), false, true)
                .withDeadline(SEARCH_MS);
        p.pending = ROUTES.submit(() -> {
            // (whereAmI() and ground() read the snapshot, so they run here, off the
            // server's thread.)
            long t0 = System.currentTimeMillis();
            Route.Point a = whereAmI(world, at);
            Route.Result r = goal != null
                    ? Route.search(world, a, goal.apply(world), options)
                    : Route.search(world, a, b, options);
            STATS.search(System.currentTimeMillis() - t0, r.looked());
            return r;
        });
        p.doing = doing;
    }

    private static void follow(Bot p, long now) {
        ServerPlayer leader = p.following;
        if (leader == null) return;
        String doing = "following " + leader.getGameProfile().getName();
        if (leader.isRemoved() || leader.level() != p.body.level()) {
            p.following = null;
            halt(p, "lost " + leader.getGameProfile().getName());
            return;
        }
        if (now - p.plannedAt < REPLAN_TICKS || p.pending != null) return;
        double d = p.body.distanceTo(leader);
        if (d <= FOLLOW_GAP) {
            if (p.path != null) halt(p, doing);
            return;
        }
        if (p.path == null && d <= FOLLOW_FAR) return;
        // Again only if something changed: whom it follows moved or, standing, it did
        // (pushed, or at the end of a stretch). The same search from the same place finds
        // the same way, or the same none, and with hundreds of followers the searches
        // queue up: one that comes back late was searched from where the body was.
        boolean leaderMoved = Double.isNaN(p.leaderX)
                || Math.abs(leader.getX() - p.leaderX) + Math.abs(leader.getZ() - p.leaderZ) > MOVED;
        boolean selfMoved = Math.abs(p.body.getX() - p.selfX) + Math.abs(p.body.getZ() - p.selfZ) > MOVED;
        if (!leaderMoved && (p.path != null || !selfMoved)) return;
        p.plannedAt = now;
        p.leaderX = leader.getX();
        p.leaderZ = leader.getZ();
        p.selfX = p.body.getX();
        p.selfZ = p.body.getZ();
        p.replans = 0;
        plan(p, leader.blockPosition(), FOLLOW_NEAR, doing);
    }

    /**
     * The tile a search starts from, found as Masurium's client bots find it, and NOT the
     * one under the centre. A body stands on its box, one foot on the next block holds it
     * up: at a block's edge the centre is over an empty column, and a search from there
     * fails with "where I am is not a spot where one can stand", again each second, which a
     * follower walks as a stutter. The tile under the centre first, then those under the
     * box's corners; one up (the feet are inside a partial block's cell: a path, a slab)
     * and one down (in a jump, or pushed off an edge).
     */
    private static Route.Point whereAmI(SnapshotWorld world, Vec3 at) {
        int y0 = (int) Math.floor(at.y);
        double[] offsets = {0, -HALF_WIDTH, HALF_WIDTH};
        for (int y : new int[]{y0, y0 + 1, y0 - 1}) {
            for (double dx : offsets) {
                for (double dz : offsets) {
                    int x = (int) Math.floor(at.x + dx), z = (int) Math.floor(at.z + dz);
                    if (world.canStand(x, y, z)) return new Route.Point(x, y, z);
                }
            }
        }
        // None: the tile under the centre, so the search says what is really wrong.
        return new Route.Point((int) Math.floor(at.x), y0, (int) Math.floor(at.z));
    }

    /** The first tile one can stand on under {@code at} (24 down at most), else {@code at}. */
    private static Route.Point ground(SnapshotWorld world, Route.Point at) {
        for (int dy = 1; dy >= -24; dy--) {
            if (world.canStand(at.x(), at.y() + dy, at.z())) {
                return new Route.Point(at.x(), at.y() + dy, at.z());
            }
        }
        return at;
    }

    /** A finished search becomes the route to walk. */
    private static void adopt(Bot p) {
        if (p.pending == null || !p.pending.isDone()) return;
        Route.Result r;
        try {
            r = p.pending.get();
        } catch (Exception e) {
            r = null;
        }
        p.pending = null;
        p.searchFailed = r == null || !r.hasRoute();
        if (r != null && r.hasRoute() && r.steps().size() < 2) {       // already there
            if (p.target != null) {
                String t = p.target.toShortString();
                p.target = null;
                halt(p, "arrived at " + t);
            } else if (p.path != null) {
                halt(p, p.doing);             // a follower already beside whom it follows
            }
            return;
        }
        if (r == null || !r.hasRoute()) {
            p.target = null;
            halt(p, "no way: " + (r == null ? "the search failed" : r.reason()));
            return;
        }
        p.path = r.steps();
        // The first point is where the body stood when the search began, and it may have
        // walked on while the search waited its turn: it joins the route where it is,
        // instead of turning back to its start.
        p.next = Math.min(nearest(p.path, p.body.position()) + 1, p.path.size() - 1);
        p.partial = r.isPartial();
        p.ticks = 0;
        p.jumps = 0;
        p.lastPos = p.body.position();
    }

    /** It stops walking: keys released, a door it opened closed behind it. */
    static void halt(Bot p, String doing) {
        if (p.pending != null) p.pending.cancel(true);
        p.pending = null;
        p.path = null;
        release(p.body);
        closeOnStop(p);
        p.doing = doing;
    }

    static void release(BotPlayer body) {
        body.zza = 0;
        body.xxa = 0;
        body.setJumping(false);
        body.setSprinting(false);
    }

    /**
     * One step along the route, as Masurium's client Walker takes it: look at the next point,
     * push forward, jump when it is higher, when something is in the way or in water, and
     * move to the next one on standing on it.
     */
    private static void steer(Bot p) {
        BotPlayer b = p.body;
        if (p.path == null) {
            release(b);
            return;
        }
        Route.Point goal = p.path.get(p.next);
        boolean lastOne = p.next == p.path.size() - 1;
        Vec3 center = new Vec3(goal.x() + 0.5, goal.y(), goal.z() + 0.5);
        double missing = horizontal(b.position(), center);

        // Arriving is STANDING on the point, not flying past its height.
        boolean settled = b.onGround() || b.isInWater();
        if (missing <= (lastOne ? NEAR_END : NEAR) && Math.abs(b.getY() - goal.y()) <= 0.5 && settled) {
            if (!lastOne) {
                p.next++;
                p.jumps = 0;
                return;
            }
            if (p.partial && p.target != null) {
                // A stretch of the way: the rest is searched from here.
                p.path = null;
                release(b);
                plan(p, p.target, "going to " + p.target.toShortString());
                return;
            }
            if (p.target != null) {
                BlockPos t = p.target;
                p.target = null;
                halt(p, String.format(Locale.ROOT, "arrived at %s (%.1f from it)", t.toShortString(), missing));
            } else {
                halt(p, p.following != null ? "following " + p.following.getGameProfile().getName() : "standing");
            }
            return;
        }

        if (++p.ticks > TICKS_MAX) {
            p.target = null;
            halt(p, "ran out of time at " + b.blockPosition().toShortString());
            return;
        }

        if (p.ticks % STUCK_TICKS == 0) {
            if (horizontal(b.position(), p.lastPos) < MIN_PROGRESS) {
                if (++p.jumps > JUMPS_MAX && !replan(p)) return;
            } else {
                p.jumps = 0;
            }
            p.lastPos = b.position();
        }

        closeIfDue(p);
        if (openIfNeeded(p, goal)) {
            release(b);
            return;
        }

        double dx = center.x - b.getX(), dz = center.z - b.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        b.setYRot(yaw);
        b.setYHeadRot(yaw);
        b.setXRot(b.getXRot() * 0.7f);
        b.setSprinting(canRun(p, goal));
        b.zza = 1.0f;
        b.xxa = 0;
        int floor = floorY(p);
        b.setJumping(p.jumps > 0
                || goal.y() > floor
                || (b.horizontalCollision && b.onGround())
                || (b.isInWater() && goal.y() >= floor));
    }

    /** Stuck for real: another search, from here, a few times; then it gives up, saying where. */
    private static boolean replan(Bot p) {
        if (p.following != null) {
            p.plannedAt = -REPLAN_TICKS;       // the follower's next look searches again
            p.leaderX = Double.NaN;
            p.jumps = 0;
            return true;
        }
        if (p.target == null || ++p.replans > REPLANS_MAX) {
            p.target = null;
            halt(p, "stuck at " + p.body.blockPosition().toShortString());
            return false;
        }
        plan(p, p.target, "going to " + p.target.toShortString() + " (searching again: stuck)");
        p.jumps = 0;
        return true;
    }

    /** Only on flat ground with flat ground ahead, and not on the last stretch. */
    private static boolean canRun(Bot p, Route.Point goal) {
        if (goal.y() > floorY(p)) return false;
        if (p.next + 1 >= p.path.size()) return false;
        return p.path.get(p.next + 1).y() == goal.y();
    }

    /**
     * The tile the feet are on, which is not floor(y) on a partial block (a dirt path,
     * farmland, soul sand): the path finder counts the tile above it.
     */
    private static int floorY(Bot p) {
        BotPlayer b = p.body;
        int y = (int) Math.floor(b.getY());
        BlockPos at = BlockPos.containing(b.getX(), y, b.getZ());
        var box = b.level().getBlockState(at).getCollisionShape(b.level(), at);
        if (!box.isEmpty()) {
            double cap = box.max(Direction.Axis.Y);
            if (cap > 0.5 && b.getY() >= y + cap - 0.02) return y + 1;
        }
        return y;
    }

    // --- doors: opened with the hand, closed behind it -----------------------------------

    private static boolean openIfNeeded(Bot p, Route.Point goal) {
        BotPlayer b = p.body;
        BlockPos feet = new BlockPos(goal.x(), goal.y(), goal.z());
        BlockPos me = b.blockPosition();
        BlockPos which = closed(b, feet) ? feet
                : closed(b, feet.above()) ? feet.above()
                : closed(b, me) ? me
                : closed(b, me.above()) ? me.above() : null;
        if (which == null) return false;
        if (b.getEyePosition().distanceTo(Vec3.atCenterOf(which)) > PRESS_DOOR) return false;
        press(b, which);
        p.doorOpen = which;
        p.doorSide = Math.signum(projection(b, which));
        return true;
    }

    private static void closeIfDue(Bot p) {
        if (p.doorOpen == null) return;
        BotPlayer b = p.body;
        double d = b.getEyePosition().distanceTo(Vec3.atCenterOf(p.doorOpen));
        if (d > 4.0 || !handheld(b, p.doorOpen) || closed(b, p.doorOpen)) {
            p.doorOpen = null;
            return;
        }
        if (d < 1.2) return;
        double s = projection(b, p.doorOpen);
        if (Math.signum(s) == p.doorSide || Math.abs(s) < DOOR_PASS) return;
        press(b, p.doorOpen);
        p.doorOpen = null;
    }

    private static void closeOnStop(Bot p) {
        if (p.doorOpen == null) return;
        BotPlayer b = p.body;
        double d = b.getEyePosition().distanceTo(Vec3.atCenterOf(p.doorOpen));
        if (d >= 1.2 && d <= 4.0 && handheld(b, p.doorOpen) && !closed(b, p.doorOpen)) press(b, p.doorOpen);
        p.doorOpen = null;
    }

    private static double projection(BotPlayer b, BlockPos door) {
        var state = b.level().getBlockState(door);
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return 0;
        Direction f = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Vec3 c = Vec3.atCenterOf(door);
        return (b.getX() - c.x) * f.getStepX() + (b.getZ() - c.z) * f.getStepZ();
    }

    /** The hand on the door: the same use of a block a player's click makes. */
    private static void press(BotPlayer b, BlockPos where) {
        Vec3 center = Vec3.atCenterOf(where);
        b.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        b.gameMode.useItemOn(b, b.level(), b.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(center, Direction.UP, where, false));
        b.swing(InteractionHand.MAIN_HAND);
    }

    private static boolean handheld(BotPlayer b, BlockPos where) {
        var state = b.level().getBlockState(where);
        return state.is(BlockTags.WOODEN_DOORS) || state.is(BlockTags.FENCE_GATES);
    }

    private static boolean closed(BotPlayer b, BlockPos where) {
        if (!handheld(b, where)) return false;
        var state = b.level().getBlockState(where);
        return state.hasProperty(BlockStateProperties.OPEN) && !state.getValue(BlockStateProperties.OPEN);
    }

    /** The point, among the route's first {@link #JOIN_WITHIN}, nearest {@code at}; height weighs double. */
    private static int nearest(List<Route.Point> path, Vec3 at) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < Math.min(path.size(), JOIN_WITHIN); i++) {
            Route.Point q = path.get(i);
            double dx = q.x() + 0.5 - at.x, dy = 2 * (q.y() - at.y), dz = q.z() + 0.5 - at.z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
