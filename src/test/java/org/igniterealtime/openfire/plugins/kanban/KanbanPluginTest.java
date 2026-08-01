package org.igniterealtime.openfire.plugins.kanban;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jivesoftware.openfire.container.Plugin;
import org.junit.jupiter.api.Test;

class KanbanPluginTest {
    @Test
    void implementsTheOpenfireLifecycleContract() {
        assertTrue(Plugin.class.isAssignableFrom(KanbanPlugin.class));
    }
}
