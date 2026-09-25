package tachyon;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;
import tachyon.mixin.PlayerListAccess;
import tachyon.path.Route;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ScoreHolderArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.ScoreHolder;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
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
 *   /tachyon spawn &lt;name&gt; [count]   a bot where you stand; with a count over 1,
 *                                          that many: name1, name2...
 *   /tachyon goto &lt;who&gt; &lt;x y z&gt;     walks there
 *   /tachyon follow &lt;who&gt; &lt;player&gt;  walks after them
 *   /tachyon stop &lt;who&gt;             stands still
 *   /tachyon remove &lt;who&gt;           leaves
 *   /tachyon hunt &lt;who&gt; &lt;mob&gt; [count] kills that many each (every one around)
 *   /tachyon clear &lt;who&gt; &lt;from&gt; &lt;to&gt; breaks every block in the box
 *   /tachyon tell &lt;who&gt; &lt;words&gt;   as if said to it in the chat
 *   /tachyon settings &lt;who&gt;         its settings, and where each comes from
 *   /tachyon set &lt;who&gt; &lt;key&gt; &lt;value|default&gt;  one of them changed
 *   /tachyon config [&lt;who&gt;]         the settings in a chest menu (see ConfigMenu)
 *   /tachyon defaults [&lt;key&gt; &lt;value|default&gt;]  the server's defaults, set in game
 *   /tachyon brain [reload]         how they think (tachyon.properties)
 *   /tachyon owner &lt;who&gt; [player]   whose it is, or give it to them
 *   /tachyon list | stats [reset]
 * </pre>
 *
 * <p>Here are the bots' coming and going (spawn, remove, a death and the respawn after it,
 * owner, list), their brain and
 * figures, their orders, and their legs: the routes and the keys. What they do is the
 * abilities' ({@link Abilities}): each brings its commands, tools, settings and
 * reflexes, orders the bots through the {@code order...} methods here, and is told of
 * their lives through its hooks. A reflex may take a bot's body or hands over for a
 * while ({@link #takeOver}, {@link #holdHands}), and its order waits meanwhile.
 *
 * <p>Operators order every bot; any other player, the bots that are theirs (who
 * brought it in, or whom an operator gave it to). Bringing them in, handing them over,
 * the brain and the figures are the operators'. Whose a bot is, is kept in its data
 * ({@link BotData}): brought in again from the console, it is still its owner's.
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
    /** A bot's data that changed is written this often at most (besides when it leaves). */
    private static final int SAVE_TICKS = 20 * 30;

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
    /** A fixed pool, as Executors.newFixedThreadPool makes one, kept as what it is to ask how many searches wait. */
    private static final ThreadPoolExecutor ROUTES = new ThreadPoolExecutor(ROUTE_THREADS, ROUTE_THREADS, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "tachyon-routes-" + ROUTE_THREAD_N.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    /**
     * Searches waiting for a routes thread, past which the searches an ability may do without
     * (whether a creeper can walk to a bot) are not asked: the walks come first.
     */
    private static final int ROUTES_BUSY = 8 * ROUTE_THREADS;

    /** What the bots cost the server's thread, per tick, and what their searches took. */
    private static final Stats STATS = new Stats();

    static final class Bot {
        /**
         * Its body. A respawn gives it a new one (see {@link #respawn}), as it gives a player:
         * read it where it is used, and never keep it (in a job, a slot, a brain), or what
         * is kept is a corpse. The server's thread's: another thread (a brain's) reads only
         * {@link #name}.
         */
        BotPlayer body;
        /** Its name, as its body's profile has it: kept apart, for the threads that must not touch the body. */
        private final String name;
        /** What it keeps across leaving and coming back: read as it comes in. */
        final BotData data;
        String doing = "standing";
        /** Why it is leaving, once it is (null while it is in the game): for Ability.left. */
        Leaving leaving;

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
        /** Who gave the order it carries out, to be told when it is over (null: nobody to tell). */
        Order order;

        // What waits, and who holds what: see takeOver, holdHands and orderStanding.
        /** The reflex that holds its body, or null; how urgently (see takeOver); and the order it set aside meanwhile. */
        Ability heldBy;
        int heldUrgency;
        Aside aside;
        /** The reflex that holds its hands, until that tick. */
        Ability handsBy;
        long handsUntil;
        /** Its standing order: its job, live or set aside for a detour, and who gave it. Null: none. */
        Standing standing;

        /** Each ability's own state for it while it is in the game: see slot. */
        private final Map<Class<?>, Object> slots = new HashMap<>();

        /** @param name its body's, as its profile has it */
        Bot(BotPlayer body, String name, BotData data) {
            this.body = body;
            this.name = name;
            this.data = data;
        }

        /** Its name; any thread may ask. */
        String name() {
            return name;
        }

        /** Why it is leaving, in Ability.left; null while it is in the game. */
        Leaving leaving() {
            return leaving;
        }

        /**
         * An ability's own state for this bot while it is in the game (a timer, a counter,
         * a list of reminders), by a class of the ability's: made by {@code make} the first
         * time, gone when the bot leaves. What must outlast that goes in {@link #data}. On
         * the server's thread.
         */
        <T> T slot(Class<T> key, Supplier<? extends T> make) {
            Object v = slots.get(key);
            if (v == null) {
                v = make.get();
                slots.put(key, v);
            }
            return key.cast(v);
        }
    }

    /** Why a bot leaves the game. */
    enum Leaving {
        /** {@code /tachyon remove}. */
        REMOVED,
        /** Its body died and it does not come back (its respawn setting, or too many deaths: see {@link Respawning}). */
        DIED,
        /** The server stops. */
        STOPPING
    }

    /** An order set aside while a reflex holds the body: taken up again when it lets go. */
    private record Aside(Job job, BlockPos target, ServerPlayer following, Order order) {
    }

    /** A standing order: a job that detours wait for, and who is told when it is over. */
    private record Standing(Job job, Order by) {
    }

    /**
     * Who gave an order: told how it went once it is over by itself (done, or given up).
     * Spoken (in the chat): its brain tells them, in its words; else a line to them.
     */
    record Order(UUID who, String name, boolean spoken) {
    }

    /**
     * A command's order, to be told back: only to one bot. A crowd's reports would flood
     * whoever gave it; theirs are in {@code list}.
     */
    static Order order(CommandSourceStack s, List<Bot> them) {
        if (them.size() != 1) return null;
        ServerPlayer pl = s.getPlayer();
        return new Order(pl == null ? null : pl.getUUID(), s.getTextName(), false);
    }

    private static final class Stats {
        long ticks, nanos, maxNanos, bodyTicks;
        long snapshots, snapshotNanos, maxSnapshotNanos;
        long searches, searchMs, maxSearchMs, nodes;
        /** The searches waiting for a routes thread, summed over the ticks, and the most at the end of one. */
        long waiting, maxWaiting;
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
                            + " %d tiles looked at; %.1f waiting for a thread on average, %d at most.",
                    bots, ticks == 0 ? 0 : nanos / 1e6 / ticks, maxNanos / 1e6, ticks,
                    bodyTicks == 0 ? 0 : nanos / 1e6 / bodyTicks,
                    snapshots, snapshots == 0 ? 0 : snapshotNanos / 1e6 / snapshots, maxSnapshotNanos / 1e6,
                    searches, searches == 0 ? 0 : (double) searchMs / searches, maxSearchMs, nodes,
                    ticks == 0 ? 0 : (double) waiting / ticks, maxWaiting);
        }

        synchronized void reset() {
            ticks = nanos = maxNanos = bodyTicks = 0;
            snapshots = snapshotNanos = maxSnapshotNanos = 0;
            searches = searchMs = maxSearchMs = nodes = 0;
            waiting = maxWaiting = 0;
        }
    }

    // --- commands ---------------------------------------------------------------------

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> tachyon = Commands.literal("tachyon");
        // Orders are anyone's to give, to the bots they own (see find); bringing bots
        // in, handing them over and the server's figures are the operators'.
        tachyon.then(Commands.literal("spawn")
                        .requires(Bots::operator)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(c -> spawn(c, 0))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, SPAWN_MAX))
                                        .executes(c -> spawn(c, IntegerArgumentType.getInteger(c, "count"))))))
                .then(Commands.literal("remove")
                        .then(who().executes(Bots::remove)));
        // The mod's own that come after the abilities', made first so that an ability
        // that adds one of their names is told (see Abilities.commands).
        LiteralArgumentBuilder<CommandSourceStack> rest = Commands.literal("tachyon");
        Settings.commands(rest);
        ConfigMenu.commands(rest);
        rest.then(Commands.literal("owner")
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
                        })));
        Set<String> taken = new HashSet<>();
        for (CommandNode<CommandSourceStack> n : rest.getArguments()) taken.add(n.getName());
        // What they do: every ability's commands (goto, follow, stop, hunt, clear, tell...).
        Abilities.commands(tachyon, event.getBuildContext(), taken);
        for (CommandNode<CommandSourceStack> n : rest.getArguments()) tachyon.then(n);
        event.getDispatcher().register(tachyon);
    }

    /**
     * The bots an order is for. Vanilla's score holder argument reads it, the one
     * {@code /scoreboard} takes {@code *} with: it reads any word up to a space (a word
     * argument refuses {@code *}) or a selector, and a client without the mod knows it.
     */
    static RequiredArgumentBuilder<CommandSourceStack, ScoreHolderArgument.Result> who() {
        return Commands.argument(WHO, ScoreHolderArgument.scoreHolders())
                .suggests((c, b) -> {
                    List<String> names = new ArrayList<>(List.of("*"));
                    for (Bot p : ALL.values()) names.add(p.name());
                    return SharedSuggestionProvider.suggest(names, b);
                });
    }

    private static final String WHO = "who";

    /** @param count 0 or 1: one bot, named {@code name}; else that many, name1 to nameN */
    private static int spawn(CommandContext<CommandSourceStack> c, int count) {
        CommandSourceStack source = c.getSource();
        String name = StringArgumentType.getString(c, "name");
        if (count <= 1) {
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
        String refused = refusal(source.getServer(), name);
        if (refused != null) {
            fail(source, refused);
            return false;
        }
        // Whoever brings it in owns it (a bot too, through execute as); from the console,
        // nobody, so it is still its last owner's.
        bringIn(source.getServer(), source.getLevel(), name, at, source.getRotation().y, source.getPlayer(), "spawned");
        return true;
    }

    /** Why no bot of that name can come in (no player name, or one in the game already), or null. */
    static String refusal(MinecraftServer server, String name) {
        if (!NAME.matcher(name).matches()) return name + " is no player name: 3 to 16 letters, digits or _";
        if (server.getPlayerList().getPlayerByName(name) != null) return name + " is already in the game";
        return null;
    }

    /**
     * A bot into the game, through the door a joining player uses: its body (what its
     * player save kept: inventory, health, where it stood), its data, its owner, then the
     * abilities told. The name must be free (see {@link #refusal}). Every way in goes
     * through here: a spawn, and a bot brought back as the server starts
     * ({@link Returning}). A respawn does not: the bot never left, and only its body is
     * new (see {@link #respawn}).
     *
     * @param at    where it stands, facing {@code yaw}; null: where its player save left it
     *              (the world's spawn, the first time)
     * @param owner whose it is from now on; null: whose its data says it was
     * @param how   how it came, for the one line in the log: "spawned", "came back"
     */
    static Bot bringIn(MinecraftServer server, ServerLevel level, String name, Vec3 at, float yaw, ServerPlayer owner, String how) {
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        BotPlayer body = new BotPlayer(server, level, profile);
        // (A name that died and left saved a dead body: it is made whole as its save is
        // read, before the level shows it to anyone. See BotPlayer.readAdditionalSaveData.)
        server.getPlayerList().placeNewPlayer(new BotConnection(), body, CommonListenerCookie.createInitial(profile, false));
        if (at != null) body.teleportTo(level, at.x, at.y, at.z, yaw, 0);
        // Its file, read here on the server's thread as the player's own save just was:
        // a few hundred bytes, once, as it comes in.
        Bot p = new Bot(body, name, BotData.load(BotData.folder(server), name));
        if (owner != null) setOwner(p, owner);
        else ownerFromData(p);
        body.bot = p;
        body.pilot = () -> pilot(p);
        ALL.put(key(name), p);
        LOG.info("[tachyon] bot {} {} at {} in {}{}", name, how, Brain.pos(body.blockPosition()),
                body.level().dimension().location(), p.ownerName == null ? ", nobody's" : ", " + p.ownerName + "'s");
        Abilities.joined(p);
        return p;
    }

    /** The bot an entity is the body of (an event's, say), or null when it is none in the game. */
    static Bot of(Entity e) {
        return e instanceof BotPlayer b ? b.bot : null;
    }

    /** Whose it is, from now on; kept in its data. */
    private static void setOwner(Bot p, ServerPlayer to) {
        p.owner = to.getUUID();
        p.ownerName = to.getGameProfile().getName();
        JsonObject kept = p.data.section(OWNER);
        String uuid = p.owner.toString();
        if (new JsonPrimitive(uuid).equals(kept.get("uuid")) && new JsonPrimitive(p.ownerName).equals(kept.get("name"))) {
            return;             // already so: nothing to write
        }
        kept.addProperty("uuid", uuid);
        kept.addProperty("name", p.ownerName);
        p.data.changed();
    }

    /** Whose it was, as its data keeps it (nothing kept: nobody's). */
    private static void ownerFromData(Bot p) {
        JsonObject kept = p.data.read(OWNER);
        if (!kept.has("uuid")) return;
        try {
            p.owner = UUID.fromString(kept.get("uuid").getAsString());
            p.ownerName = kept.has("name") ? kept.get("name").getAsString() : p.owner.toString();
        } catch (RuntimeException e) {
            // Edited by hand into something else: nobody's, as with nothing kept.
            p.owner = null;
            p.ownerName = null;
            LOG.warn("[tachyon] {}'s data: its owner {} is no owner; it is nobody's", p.name(), kept);
        }
    }

    /** The section of a bot's data its owner is kept in: {@code uuid} and {@code name}. */
    private static final String OWNER = "owner";

    private static int remove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bot> them = find(c);
        for (Bot p : them) leave(p, Leaving.REMOVED);
        return told(c, them, "left");
    }

    // --- orders: from the commands and from the brain's tools -----------------------------

    // Each order replaces the last, which then is not over by itself: nobody is told.
    // {@code by}: who is told when this one is (null: nobody). An ability's own orders
    // are made the same way; one that starts a job, with orderJob. The abilities hear of
    // each through Ability.ordered.

    static void orderGoto(Bot p, BlockPos to, Order by) {
        clearFor(p);
        p.order = by;
        p.following = null;
        p.target = to;
        p.replans = 0;
        plan(p, to, "going to " + to.toShortString());
        Abilities.ordered(p);
    }

    static void orderFollow(Bot p, ServerPlayer leader, Order by) {
        clearFor(p);
        p.order = by;
        p.following = leader;
        p.target = null;
        p.plannedAt = -REPLAN_TICKS;
        p.leaderX = Double.NaN;
        p.doing = "following " + leader.getGameProfile().getName();
        Abilities.ordered(p);
    }

    /** It stops whatever it does, a standing order too, and stands. */
    static void orderStop(Bot p) {
        dropOrder(p, "standing");
        Abilities.ordered(p);
    }

    /**
     * Whatever it did, dropped, and nobody told: a reflex's hold on its body, its job, a
     * standing order, where it went, whom it followed. It stands, saying {@code doing}.
     */
    private static void dropOrder(Bot p, String doing) {
        dropHold(p);
        endJob(p);
        // A standing order set aside for a detour: its job was let go of then.
        p.standing = null;
        p.order = null;
        p.following = null;
        p.target = null;
        halt(p, doing);
    }

    /** A hunt as Hunting makes it: kinds of mob, how many, which way to look (see {@link Hunt}). */
    static void orderHunt(Bot p, Hunt hunt, Order by) {
        orderJob(p, hunt, hunt.status(), by);
    }

    /** @return why not (a box too big), or null once the order is given */
    static String orderClear(Bot p, BlockPos a, BlockPos b, Order by) {
        String refused = tooBig(a, b);
        if (refused != null) return refused;
        clearWith(p, new Clear.Area(p.body.serverLevel(), a, b), by);
        return null;
    }

    /** A box to clear that others may be told to clear too: they share it. */
    static void clearWith(Bot p, Clear.Area area, Order by) {
        orderJob(p, new Clear(area), "clearing " + area.box(), by);
    }

    /**
     * A job for it, in place of whatever it did: it stops walking, says {@code doing}
     * until the job says otherwise, and {@code by} is told when the job is over by itself.
     */
    static void orderJob(Bot p, Job job, String doing, Order by) {
        clearFor(p);
        p.order = by;
        p.following = null;
        p.target = null;
        halt(p, doing);
        p.job = job;
        Abilities.ordered(p);
    }

    /**
     * A standing order (an escort, a guard, an errand to come back to): a job, as
     * orderJob gives it, that the orders given after it do not end. They are detours: its
     * job is set aside (told {@link Job#end}, and kept), and taken up again when the
     * detour is over by itself. Stop ends it, and so does another standing order; and so
     * does its job, over by itself, when {@code by} is told.
     */
    static void orderStanding(Bot p, Job job, String doing, Order by) {
        dropHold(p);
        endJob(p);
        p.standing = null;          // one before it, set aside: let go of when it was
        p.order = by;
        p.following = null;
        p.target = null;
        halt(p, doing);
        p.job = job;
        p.standing = new Standing(job, by);
        Abilities.ordered(p);
    }

    /**
     * What it did, cleared for a new order: its job ended; but a standing order's is set
     * aside, to be taken up again once the new order is over. A reflex that held its body
     * lets go of it: the new order is what it does now (the reflex may take it again).
     */
    private static void clearFor(Bot p) {
        dropHold(p);
        if (p.job != null && p.standing != null && p.job == p.standing.job()) {
            Job j = p.job;
            p.job = null;
            j.end(p);
        } else {
            endJob(p);
        }
    }

    /** A standing order set aside, taken up again once it has nothing else to do. */
    private static void takeUpStanding(Bot p) {
        Standing s = p.standing;
        if (s == null || p.heldBy != null || p.job != null || p.target != null || p.following != null) return;
        halt(p, s.job().status());
        p.job = s.job();
        p.order = s.by();
        Abilities.ordered(p);
    }

    // --- reflexes: the body or the hands, taken over for a while ---------------------------

    /**
     * Its body, taken over by a reflex (fleeing, coming up for air): its order is set
     * aside (its job told {@link Job#end} and kept; where it went, whom it followed and
     * who is told kept too) and it stands, saying {@code doing}, until the reflex gives it
     * back. Meanwhile its job does not think and its follow does not search; the walk is
     * the reflex's: {@link #plan} a route and it is walked, or press keys in
     * {@link Ability#act}. An order given meanwhile ends the hold and replaces what was set
     * aside (the reflex sees it no longer holds it, and may take it again).
     *
     * @return whether the reflex holds it now: false when another does (first come, first served)
     */
    static boolean takeOver(Bot p, Ability by, String doing) {
        return takeOver(p, by, 0, doing);
    }

    /**
     * Its body, taken over by a reflex that comes before others: from a reflex that holds it
     * less urgently, too. Fleeing a creeper about to blow comes before backing off from a
     * zombie, and coming up for air before both (the ranks are in
     * docs/adding-an-ability.md). The reflex it is taken from finds it no longer holds it
     * ({@link #holding} is not itself), as when an order ends a hold, and lets go of what
     * it did with it; what was set aside stays set aside, and whoever gives the body back
     * gives the order back. A reflex still in need takes it again on its next tick.
     *
     * @param urgency how urgent the hold is; the plain {@link #takeOver} holds at 0, and
     *                gives way to any other
     * @return whether the reflex holds it now: false when another holds it as urgently or more
     */
    static boolean takeOver(Bot p, Ability by, int urgency, String doing) {
        if (p.heldBy == by) {
            p.heldUrgency = urgency;
            return true;
        }
        if (p.heldBy != null && urgency <= p.heldUrgency) return false;
        if (p.heldBy == null) {
            Job j = p.job;
            p.job = null;
            if (j != null) j.end(p);
            p.aside = new Aside(j, p.target, p.following, p.order);
            p.target = null;
            p.following = null;
            p.order = null;
        }
        p.heldBy = by;
        p.heldUrgency = urgency;
        halt(p, doing);
        return true;
    }

    /** The reflex that holds its body, or null. */
    static Ability holding(Bot p) {
        return p.heldBy;
    }

    /** Its job: the one it is at, or the one set aside while a reflex holds its body; null: none. */
    static Job job(Bot p) {
        return p.job != null ? p.job : p.aside != null ? p.aside.job() : null;
    }

    /**
     * Its body, given back by the reflex that held it: its order taken up again where it
     * was, a walk searched again from where it stands. Nothing, if {@code by} does not hold it.
     */
    static void giveBack(Bot p, Ability by) {
        if (p.heldBy != by) return;
        Aside a = p.aside;
        p.heldBy = null;
        p.heldUrgency = 0;
        p.aside = null;
        halt(p, "standing");
        p.job = a.job();
        p.target = a.target();
        p.following = a.following();
        p.order = a.order();
        goOn(p);
    }

    /**
     * Its order, taken up again from where it stands, which is not where it was given: a
     * walk searched again, a follower's next look searching again, a job going on (it
     * thinks from its fields); with none, a standing order set aside. After a reflex let go
     * of the body, and after a respawn.
     */
    private static void goOn(Bot p) {
        if (p.target != null) {
            p.replans = 0;
            plan(p, p.target, "going to " + p.target.toShortString());
        } else if (p.following != null) {
            p.plannedAt = -REPLAN_TICKS;
            p.leaderX = Double.NaN;
            p.doing = "following " + p.following.getGameProfile().getName();
        } else if (p.job != null) {
            p.doing = p.job.status();
        } else {
            takeUpStanding(p);
        }
    }

    /** A hold on its body ended by an order: what it set aside is replaced (its job was let go of then). */
    private static void dropHold(Bot p) {
        p.heldBy = null;
        p.heldUrgency = 0;
        p.aside = null;
    }

    /**
     * Its hands, for {@code ticks} ticks, by a reflex (a bite, a swing, a bow drawn): the
     * job's hands wait (its act is skipped, and {@link Job#hold} swaps nothing), while its
     * walk goes on. Asked again, it is held longer. For longer than a few seconds, the
     * body is the thing to take.
     *
     * @return whether the reflex holds them: false while another does
     */
    static boolean holdHands(Bot p, Ability by, int ticks) {
        long now = p.body.getServer().getTickCount();
        if (p.handsBy != null && p.handsBy != by && now < p.handsUntil) return false;
        p.handsBy = by;
        p.handsUntil = now + ticks;
        return true;
    }

    /** Its hands, let go of by the reflex that held them. */
    static void freeHands(Bot p, Ability by) {
        if (p.handsBy == by) p.handsBy = null;
    }

    /** No reflex holds its hands: the job's are free to act. */
    static boolean handsFree(Bot p) {
        return p.handsBy == null || p.body.getServer().getTickCount() >= p.handsUntil;
    }

    static long volume(BlockPos a, BlockPos b) {
        return (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1)
                * (Math.abs(a.getZ() - b.getZ()) + 1);
    }

    /** Why a box is not to be cleared (too big), or null. */
    static String tooBig(BlockPos a, BlockPos b) {
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
     * A bot named in the chat hears it: by its name as a word, in any case. The abilities
     * have their say first (Ability.heard: a stop word answered at once, a listener more);
     * then only its owner is heard, operators not included: the rest cost nothing, since
     * every answer is a call to a model someone pays for. And no more than
     * {@code per_minute} times a minute a player.
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
        List<Bot> heard = new ArrayList<>();
        for (Bot p : named) {
            Ability.Heard h = Abilities.heard(p, from, text);
            if (h == Ability.Heard.LISTEN || h == Ability.Heard.PASS && from.getUUID().equals(p.owner)) heard.add(p);
        }
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
    static List<Bot> find(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
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

    /**
     * A player, as the menu sees them: its viewer, whose rights are theirs and not those of
     * whatever ran the command that opened it ({@code execute as} keeps the console's).
     */
    static boolean operator(ServerPlayer pl) {
        return pl.hasPermissions(2);
    }

    /** The same rule for a player: an operator every bot, anyone else the ones that are theirs. */
    static boolean mayOrder(ServerPlayer pl, Bot p) {
        return operator(pl) || pl.getUUID().equals(p.owner);
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
        for (Bot p : them) setOwner(p, to);
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
    static int told(CommandContext<CommandSourceStack> c, List<Bot> them, String doing) {
        if (them.isEmpty()) return 0;
        say(c.getSource(), (them.size() == 1 ? them.get(0).name() : them.size() + " bots") + ": " + doing);
        return them.size();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    static int say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("[tachyon] " + text), false);
        return 1;
    }

    static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal("[tachyon] " + text));
        return 0;
    }

    // --- coming and going ---------------------------------------------------------------

    /** How long a dead bot lies before it comes back: 2 s, about what a player takes to press "respawn". */
    private static final int RESPAWN_TICKS = 40;

    /** A bot that died and comes back: at tick {@code at}. {@code death}: "Ada died (Ada fell from a high place)". */
    private record Dead(long at, String death) {
    }

    /** The bots that died and come back, until they do. The server's thread's. */
    private static final Map<Bot, Dead> DEAD = new LinkedHashMap<>();

    /**
     * Its death, from the body ({@link BotPlayer#die}); {@code message} is the one said in
     * the chat. The abilities are told, while its order is as it was. Then it comes back in
     * 2 s ({@link #respawn}) or, when {@link Respawning} says it does not (its setting, or
     * too many deaths lately), it leaves, as a disconnected player would. Its owner is told
     * which, in a line.
     *
     * <p>It may come in the middle of the bot's own tick: a job's hit that thorns paid back,
     * a reflex's. Its order is gone when this returns ({@code p.job} null): whoever called
     * what killed it looks again before going on (see {@link #pilot}).
     */
    static void died(BotPlayer body, DamageSource cause, Component message) {
        Bot p = body.bot;
        if (p == null) return;          // already on its way out
        Abilities.died(p, cause);
        String death = p.name() + " died (" + message.getString() + ")";
        String staying = Respawning.staysDead(p, System.currentTimeMillis());
        if (staying != null) {
            tellOwner(p, death + " and left: " + staying);
            leave(p, Leaving.DIED);
            return;
        }
        // What it did is over now, not in 2 s: its hands are gone, and a job's claims (a
        // block of a box others clear too) are let go of. Nobody is told: its owner hears
        // of the death. A dead hand closes no door.
        p.doorOpen = null;
        dropOrder(p, "dead: back in a moment");
        DEAD.put(p, new Dead(body.getServer().getTickCount() + RESPAWN_TICKS, death));
    }

    /**
     * The dead whose time came ({@code tick}, or every one with {@link Long#MAX_VALUE}),
     * back in the game. At the end of a tick, outside every body's: a body is taken out of
     * a level and another put in.
     */
    private static void backFromDeath(long tick) {
        if (DEAD.isEmpty()) return;
        List<Bot> due = new ArrayList<>();
        DEAD.forEach((p, d) -> {
            if (d.at() <= tick) due.add(p);
        });
        for (Bot p : due) {
            Dead d = DEAD.remove(p);
            try {
                respawn(p, d);
            } catch (RuntimeException e) {
                // Never a crash of the tick; and never a bot left dead for good either.
                LOG.error("[tachyon] {} could not come back: it leaves", p.name(), e);
                tellOwner(p, d.death() + " and left: it could not come back (the server's log says why)");
                leave(p, Leaving.DIED);
            }
        }
    }

    /**
     * A dead bot back in the game, as a player who pressed "respawn" comes back: at its
     * respawn point (its bed or respawn anchor, if it set one and it is still there, else
     * the world's spawn), whole, in a new body. Its record stays as it was: owner, settings,
     * data, brain, the abilities' slots. Its order was dropped as it died. One given to it
     * while it lay dead (a command, a tool its brain called), which its corpse did nothing
     * of, it carries out now, from where it came back; and its brain thinks about what was
     * said to it meanwhile ({@link Brain#back}). Whoever followed its corpse follows the new
     * body ({@link #onClone}).
     *
     * <p>It does what {@code PlayerList.respawn} does, which cannot be called: that makes a
     * plain {@code ServerPlayer}, with no pilot and no bot, in place of the
     * {@link BotPlayer}. The body is a new entity and not the old one healed: a client that
     * saw the death counts the corpse's {@code deathTime} up while its health is 0 and draws
     * it lying down while that is over 0, and health given back in place does not undo it.
     * Taken out of its level and a new one put in, with the same entity id, the trackers
     * tell every client to forget the corpse and to add a player standing where it came back.
     */
    private static void respawn(Bot p, Dead d) {
        BotPlayer old = p.body;
        MinecraftServer server = old.getServer();
        PlayerListAccess list = (PlayerListAccess) server.getPlayerList();
        list.tachyon$players().remove(old);
        old.serverLevel().removePlayerImmediately(old, Entity.RemovalReason.KILLED);
        DimensionTransition to = old.findRespawnPositionAndUseSpawnBlock(false, DimensionTransition.DO_NOTHING);
        PlayerRespawnPositionEvent where = NeoForge.EVENT_BUS.post(new PlayerRespawnPositionEvent(old, to, false));
        to = where.getDimensionTransition();
        ServerLevel level = to.newLevel();
        BotPlayer body = new BotPlayer(server, level, old.getGameProfile());
        body.connection = old.connection;
        body.restoreFrom(old, false);
        body.setId(old.getId());
        body.setMainArm(old.getMainArm());
        if (where.copyOriginalSpawnPosition()) body.copyRespawnPosition(old);
        for (String tag : old.getTags()) body.addTag(tag);
        body.moveTo(to.pos().x, to.pos().y, to.pos().z, to.yRot(), to.xRot());
        // What else PlayerList.respawn sends goes to the player's own client (the world,
        // the time, where it stands): a bot has none. The connection is its player's,
        // which vanilla sets from what respawn returns.
        old.connection.player = body;
        old.bot = null;
        old.pilot = null;
        p.body = body;
        // Before it is in the level: the count of sleepers is taken again as it goes in,
        // and asks whether it is a bot (Sleeping).
        body.bot = p;
        body.pilot = () -> pilot(p);
        level.addRespawnedPlayer(body);
        list.tachyon$players().add(body);
        list.tachyon$playersByUUID().put(body.getUUID(), body);
        body.initInventoryMenu();
        body.setHealth(body.getHealth());
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerRespawnEvent(body, false));
        p.handsBy = null;
        // Nothing of the corpse's walk is left (a search from where it lay, keys pressed);
        // then what it was ordered while dead, if anything, from here.
        halt(p, "standing");
        goOn(p);
        tellOwner(p, d.death() + " and is back at " + Brain.pos(body.blockPosition()));
        if (p.brain != null) p.brain.back();
    }

    /**
     * A player's body replaced by a new one: a respawn (a bot's, in {@link #respawn}, and a
     * player's), or the way back from the End. Whoever followed the old body follows the
     * new one, searching again toward where it is now; a follow set aside by a reflex too.
     * NeoForge posts it from {@code ServerPlayer.restoreFrom}, as the new body is made.
     */
    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        if (!(event.getOriginal() instanceof ServerPlayer old) || !(event.getEntity() instanceof ServerPlayer now)) return;
        for (Bot q : ALL.values()) {
            if (q.following == old) {
                q.following = now;
                q.plannedAt = -REPLAN_TICKS;
                q.leaderX = Double.NaN;
            }
            if (q.aside != null && q.aside.following() == old) {
                q.aside = new Aside(q.aside.job(), q.aside.target(), now, q.aside.order());
            }
        }
    }

    /** A line to its owner, if they are in the game; and to the log, for the console. */
    private static void tellOwner(Bot p, String text) {
        LOG.info("[tachyon] {}", text);
        ServerPlayer owner = p.owner == null ? null : p.body.getServer().getPlayerList().getPlayer(p.owner);
        if (owner != null && owner != p.body) owner.sendSystemMessage(Component.literal("[tachyon] " + text));
    }

    /**
     * It leaves the game. The abilities are told first, while it is still among the bots
     * and its order is as it was; then its hands and brain are stopped, its data written
     * (on the writer's thread; at the server's stop, here), and its body let go. A dead
     * body is disconnected at the end of the tick: its death is being dealt out right now,
     * inside its hurt, which goes on after this.
     */
    private static void leave(Bot p, Leaving why) {
        leave(p, why, true);
    }

    /**
     * @param letGo whether its body is let go of (disconnected, out of the player list and
     *              its level): not after a crash ({@link #onStopped}), when the server
     *              has saved every player and closed its levels already
     */
    private static void leave(Bot p, Leaving why, boolean letGo) {
        DEAD.remove(p);             // dead, and removed before it came back
        p.leaving = why;
        Abilities.left(p);
        dropHold(p);
        endJob(p);
        p.standing = null;
        if (p.brain != null) p.brain.stop();
        if (p.pending != null) p.pending.cancel(true);
        if (why == Leaving.STOPPING) p.data.saveNow();
        else p.data.saveLater();
        ALL.remove(key(p.name()));
        BotPlayer body = p.body;
        body.pilot = null;
        body.bot = null;
        if (!letGo) return;
        String words = switch (why) {
            case REMOVED -> "removed";
            case DIED -> "died";
            case STOPPING -> "the server stops";
        };
        Runnable disconnect = () -> body.connection.onDisconnect(
                new DisconnectionDetails(Component.literal(words), Optional.empty(), Optional.empty()));
        if (why == Leaving.DIED) later(disconnect);
        else disconnect.run();
    }

    /**
     * As the server starts, its levels loaded and before any bot comes in (Returning brings
     * them back once it has started): the server's defaults set in game, read from the world.
     */
    @SubscribeEvent
    public void onStarting(ServerStartingEvent event) {
        Abilities.settings().game(Settings.gameStore(event.getServer()));
    }

    /** The server's stop began as a stop does, with its stopping event: see {@link #onStopped}. The server's thread's. */
    private static boolean stopping;

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent event) {
        stopping = true;
        runLater();
        // The dead that were to come back in a moment come back now: they leave from where
        // they came back to, and that is where they are when the server starts again.
        backFromDeath(Long.MAX_VALUE);
        for (Bot p : List.copyOf(ALL.values())) leave(p, Leaving.STOPPING);
        ALL.clear();
        closeStores();
    }

    /**
     * Once the server has stopped. After a stop there is nothing left to do: onStopping did
     * it. After a crash there was no stopping (NeoForge posts ServerStoppingEvent only from
     * the end of the server's loop, and this one from its {@code finally}), and the bots are
     * still here. They leave now, as at a stop, so that their data is written and Returning
     * records them where they were, to come back when the server starts again. That is why
     * this runs first ({@code HIGHEST}): Returning writes its roster at this same event. Their
     * bodies are left as they are: the server saved them with every player, and closed the
     * levels they were in. What was to happen at the end of the tick (a dead bot's
     * disconnection) and a respawn due are dropped with the tick they were for.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onStopped(ServerStoppedEvent event) {
        if (stopping) {
            stopping = false;
            return;
        }
        if (!ALL.isEmpty()) LOG.warn("[tachyon] the server stopped without a stop (a crash): its {} bot(s) leave now", ALL.size());
        LATER.clear();
        for (Bot p : List.copyOf(ALL.values())) {
            try {
                leave(p, Leaving.STOPPING, false);
            } catch (RuntimeException e) {
                LOG.error("[tachyon] {} could not leave as the server stopped", p.name(), e);
            }
        }
        ALL.clear();
        DEAD.clear();
        closeStores();
    }

    /**
     * The shared stores, the defaults set in game among them, written here and now; then the
     * writes still on their way, and the ones that failed, of bots long gone too.
     */
    private static void closeStores() {
        BotData.saveShared(true);
        Abilities.settings().game(null);
        BotData.forgetShared();
        BotData.stop(10_000);
    }

    /** A bot hurt: the abilities told (after the hit, before a death it brings). */
    @SubscribeEvent
    public void onDamage(LivingDamageEvent.Post event) {
        Bot p = of(event.getEntity());
        if (p != null) Abilities.hurt(p, event.getSource(), event.getNewDamage());
    }

    /** What waits for the end of the tick: see {@link #later}. The server's thread's. */
    private static final ArrayDeque<Runnable> LATER = new ArrayDeque<>();

    /**
     * {@code work} at the end of this tick, on the server's thread: for what must not
     * happen in the middle of a body's tick or of the bots' loop (a bot sent away, the
     * list of bots changed). {@code server.execute} does NOT wait on the server's thread:
     * there it runs at once, in the middle of whatever called it. From another thread,
     * {@code server.execute} is the way back.
     */
    static void later(Runnable work) {
        LATER.addLast(work);
    }

    private static void runLater() {
        Runnable r;
        while ((r = LATER.pollFirst()) != null) {
            try {
                r.run();
            } catch (RuntimeException e) {
                LOG.warn("[tachyon] something left for the end of the tick failed", e);
            }
        }
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
        runLater();
        int tick = event.getServer().getTickCount();
        backFromDeath(tick);
        // What changed in the bots' data, every 30 s: a crash loses that much at most.
        // Each bot on a tick of its own (by its name), so that a crowd's copies are not
        // all taken in one tick; the shared stores and the writes that failed, together.
        for (Bot p : ALL.values()) {
            if (Math.floorMod(tick + p.name().hashCode(), SAVE_TICKS) == 0) p.data.saveLater();
        }
        if (tick % SAVE_TICKS == 0) {
            BotData.saveShared(false);
            BotData.retry();
        }
        if (ALL.isEmpty()) return;
        int queued = ROUTES.getQueue().size();
        synchronized (STATS) {
            STATS.ticks++;
            STATS.nanos += STATS.thisTick;
            STATS.maxNanos = Math.max(STATS.maxNanos, STATS.thisTick);
            STATS.thisTick = 0;
            STATS.waiting += queued;
            STATS.maxWaiting = Math.max(STATS.maxWaiting, queued);
        }
    }

    /** Whether the routes threads are behind: more searches wait than they get through in a moment. */
    static boolean routesBusy() {
        return ROUTES.getQueue().size() > ROUTES_BUSY;
    }

    // --- the keys, a tick at a time -----------------------------------------------------

    /**
     * Before each tick of its body: what it is doing, turned into keys. The abilities'
     * reflexes first (one may take the body or the hands); then, unless a reflex holds
     * the body, the walk's plans and the job's; the walk's keys; the reflexes' hands; and,
     * unless the body or the hands are held, the job's hands.
     *
     * <p>Any of them may get it killed (a hit that thorns pays back, a guardian's answer),
     * and a death drops its order there and then ({@link #died}): its job is gone, and
     * whatever came next in this tick is not for a corpse. So it looks again after each.
     */
    private static void pilot(Bot p) {
        BotPlayer body = p.body;
        if (!body.isAlive()) return;
        long now = body.getServer().getTickCount();
        Abilities.tick(p, now);
        if (!body.isAlive()) return;
        if (p.heldBy == null) {
            follow(p, now);
            Job j = p.job;
            if (j != null && !j.think(p, now) && p.job == j) {
                endJob(p);
                finished(p);
            }
            if (!body.isAlive()) return;
        }
        adopt(p);
        steer(p);
        Abilities.act(p, now);
        if (!body.isAlive()) return;
        Job j = p.job;
        if (p.heldBy == null && j != null) {
            if (handsFree(p)) j.act(p);
            if (p.job == j) p.doing = j.status();
        }
    }

    /**
     * Its order, over by itself (done, or given up), in {@code doing}: whoever gave it is
     * told, once. Said in the chat, its brain tells them in its words. Then the abilities
     * hear of it, and a standing order set aside for this one is taken up again.
     */
    static void finished(Bot p) {
        String how = p.doing;
        Order o = p.order;
        p.order = null;
        if (o != null) {
            LOG.info("[tachyon] {} is over: {}", p.name(), how);
            if (o.spoken()) {
                brain(p).over(o.who(), o.name(), how);
            } else {
                ServerPlayer pl = o.who() == null ? null : p.body.getServer().getPlayerList().getPlayer(o.who());
                if (pl != null) pl.sendSystemMessage(Component.literal("[tachyon] " + p.name() + ": " + how));
            }
        }
        Abilities.over(p, how);
        takeUpStanding(p);
    }

    /** Its job, over for good: what its hands held is let go. A standing order's, the standing order with it. */
    static void endJob(Bot p) {
        if (p.job == null) return;
        Job j = p.job;
        p.job = null;
        if (p.standing != null && p.standing.job() == j) p.standing = null;
        j.end(p);
    }

    /** The bot in the game called so, in any case, or null. */
    static Bot named(String name) {
        return ALL.get(key(name));
    }

    /**
     * Every bot in the game, for a job that looks at what the others do. The live list:
     * to send some away or bring some in while going through it, go through a copy.
     */
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

    /** The same ring, searched with options of its own (a chase's longer fall: see Hunt.chase). */
    static void plan(Bot p, BlockPos to, double near, Route.Options wanted, String doing) {
        Route.Point b = new Route.Point(to.getX(), to.getY(), to.getZ());
        plan(p, to, world -> Route.Meta.near(ground(world, b), near), wanted, doing);
    }

    /**
     * A search toward a goal of a job's own, made with the snapshot the search reads
     * (null: the tile {@code to} itself). {@code to} bounds the chunks read.
     */
    static void plan(Bot p, BlockPos to, Function<SnapshotWorld, Route.Meta> goal, String doing) {
        // Partial routes: a stretch that gets closer is walked and searched on from its
        // end, which is what a follower needs and a goto does too (see steer).
        plan(p, to, goal, new Route.Options(3, Route.Options.byDefault().maxNodes(), false, true), doing);
    }

    /**
     * The same, with a search's own options: a longer fall allowed with more health, a
     * route that builds or breaks its way. What the ability asks for is searched, within
     * the search's time budget, which is the server's.
     */
    static void plan(Bot p, BlockPos to, Function<SnapshotWorld, Route.Meta> goal, Route.Options wanted, String doing) {
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
        Route.Options options = wanted.withDeadline(SEARCH_MS);
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

    /**
     * A search of an ability's own, apart from any bot's walk (whether a creeper can walk to
     * a bot, say): {@code work} runs on a routes thread over the loaded chunks around
     * {@code a} and {@code b}, and its answer comes as a future, looked at on a later tick
     * and never waited for on the server's thread. The snapshot is taken here, on it. It
     * counts in {@code /tachyon stats} as a search (its tiles are not counted).
     */
    static <T> Future<T> search(ServerLevel level, BlockPos a, BlockPos b, Function<SnapshotWorld, T> work) {
        long started = System.nanoTime();
        SnapshotWorld world = SnapshotWorld.around(level, a, b, MARGIN, MAX_CHUNKS);
        long took = System.nanoTime() - started;
        synchronized (STATS) {
            STATS.snapshots++;
            STATS.snapshotNanos += took;
            STATS.maxSnapshotNanos = Math.max(STATS.maxSnapshotNanos, took);
        }
        return ROUTES.submit(() -> {
            long t0 = System.currentTimeMillis();
            T answer = work.apply(world);
            STATS.search(System.currentTimeMillis() - t0, 0);
            return answer;
        });
    }

    private static void follow(Bot p, long now) {
        ServerPlayer leader = p.following;
        if (leader == null) return;
        String doing = "following " + leader.getGameProfile().getName();
        // Dead and still in the game (a player on the death screen, a bot 2 s from coming
        // back): it waits, searching nothing new. The body that comes back is followed
        // (onClone). Its corpse is gone from the level after a second, and is not "lost".
        if (leader.isDeadOrDying() && p.body.getServer().getPlayerList().getPlayer(leader.getUUID()) == leader) return;
        if (leader.isRemoved() || leader.level() != p.body.level()) {
            p.following = null;
            halt(p, "lost " + leader.getGameProfile().getName());
            finished(p);
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
                finished(p);
            } else if (p.path != null) {
                halt(p, p.doing);             // a follower already beside whom it follows
            }
            return;
        }
        if (r == null || !r.hasRoute()) {
            // A goto's search, over; a job's or a follower's, theirs to try again.
            boolean going = p.target != null;
            p.target = null;
            halt(p, "no way: " + (r == null ? "the search failed" : r.reason()));
            if (going) finished(p);
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
                finished(p);
            } else {
                halt(p, p.following != null ? "following " + p.following.getGameProfile().getName() : "standing");
            }
            return;
        }

        if (++p.ticks > TICKS_MAX) {
            boolean going = p.target != null;
            p.target = null;
            halt(p, "ran out of time at " + b.blockPosition().toShortString());
            if (going) finished(p);
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
            boolean going = p.target != null;
            p.target = null;
            halt(p, "stuck at " + p.body.blockPosition().toShortString());
            if (going) finished(p);
            return false;
        }
        plan(p, p.target, "going to " + p.target.toShortString() + " (searching again: stuck)");
        p.jumps = 0;
        return true;
    }

    /** Only on flat ground with flat ground ahead, not on the last stretch, and if it may (its setting). */
    private static boolean canRun(Bot p, Route.Point goal) {
        if (!Settings.bool(p, Walking.SPRINT)) return false;
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
