package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/**
 * One remote shard in {@code network.shards}.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class ShardSettings {

    /** The reserved name of the home shard. */
    public static final String HOME = "home";

    @Comment("A short name for the shard (1-32 of a-z, 0-9, _ and -).")
    private String name = "shard";

    private StorageSettings storage = new StorageSettings();

}
