package tachyon;

import java.util.ArrayDeque;

/**
 * What a bot does when it dies: it comes back 2 s later, as a player who pressed
 * "respawn" does (at its bed or respawn anchor if it set one and it is still there, else
 * the world's spawn; whole, with its order dropped, and what it was told while dead taken
 * up), or it leaves the game. Its owner is told which, in a line. What it dropped stays
 * where it died.
 *
 * <p>The respawn itself is Bots' ({@code Bots.respawn}, where a bot's coming and going
 * is), which asks here whether the bot comes back ({@link #staysDead}): its setting, and
 * a count that keeps a bot that dies over and over (in lava, or where something kills it
 * as soon as it is back) from dying for ever. Past {@link Deaths#MAX} deaths in five
 * minutes, it leaves instead.
 */
final class Respawning implements Ability {

    /** Whether it comes back when it dies; asked in Bots.died, through {@link #staysDead}. */
    static final String RESPAWN = "respawn";

    @Override
    public void settings(Settings settings) {
        settings.bool(RESPAWN, true, "whether it comes back by itself when it dies (5 times in 5 minutes at most);"
                + " false: it leaves the game", Settings.Who.OWNER)
                .label("Come back after dying").group("Life").basic();
    }

    /**
     * Why a bot that just died (at {@code now}, in ms) does not come back, in words for its
     * owner, or null when it does: its setting is false, or it died too often lately. A
     * death it comes back from is counted. On the server's thread.
     */
    static String staysDead(Bots.Bot p, long now) {
        if (!Settings.bool(p, RESPAWN)) return "respawn is false";
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
}
