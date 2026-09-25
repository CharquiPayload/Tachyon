package tachyon;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a bot's body tells its owner without being asked: how going back for its things
 * after a death went, a player who keeps hitting it, what it could not deal with by itself.
 * Any ability says it with {@link #say}; its {@code notices} setting says how it reaches
 * its owner, and its owner alone (these say where it died, where its things lie, where it
 * is cornered: nobody else's business):
 * <ul>
 * <li>{@code brain} (the default): its brain says it in its own words, in one call to its
 *     model with no tools, as it tells how an order went, whispered to its owner
 *     ({@link Brain#report});</li>
 * <li>{@code plain}: a fixed line to its owner, {@code [tachyon] Ada: ...}, no call;</li>
 * <li>{@code off}: nothing.</li>
 * </ul>
 * A bot without a brain set up (no {@code url}) says it plainly. A full backpack goes to
 * its brain with its tools ({@link Tossing}), and follows this setting too.
 *
 * <p>Two brakes, since a call to a model is paid for and a flood of lines is noise: each
 * kind of notice is said at most once every {@link Rest#REST_MS 10 minutes} per bot, and
 * only while its owner is in the game (a bot nobody owns says nothing). Masurium's body
 * notices rested 10 minutes each the same way. A notice that finds its owner away is not
 * kept for later: what it says is old news by then.
 *
 * <p>What the mod itself must tell a player about a bot (it died and is back, an order
 * given to it by a command is over, its brain could not think) goes the same way, through
 * {@link #tell}, without the rest: each happens once. And the {@code verbose} setting: off
 * (the default), a player reads no technical line of the mod's; on, its owner also gets
 * the lines the server's log has about the bot ({@link #technical}: what it does by
 * itself, where, with coordinates), for finding out what went wrong. A command's own answer
 * is not one of these: it is what the command says.
 */
final class Notices implements Ability {

    private static final Logger LOG = LogUtils.getLogger();

    /** How it tells its owner: one of {@link #OPTIONS}. */
    static final String NOTICES = "notices";
    static final String BRAIN = "brain", PLAIN = "plain", OFF = "off";
    static final List<String> OPTIONS = List.of(BRAIN, PLAIN, OFF);
    /** Whether its owner also gets the technical lines the log has about it. */
    static final String VERBOSE = "verbose";

    @Override
    public void settings(Settings settings) {
        settings.choice(NOTICES, BRAIN, OPTIONS, "It tells its owner what nobody asked about (how going back for its"
                        + " things went, a player hitting it, a full backpack, what it could not deal with, a death), and"
                        + " whoever gave it an order by a command how that order ended: in its own words, whispered to"
                        + " them alone (brain: a call to its model), in a fixed line (plain), or not at all (off).",
                        Settings.Who.OWNER)
                .label("Notices to its owner").group("Brain").basic();
        settings.bool(VERBOSE, false, "Its owner also gets the technical lines the server's log has about it (what it does"
                        + " by itself, with coordinates), for finding out what went wrong. When it is off, its owner hears"
                        + " only its notices.", Settings.Who.OWNER)
                .label("Technical lines").group("Brain").advanced();
    }

    /**
     * Something the body has to tell its owner unasked, said as its {@code notices} setting
     * says, unless that kind was said less than 10 minutes ago, or its owner is not in the
     * game. On the server's thread.
     *
     * @param kind what it is about, the key it rests under: "recovery", or with the detail
     *             in it when two cases must both be told ("hit:Steve" and "hit:Alex")
     * @param text what happened, in plain English words for its owner and its model alike:
     *             about the bot without naming it, as the lines of a finished order are
     *             ("got back 12 of the 14 items it dropped at 10 64 -3"), with no period at
     *             the end; the numbers in it
     * @return whether it was said
     */
    static boolean say(Bots.Bot p, String kind, String text) {
        String how = Settings.choice(p, NOTICES);
        if (how.equals(OFF)) return false;
        ServerPlayer owner = p.owner == null ? null : p.body.getServer().getPlayerList().getPlayer(p.owner);
        if (owner == null || owner == p.body) return false;
        if (!p.slot(Rest.class, Rest::new).due(kind, System.currentTimeMillis())) return false;
        LOG.info("[tachyon] {} tells {} ({}, {}): {}", p.name(), p.ownerName, kind, how, text);
        if (how.equals(BRAIN) && !Brain.config().url(p.name()).isEmpty()) {
            Bots.brain(p).report(p.owner, p.ownerName, text);
        } else {
            owner.sendSystemMessage(Component.literal("[tachyon] " + p.name() + ": " + text));
        }
        return true;
    }

    /**
     * What the mod itself must tell a player about a bot, once, when it happens: it died
     * and is back (or left), an order given to it by a command is over, its brain could not
     * think. Logged as {@code technical} always. With its {@code verbose} on, {@code to}
     * gets that line, with where the bot is when it names no place (and its brain's words too, when its
     * notices are said by its brain). Off, {@code text} goes as its {@code notices} setting
     * says: its brain says it in its words, whispered to {@code to}; or a plain line; or
     * nothing. On the server's thread.
     *
     * @param to         whom it is for: its owner, or who gave the order; null (the console,
     *                   nobody's bot) or away: only the log has it
     * @param text       what happened, about the bot without naming it, with no period at the
     *                   end, as {@link #say} takes it
     * @param technical  the log's line, the bot's name first
     * @param inItsWords whether its brain may say it: not once the bot is leaving (it has no
     *                   brain left then), and not when what is told is that its brain failed
     */
    static void tell(Bots.Bot p, UUID to, String text, String technical, boolean inItsWords) {
        LOG.info("[tachyon] {}", technical);
        ServerPlayer pl = to == null ? null : p.body.getServer().getPlayerList().getPlayer(to);
        if (pl == null || pl == p.body) return;
        String how = Settings.choice(p, NOTICES);
        boolean brain = inItsWords && how.equals(BRAIN) && !Brain.config().url(p.name()).isEmpty();
        if (Settings.bool(p, VERBOSE)) {
            pl.sendSystemMessage(Component.literal("[tachyon] " + placed(p, technical)));
            if (brain) Bots.brain(p).report(to, pl.getGameProfile().getName(), text);
            return;
        }
        if (how.equals(OFF)) return;
        if (brain) Bots.brain(p).report(to, pl.getGameProfile().getName(), text);
        else pl.sendSystemMessage(Component.literal("[tachyon] " + p.name() + ": " + text));
    }

    /**
     * A technical line about a bot (a reflex taking over, a tool its brain called, what it
     * gave up): to the server's log, through the caller's logger (whose lines carry the time,
     * and keep their words for whoever reads or searches them); and, with its {@code verbose}
     * on, to its owner too, if they are in the game, with where the bot is when the line says
     * no place of its own ({@link #placed}). On the server's thread.
     *
     * @param text the line, the bot's name first, as the log has it
     */
    static void technical(Logger log, Bots.Bot p, String text) {
        log.info("[tachyon] {}", text);
        if (p.owner == null || p.leaving() != null || !Settings.bool(p, VERBOSE)) return;
        ServerPlayer owner = p.body.getServer().getPlayerList().getPlayer(p.owner);
        if (owner != null && owner != p.body) owner.sendSystemMessage(Component.literal("[tachyon] " + placed(p, text)));
    }

    /** Three whole numbers in a row, as a place is written ("12 64 -30", "12, 64, -30"). */
    private static final Pattern PLACE = Pattern.compile("-?\\d+,? -?\\d+,? -?\\d+");

    /**
     * A technical line, with where the bot stands when it says no place of its own: "Ada
     * backs off: 5 health, a zombie 3 blocks away (at 12 64 -30)". A line for finding out
     * what went wrong is little use without where.
     */
    static String placed(Bots.Bot p, String text) {
        if (PLACE.matcher(text).find()) return text;
        return text + " (at " + Brain.pos(p.body.blockPosition()) + ")";
    }

    /**
     * When each kind of notice was last said, for a bot while it is in the game (its slot):
     * a kind is said again only {@link #REST_MS} after. Plain numbers, for a test.
     */
    static final class Rest {
        static final long REST_MS = 10 * 60_000L;
        private final Map<String, Long> said = new HashMap<>();

        /** Whether {@code kind} may be said at {@code now}, in ms; if so, it counts as said then. */
        boolean due(String kind, long now) {
            // What rested its 10 minutes is forgotten: the map holds only the kinds said lately.
            said.values().removeIf(t -> now - t >= REST_MS);
            if (said.containsKey(kind)) return false;
            said.put(kind, now);
            return true;
        }
    }
}
