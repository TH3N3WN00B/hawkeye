package gg.moonrise.hawkeye.command;

import gg.moonrise.engine.message.Message;
import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.command.query.QueryInput;
import gg.moonrise.hawkeye.config.file.RollbackSettings;
import gg.moonrise.hawkeye.config.message.GeneralMessages;
import gg.moonrise.hawkeye.config.message.RollbackMessages;
import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.service.network.DispatchOutcome;
import gg.moonrise.hawkeye.core.service.network.JobWatcher;
import gg.moonrise.hawkeye.core.service.network.NetworkJobService;
import gg.moonrise.hawkeye.core.service.query.BuildResult;
import gg.moonrise.hawkeye.core.service.query.syntax.QueryFlag;
import gg.moonrise.hawkeye.core.service.query.syntax.QueryParameters;
import gg.moonrise.hawkeye.core.service.rollback.RollbackIssuer;
import gg.moonrise.hawkeye.core.service.rollback.RollbackListener;
import gg.moonrise.hawkeye.core.service.rollback.RollbackMode;
import gg.moonrise.hawkeye.core.service.rollback.RollbackOutcome;
import gg.moonrise.hawkeye.core.service.rollback.RollbackRequest;
import gg.moonrise.hawkeye.core.service.rollback.RollbackService;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.entity.job.JobKind;
import gg.moonrise.hawkeye.entity.job.JobResult;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /hawkeye rollback}, {@code restore}, {@code apply}, {@code cancel} and {@code undo}. The work
 * runs off the server thread; replies arrive on the sender's own thread.
 *
 * <p>The optional parameter list is a nullable argument rather than {@code @Default("")}: Cloud 2.0.0
 * recurses without end parsing an empty greedy string default.
 */
@SpringComponent
@RequiredArgsConstructor
public final class RollbackCommand implements PaperCommand {

    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull QueryInput queryInput;
    private final @NotNull RollbackService rollbackService;
    private final @NotNull ServerThreads threads;
    private final @NotNull NetworkJobService networkJobs;

    @Command(Permissions.ROOT + " rollback|rb [parameters]")
    @CommandDescription("Undoes recorded changes (#preview to preview them first)")
    @Permission(Permissions.ROLLBACK)
    public void rollback(@NotNull CommandSourceStack source,
            @Argument(value = "parameters", suggestions = QueryInput.SUGGESTIONS) @Greedy @Nullable String parameters) {
        start(source.getSender(), RollbackMode.ROLLBACK, "rollback", Objects.requireNonNullElse(parameters, ""));
    }

    @Command(Permissions.ROOT + " restore|rs [parameters]")
    @CommandDescription("Redoes rolled-back changes (#preview to preview them first)")
    @Permission(Permissions.ROLLBACK)
    public void restore(@NotNull CommandSourceStack source,
            @Argument(value = "parameters", suggestions = QueryInput.SUGGESTIONS) @Greedy @Nullable String parameters) {
        start(source.getSender(), RollbackMode.RESTORE, "restore", Objects.requireNonNullElse(parameters, ""));
    }

    @Command(Permissions.ROOT + " apply")
    @CommandDescription("Applies your rollback or restore preview")
    @Permission(Permissions.ROLLBACK)
    public void apply(@NotNull CommandSourceStack source) {
        CommandSender sender = source.getSender();
        reply(sender, rollbackService.apply(RollbackIssuer.of(sender), new SenderListener(sender)));
    }

    @Command(Permissions.ROOT + " cancel")
    @CommandDescription("Cancels your rollback or restore preview")
    @Permission(Permissions.ROLLBACK)
    public void cancel(@NotNull CommandSourceStack source) {
        CommandSender sender = source.getSender();
        tell(sender, rollbackService.cancel(RollbackIssuer.of(sender)));
    }

    @Command(Permissions.ROOT + " undo")
    @CommandDescription("Reverses your last rollback or restore")
    @Permission(Permissions.ROLLBACK)
    public void undo(@NotNull CommandSourceStack source) {
        CommandSender sender = source.getSender();
        reply(sender, rollbackService.undo(RollbackIssuer.of(sender), new SenderListener(sender)));
    }

    private void start(@NotNull CommandSender sender, @NotNull RollbackMode mode, @NotNull String command,
            @NotNull String parameters) {
        RollbackSettings settings = configProvider.get().rollback();
        Optional<BuildResult.Built> built = queryInput.read(sender, parameters, settings.defaultRadius(),
                settings.maxRadius());
        if (built.isEmpty()) {
            return;
        }
        QueryParameters given = built.get().parameters();
        if (given.time() instanceof QueryParameters.TimeSpec.Any) {
            Message.of(general().getTimeRequired()).send(sender, Placeholder.unparsed("command", command));
            return;
        }
        if (!(sender instanceof Entity) && given.radius() instanceof QueryParameters.RadiusSpec.Unspecified
                && given.servers() instanceof QueryParameters.ServerSpec.Local) {
            Message.of(messages().getAreaRequired()).send(sender);
            return;
        }
        if (!rollbackService.isLocal(built.get().query().servers())) {
            dispatch(sender, mode, built.get().query(), given.has(QueryFlag.PREVIEW));
            return;
        }
        RollbackRequest request = new RollbackRequest(mode, built.get().query(), RollbackIssuer.of(sender),
                given.has(QueryFlag.PREVIEW), new SenderListener(sender));
        reply(sender, rollbackService.run(request));
    }

    /**
     * A rollback or restore on other servers: one network job per target server, each applied by its
     * own server; every result is reported to the sender as it arrives.
     */
    private void dispatch(@NotNull CommandSender sender, @NotNull RollbackMode mode, @NotNull LookupQuery query,
            boolean preview) {
        RollbackMessages messages = messages();
        if (preview) {
            Message.of(messages.getNetworkPreview()).send(sender);
            return;
        }
        JobKind kind = mode == RollbackMode.ROLLBACK ? JobKind.ROLLBACK : JobKind.RESTORE;
        String issuer = sender instanceof Player player ? player.getName() : RollbackIssuer.CONSOLE;
        JobWatcher watcher = (server, result) -> threads.runFor(sender, () -> tellJob(sender, mode, server, result));
        networkJobs.dispatch(kind, query, issuer, watcher).whenComplete((outcome, error) -> threads.runFor(sender,
                () -> {
                    DispatchOutcome reply = outcome != null ? outcome : new DispatchOutcome.Failed(
                            Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName()));
                    switch (reply) {
                        case DispatchOutcome.Dispatched dispatched -> Message.of(messages().getNetworkDispatched())
                                .send(sender, mode(mode), Placeholder.unparsed("servers",
                                        String.join(", ", dispatched.servers())));
                        case DispatchOutcome.NoServers none -> Message.of(messages().getNetworkNoServers()).send(sender);
                        case DispatchOutcome.NotReady notReady -> Message.of(general().getStorageNotReady()).send(sender);
                        case DispatchOutcome.Failed failed -> Message.of(general().getOperationFailed()).send(sender,
                                Placeholder.unparsed("reason", failed.reason()));
                    }
                }));
    }

    private void tellJob(@NotNull CommandSender sender, @NotNull RollbackMode mode, @NotNull String server,
            @NotNull JobResult result) {
        switch (result) {
            case JobResult.Applied applied -> Message.of(messages().getNetworkJobFinished()).send(sender, mode(mode),
                    Placeholder.unparsed("server", server),
                    Placeholder.unparsed("blocks", Long.toString(applied.blocks())),
                    Placeholder.unparsed("items", Long.toString(applied.items())),
                    Placeholder.unparsed("entities", Long.toString(applied.entities())));
            case JobResult.Failed failed -> Message.of(messages().getNetworkJobFailed()).send(sender, mode(mode),
                    Placeholder.unparsed("server", server), Placeholder.unparsed("reason", failed.reason()));
        }
    }

    private void reply(@NotNull CommandSender sender, @NotNull CompletableFuture<RollbackOutcome> outcome) {
        outcome.whenComplete((result, error) -> {
            RollbackOutcome reply = result != null ? result : new RollbackOutcome.Failed(
                    Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName()));
            threads.runFor(sender, () -> tell(sender, reply));
        });
    }

    private void tell(@NotNull CommandSender sender, @NotNull RollbackOutcome outcome) {
        RollbackMessages messages = messages();
        switch (outcome) {
            case RollbackOutcome.Applied applied -> Message.of(messages.getFinished()).send(sender,
                    mode(applied.mode()),
                    Placeholder.unparsed("events", Integer.toString(applied.events())),
                    Placeholder.unparsed("blocks", Integer.toString(applied.blocks())),
                    Placeholder.unparsed("items", Integer.toString(applied.items())),
                    Placeholder.unparsed("entities", Integer.toString(applied.entities())),
                    Placeholder.unparsed("seconds", String.format(Locale.ROOT, "%.1f", applied.seconds())));
            case RollbackOutcome.Previewed previewed -> Message.of(messages.getPreviewShown()).send(sender,
                    mode(previewed.mode()),
                    Placeholder.unparsed("blocks", Integer.toString(previewed.blocks())),
                    Placeholder.unparsed("minutes", Integer.toString(previewed.minutes())));
            case RollbackOutcome.PreviewCancelled cancelled -> Message.of(messages.getPreviewCancelled()).send(sender);
            case RollbackOutcome.NothingToDo nothing -> Message.of(messages.getNothingToDo()).send(sender,
                    mode(nothing.mode()));
            case RollbackOutcome.NothingToUndo nothing -> Message.of(messages.getNothingToUndo()).send(sender);
            case RollbackOutcome.NoPreview none -> Message.of(messages.getNoPreview()).send(sender);
            case RollbackOutcome.TooLarge tooLarge -> Message.of(messages.getTooLarge()).send(sender,
                    Placeholder.unparsed("events", Long.toString(tooLarge.events())),
                    Placeholder.unparsed("max", Integer.toString(tooLarge.max())));
            case RollbackOutcome.AlreadyRunning running -> Message.of(messages.getAlreadyRunning()).send(sender);
            case RollbackOutcome.NotReady notReady -> Message.of(general().getStorageNotReady()).send(sender);
            case RollbackOutcome.NetworkUnsupported network ->
                    Message.of(messages.getNetworkUnsupported()).send(sender);
            case RollbackOutcome.PreviewNeedsPlayer needsPlayer -> Message.of(general().getPlayersOnly()).send(sender);
            case RollbackOutcome.Failed failed -> Message.of(general().getOperationFailed()).send(sender,
                    Placeholder.unparsed("reason", failed.reason()));
        }
    }

    private @NotNull TagResolver.Single mode(@NotNull RollbackMode mode) {
        RollbackMessages messages = messages();
        return Placeholder.unparsed("mode", switch (mode) {
            case ROLLBACK -> messages.getModeRollback();
            case RESTORE -> messages.getModeRestore();
        });
    }

    private @NotNull RollbackMessages messages() {
        return configProvider.messages().getRollback();
    }

    private @NotNull GeneralMessages general() {
        return configProvider.messages().getGeneral();
    }

    /** Tells the sender how their operation goes, on their own thread. */
    private final class SenderListener implements RollbackListener {

        private final @NotNull CommandSender sender;

        private SenderListener(@NotNull CommandSender sender) {
            this.sender = sender;
        }

        @Override
        public void started(@NotNull RollbackMode mode, long events) {
            String template = messages().getStarted();
            TagResolver.Single modeName = mode(mode);
            threads.runFor(sender, () -> Message.of(template).send(sender, modeName,
                    Placeholder.unparsed("events", Long.toString(events))));
        }

        @Override
        public void progress(@NotNull RollbackMode mode, int done, int total) {
            String template = messages().getProgress();
            TagResolver.Single modeName = mode(mode);
            int percent = total == 0 ? 100 : (int) (100L * done / total);
            threads.runFor(sender, () -> Message.of(template).send(sender, modeName,
                    Placeholder.unparsed("done", Integer.toString(done)),
                    Placeholder.unparsed("total", Integer.toString(total)),
                    Placeholder.unparsed("percent", Integer.toString(percent))));
        }

        @Override
        public void previewExpired(@NotNull RollbackMode mode) {
            String template = messages().getPreviewExpired();
            TagResolver.Single modeName = mode(mode);
            threads.runFor(sender, () -> Message.of(template).send(sender, modeName));
        }
    }
}
