package gg.moonrise.hawkeye.core.service.rollback;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.util.BlockPositions;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * Collects the events of one rollback or restore, page by page, and turns them into a
 * {@link RollbackPlan}. The result does not depend on the order events are added in. Not
 * thread-safe: one builder per operation, fed from one thread.
 *
 * <p>Per block position a rollback puts back the "before" of the oldest matching event (the broken
 * block of a break, the replaced block of a place or change) and a restore the "after" of the newest
 * (what a break left, the placed or changed block). Sign edits only change text: a rollback writes
 * each side as it was before the oldest edit that changed it, a restore as it was after the newest.
 * A broken container gets its recorded contents back and a broken sign its text.
 */
public final class PlanBuilder {

    private static final String AIR = "minecraft:air";
    private static final byte[] NO_ITEM = new byte[0];

    private final @NotNull RollbackMode mode;
    private final @NotNull PlanOptions options;
    private final Map<BlockKey, List<StoredEvent>> blocks = new HashMap<>();
    private final Map<ItemKey, Integer> items = new LinkedHashMap<>();
    private final List<Located> entities = new ArrayList<>();
    private final List<Long> otherIds = new ArrayList<>();

    /**
     * An empty builder.
     *
     * @param mode    rollback or restore
     * @param options what to include and where to start
     */
    public PlanBuilder(@NotNull RollbackMode mode, @NotNull PlanOptions options) {
        this.mode = mode;
        this.options = options;
    }

    /**
     * Adds a matching event; events that rollback and restore do not reverse are ignored.
     *
     * @param event the event
     */
    public void add(@NotNull StoredEvent event) {
        if (!event.action().reversible()) {
            return;
        }
        switch (event.action().family()) {
            case BLOCK -> blocks.computeIfAbsent(new BlockKey(event.world(), event.position()),
                    key -> new ArrayList<>(2)).add(event);
            case CONTAINER -> addItems(event);
            case ENTITY -> addKill(event);
            default -> {
                // Item, message and session actions are never reversible.
            }
        }
    }

    /**
     * Builds the plan from everything added so far.
     *
     * @return the plan
     */
    public @NotNull RollbackPlan build() {
        Map<ChunkKey, ChunkChanges> chunks = new HashMap<>();
        List<Long> ids = new ArrayList<>(otherIds);
        blocks.forEach((key, events) -> {
            List<StoredEvent> history = new ArrayList<>(events);
            history.sort(Comparator.comparingLong(StoredEvent::id));
            Optional<PlannedChange> change = mode == RollbackMode.ROLLBACK ? rollback(key.position(), history)
                    : restore(key.position(), history);
            change.ifPresent(planned -> chunk(chunks, key.world(), key.position()).blocks().add(planned));
            history.forEach(event -> ids.add(event.id()));
        });
        items.forEach((key, delta) -> {
            if (delta != 0) {
                chunk(chunks, key.world(), key.position()).items().add(new PlannedChange.AdjustItems(key.position(),
                        key.material(), bytes(key.item()), delta));
            }
        });
        entities.forEach(located -> chunk(chunks, located.world(), located.change().position()).entities()
                .add(located.change()));
        List<ChunkPlan> ordered = chunks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(chunkOrder()))
                .map(entry -> entry.getValue().plan(entry.getKey()))
                .toList();
        ids.sort(Comparator.naturalOrder());
        return new RollbackPlan(mode, ordered, ids);
    }

    private void addItems(@NotNull StoredEvent event) {
        if (!options.restoreContainers() || event.amount() <= 0 || event.subject().isEmpty()) {
            return;
        }
        // A rollback takes deposited items out and puts withdrawn ones back; a restore does the opposite.
        int sign = event.action() == Action.CONTAINER_DEPOSIT ? -1 : 1;
        if (mode == RollbackMode.RESTORE) {
            sign = -sign;
        }
        byte[] item = payload(event).orElse(null) instanceof Payload.Item stack ? stack.bytes() : NO_ITEM;
        items.merge(new ItemKey(event.world(), event.position(), event.subject(), ByteBuffer.wrap(item)),
                sign * event.amount(), Integer::sum);
        otherIds.add(event.id());
    }

    private void addKill(@NotNull StoredEvent event) {
        // A restore cannot kill the respawned entity again, so it leaves kills alone and flagged.
        if (mode != RollbackMode.ROLLBACK || !options.restoreEntities()) {
            return;
        }
        if (payload(event).orElse(null) instanceof Payload.Entity entity) {
            entities.add(new Located(event.world(), new PlannedChange.SpawnEntity(event.position(), entity.bytes())));
            otherIds.add(event.id());
        }
    }

    private static @NotNull Optional<PlannedChange> rollback(long position, @NotNull List<StoredEvent> history) {
        int first = 0;
        while (first < history.size() && history.get(first).action() == Action.SIGN_EDIT) {
            first++;
        }
        SignPatch edits = textBefore(history.subList(0, first));
        if (first == history.size()) {
            return edits.isEmpty() ? Optional.empty() : Optional.of(new PlannedChange.SetSignText(position, edits));
        }
        StoredEvent oldest = history.get(first);
        if (oldest.action() != Action.BLOCK_BREAK) {
            return Optional.of(new PlannedChange.SetBlock(position, secondaryOrAir(oldest), edits, List.of()));
        }
        Optional<Payload> payload = payload(oldest);
        SignPatch text = payload.orElse(null) instanceof Payload.Sign sign ? SignPatch.full(brokenText(sign))
                : SignPatch.NONE;
        List<Payload.Slot> contents = payload.orElse(null) instanceof Payload.Inventory inventory ? inventory.slots()
                : List.of();
        return Optional.of(new PlannedChange.SetBlock(position, oldest.subject(), text.overriddenBy(edits), contents));
    }

    private static @NotNull Optional<PlannedChange> restore(long position, @NotNull List<StoredEvent> history) {
        int last = history.size() - 1;
        while (last >= 0 && history.get(last).action() == Action.SIGN_EDIT) {
            last--;
        }
        SignPatch edits = textAfter(history.subList(last + 1, history.size()));
        if (last < 0) {
            return edits.isEmpty() ? Optional.empty() : Optional.of(new PlannedChange.SetSignText(position, edits));
        }
        StoredEvent newest = history.get(last);
        if (newest.action() == Action.BLOCK_BREAK) {
            return Optional.of(new PlannedChange.SetBlock(position, secondaryOrAir(newest), SignPatch.NONE,
                    List.of()));
        }
        return Optional.of(new PlannedChange.SetBlock(position, newest.subject(), edits, List.of()));
    }

    /** Each side as it was before the oldest of these edits that changed it. */
    private static @NotNull SignPatch textBefore(@NotNull List<StoredEvent> edits) {
        SidePicker picker = new SidePicker();
        edits.forEach(edit -> picker.offer(edit, true));
        return picker.patch();
    }

    /** Each side as it was after the newest of these edits that changed it. */
    private static @NotNull SignPatch textAfter(@NotNull List<StoredEvent> edits) {
        SidePicker picker = new SidePicker();
        edits.reversed().forEach(edit -> picker.offer(edit, false));
        return picker.patch();
    }

    /** The text of a broken sign: recorded as "after" with an empty "before". */
    private static @NotNull SignSides brokenText(@NotNull Payload.Sign sign) {
        return sign.after().isEmpty() ? sign.before() : sign.after();
    }

    private static @NotNull String secondaryOrAir(@NotNull StoredEvent event) {
        return event.secondaryValue().filter(state -> !state.isEmpty()).orElse(AIR);
    }

    private static @NotNull Optional<Payload> payload(@NotNull StoredEvent event) {
        try {
            return event.payload();
        } catch (RuntimeException e) {
            // A payload this build cannot read restores like an event without one.
            return Optional.empty();
        }
    }

    private static byte @NotNull [] bytes(@NotNull ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.duplicate().get(out);
        return out;
    }

    private static @NotNull ChunkChanges chunk(@NotNull Map<ChunkKey, ChunkChanges> chunks, @NotNull String world,
            long position) {
        return chunks.computeIfAbsent(new ChunkKey(world, BlockPositions.chunkX(position),
                BlockPositions.chunkZ(position)), key -> new ChunkChanges(new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>()));
    }

    private @NotNull Comparator<ChunkKey> chunkOrder() {
        Comparator<ChunkKey> fixed = Comparator.comparing(ChunkKey::world)
                .thenComparingInt(ChunkKey::chunkX)
                .thenComparingInt(ChunkKey::chunkZ);
        Optional<RollbackOrigin> origin = options.originValue();
        if (origin.isEmpty()) {
            return fixed;
        }
        String world = origin.get().world();
        int originX = origin.get().x() >> 4;
        int originZ = origin.get().z() >> 4;
        Comparator<ChunkKey> otherWorldsLast = Comparator.comparing(key -> !key.world().equals(world));
        return otherWorldsLast
                .thenComparingLong(key -> key.world().equals(world) ? distance(key, originX, originZ) : 0L)
                .thenComparing(fixed);
    }

    private static long distance(@NotNull ChunkKey key, int chunkX, int chunkZ) {
        long dx = (long) key.chunkX() - chunkX;
        long dz = (long) key.chunkZ() - chunkZ;
        return dx * dx + dz * dz;
    }

    /** Picks, per sign side, the first offered edit that changed it. */
    private static final class SidePicker {

        private List<String> front = List.of();
        private List<String> back = List.of();
        private boolean frontSet;
        private boolean backSet;

        private void offer(@NotNull StoredEvent edit, boolean before) {
            if (!(payload(edit).orElse(null) instanceof Payload.Sign sign)) {
                return;
            }
            SignSides chosen = before ? sign.before() : sign.after();
            if (!frontSet && !sign.before().front().equals(sign.after().front())) {
                front = chosen.front();
                frontSet = true;
            }
            if (!backSet && !sign.before().back().equals(sign.after().back())) {
                back = chosen.back();
                backSet = true;
            }
        }

        private @NotNull SignPatch patch() {
            return new SignPatch(new SignSides(front, back), frontSet, backSet);
        }
    }

    private record BlockKey(@NotNull String world, long position) {
    }

    private record ItemKey(@NotNull String world, long position, @NotNull String material, @NotNull ByteBuffer item) {
    }

    private record ChunkKey(@NotNull String world, int chunkX, int chunkZ) {
    }

    private record Located(@NotNull String world, @NotNull PlannedChange change) {
    }

    private record ChunkChanges(@NotNull List<PlannedChange> blocks, @NotNull List<PlannedChange> items,
            @NotNull List<PlannedChange> entities) {

        private @NotNull ChunkPlan plan(@NotNull ChunkKey key) {
            List<PlannedChange> changes = new ArrayList<>(blocks.size() + items.size() + entities.size());
            blocks.stream().sorted(Comparator.comparingLong(PlannedChange::position)).forEach(changes::add);
            changes.addAll(items);
            changes.addAll(entities);
            return new ChunkPlan(key.world(), key.chunkX(), key.chunkZ(), changes);
        }
    }
}
