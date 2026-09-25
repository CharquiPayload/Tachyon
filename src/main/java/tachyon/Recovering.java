package tachyon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Going back for what it dropped when it died, by itself, once it is back: as Masurium's
 * client bots did. What a player drops at a death vanishes after 5 minutes, and a brain
 * that thinks only when spoken to would hear of it too late; so the body goes, and its
 * owner hears how it went, with numbers ({@link Notices}). The trip itself is the
 * {@link Recover} job.
 *
 * <p>Each death that dropped something leaves a grave ({@link Grave}): where, when, and
 * the things it dropped, by their entities' ids (known from the drop itself: it goes for
 * its own things, not for whatever lies around). A death within {@link #RADIUS} of a grave
 * it has not given up on is the same grave: dying where it went back to is the trip that
 * failed. Two seconds after it is back (Masurium's breather), with nothing else to do, it
 * sets off for the graves it can still go to, the latest first. The brakes, which matter
 * more than the trip:
 * <ul>
 * <li>never after a death in lava (what it dropped burned), in the void (it fell with it)
 *     or by drowning (it lies under the water that drowned it): there is nothing there to
 *     get, and going back is dying again;</li>
 * <li>{@link #TRIES 2} tries at most for each grave: a bot once died twice at the same spot,
 *     the second time on its way back for the first;</li>
 * <li>within {@link #DEADLINE_TICKS about 6 minutes} of the death (5 of them before what
 *     lies there vanishes, and one to spare);</li>
 * <li>in the dimension it came back in: crossing to another comes with the portals, later;</li>
 * <li>any order given to it ends the trip, and so does a death on the way;</li>
 * <li>its {@code recover_items} setting, true by default.</li>
 * </ul>
 * Each end is said to its owner with what it knows: how many of the items it dropped it
 * got back, how many were gone, how many it saw and could not reach.
 *
 * <p>Its reflex costs nothing while no bot has just come back: a look at an empty map.
 */
final class Recovering implements Ability {

    /** Whether it goes back for what it dropped when it died. */
    static final String RECOVER = "recover_items";
    /** Trips to one grave at most. */
    static final int TRIES = 2;
    /** Graves this old are let go: 6 minutes, in ticks (what lies there vanishes after 5). */
    static final long DEADLINE_TICKS = 6 * 60 * 20;
    /** What a player drops vanishes after this long in a loaded chunk: 5 minutes, in ticks. */
    static final long VANISH_TICKS = 5 * 60 * 20;
    /** A death this close to a grave is the same grave; and what it dropped is looked for this far around it. */
    static final double RADIUS = 12.0;
    /** Ticks after it is back before it sets off: Masurium's breather. */
    static final int BREATHER = 40;

    /** The bots that just came back, and the tick they set off at. The server's thread's. */
    private final Map<Bots.Bot, Long> due = new HashMap<>();

    @Override
    public void settings(Settings settings) {
        settings.bool(RECOVER, true, "Once it is back from a death, it goes back for what it dropped: 2 tries within 6"
                + " minutes of the death at most, and never after lava, the void or drowning.",
                Settings.Who.OWNER).label("Go back for its things").group("Life").basic();
    }

    /**
     * A bot back from the dead (Bots.respawn posts NeoForge's respawn event for it, its new
     * body already its own): it sets off after the breather. And the things a bot picks up
     * (NeoForge's pickup event): counted when they are some it dropped.
     */
    @Override
    public void events(IEventBus bus) {
        bus.addListener(PlayerEvent.PlayerRespawnEvent.class, e -> {
            Bots.Bot p = Bots.of(e.getEntity());
            if (p != null) due.put(p, (long) p.body.getServer().getTickCount() + BREATHER);
        });
        bus.addListener(ItemEntityPickupEvent.Post.class, e -> {
            Bots.Bot p = Bots.of(e.getPlayer());
            if (p == null) return;
            Graves g = p.slot(Graves.class, Graves::new);
            if (!g.list.isEmpty()) {
                g.picked(e.getItemEntity().getUUID(), e.getOriginalStack().getCount() - e.getCurrentStack().getCount());
            }
        });
    }

    /** Its death: a grave, or the one it died at again. A trip it was on is over (the try counted). */
    @Override
    public void died(Bots.Bot p, DamageSource cause) {
        due.remove(p);
        Graves g = p.slot(Graves.class, Graves::new);
        g.running = null;
        Respawning.Death d = Respawning.last(p);
        long now = p.body.getServer().getTickCount();
        if (d == null || d.tick() != now) return;           // not this death's: its record failed
        g.forget(now);
        g.died(d.dimension(), d.at(), now, d.drops(), d.items(), d.lost());
    }

    @Override
    public void left(Bots.Bot p) {
        due.remove(p);
    }

    /** Two seconds after it came back: it sets off, or says why not. The rest of the time, a look at an empty map. */
    @Override
    public void tick(Bots.Bot p, long now) {
        if (due.isEmpty()) return;
        Long at = due.get(p);
        if (at == null || now < at) return;
        due.remove(p);
        setOff(p, now);
    }

    /**
     * An order given to it while it goes back for its things (a command, a tool, a stop):
     * the trip is over, and its owner hears what it left behind.
     */
    @Override
    public void ordered(Bots.Bot p) {
        Graves g = p.slot(Graves.class, Graves::new);
        Recover r = g.running;
        if (r == null || p.job == r) return;
        g.running = null;
        r.stopped(p, p.body.getServer().getTickCount());
    }

    /**
     * Back from the dead, and the breather over: off to the graves it can go to, the latest
     * first; its owner told why not, when the grave of this death is one it does not go to.
     * Given an order meanwhile (while it lay dead, or since), it does that instead, and says
     * what it leaves behind.
     */
    private static void setOff(Bots.Bot p, long now) {
        Graves g = p.slot(Graves.class, Graves::new);
        g.forget(now);
        Grave latest = g.latest;
        g.latest = null;
        if (!Settings.bool(p, RECOVER)) return;
        String here = p.body.level().dimension().location().toString();
        List<Grave> go = g.toVisit(here, now);
        if (latest != null && !go.contains(latest) && !latest.done && latest.left() > 0) {
            String why = latest.skip(here, now);
            if (why != null) Notices.say(p, key(latest, latest.skipped(here, now)), why);
        }
        if (go.isEmpty()) return;
        if (p.job != null || p.target != null || p.following != null || Bots.holding(p) != null) {
            Grave first = go.get(0);
            first.done = true;
            Notices.say(p, key(first, "busy"), "did not go back for its things at " + first.pos() + ": it was given something"
                    + " else to do; " + first.left() + " of the " + first.dropped + " items it dropped there are left, "
                    + first.leftFor(now));
            return;
        }
        Recover r = new Recover(g, go);
        Bots.orderJob(p, r, r.status(), null);
        g.running = r;
    }

    /**
     * The kind of notice a grave's news is: by place and by what happened ({@code what}: "back",
     * "tries", "stopped"...), so that two graves are two notices, and so are two pieces of news
     * of one grave ("got back all", then "died there again, twice"), which the 10 minutes' rest
     * of one kind would otherwise swallow.
     */
    static String key(Grave g, String what) {
        return "recovery:" + g.at.getX() + "," + g.at.getY() + "," + g.at.getZ() + ":" + what;
    }

    // --- the graves: plain data, for a test ------------------------------------------------------

    /**
     * Where a bot died and dropped things, and how going back for them goes: the things by
     * their entities' ids, how many items they were and how many it got back, its tries.
     */
    static final class Grave {
        final String dimension;
        final BlockPos at;
        /** The tick of its last death here that dropped something: what lies here vanishes from then on. */
        long died;
        final Set<UUID> items = new HashSet<>();
        /** Items dropped here, and got back. */
        int dropped, picked;
        /** Trips to it started. */
        int tries;
        /** Why what lies here is gone or out of reach ({@link Respawning#lost}), or null. */
        String lost;
        /** Given up on, or cleared: not gone back to again. */
        boolean done;

        Grave(String dimension, BlockPos at, long died) {
            this.dimension = dimension;
            this.at = at;
            this.died = died;
        }

        String pos() {
            return at.getX() + " " + at.getY() + " " + at.getZ();
        }

        /** Items dropped here it has not got back: as far as it knows, still there. */
        int left() {
            return Math.max(0, dropped - picked);
        }

        /**
         * Why it does not go back to it from {@code here} (a dimension's id) at tick {@code now},
         * in a word, or null when it goes: what it dropped is "lost" (lava, the void, drowning),
         * it is in another "dimension", it used its "tries", it is too "late".
         */
        String skipped(String here, long now) {
            if (lost != null) return "lost";
            if (!dimension.equals(here)) return "dimension";
            if (tries >= TRIES) return "tries";
            if (now - died > DEADLINE_TICKS) return "late";
            return null;
        }

        /** The same ({@link #skipped}), in words for its owner, with the numbers; null when it goes. */
        String skip(String here, long now) {
            String why = skipped(here, now);
            if (why == null) return null;
            if (why.equals("lost")) {
                return switch (lost) {
                    case "lava" -> "died in lava at " + pos() + ": the " + dropped + " items it had burned there;"
                            + " it is not going back";
                    case "void" -> "died in the void at " + pos() + ": the " + dropped + " items it had fell with it;"
                            + " it is not going back";
                    case "drowning" -> "drowned at " + pos() + ": the " + dropped + " items it had are under the water"
                            + " that drowned it; it is not going back";
                    default -> "died at " + pos() + " where what it had is lost (" + lost + "); it is not going back";
                };
            }
            if (why.equals("dimension")) {
                return "died in " + Respawning.dimension(dimension) + " at " + pos() + " and is back in "
                        + Respawning.dimension(here) + ": it does not cross dimensions yet, so the " + left()
                        + " items it dropped there are left, " + leftFor(now);
            }
            if (why.equals("tries")) {
                return "went back to " + pos() + " " + (tries == 2 ? "twice" : tries + " times")
                        + " and died again: it does not try once more; " + left() + " of the " + dropped
                        + " items it dropped there are left, " + leftFor(now);
            }
            return "died at " + pos() + " " + (now - died) / 1200 + " minutes ago: the " + left()
                    + " items it dropped there have vanished by now; it is not going back";
        }

        /** How long what lies here lasts, in words: "for about 4 more minutes", counted from its last death here. */
        String leftFor(long now) {
            long ticks = died + VANISH_TICKS - now;
            if (ticks <= 0) return "though they may have vanished by now";
            if (ticks < 1200) return "for less than a minute more";
            long minutes = (ticks + 600) / 1200;
            return "for about " + minutes + " more minute" + (minutes == 1 ? "" : "s");
        }
    }

    /** A bot's graves while it is in the game (its slot), and the trip it is on. */
    static final class Graves {
        /** Oldest first. */
        final List<Grave> list = new ArrayList<>();
        /** The grave of its last death (null: that death dropped nothing, and was at no grave); asked as it sets off. */
        Grave latest;
        /** The trip it is on, or null. */
        Recover running;

        /**
         * A death in {@code dimension} at {@code at}, at tick {@code now}, which dropped
         * {@code items} items ({@code drops}, their entities' ids): a grave of its own, or
         * the one it died at before (within {@link #RADIUS}, not too old), which then has
         * these too. A death there that dropped nothing is a try that failed; one that
         * dropped nothing anywhere else leaves no grave. What it dropped is lost for
         * {@code lost} (lava...): so is the grave's, since the place kills.
         *
         * @return the grave, or null when there is none
         */
        Grave died(String dimension, BlockPos at, long now, Collection<UUID> drops, int items, String lost) {
            Grave g = null;
            for (Grave x : list) {
                if (x.dimension.equals(dimension) && now - x.died <= DEADLINE_TICKS
                        && x.at.distSqr(at) <= RADIUS * RADIUS) g = x;
            }
            if (g == null) {
                if (items == 0) {
                    latest = null;
                    return null;
                }
                g = new Grave(dimension, at, now);
                list.add(g);
            }
            g.items.addAll(drops);
            if (items > 0) {
                g.dropped += items;
                g.died = now;
                g.done = false;
            }
            if (lost != null) g.lost = lost;
            latest = g;
            return g;
        }

        /** The graves it goes to from {@code here} at {@code now}: those it can, with something left, the latest first. */
        List<Grave> toVisit(String here, long now) {
            List<Grave> out = new ArrayList<>();
            for (int i = list.size() - 1; i >= 0; i--) {
                Grave g = list.get(i);
                if (!g.done && g.left() > 0 && g.skip(here, now) == null) out.add(g);
            }
            return out;
        }

        /** What it picked up: counted for the grave it dropped it at, if any. */
        void picked(UUID item, int count) {
            for (Grave g : list) {
                if (g.items.contains(item)) {
                    g.picked += count;
                    return;
                }
            }
        }

        /** Graves too old to go back to are let go of. */
        void forget(long now) {
            list.removeIf(g -> now - g.died > DEADLINE_TICKS);
            if (latest != null && !list.contains(latest)) latest = null;
        }
    }
}
