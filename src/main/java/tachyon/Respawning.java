package tachyon;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a bot does when it dies: it comes back 2 s later, as a player who pressed
 * "respawn" does (at its bed or respawn anchor if it set one and it is still there, else
 * the world's spawn; whole, with its order dropped, and what it was told while dead taken
 * up), or it leaves the game. Its owner is told which, in a line. What it dropped stays
 * where it died ({@link Recovering} goes back for it).
 *
 * <p>The respawn itself is Bots' ({@code Bots.respawn}, where a bot's coming and going
 * is), which asks here whether the bot comes back ({@link #staysDead}): its setting, and
 * a count that keeps a bot that dies over and over (in lava, or where something kills it
 * as soon as it is back) from dying for ever. Past {@link Deaths#MAX} deaths in five
 * minutes, it leaves instead.
 *
 * <p>And what it keeps of its last death ({@link Death}): where (the dimension, the block),
 * how (the line the chat said, the kind of damage, who killed it), when, and what it
 * dropped there. Kept in its data (section {@code death}, with how many times it has died
 * in all), so that it is still known after it left or the server restarted; its brain sees
 * it in its state for 10 minutes after, and asks for it with {@code last_death}. Masurium
 * logged the same, the night a zombie killed it while its brain was thinking and nobody
 * could tell afterwards where or how.
 *
 * <p>A spawn point set with {@code /spawnpoint} where the player stood on a partial block
 * (a slab, a dirt path, deep snow: the command takes the block the feet are in, which is
 * that block itself) is refused by the game as blocked, and a player sent to the world's
 * spawn instead. A bot tries the spot one block up first ({@link #oneUp}), then the game's
 * rule.
 */
final class Respawning implements Ability {

    /** Whether it comes back when it dies; asked in Bots.died, through {@link #staysDead}. */
    static final String RESPAWN = "respawn";
    /** The section of a bot's data its last death is kept in. */
    static final String DEATH = "death";
    /** How long its brain's state says it died lately: 10 minutes. */
    private static final long RECENT_MS = 10 * 60_000L;

    @Override
    public void settings(Settings settings) {
        settings.bool(RESPAWN, true, "When it dies, it comes back by itself 2 seconds later, 5 times in 5 minutes at"
                + " most. When it is off, a bot that dies leaves the game.", Settings.Who.OWNER)
                .label("Come back after dying").group("Life").basic();
    }

    /**
     * Why a bot that just died (at {@code now}, in ms) does not come back, in words for its
     * owner, or null when it does: its setting is false, or it died too often lately. A
     * death it comes back from is counted. On the server's thread.
     */
    static String staysDead(Bots.Bot p, long now) {
        if (!Settings.bool(p, RESPAWN)) return "its \"Come back after dying\" is off";
        if (!p.slot(Deaths.class, Deaths::new).comesBack(now)) return Deaths.MAX + " deaths in 5 minutes";
        return null;
    }

    /**
     * A bot's deaths lately, while it is in the game (its slot: the count starts again
     * when it leaves): at most {@link #MAX} it comes back from in any {@link #WINDOW_MS}.
     * The next one within the window, it does not. Plain numbers, for a test to count.
     */
    static final class Deaths {
        static final int MAX = 5;
        static final long WINDOW_MS = 5 * 60_000L;
        /** When it died and came back, within the window, oldest first. */
        private final ArrayDeque<Long> times = new ArrayDeque<>();

        /** A death at {@code now}, in ms: whether it comes back from it (and then it is counted). */
        boolean comesBack(long now) {
            while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MS) times.removeFirst();
            if (times.size() >= MAX) return false;
            times.addLast(now);
            return true;
        }
    }

    // --- its last death -----------------------------------------------------------------------

    /**
     * A death, as the bot keeps it while it is in the game.
     *
     * @param dimension the dimension's id, "minecraft:overworld"
     * @param at        the block its body was in
     * @param how       the line the chat said: "Ada was slain by Zombie"
     * @param cause     the kind of damage that killed it: "minecraft:mob_attack"
     * @param killer    who killed it, a player's name or a mob's ("Zombie"), or null
     * @param byPlayer  whether that was a player
     * @param tick      the server's tick it died at
     * @param drops     the things it dropped, by their entities' ids (as the world has them
     *                  until they are picked up, merge or vanish)
     * @param items     how many items they were, in all
     * @param lost      why what it dropped is gone or out of reach ({@link #lost}), or null
     */
    record Death(String dimension, BlockPos at, String how, String cause, String killer, boolean byPlayer,
                 long tick, List<UUID> drops, int items, String lost) {
    }

    /** What its death is, while it is being dealt out: said by the game's events before {@link #died}. */
    private static final class Dying {
        String how;
        final List<UUID> drops = new ArrayList<>();
        int items;
    }

    /** Its last death, while it is in the game: {@link #last}. */
    private static final class Last {
        Death death;
    }

    /**
     * The game's death of a bot, before its body drops anything (NeoForge's LivingDeathEvent,
     * as the death begins): the line the chat will say, taken now, while its combat tracker
     * still knows it (the death clears it). Then what it drops (LivingDropsEvent): the things
     * that stay in the world, not those a mod (a graves mod) took instead. The lowest priority,
     * after every other mod had its say, and never for a death or drops a mod cancelled.
     */
    @Override
    public void events(IEventBus bus) {
        bus.addListener(PlayerRespawnPositionEvent.class, Respawning::oneUp);
        bus.addListener(EventPriority.LOWEST, LivingDeathEvent.class, e -> {
            Bots.Bot p = Bots.of(e.getEntity());
            if (p == null) return;
            Dying d = new Dying();
            d.how = e.getEntity().getCombatTracker().getDeathMessage().getString();
            dying.put(p, d);
        });
        bus.addListener(EventPriority.LOWEST, LivingDropsEvent.class, e -> {
            Bots.Bot p = Bots.of(e.getEntity());
            Dying d = p == null ? null : dying.get(p);
            if (d == null) return;
            for (ItemEntity item : e.getDrops()) {
                d.drops.add(item.getUUID());
                d.items += item.getItem().getCount();
            }
        });
    }

    /**
     * Where a bot comes back, when its spawn point is one {@code /spawnpoint} set (a forced
     * one: no bed, no anchor) that the game refuses: the feet were inside a partial block
     * the player stood on. It tries one block up, by the game's own rule for a forced spawn
     * point (neither that block nor the one over it solid or a liquid); only when that is
     * refused too does it go to the world's spawn, as the game decided. The spawn point is
     * kept for the next death then. For bots only: a player's respawn is the game's.
     */
    static void oneUp(PlayerRespawnPositionEvent e) {
        if (Bots.of(e.getEntity()) == null || !(e.getEntity() instanceof ServerPlayer old)) return;
        if (!e.getDimensionTransition().missingRespawnBlock() || !old.isRespawnForced()) return;
        BlockPos at = old.getRespawnPosition();
        ServerLevel level = old.getServer().getLevel(old.getRespawnDimension());
        if (at == null || level == null) return;
        BlockPos up = at.above();
        if (!free(level.getBlockState(up)) || !free(level.getBlockState(up.above()))) return;
        Vec3 feet = new Vec3(up.getX() + 0.5, up.getY() + 0.1, up.getZ() + 0.5);
        e.setDimensionTransition(new DimensionTransition(level, feet, Vec3.ZERO, old.getRespawnAngle(), 0.0F,
                DimensionTransition.DO_NOTHING));
        e.setCopyOriginalSpawnPosition(true);
    }

    /** The game's rule for a block a forced spawn point may be in: not solid, not a liquid. */
    private static boolean free(BlockState s) {
        return s.getBlock().isPossibleToRespawnInThis(s);
    }

    /**
     * How it died, about the bot without naming it: the chat's line without the name at its
     * head ("was slain by Zombie", "fell from a high place"), or, when the line does not start
     * so (a mod's), "died (the line)".
     */
    static String died(String name, String line) {
        String head = name + " ";
        return line.startsWith(head) ? line.substring(head.length()) : "died (" + line + ")";
    }

    /**
     * Where a bot came back, in words for its owner: "at its bed", "at its respawn anchor",
     * "at its spawn point", "at the world's spawn", and why there when it had a spawn point
     * the game refused. {@code old} is the body that died, which still has its spawn point.
     */
    static String where(ServerPlayer old, DimensionTransition to) {
        BlockPos at = old.getRespawnPosition();
        if (at == null) return "at the world's spawn";
        if (to.missingRespawnBlock()) return "at the world's spawn: its bed or spawn point is gone or blocked";
        ServerLevel level = old.getServer().getLevel(old.getRespawnDimension());
        BlockState s = level == null ? null : level.getBlockState(at);
        if (s != null && s.getBlock() instanceof BedBlock) return "at its bed";
        if (s != null && s.getBlock() instanceof RespawnAnchorBlock) return "at its respawn anchor";
        return "at its spawn point";
    }

    /** The deaths being dealt out right now: from LivingDeathEvent to {@link #died}. The server's thread's. */
    private final Map<Bots.Bot, Dying> dying = new HashMap<>();

    /** Its death, kept: in its data (written with it) and, with what it dropped, in its slot for {@link #last}. */
    @Override
    public void died(Bots.Bot p, DamageSource cause) {
        Dying d = dying.remove(p);
        BotPlayer b = p.body;
        String causeId = cause.typeHolder().unwrapKey().map(key -> key.location().toString()).orElse("unknown");
        Entity k = cause.getEntity() != null ? cause.getEntity() : b.getKillCredit();
        String killer = k == null ? null : k instanceof Player pl ? pl.getGameProfile().getName() : k.getName().getString();
        Death death = new Death(b.level().dimension().location().toString(), b.blockPosition(),
                d != null ? d.how : cause.getLocalizedDeathMessage(b).getString(), causeId, killer, k instanceof Player,
                b.getServer().getTickCount(), d != null ? List.copyOf(d.drops) : List.of(), d != null ? d.items : 0,
                lost(causeId, b.isInLava(), b.getY() < b.level().getMinBuildHeight()));
        p.slot(Last.class, Last::new).death = death;

        JsonObject kept = p.data.section(DEATH);
        int deaths = kept.has("deaths") ? kept.get("deaths").getAsInt() + 1 : 1;
        for (String key : List.copyOf(kept.keySet())) kept.remove(key);
        kept.addProperty("dimension", death.dimension());
        kept.addProperty("x", death.at().getX());
        kept.addProperty("y", death.at().getY());
        kept.addProperty("z", death.at().getZ());
        kept.addProperty("how", death.how());
        kept.addProperty("cause", causeId);
        if (killer != null) {
            kept.addProperty("killer", killer);
            kept.addProperty("by_player", death.byPlayer());
        }
        kept.addProperty("when", System.currentTimeMillis());
        kept.addProperty("items", death.items());
        kept.addProperty("kept_inventory", b.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY));
        if (death.lost() != null) kept.addProperty("lost", death.lost());
        kept.addProperty("deaths", deaths);
        p.data.changed();
    }

    @Override
    public void left(Bots.Bot p) {
        dying.remove(p);
    }

    /** Its last death while it has been in the game (with what it dropped), or null. On the server's thread. */
    static Death last(Bots.Bot p) {
        return p.slot(Last.class, Last::new).death;
    }

    /**
     * What came of what it dropped at its last death, in words ("got back 12 of the 14 items
     * ..."): kept with the death, for {@code last_death}. From {@link Recovering}.
     */
    static void things(Bots.Bot p, String how) {
        JsonObject kept = p.data.section(DEATH);
        kept.addProperty("things", how);
        p.data.changed();
    }

    /**
     * Why what a bot dropped where it died is gone or out of reach, from how it died: in lava
     * it burned ("lava"); below the world it fell into the void ("void"); drowned, it lies
     * under the water that drowned it ("drowning"). Null for any other death. The kind of
     * damage is not all: a bot on fire in lava may die of the burning.
     */
    static String lost(String cause, boolean inLava, boolean belowWorld) {
        if (cause.equals("minecraft:lava") || inLava) return "lava";
        if (cause.equals("minecraft:out_of_world") || belowWorld) return "void";
        if (cause.equals("minecraft:drown")) return "drowning";
        return null;
    }

    /** A dimension's id in words: "the overworld", "the nether", "the end", or its own name. */
    static String dimension(String id) {
        return switch (id) {
            case "minecraft:overworld" -> "the overworld";
            case "minecraft:the_nether" -> "the nether";
            case "minecraft:the_end" -> "the end";
            default -> id.substring(id.indexOf(':') + 1).replace('_', ' ');
        };
    }

    /** "3 min", "40 s", "2 h": how long ago, in ms, roughly. */
    static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + " s";
        if (s < 2 * 3600) return (s / 60) + " min";
        return (s / 3600) + " h";
    }

    // --- its brain ------------------------------------------------------------------------------

    /** For 10 minutes after it died, its state says so: "you died 3 min ago at 10 64 -3 (Ada was slain by Zombie)". */
    @Override
    public void state(Bots.Bot p, List<String> parts) {
        JsonObject d = p.data.read(DEATH);
        if (!d.has("when") || !d.has("how")) return;
        long ago = System.currentTimeMillis() - d.get("when").getAsLong();
        if (ago < 0 || ago > RECENT_MS) return;
        parts.add("you died " + ago(ago) + " ago at " + d.get("x").getAsInt() + " " + d.get("y").getAsInt() + " "
                + d.get("z").getAsInt() + " (" + d.get("how").getAsString() + ")");
    }

    @Override
    public void tools(Tools tools) {
        tools.add(new Tool("last_death", "Where and how you last died, and what became of what you dropped there.",
                List.of(), call -> lastDeath(call.bot())));
    }

    /** Its last death in words for its brain, from its data: it is kept across leaving and restarts. */
    private static String lastDeath(Bots.Bot p) {
        JsonObject d = p.data.read(DEATH);
        if (!d.has("how")) return "you have not died, as far as you know";
        StringBuilder s = new StringBuilder("you last died");
        if (d.has("when")) s.append(" ").append(ago(System.currentTimeMillis() - d.get("when").getAsLong())).append(" ago");
        s.append(" at ").append(d.get("x").getAsInt()).append(" ").append(d.get("y").getAsInt()).append(" ")
                .append(d.get("z").getAsInt()).append(" in ").append(dimension(d.get("dimension").getAsString()))
                .append(": ").append(d.get("how").getAsString());
        int items = d.has("items") ? d.get("items").getAsInt() : 0;
        if (items == 0) {
            s.append("; you dropped nothing there").append(d.has("kept_inventory") && d.get("kept_inventory").getAsBoolean()
                    ? " (the server keeps a player's things through a death)" : "");
        } else {
            s.append("; you dropped ").append(items).append(" items there");
        }
        // What came of going back for its things since (the place is in the words: a death
        // with nothing on it may have sent it back to the one before).
        if (d.has("things")) s.append("; going back for your things: ").append(d.get("things").getAsString());
        if (d.has("deaths")) {
            int n = d.get("deaths").getAsInt();
            s.append("; ").append(n).append(n == 1 ? " death in all" : " deaths in all");
        }
        return s.toString();
    }
}
