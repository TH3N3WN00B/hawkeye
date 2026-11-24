package gg.moonrise.hawkeye.core.service.capture.container;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Which changes of a virtual container were the viewer's own. Plugin menus change their own contents
 * (page flips, animations, buttons) while the viewer's clicks are cancelled, so a change of the
 * container counts only as far as what the viewer holds changed the opposite way: per kind of item
 * ({@link ItemStack#isSimilar}), a container that gained items while the viewer lost some is a deposit
 * of the smaller count, a container that lost items while the viewer gained some a withdrawal of the
 * smaller count, anything else nothing.
 */
public final class VirtualTransfers {

    private VirtualTransfers() {
    }

    /**
     * The viewer's own moves.
     *
     * @param container the net changes of the container, as {@link InventoryDiff#diff} returns them
     * @param held      the net changes of what the viewer holds (inventory, cursor, and what they dropped
     *                  from the container)
     * @return one delta per kind of item that moved: positive for a deposit, negative for a withdrawal, in
     *         the container's order
     */
    public static @NotNull List<InventoryDiff.Delta> match(@NotNull List<InventoryDiff.Delta> container,
            @NotNull List<InventoryDiff.Delta> held) {
        List<InventoryDiff.Delta> moves = new ArrayList<>();
        for (InventoryDiff.Delta change : container) {
            int own = net(held, change.item());
            int moved = 0;
            if (change.amount() > 0 && own < 0) {
                moved = Math.min(change.amount(), -own);
            } else if (change.amount() < 0 && own > 0) {
                moved = -Math.min(-change.amount(), own);
            }
            if (moved != 0) {
                moves.add(new InventoryDiff.Delta(change.item(), moved));
            }
        }
        return moves;
    }

    /**
     * Net changes without the part already accounted for elsewhere (the moves another viewer of the same
     * inventory was credited with).
     *
     * @param changes   the net changes
     * @param accounted the changes to take out
     * @return one delta per kind of item whose count still changed, in the order the kinds first appear
     */
    public static @NotNull List<InventoryDiff.Delta> without(@NotNull List<InventoryDiff.Delta> changes,
            @NotNull List<InventoryDiff.Delta> accounted) {
        if (accounted.isEmpty()) {
            return changes;
        }
        List<InventoryDiff.Delta> sums = new ArrayList<>(changes);
        for (InventoryDiff.Delta taken : accounted) {
            int index = indexOf(sums, taken.item());
            if (index < 0) {
                sums.add(new InventoryDiff.Delta(taken.item(), -taken.amount()));
            } else {
                InventoryDiff.Delta sum = sums.get(index);
                sums.set(index, new InventoryDiff.Delta(sum.item(), sum.amount() - taken.amount()));
            }
        }
        sums.removeIf(delta -> delta.amount() == 0);
        return sums;
    }

    private static int net(@NotNull List<InventoryDiff.Delta> deltas, @NotNull ItemStack item) {
        int net = 0;
        for (InventoryDiff.Delta delta : deltas) {
            if (delta.item().isSimilar(item)) {
                net += delta.amount();
            }
        }
        return net;
    }

    private static int indexOf(@NotNull List<InventoryDiff.Delta> deltas, @NotNull ItemStack item) {
        for (int index = 0; index < deltas.size(); index++) {
            if (deltas.get(index).item().isSimilar(item)) {
                return index;
            }
        }
        return -1;
    }
}
