package tachyon;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import java.util.ArrayList;
import java.util.List;

/**
 * The night, and the bots in it. With {@code ignore_for_sleep} (the default), a bot is
 * not a player to the night: the players skip it by sleeping without it, as if it were
 * not there, and the "n/m players sleeping" line counts only them; and no phantom is
 * spawned because of it, though phantoms spawned for a player who has not slept for three
 * days attack any player near, a bot too. With the setting false, it counts as any player
 * does, and a night is skipped only if enough of the bots sleep too.
 *
 * <p>The count is Minecraft's own ({@code SleepStatus.update}), taken whenever a player
 * comes in, leaves, lies down or gets up; {@code SleepStatusMixin} hands it the players
 * without the bots {@link #counted} leaves out. Phantoms come from the time since a player
 * last slept (a statistic, which a sleep sets back to 0): a bot's is set back to 0 every
 * second, and never nears the three days the phantom spawner waits for.
 *
 * <p>Public for the mixin only, whose code runs inside Minecraft's class.
 */
public final class Sleeping implements Ability {

    /** Whether the night goes on without it: it does not count for sleeping, and no phantom spawns because of it. */
    static final String IGNORED = "ignore_for_sleep";

    /** What its setting was at its last look: a change is counted again at once, not at the next bed. */
    private static final class Seen {
        boolean ignored;
    }

    @Override
    public void settings(Settings settings) {
        // The operators': whether the others can skip the night is the server's business.
        settings.bool(IGNORED, true, "whether the players skip the night without it (it does not count for the"
                + " sleeping percentage) and no phantoms spawn because of it", Settings.Who.OPERATOR)
                .label("Left out of sleeping").group("Night").basic();
    }

    /**
     * As it came in, the level counted it as a player: it was not a bot yet (its data is
     * read after the door). Counted again, now that it is.
     */
    @Override
    public void joined(Bots.Bot p) {
        p.slot(Seen.class, Seen::new).ignored = Settings.bool(p, IGNORED);
        p.body.serverLevel().updateSleepingPlayerList();
    }

    /** Once a second: its rest set back to 0, and a change of its setting counted. */
    @Override
    public void tick(Bots.Bot p, long now) {
        if (Math.floorMod(now + p.name().hashCode(), 20) != 0) return;
        boolean ignored = Settings.bool(p, IGNORED);
        // What the phantom spawner reads, for each player about once a minute: a player is
        // a candidate only after 72000 ticks of it, three days without a bed.
        if (ignored) p.body.resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
        Seen seen = p.slot(Seen.class, Seen::new);
        if (seen.ignored != ignored) {
            seen.ignored = ignored;
            p.body.serverLevel().updateSleepingPlayerList();
        }
    }

    /**
     * The players a level counts for sleeping: {@code players} without the bots that do not
     * count. The same list when there are none, so that a server without bots pays nothing.
     * On the server's thread, from {@code SleepStatusMixin}: as the count is taken, and every
     * tick while enough players are asleep.
     */
    public static List<ServerPlayer> counted(List<ServerPlayer> players) {
        List<ServerPlayer> out = null;
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer pl = players.get(i);
            Bots.Bot p = Bots.of(pl);
            boolean left = p != null && Settings.bool(p, IGNORED);
            if (left && out == null) out = new ArrayList<>(players.subList(0, i));
            else if (!left && out != null) out.add(pl);
        }
        return out == null ? players : out;
    }
}
