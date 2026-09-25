package tachyon;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.core.component.DataComponents;

import java.util.Locale;

/**
 * What a bot carries, as a player sees it: the 36 slots of its inventory (the hotbar, 0 to
 * 8, and the backpack, 9 to 35), besides its armor and its off hand. Small helpers the
 * abilities share: an item's id in words and back, how well something hits or protects,
 * how many of something it has, and bringing something up from the backpack into the hand.
 *
 * <p>The server's inventory is the only one there is: nothing here waits for a client to
 * agree, as Masurium's client bots had to (a click into a menu that was not the one open
 * was dropped without a word). What moves, moves at once, as a player's own clicks in their
 * inventory would move it.
 */
final class Gear {

    /** The slots a player's hands reach: the hotbar and the backpack. 36 to 40 are the armor and the off hand. */
    static final int SLOTS = 36;

    private Gear() {
    }

    // --- words ---------------------------------------------------------------------------

    /** An item's id as the model and the players say it: {@code bread}, and a mod's with its name, {@code create:cog}. */
    static String id(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
    }

    static String id(ItemStack stack) {
        return id(stack.getItem());
    }

    /**
     * The item some words name, or null when none does: an id ({@code cooked_beef},
     * {@code minecraft:cooked_beef}, {@code create:cog}), in any case and with spaces for
     * underscores, as a model may write it ("Cooked Beef").
     */
    static Item item(String words) {
        if (words == null) return null;
        String w = words.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (w.isEmpty()) return null;
        ResourceLocation key = ResourceLocation.tryParse(w.contains(":") ? w : "minecraft:" + w);
        if (key == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        return item == null || item == Items.AIR ? null : item;
    }

    // --- what it has ------------------------------------------------------------------------

    /** How many of an item it carries in its 36 slots (not what it wears, nor its off hand). */
    static int count(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** The first of its 36 slots with that item, the hotbar first; -1 when it has none. */
    static int find(Inventory inv, Item item) {
        for (int i = 0; i < SLOTS; i++) {
            if (inv.getItem(i).is(item)) return i;
        }
        return -1;
    }

    /** Whether a stack would go in whole or in part: a free slot, or a stack of it with room left. */
    static boolean fits(Inventory inv, ItemStack stack) {
        return inv.getFreeSlot() >= 0 || inv.getSlotWithRemainingSpace(stack) >= 0;
    }

    // --- what it hits with ----------------------------------------------------------------

    /**
     * A real weapon: a sword, an axe, a trident, a mace. A pickaxe hits too, but it is not a
     * weapon: it costs double durability, and it is the tool the bot is working with.
     */
    static boolean isWeapon(ItemStack s) {
        Item it = s.getItem();
        return it instanceof SwordItem || it instanceof AxeItem || it instanceof TridentItem || it instanceof MaceItem;
    }

    /**
     * Damage per second in the main hand: {@code (1 + attack damage) x (4 + attack speed)},
     * from the item's own attributes, so a mod's weapons count as they hit. Per second and
     * not per hit: a wooden sword (4 x 1.6) out-hits a diamond pickaxe (5 x 1.2), which one
     * hit alone would not say. An empty hand is 1 x 4.
     */
    static double damagePerSecond(ItemStack s) {
        double damage = 1 + sum(s, Attributes.ATTACK_DAMAGE, EquipmentSlot.MAINHAND);
        double speed = Math.max(0.1, 4 + sum(s, Attributes.ATTACK_SPEED, EquipmentSlot.MAINHAND));
        return damage * speed;
    }

    /**
     * How good it is to hit with, as {@link Job#wield} weighs it: a weapon always above a
     * tool, whatever each hits (Masurium's bots were once seen defending themselves with a
     * pickaxe, a diamond sword in their backpack), then damage per second.
     */
    static double weapon(ItemStack s) {
        return (isWeapon(s) ? 1000 : 0) + damagePerSecond(s);
    }

    /** What it hits best with of what it carries, by {@link #weapon}; empty when nothing beats a bare hand. */
    static ItemStack bestWeapon(Inventory inv) {
        ItemStack best = ItemStack.EMPTY;
        double bestScore = weapon(ItemStack.EMPTY);
        for (int i = 0; i < SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            double v = weapon(s);
            if (v > bestScore) {
                bestScore = v;
                best = s;
            }
        }
        return best;
    }

    /**
     * How well a piece protects in a body slot: its armor points there, and a tenth of its
     * toughness to break ties. From the item's attributes for that slot, so it holds for a
     * mod's armor; 0 for what gives none there (an elytra, a pumpkin, a sword).
     */
    static double protection(ItemStack s, EquipmentSlot slot) {
        if (s.isEmpty()) return 0;
        return sum(s, Attributes.ARMOR, slot) + sum(s, Attributes.ARMOR_TOUGHNESS, slot) / 10.0;
    }

    /** What an item adds to an attribute in a slot: its flat modifiers there. */
    private static double sum(ItemStack s, Holder<Attribute> attribute, EquipmentSlot slot) {
        if (s.isEmpty()) return 0;
        double[] total = {0};
        s.forEachModifier(slot, (a, m) -> {
            if (a.value() == attribute.value() && m.operation() == AttributeModifier.Operation.ADD_VALUE) total[0] += m.amount();
        });
        return total[0];
    }

    // --- into the hand ------------------------------------------------------------------------

    /**
     * What is in slot {@code slot} into the hand: selected, if it is in the hotbar; brought up
     * from the backpack first, if not, as a player does with the inventory open (a number
     * key over it). Which hotbar slot it takes is Masurium's rule ({@link #giveUp}). The
     * item in hand changes, so the attack's charge starts again, as a player's does.
     *
     * @return whether the hand changed
     */
    static boolean toHand(Bots.Bot p, int slot) {
        Inventory inv = p.body.getInventory();
        if (slot == inv.selected || slot < 0 || slot >= SLOTS) return false;
        if (Inventory.isHotbarSlot(slot)) {
            inv.selected = slot;
            return true;
        }
        int to = giveUp(inv, inv.getItem(slot));
        ItemStack down = inv.getItem(to);
        inv.setItem(to, inv.getItem(slot));
        inv.setItem(slot, down);
        inv.selected = to;
        return true;
    }

    /**
     * The hotbar slot to give up for {@code coming} (what goes down swaps into the backpack,
     * where {@code coming} was: nothing is lost). For a weapon, as Masurium's WeaponPicker
     * chooses: the weakest weapon that is not a tool, if it is weaker than the one coming (an
     * old sword makes room for a better one; not an axe, which also chops); else an empty
     * slot; else the most expendable, the smallest stack of what is not a tool, weapon, bow,
     * food or torch; else the one in hand.
     * For anything else, as its takeFromBackpack does: an empty slot, else the one in hand,
     * the one it is letting go of anyway.
     */
    static int giveUp(Inventory inv, ItemStack coming) {
        if (isWeapon(coming)) {
            int weakest = -1;
            for (int i = 0; i < Inventory.getSelectionSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!isWeapon(s) || s.getItem() instanceof DiggerItem) continue;
                if (weakest < 0 || weapon(s) < weapon(inv.getItem(weakest))) weakest = i;
            }
            if (weakest >= 0 && weapon(inv.getItem(weakest)) < weapon(coming)) return weakest;
        }
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inv.getItem(i).isEmpty()) return i;
        }
        if (isWeapon(coming)) {
            int expendable = -1;
            for (int i = 0; i < Inventory.getSelectionSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (i == inv.selected || valuable(s)) continue;
                if (expendable < 0 || s.getCount() < inv.getItem(expendable).getCount()) expendable = i;
            }
            if (expendable >= 0) return expendable;
        }
        return inv.selected;
    }

    /** What is not sent down to the backpack to make room for a weapon: tools, weapons, bows, food, torches. */
    private static boolean valuable(ItemStack s) {
        Item it = s.getItem();
        return it instanceof DiggerItem || isWeapon(s) || it instanceof ProjectileWeaponItem
                || s.has(DataComponents.FOOD) || it == Items.TORCH;
    }
}
