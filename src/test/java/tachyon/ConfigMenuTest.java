package tachyon;

import net.minecraft.world.inventory.ClickType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which of a vanilla client's clicks mean something to the config menu: the one place that
 * decides which clicks may change a setting. Every other is nothing: a double click's second
 * half counted as a click would turn a switch over twice, and a swap or a drag must never
 * reach the chest's own handling. Every click type, with every button a client can send it
 * with and some it cannot.
 */
class ConfigMenuTest {

    @Test
    @DisplayName("only a left or right click (with shift or not) and Q mean something; every other click is nothing")
    void clicksRead() {
        for (ClickType type : ClickType.values()) {
            for (int button = -1; button <= 40; button++) {
                ConfigPages.Click read = ConfigMenu.read(type, button);
                String what = type + " " + button;
                switch (type) {
                    case PICKUP -> assertEquals(button == 0 ? ConfigPages.Click.LEFT : button == 1 ? ConfigPages.Click.RIGHT : null,
                            read, what);
                    case QUICK_MOVE -> assertEquals(button == 0 ? ConfigPages.Click.SHIFT_LEFT
                            : button == 1 ? ConfigPages.Click.SHIFT_RIGHT : null, read, what);
                    case THROW -> assertEquals(ConfigPages.Click.DROP, read, what + ": Q, with ctrl or not");
                    case SWAP, CLONE, QUICK_CRAFT, PICKUP_ALL -> assertNull(read, what);
                }
            }
        }
        assertEquals(7, ClickType.values().length, "a click type Minecraft added is to be read here too");
    }
}
