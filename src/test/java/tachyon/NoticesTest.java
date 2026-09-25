package tachyon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rest a kind of notice takes before it is said again, and the setting's options: plain times, in ms. */
class NoticesTest {

    private static final long MINUTE = 60_000;

    @Test
    @DisplayName("a kind of notice is said once in 10 minutes; another kind has a rest of its own")
    void tenMinutesEachKind() {
        Notices.Rest r = new Notices.Rest();
        assertTrue(r.due("hit:Steve", 0));
        assertFalse(r.due("hit:Steve", 1));
        assertFalse(r.due("hit:Steve", 10 * MINUTE - 1), "one ms short of its 10 minutes");
        assertTrue(r.due("hit:Alex", 5 * MINUTE), "another kind: said");
        assertTrue(r.due("recovery:1,64,2", 5 * MINUTE));
        assertTrue(r.due("hit:Steve", 10 * MINUTE), "its 10 minutes over");
        assertFalse(r.due("hit:Alex", 14 * MINUTE));
        assertTrue(r.due("hit:Alex", 15 * MINUTE));
        assertEquals(10 * MINUTE, Notices.Rest.REST_MS);
    }

    @Test
    @DisplayName("the options are brain (the default), plain and off, declared as a basic choice of the Brain group")
    void theSetting() {
        Settings.Setting s = Abilities.settings().get(Notices.NOTICES);
        assertTrue(s.isChoice());
        assertEquals(List.of("brain", "plain", "off"), s.options);
        assertEquals("brain", s.words(s.byDefault));
        assertEquals(Settings.Level.BASIC, s.level);
        assertEquals("Brain", s.group);
        assertEquals(Settings.Who.OWNER, s.who);
    }

    @Test
    @DisplayName("verbose: a switch, off by default, advanced, in the Brain group, its owner's to change")
    void verbose() {
        Settings.Setting s = Abilities.settings().get(Notices.VERBOSE);
        assertTrue(s.isSwitch);
        assertEquals("off", s.words(s.byDefault));
        assertEquals("Technical lines", s.label);
        assertEquals(Settings.Level.ADVANCED, s.level);
        assertEquals("Brain", s.group);
        assertEquals(Settings.Who.OWNER, s.who);
    }
}
