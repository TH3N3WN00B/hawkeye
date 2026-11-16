package gg.moonrise.hawkeye.core.service.capture;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Compares two snapshots of a container's contents. Items are grouped with
 * {@link ItemStack#isSimilar}, so moving a stack between slots or splitting it changes nothing, and
 * only the net count per kind of item is reported.
 */
public final class InventoryDiff {

    private InventoryDiff() {
    }

    /**
     * The net change of one kind of item.
     *
     * @param item   an item of that kind (its amount is meaningless)
     * @param amount how many more there are now; negative when items were taken out
     */
    public record Delta(@NotNull ItemStack item, int amount) {
    }

    /**
     * Copies inventory contents so later changes to the live stacks do not reach the snapshot.
     *
     * @param contents the contents, as {@code Inventory#getContents()} returns them
     * @return a copy with every empty slot as {@code null}
     */
    public static @Nullable ItemStack @NotNull [] copy(@Nullable ItemStack @NotNull [] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            copy[slot] = item == null || item.isEmpty() ? null : item.clone();
        }
        return copy;
    }

    /**
     * The net changes between two snapshots.
     *
     * @param before the earlier contents
     * @param after  the later contents
     * @return one delta per kind of item whose count changed, in the order the kinds first appear
     */
    public static @NotNull List<Delta> diff(@Nullable ItemStack @NotNull [] before,
            @Nullable ItemStack @NotNull [] after) {
        List<Tally> tallies = new ArrayList<>();
        count(tallies, before, -1);
        count(tallies, after, 1);
        List<Delta> deltas = new ArrayList<>();
        for (Tally tally : tallies) {
            if (tally.net != 0) {
                deltas.add(new Delta(tally.item, tally.net));
            }
        }
        return deltas;
    }

    private static void count(@NotNull List<Tally> tallies, @Nullable ItemStack @NotNull [] contents, int sign) {
        for (ItemStack item : contents) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            tally(tallies, item).net += sign * item.getAmount();
        }
    }

    private static @NotNull Tally tally(@NotNull List<Tally> tallies, @NotNull ItemStack item) {
        for (Tally tally : tallies) {
            if (tally.item.isSimilar(item)) {
                return tally;
            }
        }
        Tally tally = new Tally(item);
        tallies.add(tally);
        return tally;
    }

    /** The running net count of one kind of item. */
    private static final class Tally {

        private final ItemStack item;
        private int net;

        private Tally(@NotNull ItemStack item) {
            this.item = item;
        }
    }
}
