package org.igniterealtime.openfire.plugins.kanban;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

class PluginArchiveIT {
    @Test
    void assembledPluginHasCanonicalNameLocalizationAndCompiledAdminPage() throws Exception {
        final Path archive = Path.of("target", "kanban.jar");
        assertTrue(Files.isRegularFile(archive));

        try (ZipFile plugin = new ZipFile(archive.toFile())) {
            assertNotNull(plugin.getEntry("plugin.xml"));
            assertNotNull(plugin.getEntry("i18n/kanban_i18n.properties"));
            assertNotNull(plugin.getEntry("web/WEB-INF/web.xml"));

            final ZipEntry library = plugin.stream()
                .filter(entry -> entry.getName().startsWith("lib/kanban-"))
                .findFirst()
                .orElseThrow();
            assertTrue(contains(plugin.getInputStream(library).readAllBytes(),
                "org/jivesoftware/openfire/plugin/kanban/kanban_002dsettings_jsp.class"));
        }
    }

    private static boolean contains(byte[] jar, String expectedEntry) throws IOException {
        try (JarInputStream input = new JarInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (expectedEntry.equals(entry.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
