package gg.moonrise.hawkeye.core.storage.replay;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.hawkeye.util.codec.PayloadCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Turns the rows of an exported CoreProtect database into the events Hawkeye would have captured for
 * the same activity, second by second in capture order.
 *
 * <p>What a row becomes: a block row is a break, place or interaction whose subject is the block state
 * (material plus properties) with air as the other side, or a kill whose payload is the stored entity;
 * a container row is a deposit or withdrawal of a material and count, with the item's stored data when
 * it has any; entity container and interaction rows carry the entity's UUID; chat, commands and signs
 * carry their text. CoreProtect stores whole seconds and a row id per table, so inside a second each
 * table's rows are spread evenly over the second in row id order and the tables are merged by that
 * time: the same information, as capture stamps.
 */
public final class ReferenceEvents {

    private static final String AIR = "minecraft:air";
    private static final Action[] ENTITY_ACTIONS = {Action.ENTITY_SHEAR, Action.ENTITY_NAME, Action.ENTITY_LEASH,
            Action.ENTITY_UNLEASH, Action.ENTITY_TAME, Action.ENTITY_DYE};

    private final ReferenceDump dump;
    private final Actor[] actors;
    private final String[] worlds;
    private final String[] materials;
    private final String[] properties;
    private final String[] entities;
    private final Map<Long, UUID> spawns = new HashMap<>();
    private final Map<String, String> states = new HashMap<>();
    private long lastStamp;
    private long rolledBack;

    /**
     * Loads the export's dictionaries.
     *
     * @param dump the export
     * @throws IOException when a dictionary cannot be read
     */
    public ReferenceEvents(@NotNull ReferenceDump dump) throws IOException {
        this.dump = dump;
        List<String[]> users = dump.dictionary("users");
        this.actors = new Actor[maxId(users) + 1];
        for (String[] user : users) {
            actors[Integer.parseInt(user[0])] = actor(user);
        }
        this.worlds = names(dump.dictionary("worlds"), "");
        this.materials = names(dump.dictionary("materials"), "");
        this.properties = names(dump.dictionary("blockdata"), "");
        this.entities = names(dump.dictionary("entities"), "minecraft:");
        for (String[] spawn : dump.dictionary("spawns")) {
            if (spawn.length > 1 && spawn[1].length() == 36) {
                spawns.put(Long.parseLong(spawn[0]), UUID.fromString(spawn[1]));
            }
        }
    }

    /**
     * Replays one day.
     *
     * @param day  the UTC day number
     * @param sink receives the events of each second that has any, in capture order
     * @return the number of events
     * @throws IOException when a row file cannot be read
     */
    public long replay(int day, @NotNull SecondSink sink) throws IOException {
        List<Cursor> cursors = new ArrayList<>();
        try {
            for (int table = 0; table < ReferenceDump.TABLES.size(); table++) {
                Optional<ReferenceDump.Rows> rows = dump.open(day, ReferenceDump.TABLES.get(table));
                if (rows.isPresent()) {
                    cursors.add(new Cursor(table, rows.get()));
                }
            }
            long total = 0;
            List<Draft> second = new ArrayList<>();
            while (true) {
                long time = Long.MAX_VALUE;
                for (Cursor cursor : cursors) {
                    time = Math.min(time, cursor.time);
                }
                if (time == Long.MAX_VALUE) {
                    return total;
                }
                second.clear();
                for (Cursor cursor : cursors) {
                    int from = second.size();
                    while (cursor.time == time) {
                        second.add(draft(cursor));
                        cursor.advance();
                    }
                    int count = second.size() - from;
                    for (int index = 0; index < count; index++) {
                        Draft draft = second.get(from + index);
                        draft.millis = (int) (1000L * index / count);
                        draft.order = from + index;
                    }
                }
                second.sort(Comparator.comparingInt((Draft draft) -> draft.millis).thenComparingInt(draft -> draft.order));
                List<PendingEvent> events = new ArrayList<>(second.size());
                for (Draft draft : second) {
                    lastStamp = Math.max(EventIds.stamp(time * 1000 + draft.millis, 0), lastStamp + 1);
                    events.add(new PendingEvent(lastStamp, draft.world, draft.position, draft.actor, draft.action,
                            draft.subject, draft.secondary, draft.amount, draft.data));
                }
                total += events.size();
                sink.accept(time, events);
            }
        } finally {
            for (Cursor cursor : cursors) {
                cursor.rows.close();
            }
        }
    }

    /**
     * Rows whose rolled-back flag was set; the replay does not carry the flag.
     *
     * @return the count so far
     */
    public long rolledBack() {
        return rolledBack;
    }

    private @NotNull Draft draft(@NotNull Cursor cursor) {
        ReferenceDump.Rows rows = cursor.rows;
        Draft draft = new Draft();
        int user = (int) rows.integer(cursor.user);
        draft.actor = user > 0 && user < actors.length && actors[user] != null ? actors[user] : Actor.source("unknown");
        draft.world = name(worlds, rows.integer(cursor.world), "world");
        draft.position = BlockPositions.pack((int) rows.integer(cursor.x),
                Math.clamp((int) rows.integer(cursor.y), BlockPositions.MIN_Y, BlockPositions.MAX_Y),
                (int) rows.integer(cursor.z));
        if (cursor.rolledBack >= 0 && rows.integer(cursor.rolledBack) != 0) {
            rolledBack++;
        }
        switch (cursor.table) {
            case 0 -> block(cursor, draft);
            case 1 -> container(cursor, draft, null);
            case 2 -> container(cursor, draft, spawns.get(rows.integer(cursor.spawn)));
            case 3 -> {
                int action = (int) rows.integer(cursor.action);
                draft.action = ENTITY_ACTIONS[Math.floorMod(action, ENTITY_ACTIONS.length)];
                draft.subject = name(entities, rows.integer(cursor.type), "minecraft:unknown");
                UUID uuid = spawns.get(rows.integer(cursor.spawn));
                draft.data = uuid == null ? null : PayloadCodec.encode(new Payload.EntityRef(uuid));
            }
            case 4 -> {
                draft.action = itemAction((int) rows.integer(cursor.action));
                draft.subject = name(materials, rows.integer(cursor.type), AIR);
                draft.amount = (int) rows.integer(cursor.amount);
                draft.data = rows.bytes(cursor.data).map(bytes -> PayloadCodec.encode(new Payload.Item(bytes)))
                        .orElse(null);
            }
            case 5 -> {
                draft.action = Action.SIGN_EDIT;
                List<String> front = new ArrayList<>();
                List<String> back = new ArrayList<>();
                for (int line = 0; line < 4; line++) {
                    front.add(rows.text(cursor.lines + line));
                    back.add(rows.text(cursor.lines + 4 + line));
                }
                draft.data = PayloadCodec.encode(new Payload.Sign(SignSides.EMPTY, new SignSides(front, back)));
            }
            case 6, 7 -> {
                draft.action = cursor.table == 6 ? Action.CHAT : Action.COMMAND;
                draft.data = PayloadCodec.encode(new Payload.Text(rows.text(cursor.message)));
            }
            default -> draft.action = rows.integer(cursor.action) == 1 ? Action.JOIN : Action.QUIT;
        }
        return draft;
    }

    private void block(@NotNull Cursor cursor, @NotNull Draft draft) {
        ReferenceDump.Rows rows = cursor.rows;
        int action = (int) rows.integer(cursor.action);
        if (action == 3) {
            draft.action = Action.ENTITY_KILL;
            draft.subject = name(entities, rows.integer(cursor.type), "minecraft:unknown");
            draft.data = rows.bytes(cursor.entity).map(bytes -> PayloadCodec.encode(new Payload.Entity(bytes)))
                    .orElse(null);
            return;
        }
        draft.action = switch (action) {
            case 0 -> Action.BLOCK_BREAK;
            case 1 -> Action.BLOCK_PLACE;
            case 2 -> Action.INTERACT;
            default -> Action.BLOCK_CHANGE;
        };
        String material = name(materials, rows.integer(cursor.type), AIR);
        draft.subject = rows.bytes(cursor.blockData)
                .map(list -> states.computeIfAbsent(material + '|' + new String(list, StandardCharsets.US_ASCII),
                        key -> state(material, new String(list, StandardCharsets.US_ASCII))))
                .orElse(material);
        draft.secondary = action == 0 || action == 1 ? AIR : null;
        draft.data = rows.bytes(cursor.meta)
                .map(bytes -> PayloadCodec.encode(new Payload.Inventory(List.of(new Payload.Slot(0, bytes)))))
                .orElse(null);
    }

    private void container(@NotNull Cursor cursor, @NotNull Draft draft, @Nullable UUID entity) {
        ReferenceDump.Rows rows = cursor.rows;
        draft.action = rows.integer(cursor.action) == 1 ? Action.CONTAINER_DEPOSIT : Action.CONTAINER_WITHDRAW;
        draft.subject = name(materials, rows.integer(cursor.type), AIR);
        draft.amount = (int) rows.integer(cursor.amount);
        Optional<byte[]> item = rows.bytes(cursor.metadata);
        if (item.isPresent()) {
            draft.data = PayloadCodec.encode(new Payload.Item(item.get()));
        } else if (entity != null) {
            draft.data = PayloadCodec.encode(new Payload.EntityRef(entity));
        }
    }

    /** A block state the way Bukkit prints it: {@code minecraft:lever[face=wall,facing=north,powered=true]}. */
    private @NotNull String state(@NotNull String material, @NotNull String list) {
        StringBuilder state = new StringBuilder(material).append('[');
        for (String id : list.split(",")) {
            try {
                String property = name(properties, Long.parseLong(id.trim()), "");
                if (!property.isEmpty()) {
                    state.append(state.charAt(state.length() - 1) == '[' ? "" : ",").append(property);
                }
            } catch (NumberFormatException e) {
                // Not an interned property list (an older row): the material alone is the state.
                return material;
            }
        }
        return state.charAt(state.length() - 1) == '[' ? material : state.append(']').toString();
    }

    private static @NotNull Action itemAction(int action) {
        return switch (action) {
            case 3, 4 -> Action.ITEM_PICKUP;
            case 6, 7 -> Action.ITEM_THROW;
            case 8, 9 -> Action.ITEM_BREAK;
            case 10 -> Action.ITEM_CRAFT;
            case 11, 12 -> Action.ITEM_TRADE;
            default -> Action.ITEM_DROP;
        };
    }

    private static @NotNull Actor actor(String @NotNull [] user) {
        String name = user[1].isEmpty() ? "unknown" : user[1];
        if (user.length > 2 && user[2].length() == 36) {
            return Actor.player(UUID.fromString(user[2]), name.length() > Actor.MAX_LENGTH
                    ? name.substring(0, Actor.MAX_LENGTH) : name);
        }
        return Actor.source(name.length() > Actor.MAX_LENGTH - 1 ? name.substring(0, Actor.MAX_LENGTH - 1) : name);
    }

    private static int maxId(@NotNull List<String[]> rows) {
        int max = 0;
        for (String[] row : rows) {
            max = Math.max(max, Integer.parseInt(row[0]));
        }
        return max;
    }

    private static String @NotNull [] names(@NotNull List<String[]> rows, @NotNull String prefix) {
        String[] names = new String[maxId(rows) + 1];
        for (String[] row : rows) {
            names[Integer.parseInt(row[0])] = prefix + (row.length > 1 ? row[1] : "");
        }
        return names;
    }

    private static @NotNull String name(String @NotNull [] names, long id, @NotNull String fallback) {
        return id > 0 && id < names.length && names[(int) id] != null ? names[(int) id] : fallback;
    }

    /** Receives one second of events. */
    @FunctionalInterface
    public interface SecondSink {

        /**
         * Takes the events of one second.
         *
         * @param epochSecond the second
         * @param events      its events, in capture order
         */
        void accept(long epochSecond, @NotNull List<PendingEvent> events);
    }

    /** One table's rows of a day, positioned on the next unread row. */
    private static final class Cursor {

        private final int table;
        private final ReferenceDump.Rows rows;
        private final int user;
        private final int world;
        private final int x;
        private final int y;
        private final int z;
        private final int type;
        private final int action;
        private final int amount;
        private final int rolledBack;
        private final int meta;
        private final int blockData;
        private final int entity;
        private final int metadata;
        private final int spawn;
        private final int data;
        private final int message;
        private final int lines;
        private long time;

        private Cursor(int table, @NotNull ReferenceDump.Rows rows) throws IOException {
            this.table = table;
            this.rows = rows;
            this.user = rows.column("user");
            this.world = rows.column("wid");
            this.x = rows.column("x");
            this.y = rows.column("y");
            this.z = rows.column("z");
            this.type = optional("type");
            this.action = optional("action");
            this.amount = optional("amount");
            this.rolledBack = optional("rolled_back");
            this.meta = optional("meta");
            this.blockData = optional("blockdata");
            this.entity = optional("entity");
            this.metadata = optional("metadata");
            this.spawn = optional("entity_spawn_rowid");
            this.data = optional("data");
            this.message = optional("message");
            this.lines = optional("line_1");
            advance();
        }

        private void advance() throws IOException {
            time = rows.next() ? rows.integer(0) : Long.MAX_VALUE;
        }

        private int optional(@NotNull String name) {
            try {
                return rows.column(name);
            } catch (IllegalArgumentException e) {
                return -1;
            }
        }
    }

    /** One event before its capture stamp is known. */
    private static final class Draft {

        private String world = "world";
        private long position;
        private Actor actor = Actor.source("unknown");
        private Action action = Action.BLOCK_CHANGE;
        private String subject = "";
        private String secondary;
        private int amount;
        private byte[] data;
        private int millis;
        private int order;
    }
}
