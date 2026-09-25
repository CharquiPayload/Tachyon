package tachyon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The brain's answer as it reaches the chat. */
class BrainTest {

    private static final String STATE = "[you are at -868 73 879, health 20/20, food 20/20; doing: standing; "
            + "in hand: Diamond Sword; carrying: 1 Diamond Sword; Alex is 2 blocks to the south-east]";

    @Test
    void theStateCopiedOnALineOfItsOwnIsLeftOut() {
        assertEquals("¡Hola Alex! ¿Qué tal?", Brain.withoutState(STATE + "\n¡Hola Alex! ¿Qué tal?"));
    }

    @Test
    void theStateCopiedBeforeTheWordsOnOneLineIsLeftOut() {
        assertEquals("On my way.", Brain.withoutState(STATE + " On my way."));
    }

    @Test
    void anAnswerThatIsOnlyTheStateSaysNothing() {
        assertEquals("", Brain.withoutState(STATE));
        assertEquals("", Brain.withoutState("[you are at 1 2 3, health 20/20"));
    }

    @Test
    void wordsWithoutTheStateStayAsTheyAre() {
        assertEquals("I see [two] cows.\nComing.", Brain.withoutState("I see [two] cows.\n\nComing."));
    }
}
