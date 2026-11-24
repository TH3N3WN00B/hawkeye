package gg.moonrise.hawkeye.core.service.capture.container;

import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.util.serialize.ItemBytes;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Everything a player carries, for an inventory snapshot: the slots of {@code PlayerInventory#getContents()}
 * (0 to 35 storage, 36 to 39 armour, 40 the off hand) and the stack on the cursor as
 * {@link Payload.Snapshot#CURSOR}. Reads the live inventory, so call it on the player's own thread.
 *
 * @param slots  the non-empty slots, by index
 * @param amount the number of items in them
 */
@Slf4j
public record PlayerContents(@NotNull List<Payload.Slot> slots, int amount) {

    /**
     * Copies the slot list.
     *
     * @param slots  the non-empty slots
     * @param amount the number of items in them
     */
    public PlayerContents {
        slots = List.copyOf(slots);
    }

    /**
     * What a player carries now.
     *
     * @param player the player
     * @return the slots and their item count
     */
    public static @NotNull PlayerContents of(@NotNull Player player) {
        return of(player.getInventory().getContents(), player.getItemOnCursor());
    }

    /**
     * The non-empty slots of an inventory's contents and a cursor stack. An item that cannot be serialized
     * is left out (and not counted), so one broken item never loses the rest of the snapshot.
     *
     * @param contents the inventory's contents, by slot index
     * @param cursor   the stack on the cursor, or {@code null}
     * @return the slots and their item count
     */
    public static @NotNull PlayerContents of(@Nullable ItemStack @NotNull [] contents, @Nullable ItemStack cursor) {
        List<Payload.Slot> slots = new ArrayList<>();
        int amount = 0;
        for (int index = 0; index < contents.length; index++) {
            amount += add(slots, index, contents[index]);
        }
        amount += add(slots, Payload.Snapshot.CURSOR, cursor);
        return new PlayerContents(slots, amount);
    }

    private static int add(@NotNull List<Payload.Slot> slots, int index, @Nullable ItemStack item) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        try {
            slots.add(new Payload.Slot(index, ItemBytes.serialize(item)));
            return item.getAmount();
        } catch (RuntimeException e) {
            log.debug("Could not serialize a {} in slot {}; the snapshot is recorded without it", item.getType(),
                    index, e);
            return 0;
        }
    }
}
