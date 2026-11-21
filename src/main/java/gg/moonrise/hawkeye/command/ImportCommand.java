package gg.moonrise.hawkeye.command;

import gg.moonrise.engine.message.Message;
import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.message.GeneralMessages;
import gg.moonrise.hawkeye.config.message.ImportMessages;
import gg.moonrise.hawkeye.core.service.importer.ImportListener;
import gg.moonrise.hawkeye.core.service.importer.ImportOutcome;
import gg.moonrise.hawkeye.core.service.importer.ImportProgress;
import gg.moonrise.hawkeye.core.service.importer.ImportService;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.util.Durations;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /hawkeye import coreprotect [source]}, {@code import status} and {@code import cancel}: copies a
 * CoreProtect database into Hawkeye, off the server thread; replies arrive on the sender's own thread.
 *
 * <p>The optional source is a nullable argument rather than {@code @Default("")}: Cloud 2.0.0 recurses
 * without end parsing an empty greedy string default.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ImportCommand implements PaperCommand {

    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull ImportService importService;
    private final @NotNull ServerThreads threads;

    @Command(Permissions.ROOT + " import coreprotect [source]")
    @CommandDescription("Imports a CoreProtect database (default: plugins/CoreProtect)")
    @Permission(Permissions.IMPORT)
    public void importCoreProtect(@NotNull CommandSourceStack source,
            @Argument("source") @Greedy @Nullable String path) {
        CommandSender sender = source.getSender();
        importService.start(path, new SenderListener(sender)).whenComplete((outcome, error) -> {
            ImportOutcome reply = outcome != null ? outcome : new ImportOutcome.Failed(
                    Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName()));
            threads.runFor(sender, () -> tell(sender, reply));
        });
    }

    @Command(Permissions.ROOT + " import status")
    @CommandDescription("Shows how the running import goes")
    @Permission(Permissions.IMPORT)
    public void status(@NotNull CommandSourceStack source) {
        CommandSender sender = source.getSender();
        Optional<ImportProgress> progress = importService.progress();
        String template = progress.isPresent() ? messages().getStatus() : messages().getNotRunning();
        TagResolver.Single[] placeholders = progress.map(ImportCommand::placeholders).orElse(new TagResolver.Single[0]);
        threads.runFor(sender, () -> Message.of(template).send(sender, placeholders));
    }

    @Command(Permissions.ROOT + " import cancel")
    @CommandDescription("Stops the running import after its current batch")
    @Permission(Permissions.IMPORT)
    public void cancel(@NotNull CommandSourceStack source) {
        CommandSender sender = source.getSender();
        String template = importService.cancel() ? messages().getCancelling() : messages().getNotRunning();
        threads.runFor(sender, () -> Message.of(template).send(sender));
    }

    private void tell(@NotNull CommandSender sender, @NotNull ImportOutcome outcome) {
        ImportMessages messages = messages();
        switch (outcome) {
            case ImportOutcome.Finished finished -> {
                Message.of(messages.getFinished()).send(sender, placeholders(finished.totals()));
                details(sender, finished.totals());
            }
            case ImportOutcome.Cancelled cancelled -> {
                Message.of(messages.getCancelled()).send(sender, placeholders(cancelled.totals()));
                details(sender, cancelled.totals());
            }
            case ImportOutcome.AlreadyRunning running -> Message.of(messages.getAlreadyRunning()).send(sender);
            case ImportOutcome.NotReady notReady -> Message.of(general().getStorageNotReady()).send(sender);
            case ImportOutcome.SourceMissing missing -> Message.of(messages.getSourceMissing()).send(sender,
                    Placeholder.unparsed("path", missing.path()));
            case ImportOutcome.SourceInvalid invalid -> Message.of(messages.getSourceInvalid()).send(sender,
                    Placeholder.unparsed("reason", invalid.reason()));
            case ImportOutcome.CoreProtectRunning running -> Message.of(messages.getCoreProtectRunning()).send(sender);
            case ImportOutcome.SourceMigrating migrating -> Message.of(messages.getSourceMigrating()).send(sender);
            case ImportOutcome.OrderChanged changed -> Message.of(messages.getOrderChanged()).send(sender);
            case ImportOutcome.Superseded superseded -> Message.of(messages.getSuperseded()).send(sender);
            case ImportOutcome.Failed failed -> Message.of(general().getOperationFailed()).send(sender,
                    Placeholder.unparsed("reason", failed.reason()));
        }
    }

    /** The skipped and displaced lines, each only when it has something to say. */
    private void details(@NotNull CommandSender sender, @NotNull ImportProgress totals) {
        ImportMessages messages = messages();
        if (totals.skippedTotal() > 0) {
            Message.of(messages.getSkipped()).send(sender,
                    count("skipped", totals.skippedTotal()),
                    count("old", totals.skipped(SkipReason.BEFORE_EPOCH)),
                    count("no-equivalent", totals.skipped(SkipReason.NO_EQUIVALENT)),
                    count("folded", totals.skipped(SkipReason.FOLDED)),
                    count("unknown", totals.skipped(SkipReason.UNKNOWN_REFERENCE)));
        }
        if (totals.displaced() > 0) {
            Message.of(messages.getDisplaced()).send(sender, count("displaced", totals.displaced()));
        }
    }

    private static @NotNull TagResolver.Single @NotNull [] placeholders(@NotNull ImportProgress progress) {
        return new TagResolver.Single[] {
            Placeholder.unparsed("source", progress.source()),
            count("events", progress.imported()),
            count("imported", progress.importedTotal() - progress.imported()),
            count("total", progress.importedTotal()),
            Placeholder.unparsed("percent", Integer.toString(progress.percent())),
            Placeholder.unparsed("rate", String.format(Locale.ROOT, "%.0f", progress.eventsPerSecond())),
            Placeholder.unparsed("duration", Durations.format(Duration.ofMillis(progress.elapsedMillis())))
        };
    }

    private static @NotNull TagResolver.Single count(@NotNull String name, long value) {
        return Placeholder.unparsed(name, Long.toString(value));
    }

    private @NotNull ImportMessages messages() {
        return configProvider.messages().getImporting();
    }

    private @NotNull GeneralMessages general() {
        return configProvider.messages().getGeneral();
    }

    /** Tells the sender how their import goes, on their own thread. */
    private final class SenderListener implements ImportListener {

        private final @NotNull CommandSender sender;

        private SenderListener(@NotNull CommandSender sender) {
            this.sender = sender;
        }

        @Override
        public void started(@NotNull ImportProgress progress) {
            String template = progress.resumed() ? messages().getResumed() : messages().getStarted();
            TagResolver.Single[] placeholders = placeholders(progress);
            threads.runFor(sender, () -> Message.of(template).send(sender, placeholders));
        }

        @Override
        public void progress(@NotNull ImportProgress progress) {
            String template = messages().getProgress();
            TagResolver.Single[] placeholders = placeholders(progress);
            threads.runFor(sender, () -> Message.of(template).send(sender, placeholders));
        }
    }
}
