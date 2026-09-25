package tachyon;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import java.util.Locale;
import java.util.UUID;

/**
 * A player who keeps hitting a bot: after {@link Hits#HITS 3} hits from the same player,
 * each within 20 s of the one before, its owner hears of it ({@link Notices}, kind
 * {@code hit:<player>}), and the count starts again. One hit is an accident, a stray arrow;
 * three in a row are not. Masurium's numbers.
 *
 * <p>It does not hit back: whether a bot may fight players is the operators' to say
 * ({@link Fighting}'s {@code hunt_players}, for a player named to its brain;
 * {@link Defending}'s {@code defend_from_players}, for one who hurts it or its owner), and
 * what to say to whoever does this is its brain's, when its notices go through it. Hits
 * from a player the bot hit first lately (the last one it hit, within 20 s) are left out:
 * that fight is its own, and so is one it fights back with {@code defend_from_players}, once
 * it has landed a hit. A sword's sweep that caught it while the swing was at someone else
 * is no hit on it ({@link Defending#swept}): two bots side by side against zombies catch
 * each other's sweeps, and neither is hitting the other. An arrow counts as its shooter's
 * hit. It costs nothing between hits: it runs only when the bot is hurt.
 */
final class Complaining implements Ability {

    @Override
    public void hurt(Bots.Bot p, DamageSource source, float amount) {
        if (!(source.getEntity() instanceof ServerPlayer hitter) || hitter == p.body || Defending.swept(hitter, p.body)) return;
        BotPlayer b = p.body;
        // The one it hit last, lately: it started that one (a fight a setting lets it have).
        if (b.getLastHurtMob() == hitter && b.tickCount - b.getLastHurtMobTimestamp() < Hits.WINDOW_TICKS) return;
        long now = b.getServer().getTickCount();
        Hits.Complaint c = p.slot(Hits.class, Hits::new).hit(hitter.getUUID(), now, amount);
        if (c == null) return;
        String name = hitter.getGameProfile().getName();
        Notices.say(p, "hit:" + name, words(name, c));
    }

    /** What its owner is told: "Steve hit it 3 times in 6 s (7.5 health lost); it has not hit back". */
    static String words(String name, Hits.Complaint c) {
        long seconds = Math.max(1, Math.round(c.ticks() / 20.0));
        double tenths = Math.round(c.lost() * 10) / 10.0;
        String lost = tenths == Math.rint(tenths) ? String.valueOf((long) tenths) : String.format(Locale.ROOT, "%.1f", tenths);
        return name + " hit it " + c.hits() + " times in " + seconds + " s (" + lost + " health lost); it has not hit back";
    }

    /**
     * The hits a bot takes from players, while it is in the game (its slot): who hit it last,
     * how many times in a row, since when, and the health they took. Plain numbers, for a test.
     */
    static final class Hits {
        /** Hits in a row from one player before its owner hears of it. */
        static final int HITS = 3;
        /** Two hits more than this apart are not in a row: 20 s. */
        static final int WINDOW_TICKS = 20 * 20;

        /** A run of hits worth telling: how many, over how long (ticks), and the health they took. */
        record Complaint(int hits, long ticks, float lost) {
        }

        private UUID who;
        private int count;
        private long first, last;
        private float lost;

        /**
         * A hit by {@code by} at tick {@code now}, of {@code amount} health: the run it makes,
         * when that is {@link #HITS} in a row (and then the count starts again), else null.
         * Another player's hit, or one more than {@link #WINDOW_TICKS} after the last, starts
         * a run of its own.
         */
        Complaint hit(UUID by, long now, float amount) {
            if (!by.equals(who) || now - last > WINDOW_TICKS) {
                who = by;
                count = 0;
                lost = 0;
            }
            if (count == 0) first = now;
            last = now;
            count++;
            lost += amount;
            if (count < HITS) return null;
            Complaint c = new Complaint(count, now - first, lost);
            count = 0;
            lost = 0;
            return c;
        }
    }
}
