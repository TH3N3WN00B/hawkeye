package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code server} section.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class ServerSettings {

    /** Valid server names and scopes. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    @Comment({"Unique name of this server in its database (1-32 of a-z, 0-9, _ and -). Network lookups",
            "and jobs address servers by this name. Changing it later makes this a new server."})
    private String name = "server";

    @Comment({"Servers that share world files (MultiPaper) set the same scope so their worlds are one.",
            "Leave blank to use the server name."})
    private String worldScope = "";

    /**
     * The scope this server's worlds are keyed by.
     *
     * @return the world scope, or the server name when none is set
     */
    public @NotNull String effectiveWorldScope() {
        return worldScope.isBlank() ? name : worldScope;
    }

    /**
     * Validation problems.
     *
     * @return one message per problem
     */
    public @NotNull List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!NAME.matcher(name).matches()) {
            problems.add("server.name must be 1-32 of a-z, 0-9, _ and -: '" + name + "'");
        }
        if (!worldScope.isBlank() && !NAME.matcher(worldScope).matches()) {
            problems.add("server.world-scope must be blank or 1-32 of a-z, 0-9, _ and -: '" + worldScope + "'");
        }
        return problems;
    }
}
