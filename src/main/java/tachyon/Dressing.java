package tachyon;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Armor: put on and taken off when its brain asks, and put on by itself when it carries
 * something better than what it wears ({@code dress_alone}, on by default: a Masurium
 * guard once carried a full iron set in its backpack until its brain had a turn).
 *
 * <p>A piece is weighed by what it gives in its body slot, armor points and a tenth of its
 * toughness to break ties ({@link Gear#protection}), from the item's attributes, so a
 * mod's armor counts as it protects. What it takes off goes back where the new piece came
 * from, as a player's swap in the inventory does: nothing is lost, and no free slot is
 * needed. A piece with the curse of binding is never taken off, as the game allows no
 * player to.
 */
final class Dressing implements Ability {

    private static final Logger LOG = LogUtils.getLogger();

    /** Whether it puts on better armor it carries by itself. */
    static final String DRESS_ALONE = "dress_alone";
    /** It looks at what it carries this often: every 10 s, as Masurium's dressAlone did. */
    private static final int EVERY = 200;
    /** From head to feet: the order it says what it wears in. */
    private static final EquipmentSlot[] BODY = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    @Override
    public void settings(Settings settings) {
        settings.bool(DRESS_ALONE, true, "whether it puts on better armor it carries by itself (it looks every 10 s)",
                Settings.Who.OWNER).label("Put on better armor").group("Gear").basic();
    }

    /**
     * Every 10 s, each bot on a tick of its own: better armor from what it carries, if its
     * setting says so. Not while its hands are busy (a bite, a bow drawn): a player does not
     * open the inventory mid-bite. What changed goes to the log; nothing to its brain.
     */
    @Override
    public void tick(Bots.Bot p, long now) {
        if (Math.floorMod(now + p.name().hashCode(), EVERY) != 0) return;
        if (!Settings.bool(p, DRESS_ALONE) || !p.body.isAlive() || p.body.isUsingItem() || !Bots.handsFree(p)) return;
        List<String> changes = best(p);
        if (!changes.isEmpty()) LOG.info("[tachyon] {} put on {} by itself", p.name(), String.join(", ", changes));
    }

    @Override
    public void tools(Tools tools) {
        tools.add(new Tool("equip_armor", "Put on armor you carry. Without a piece, the best you carry for each body part;"
                + " with one (iron_helmet), that piece, even if it is worse than what you wear.",
                List.of(Tool.optional("piece", "string", "The piece's id in English (iron_helmet); leave it out for the best you carry")),
                call -> {
                    JsonObject a = call.args();
                    String piece = a.has("piece") && a.get("piece").isJsonPrimitive() ? a.get("piece").getAsString().trim() : "";
                    return piece.isEmpty() ? equipBest(call.bot()) : equip(call.bot(), piece);
                }));
        tools.add(new Tool("remove_armor", "Take off a piece of armor you wear; it goes into your backpack.",
                List.of(Tool.param("piece", "string", "The worn piece's id in English (iron_helmet)")),
                call -> {
                    JsonObject a = call.args();
                    if (!a.has("piece") || !a.get("piece").isJsonPrimitive()) return "which piece? (iron_helmet...)";
                    return remove(call.bot(), a.get("piece").getAsString());
                }));
    }

    // --- the tools --------------------------------------------------------------------------

    private static String equipBest(Bots.Bot p) {
        if (p.body.isUsingItem()) return "I am " + Wielding.busy(p) + " right now; ask again in a moment";
        List<String> changes = best(p);
        if (changes.isEmpty()) return "I carry nothing better than what I wear. Wearing: " + worn(p);
        return "I put on " + String.join(", ", changes) + ". Wearing: " + worn(p);
    }

    private static String equip(Bots.Bot p, String words) {
        Item item = Gear.item(words);
        if (item == null) return "there is no item called " + words.trim();
        BotPlayer b = p.body;
        if (b.isUsingItem()) return "I am " + Wielding.busy(p) + " right now; ask again in a moment";
        for (EquipmentSlot part : BODY) {
            if (b.getItemBySlot(part).is(item)) return "I already wear " + Gear.id(item) + ". Wearing: " + worn(p);
        }
        int from = Gear.find(b.getInventory(), item);
        if (from < 0) return "I do not carry " + Gear.id(item);
        EquipmentSlot part = b.getEquipmentSlotForItem(b.getInventory().getItem(from));
        if (part.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return Gear.id(item) + " is not worn on the body";
        String refused = swap(p, from, part);
        return refused != null ? refused : "I put on " + Gear.id(item) + ". Wearing: " + worn(p);
    }

    private static String remove(Bots.Bot p, String words) {
        Item item = Gear.item(words);
        if (item == null) return "there is no item called " + words.trim();
        BotPlayer b = p.body;
        for (EquipmentSlot part : BODY) {
            ItemStack on = b.getItemBySlot(part);
            if (!on.is(item)) continue;
            if (bound(on)) return Gear.id(on) + " has the curse of binding: it cannot be taken off";
            Inventory inv = b.getInventory();
            int free = inv.getFreeSlot();
            if (free < 0) return "my backpack is full: there is nowhere to put " + Gear.id(on);
            inv.setItem(free, on);
            b.setItemSlot(part, ItemStack.EMPTY);
            return "I took off " + Gear.id(on) + "; it is in my backpack. Wearing: " + worn(p);
        }
        return "I do not wear " + Gear.id(item) + ". Wearing: " + worn(p);
    }

    // --- dressing ------------------------------------------------------------------------------

    /**
     * For each body part, the best piece it carries, if it protects more than the one worn:
     * put on, the worn one going where the new one was. A part whose piece is bound is left
     * alone. On the server's thread.
     *
     * @return what was put on, as "iron_helmet (was leather_helmet)"; empty when nothing
     */
    static List<String> best(Bots.Bot p) {
        BotPlayer b = p.body;
        Inventory inv = b.getInventory();
        List<String> changes = new ArrayList<>();
        for (EquipmentSlot part : BODY) {
            ItemStack on = b.getItemBySlot(part);
            if (bound(on)) continue;
            double best = Gear.protection(on, part);
            int chosen = -1;
            for (int i = 0; i < Gear.SLOTS; i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty() || b.getEquipmentSlotForItem(s) != part) continue;
                double v = Gear.protection(s, part);
                if (v > best) {
                    best = v;
                    chosen = i;
                }
            }
            if (chosen < 0) continue;
            String fresh = Gear.id(inv.getItem(chosen));
            String was = on.isEmpty() ? "" : " (was " + Gear.id(on) + ")";
            if (swap(p, chosen, part) == null) changes.add(fresh + was);
        }
        return changes;
    }

    /**
     * The piece in slot {@code from} onto the body part, the worn one into {@code from}: a
     * player's swap. @return why not (the worn one is bound), or null once done
     */
    private static String swap(Bots.Bot p, int from, EquipmentSlot part) {
        BotPlayer b = p.body;
        ItemStack on = b.getItemBySlot(part);
        if (bound(on)) return Gear.id(on) + " has the curse of binding: it cannot be taken off";
        Inventory inv = b.getInventory();
        ItemStack piece = inv.getItem(from).split(1);
        if (inv.getItem(from).isEmpty()) inv.setItem(from, on);
        else if (!on.isEmpty() && !inv.add(on)) b.drop(on, true);     // a stack of helmets: the old one where it fits
        b.setItemSlot(part, piece);
        return null;
    }

    /** A piece with the curse of binding: the game lets no player in survival take it off. */
    private static boolean bound(ItemStack s) {
        return !s.isEmpty() && EnchantmentHelper.has(s, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }

    /** What it wears, head to feet, in words for the model. */
    static String worn(Bots.Bot p) {
        List<String> on = new ArrayList<>();
        for (EquipmentSlot part : BODY) {
            ItemStack s = p.body.getItemBySlot(part);
            if (!s.isEmpty()) on.add(Gear.id(s));
        }
        return on.isEmpty() ? "no armor" : String.join(", ", on);
    }
}
