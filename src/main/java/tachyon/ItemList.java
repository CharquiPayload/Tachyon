package tachyon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A list of items a bot keeps in its data: the food it does not eat on its own, what it
 * tosses as trash. Every bot starts from the mod's default list, and what is changed for
 * it is kept as what was added to it and what was taken out of it, not as a whole list:
 * a bot nobody changed follows the default of whatever version runs, and one whose owner
 * banned salmon keeps salmon banned across versions.
 *
 * <p>Items are kept by their full id ({@code minecraft:golden_apple}), as the registry
 * names them, so a mod's items can be listed too. Plain strings and a bot's data: nothing
 * here needs the game, so a test holds it.
 */
final class ItemList {

    private static final String ADDED = "added", REMOVED = "removed";

    /** The section of a bot's data the list's changes are kept in. */
    private final String section;
    /** What every bot starts with, full ids. */
    private final List<String> defaults;

    ItemList(String section, List<String> defaults) {
        this.section = section;
        this.defaults = List.copyOf(defaults);
    }

    /** What every bot starts with. */
    List<String> defaults() {
        return defaults;
    }

    /** The bot's list, in order: the defaults it did not take out, then what it added. */
    List<String> ids(BotData data) {
        JsonObject kept = data.read(section);
        Set<String> removed = strings(kept.get(REMOVED));
        Set<String> out = new LinkedHashSet<>();
        for (String id : defaults) {
            if (!removed.contains(id)) out.add(id);
        }
        out.addAll(strings(kept.get(ADDED)));
        return new ArrayList<>(out);
    }

    /** Whether an item (full id) is on the bot's list. */
    boolean has(BotData data, String id) {
        JsonObject kept = data.read(section);
        if (strings(kept.get(ADDED)).contains(id)) return true;
        return defaults.contains(id) && !strings(kept.get(REMOVED)).contains(id);
    }

    /** Onto the bot's list. @return false when it was on it already, and nothing changed */
    boolean add(BotData data, String id) {
        if (has(data, id)) return false;
        JsonObject kept = data.section(section);
        take(kept, REMOVED, id);
        if (!defaults.contains(id)) put(kept, ADDED, id);
        data.changed();
        return true;
    }

    /** Off the bot's list. @return false when it was not on it, and nothing changed */
    boolean remove(BotData data, String id) {
        if (!has(data, id)) return false;
        JsonObject kept = data.section(section);
        take(kept, ADDED, id);
        if (defaults.contains(id)) put(kept, REMOVED, id);
        data.changed();
        return true;
    }

    /**
     * The bot's own change for one item undone: it is on the list again if the default list
     * has it, and off it if not. @return false when the bot had no change of its own for it
     */
    boolean reset(BotData data, String id) {
        JsonObject kept = data.read(section);
        if (!strings(kept.get(ADDED)).contains(id) && !strings(kept.get(REMOVED)).contains(id)) return false;
        JsonObject own = data.section(section);
        take(own, ADDED, id);
        take(own, REMOVED, id);
        data.changed();
        return true;
    }

    /** The strings of a JSON array, in order; none when it is not one (edited by hand into something else). */
    private static Set<String> strings(JsonElement e) {
        Set<String> out = new LinkedHashSet<>();
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement v : e.getAsJsonArray()) {
            if (v.isJsonPrimitive()) out.add(v.getAsString());
        }
        return out;
    }

    private static void put(JsonObject kept, String key, String id) {
        JsonArray a = kept.has(key) && kept.get(key).isJsonArray() ? kept.getAsJsonArray(key) : new JsonArray();
        for (JsonElement v : a) {
            if (v.isJsonPrimitive() && v.getAsString().equals(id)) return;
        }
        a.add(id);
        kept.add(key, a);
    }

    private static void take(JsonObject kept, String key, String id) {
        if (!kept.has(key) || !kept.get(key).isJsonArray()) return;
        JsonArray a = kept.getAsJsonArray(key);
        for (int i = a.size() - 1; i >= 0; i--) {
            if (a.get(i).isJsonPrimitive() && a.get(i).getAsString().equals(id)) a.remove(i);
        }
        if (a.isEmpty()) kept.remove(key);
    }
}
