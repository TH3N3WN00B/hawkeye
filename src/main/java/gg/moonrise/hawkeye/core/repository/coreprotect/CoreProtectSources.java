package gg.moonrise.hawkeye.core.repository.coreprotect;

import gg.moonrise.hawkeye.entity.importer.SourceLocation;
import gg.moonrise.hawkeye.entity.importer.SourceSettings;
import gg.moonrise.moss.spring.SpringComponent;
import java.nio.file.Path;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The entry point to CoreProtect databases: finds one from the operator's argument and opens it
 * read-only with the engine's driver.
 */
@SpringComponent
@RequiredArgsConstructor
public final class CoreProtectSources {

    private final @NotNull SourceDrivers drivers;
    private final SourceLocator locator = new SourceLocator();

    /**
     * Resolves the operator's argument ({@link SourceLocator}).
     *
     * @param argument      a folder or file; {@code null} or blank for {@code defaultFolder}
     * @param defaultFolder CoreProtect's data folder ({@code plugins/CoreProtect})
     * @param baseFolder    what a relative argument is resolved against (the server folder)
     * @return the database found, or why none was
     */
    public @NotNull SourceLocation locate(@Nullable String argument, @NotNull Path defaultFolder,
            @NotNull Path baseFolder) {
        return locator.locate(argument, defaultFolder, baseFolder);
    }

    /**
     * Opens a database read-only: one connection, through the engine's driver (DuckDB and ClickHouse
     * drivers may be downloaded on first use).
     *
     * @param settings where it is
     * @return the open source; close it when done
     * @throws SQLException when the driver cannot be had or the connection fails
     */
    public @NotNull CoreProtectSource open(@NotNull SourceSettings settings) throws SQLException {
        return CoreProtectSource.open(settings, drivers.driver(settings.type()));
    }
}
