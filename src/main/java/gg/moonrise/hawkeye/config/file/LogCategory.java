package gg.moonrise.hawkeye.config.file;

/** A switch in the {@code logging} section: which listener output is recorded. */
public enum LogCategory {

    /** Players breaking blocks. */
    BLOCK_BREAK,
    /** Players placing blocks. */
    BLOCK_PLACE,
    /** Blocks destroyed by TNT, creepers, beds, anchors and other explosions. */
    EXPLOSIONS,
    /** Fire spreading and burning blocks, and blocks being lit. */
    FIRE,
    /** Water and lava flowing. */
    FLUIDS,
    /** Pistons moving blocks. */
    PISTONS,
    /** Natural changes: leaf decay, growth, snow and ice forming, melting, spreading. */
    NATURAL,
    /** Buckets filled and emptied. */
    BUCKETS,
    /** Sign text. */
    SIGNS,
    /** Doors, levers, buttons, repeaters and other block uses. */
    INTERACTIONS,
    /** Mobs changing blocks: endermen, falling blocks, trampled farmland, eaten grass. */
    ENTITY_CHANGES,
    /** Items put into or taken from containers by players. */
    CONTAINERS,
    /** Hoppers, droppers and other automatic container transfers. */
    HOPPERS,
    /** Items dropped. */
    ITEM_DROPS,
    /** Items picked up. */
    ITEM_PICKUPS,
    /** Entities and players killed. */
    KILLS,
    /** Chat messages. */
    CHAT,
    /** Commands. */
    COMMANDS,
    /** Joins and quits. */
    SESSIONS
}
