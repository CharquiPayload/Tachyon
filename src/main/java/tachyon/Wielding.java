package tachyon;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Putting something in its hand, asked by its brain: by the item's id, from the hotbar or
 * brought up from the backpack, or by hotbar slot, 1 to 9 as a player's screen numbers
 * them (Minecraft counts them 0 to 8, a trap Masurium's tool description warned of).
 *
 * <p>The jobs choose their own tools and weapons (a hunt the best weapon before each hit,
 * a clearing the right tool for each block), so what is wielded here stays in hand until
 * one of them needs something else. It is what a freshly crafted or given tool needs to
 * be in hand at once.
 */
final class Wielding implements Ability {

    @Override
    public void tools(Tools tools) {
        tools.add(new Tool("wield", "Put something in your hand: by its id (stone_pickaxe), from your hotbar or brought up"
                + " from your backpack, or by hotbar slot, 1 to 9 as seen on screen.",
                List.of(Tool.optional("what", "string", "The item's id in English: stone_pickaxe, bread, torch..."),
                        Tool.optional("slot", "integer", "A hotbar slot, 1 to 9 as seen on screen")),
                call -> wield(call.bot(), call.args())));
    }

    private static String wield(Bots.Bot p, JsonObject a) {
        BotPlayer b = p.body;
        if (!Bots.handsFree(p)) return "my hands are busy right now (" + busy(p) + "); ask again in a moment";
        Inventory inv = b.getInventory();
        int slot;
        if (a.has("slot") && a.get("slot").isJsonPrimitive()) {
            int n;
            try {
                n = a.get("slot").getAsInt();
            } catch (RuntimeException e) {
                return "the slot is a number from 1 to 9";
            }
            if (n < 1 || n > 9) return "slot " + n + " does not exist: they go from 1 to 9";
            slot = n - 1;
            if (inv.getItem(slot).isEmpty()) return "slot " + n + " is empty";
        } else if (a.has("what") && a.get("what").isJsonPrimitive()) {
            String words = a.get("what").getAsString();
            Item item = Gear.item(words);
            if (item == null) return "there is no item called " + words.trim();
            slot = Gear.find(inv, item);
            if (slot < 0) {
                return b.getOffhandItem().is(item) ? "I have " + Gear.id(item) + " in my off hand, not in my hotbar or backpack"
                        : "I do not carry " + Gear.id(item) + ", neither in my hotbar nor in my backpack";
            }
        } else {
            return "tell me what to put in my hand: an item by its id, or a slot from 1 to 9";
        }
        Gear.toHand(p, slot);
        ItemStack held = b.getMainHandItem();
        return "I now hold " + held.getCount() + " " + Gear.id(held) + " (slot " + (inv.selected + 1) + ")"
                + (p.job != null ? "; what I am doing may switch to what it needs" : "");
    }

    /** What holds its hands, in a few words. */
    static String busy(Bots.Bot p) {
        if (Eating.eating(p)) return "eating";
        return p.body.isUsingItem() ? "using " + Gear.id(p.body.getUseItem()) : "busy";
    }
}
