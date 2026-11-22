package gg.moonrise.hawkeye.core.service.rollback.apply;

import gg.moonrise.hawkeye.core.service.capture.container.ContainerLocations;
import java.util.Comparator;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

/**
 * Container changes recorded at an entity instead of a container block: an item frame, an armour stand,
 * or an entity that carries a container ({@link ContainerLocations#carriesContainer}: minecarts with a
 * chest or hopper, chest boats, donkeys, mules and llamas). Putting items back fills an empty frame, the
 * free equipment slot of a stand that suits the item, or a carried inventory; taking them out clears a
 * frame or stand slot that holds a similar item, or takes similar items out of a carried inventory.
 * Frames come before stands, stands before carried inventories. Call only on the thread that owns the
 * block.
 */
public final class HeldItems {

    private static final List<EquipmentSlot> STAND_SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND);
    private static final List<EquipmentSlot> HANDS = List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND);

    private HeldItems() {
    }

    /**
     * Puts items into ({@code delta > 0}) or takes them out of ({@code delta < 0}) the item frames,
     * armour stands and carried inventories at a block.
     *
     * @param block    the block the entities stand in
     * @param template the item, any amount
     * @param delta    how many
     * @return how many items moved
     */
    public static int adjust(@NotNull Block block, @NotNull ItemStack template, int delta) {
        int requested = Math.abs(delta);
        int left = requested;
        for (Entity holder : holders(block)) {
            if (left == 0) {
                break;
            }
            left -= switch (holder) {
                case ItemFrame frame -> delta > 0 ? putInto(frame, template) : takeFrom(frame, template);
                case ArmorStand stand -> delta > 0 ? putInto(stand, template, left) : takeFrom(stand, template, left);
                case InventoryHolder carrier -> BlockInventories.adjust(carrier.getInventory(), template,
                        delta > 0 ? left : -left);
                default -> 0;
            };
        }
        return requested - left;
    }

    /** The frames, then the stands, then the container carriers whose position lies in the block, in a stable order. */
    private static @NotNull List<Entity> holders(@NotNull Block block) {
        return block.getWorld().getNearbyEntities(BoundingBox.of(block),
                        entity -> (entity instanceof ItemFrame || entity instanceof ArmorStand
                                || ContainerLocations.carriesContainer(entity)) && in(entity, block))
                .stream()
                .sorted(Comparator.comparingInt(HeldItems::rank).thenComparing(Entity::getUniqueId))
                .toList();
    }

    private static int rank(@NotNull Entity entity) {
        return switch (entity) {
            case ItemFrame frame -> 0;
            case ArmorStand stand -> 1;
            default -> 2;
        };
    }

    private static boolean in(@NotNull Entity entity, @NotNull Block block) {
        return entity.getLocation().getBlockX() == block.getX() && entity.getLocation().getBlockY() == block.getY()
                && entity.getLocation().getBlockZ() == block.getZ();
    }

    private static int putInto(@NotNull ItemFrame frame, @NotNull ItemStack template) {
        if (!frame.getItem().isEmpty()) {
            return 0;
        }
        frame.setItem(template.asOne(), false);
        return 1;
    }

    private static int takeFrom(@NotNull ItemFrame frame, @NotNull ItemStack template) {
        if (!frame.getItem().isSimilar(template)) {
            return 0;
        }
        frame.setItem(ItemStack.empty(), false);
        return 1;
    }

    private static int putInto(@NotNull ArmorStand stand, @NotNull ItemStack template, int wanted) {
        EquipmentSlot preferred = slotFor(template);
        List<EquipmentSlot> slots = HANDS.contains(preferred) ? HANDS : List.of(preferred);
        int put = 0;
        for (EquipmentSlot slot : slots) {
            if (put < wanted && stand.getItem(slot).isEmpty()) {
                int amount = Math.min(wanted - put, Math.max(1, template.getMaxStackSize()));
                stand.setItem(slot, template.asQuantity(amount));
                put += amount;
            }
        }
        return put;
    }

    private static int takeFrom(@NotNull ArmorStand stand, @NotNull ItemStack template, int wanted) {
        int taken = 0;
        for (EquipmentSlot slot : STAND_SLOTS) {
            ItemStack held = stand.getItem(slot);
            if (taken == wanted || !held.isSimilar(template)) {
                continue;
            }
            int amount = Math.min(wanted - taken, held.getAmount());
            stand.setItem(slot, amount == held.getAmount() ? ItemStack.empty() : held.asQuantity(held.getAmount()
                    - amount));
            taken += amount;
        }
        return taken;
    }

    /** The stand slot an item is worn in (armour, heads, shields), else the main hand. */
    private static @NotNull EquipmentSlot slotFor(@NotNull ItemStack item) {
        EquipmentSlot slot;
        try {
            slot = item.getType().getEquipmentSlot();
        } catch (RuntimeException e) {
            // A server or test double without the item's equippable data.
            return EquipmentSlot.HAND;
        }
        // Horse armour and saddles name slots a stand does not have.
        return STAND_SLOTS.contains(slot) ? slot : EquipmentSlot.HAND;
    }
}
