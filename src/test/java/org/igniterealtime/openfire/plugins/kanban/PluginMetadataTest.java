package org.igniterealtime.openfire.plugins.kanban;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class PluginMetadataTest {
    @Test
    void declaresThePluginEntryPointAdminPageAndDatabaseMigration() throws Exception {
        final Path descriptor = Path.of("plugin.xml");
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        final Document document;
        try (var input = Files.newInputStream(descriptor)) {
            document = factory.newDocumentBuilder().parse(input);
        }

        assertEquals(
            KanbanPlugin.class.getName(),
            document.getElementsByTagName("class").item(0).getTextContent()
        );
        assertEquals(
            "kanban-settings.jsp",
            document.getElementsByTagName("item").item(0).getAttributes()
                .getNamedItem("url").getNodeValue()
        );
        assertEquals("kanban", document.getElementsByTagName("databaseKey").item(0).getTextContent());
        assertEquals("1", document.getElementsByTagName("databaseVersion").item(0).getTextContent());
    }
}
