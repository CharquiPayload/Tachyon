package tachyon;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a bot's body tells its owner without being asked: how going back for its things
 * after a death went, a player who keeps hitting it. Any ability says it with
 * {@link #say}; its {@code notices} setting says how it reaches its owner:
 * <ul>
 * <li>{@code brain} (the default): its brain says it in its own words, in the chat, in one
 *     call to its model with no tools, as it tells how an order went ({@link Brain#report});</li>
 * <li>{@code plain}: a fixed line to its owner, {@code [tachyon] Ada: ...}, no call;</li>
 * <li>{@code off}: nothing.</li>
 * </ul>
 * A bot without a brain set up (no {@code url}) says it plainly.
 *
 * <p>Two brakes, since a call to a model is paid for and a flood of lines is noise: each
 * kind of notice is said at most once every {@link Rest#REST_MS 10 minutes} per bot, and
 * only while its owner is in the game (a bot nobody owns says nothing). Masurium's body
 * notices rested 10 minutes each the same way. A notice that finds its owner away is not
 * kept for later: what it says is old news by then.
 */
final class Notices implements Ability {

    private static final Logger LOG = LogUtils.getLogger();

    /** How it tells its owner: one of {@link #OPTIONS}. */
    static final String NOTICES = "notices";
    static final String BRAIN = "brain", PLAIN = "plain", OFF = "off";
    static final List<String> OPTIONS = List.of(BRAIN, PLAIN, OFF);

    @Override
    public void settings(Settings settings) {
        settings.choice(NOTICES, BRAIN, OPTIONS, "how it tells its owner what nobody asked about (how going back"
                        + " for its things went, a player hitting it): brain, in its own words (a call to its model);"
                        + " plain, a fixed line; off, not at all", Settings.Who.OWNER)
                .label("Notices to its owner").group("Brain").basic();
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
            Bots.brain(p).report(text);
        } else {
            owner.sendSystemMessage(Component.literal("[tachyon] " + p.name() + ": " + text));
        }
        return true;
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
