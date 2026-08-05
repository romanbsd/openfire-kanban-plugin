package org.igniterealtime.openfire.plugins.kanban;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.TimerTask;

import org.igniterealtime.openfire.plugins.kanban.muc.BoardDiscussionProvisioner;
import org.igniterealtime.openfire.plugins.kanban.muc.OpenfireBoardDiscussionProvisioner;
import org.igniterealtime.openfire.plugins.kanban.outbox.OutboxWorker;
import org.igniterealtime.openfire.plugins.kanban.pubsub.OpenfirePubSubPublisher;
import org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository;
import org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository.Diagnostics;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanService;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.admin.AdminManager;
import org.jivesoftware.openfire.component.InternalComponentManager;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.util.SystemProperty;
import org.jivesoftware.util.TaskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.component.ComponentException;
import org.xmpp.packet.JID;

/** Openfire lifecycle and dependency composition root for the Kanban plugin. */
public final class KanbanPlugin implements Plugin {
    private static final Logger Log = LoggerFactory.getLogger(KanbanPlugin.class);
    private static final String PLUGIN_NAME = "Kanban";

    public static final SystemProperty<Integer> DEFAULT_WIP_LIMIT = SystemProperty.Builder.ofType(Integer.class)
        .setKey("plugin.kanban.defaultWipLimit").setDefaultValue(0).setDynamic(true).setPlugin(PLUGIN_NAME).build();
    public static final SystemProperty<Integer> ACTIVITY_RETENTION_DAYS = SystemProperty.Builder.ofType(Integer.class)
        .setKey("plugin.kanban.activityRetentionDays").setDefaultValue(90).setDynamic(true).setPlugin(PLUGIN_NAME).build();
    public static final SystemProperty<Boolean> ADMINS_ONLY_BOARD_CREATION = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("plugin.kanban.adminsOnlyBoardCreation").setDefaultValue(false).setDynamic(true).setPlugin(PLUGIN_NAME).build();

    private JdbcKanbanRepository repository;
    private KanbanComponent component;
    private OutboxWorker outboxWorker;
    private TimerTask outboxTask;
    private TimerTask retentionTask;

    @Override
    public synchronized void initializePlugin(PluginManager manager, File pluginDirectory) {
        repository = new JdbcKanbanRepository(DbConnectionManager::getConnection);
        final String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        final String componentBare = "kanban." + domain;
        final BoardDiscussionProvisioner discussionProvisioner =
            new OpenfireBoardDiscussionProvisioner(componentBare);
        final KanbanService service = new KanbanService(repository, java.time.Clock.systemUTC(),
            () -> java.util.UUID.randomUUID().toString(), DEFAULT_WIP_LIMIT::getValue,
            actor -> !ADMINS_ONLY_BOARD_CREATION.getValue()
                || AdminManager.getInstance().isUserAdmin(new JID(actor), false),
            discussionProvisioner);
        final OpenfirePubSubPublisher publisher = new OpenfirePubSubPublisher(new JID("kanban." + domain));
        component = new KanbanComponent(service, "pubsub." + domain, publisher);
        try {
            InternalComponentManager.getInstance().addComponent("kanban", component);
        } catch (ComponentException exception) {
            throw new IllegalStateException("Unable to register kanban." + domain, exception);
        }
        outboxWorker = new OutboxWorker(repository, publisher);
        outboxTask = safeTask("outbox", outboxWorker);
        TaskEngine.getInstance().scheduleAtFixedRate(outboxTask, Instant.now(), Duration.ofSeconds(1));
        retentionTask = safeTask("retention", this::purgeHistory);
        TaskEngine.getInstance().scheduleAtFixedRate(
            retentionTask, Instant.now().plus(Duration.ofMinutes(1)), Duration.ofDays(1));
        Log.info("Kanban plugin initialized from {}", pluginDirectory.getAbsolutePath());
    }

    @Override
    public synchronized void destroyPlugin() {
        if (retentionTask != null) {
            TaskEngine.getInstance().cancelScheduledTask(retentionTask);
            retentionTask = null;
        }
        if (outboxTask != null) {
            TaskEngine.getInstance().cancelScheduledTask(outboxTask);
            outboxTask = null;
        }
        if (component != null) {
            InternalComponentManager.getInstance().removeComponent("kanban", component);
            component = null;
        }
        outboxWorker = null;
        repository = null;
        Log.info("Kanban plugin destroyed");
    }

    public synchronized Diagnostics diagnostics() {
        return repository == null ? new Diagnostics(0, 0, 0, 0, 0) : repository.diagnostics(System.currentTimeMillis());
    }

    public synchronized void retryOutbox() {
        if (outboxWorker != null) {
            outboxWorker.run();
        }
    }

    private void purgeHistory() {
        final int retentionDays = Math.max(1, ACTIVITY_RETENTION_DAYS.getValue());
        final long cutoff = Instant.now().minus(Duration.ofDays(retentionDays)).toEpochMilli();
        final var purged = repository.purgeHistory(cutoff);
        if (purged.activityRows() > 0 || purged.outboxRows() > 0) {
            Log.info("Purged {} Kanban activity and {} delivered outbox rows",
                purged.activityRows(), purged.outboxRows());
        }
    }

    private static TimerTask safeTask(String name, Runnable operation) {
        return new TimerTask() {
            @Override
            public void run() {
                try {
                    operation.run();
                } catch (RuntimeException exception) {
                    Log.error("Kanban {} cycle failed", name, exception);
                }
            }
        };
    }
}
