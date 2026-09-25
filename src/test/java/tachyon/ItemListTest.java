package tachyon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bot's item lists (the food it does not eat on its own, its trash): the mod's default
 * list, and the bot's own changes over it, kept in its data as what it added and took out.
 */
class ItemListTest {

    @TempDir
    Path dir;

    private final ItemList list = new ItemList("food", List.of("minecraft:golden_apple", "minecraft:enchanted_golden_apple"));

    @AfterEach
    void written() {
        BotData.awaitWrites(5000);
    }

    @Test
    @DisplayName("a bot nobody changed has the default list, and reading it writes nothing")
    void defaults() {
        BotData data = BotData.load(dir, "Ada");
        assertEquals(List.of("minecraft:golden_apple", "minecraft:enchanted_golden_apple"), list.ids(data));
        assertTrue(list.has(data, "minecraft:golden_apple"));
        assertFalse(list.has(data, "minecraft:salmon"));
        assertFalse(data.dirty(), "reading makes no section");
    }

    @Test
    @DisplayName("what is added goes after the defaults; what is taken out of them is gone; each change is said to be written")
    void addAndRemove() {
        BotData data = BotData.load(dir, "Ada");
        assertTrue(list.add(data, "minecraft:salmon"));
        assertTrue(data.dirty());
        assertFalse(list.add(data, "minecraft:salmon"), "already on it");
        assertTrue(list.remove(data, "minecraft:golden_apple"));
        assertFalse(list.remove(data, "minecraft:golden_apple"), "not on it any more");
        assertEquals(List.of("minecraft:enchanted_golden_apple", "minecraft:salmon"), list.ids(data));
        assertFalse(list.has(data, "minecraft:golden_apple"));
        assertTrue(list.has(data, "minecraft:salmon"));
    }

    @Test
    @DisplayName("an item added again after being taken out, and one taken out after being added, leave no trace")
    void undoing() {
        BotData data = BotData.load(dir, "Ada");
        list.remove(data, "minecraft:golden_apple");
        list.add(data, "minecraft:golden_apple");
        list.add(data, "minecraft:salmon");
        list.remove(data, "minecraft:salmon");
        assertEquals(list.defaults(), list.ids(data));
        assertEquals(new JsonObject(), data.read("food"), "nothing of its own left");
    }

    @Test
    @DisplayName("default undoes the bot's own change for one item, and says whether there was one")
    void reset() {
        BotData data = BotData.load(dir, "Ada");
        list.remove(data, "minecraft:golden_apple");
        list.add(data, "minecraft:salmon");
        assertTrue(list.reset(data, "minecraft:golden_apple"));
        assertTrue(list.has(data, "minecraft:golden_apple"), "back on: the default has it");
        assertTrue(list.reset(data, "minecraft:salmon"));
        assertFalse(list.has(data, "minecraft:salmon"), "off: the default does not have it");
        assertFalse(list.reset(data, "minecraft:bread"), "nothing of its own to undo");
    }

    @Test
    @DisplayName("the changes are kept in the bot's data, and come back with it")
    void kept() {
        BotData data = BotData.load(dir, "Ada");
        list.add(data, "create:cog");
        list.remove(data, "minecraft:enchanted_golden_apple");
        data.saveNow();
        BotData again = BotData.load(dir, "Ada");
        assertEquals(List.of("minecraft:golden_apple", "create:cog"), list.ids(again));
    }

    @Test
    @DisplayName("a section edited by hand into something else is read as no change at all")
    void brokenByHand() {
        BotData data = BotData.load(dir, "Ada");
        JsonObject s = data.section("food");
        s.addProperty("added", "not a list");
        JsonArray odd = new JsonArray();
        odd.add(new JsonObject());
        odd.add("minecraft:golden_apple");
        s.add("removed", odd);
        assertEquals(List.of("minecraft:enchanted_golden_apple"), list.ids(data));
        assertTrue(list.add(data, "minecraft:salmon"), "and it can still be changed");
        assertTrue(list.has(data, "minecraft:salmon"));
    }
}
