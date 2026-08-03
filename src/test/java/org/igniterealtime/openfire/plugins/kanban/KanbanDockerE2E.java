package org.igniterealtime.openfire.plugins.kanban;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.packet.UnparsedIQ;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.disco.packet.DiscoverInfo;
import org.jivesoftware.smackx.disco.packet.DiscoverItems;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Black-box coverage of the installed plugin. This class deliberately does not
 * end in Test or IT: it runs only through integration/run-e2e.sh.
 */
final class KanbanDockerE2E {
    private static final String DOMAIN = "example.org";
    private static final String COMPONENT = "kanban." + DOMAIN;
    private static final String PUBSUB = "pubsub." + DOMAIN;
    private static final String COMMANDS = "urn:xmpp:kanban:commands:0";
    private static final String KANBAN = "urn:xmpp:kanban:0";
    private static final String PUBSUB_NS = "http://jabber.org/protocol/pubsub";

    @Test
    void exercisesInstalledPluginAcrossXmppPubSubAdminAndRestart() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("KANBAN_E2E")),
            "Run with integration/run-e2e.sh");

        SmackConfiguration.setDefaultReplyTimeout(10_000);
        final int xmppPort = Integer.parseInt(System.getenv().getOrDefault("KANBAN_E2E_XMPP_PORT", "25222"));
        final int adminPort = Integer.parseInt(System.getenv().getOrDefault("KANBAN_E2E_ADMIN_PORT", "29090"));
        final String container = System.getenv().getOrDefault("KANBAN_E2E_CONTAINER", "openfire-kanban-e2e");

        verifyAdminConsole(adminPort);

        String boardId;
        try (Client owner = connect("john", xmppPort);
             Client editor = connect("jane", xmppPort);
             Client viewer = connect("bob", xmppPort);
             Client outsider = connect("mallory", xmppPort)) {
            verifyDiscovery(owner);
            assertEquals(0, elements(owner.command(IQ.Type.get, "list-boards", Map.of(), ""), "board").getLength());

            final Element created = owner.command(IQ.Type.set, "create-board",
                Map.of("expected-revision", "0"), "<name>Docker E2E</name>");
            boardId = required(created, "id");
            String boardRevision = required(created, "board-revision");

            final Element snapshot = owner.command(IQ.Type.get, "get-board", Map.of("board-id", boardId), "");
            assertEquals("Docker E2E", first(snapshot, "board").getElementsByTagName("name").item(0).getTextContent());
            final String cardsNode = required(snapshot, "cards-node");
            final String activityNode = required(snapshot, "activity-node");
            assertEquals(PUBSUB, required(snapshot, "pubsub-service"));

            owner.subscribe(cardsNode);
            owner.subscribe(activityNode);
            assertXmppError("not-allowed", () -> outsider.subscribe(cardsNode));
            assertXmppError("forbidden", () -> owner.publish(cardsNode));

            Element result = owner.command(IQ.Type.set, "create-column",
                Map.of("board-id", boardId, "expected-revision", boardRevision),
                "<name>Todo</name><wip-limit>1</wip-limit>");
            final String todoId = required(result, "id");
            boardRevision = required(result, "board-revision");
            owner.awaitEvent(activityNode, "ColumnCreated");

            result = owner.command(IQ.Type.set, "create-column",
                Map.of("board-id", boardId, "expected-revision", boardRevision), "<name>Done</name>");
            final String doneId = required(result, "id");
            boardRevision = required(result, "board-revision");

            result = owner.command(IQ.Type.set, "create-label",
                Map.of("board-id", boardId, "expected-revision", boardRevision),
                "<name>bug</name><color>rose</color>");
            final String bugLabel = required(result, "id");
            boardRevision = required(result, "board-revision");
            owner.awaitEvent(activityNode, "LabelCreated");

            result = owner.command(IQ.Type.set, "create-label",
                Map.of("board-id", boardId, "expected-revision", boardRevision),
                "<name>docs</name><color>mint</color>");
            final String docsLabel = required(result, "id");
            boardRevision = required(result, "board-revision");

            result = owner.command(IQ.Type.set, "create-card",
                Map.of("board-id", boardId, "column-id", todoId, "expected-revision", boardRevision),
                "<title>First card</title><description>Initial</description><assignee jid='john@example.org/phone'/>"
                    + "<priority>urgent</priority><labels><label id='" + bugLabel + "'/><label id='"
                    + docsLabel + "'/></labels>");
            final String firstCard = required(result, "id");
            boardRevision = required(result, "board-revision");
            String cardRevision = required(result, "revision");
            owner.awaitEvent(cardsNode, firstCard);
            owner.awaitEvent(activityNode, "CardCreated");

            final String revisionForWipFailure = boardRevision;
            assertXmppError("wip-limit-exceeded", () -> owner.command(IQ.Type.set, "create-card",
                Map.of("board-id", boardId, "column-id", todoId, "expected-revision", revisionForWipFailure),
                "<title>Over the limit</title>"));

            final String staleCardRevision = cardRevision;
            result = owner.command(IQ.Type.set, "update-card",
                Map.of("card-id", firstCard, "expected-revision", cardRevision),
                "<title>Updated card</title><description>Details</description><assignee jid='jane@example.org/tablet'/>");
            cardRevision = required(result, "revision");
            assertXmppError("revision-conflict", () -> owner.command(IQ.Type.set, "update-card",
                Map.of("card-id", firstCard, "expected-revision", staleCardRevision), "<title>Stale</title>"));

            result = owner.command(IQ.Type.set, "move-card",
                Map.of("card-id", firstCard, "column-id", doneId, "expected-revision", cardRevision), "");
            cardRevision = required(result, "revision");
            owner.awaitEvent(activityNode, "CardMoved");

            Element authoritative = owner.command(IQ.Type.get, "get-board", Map.of("board-id", boardId), "");
            final Element card = findById(authoritative, "card", firstCard);
            assertEquals("Updated card", childText(card, "title"));
            assertEquals("Details", childText(card, "description"));
            assertEquals(doneId, required(first(card, "column"), "id"));
            assertEquals("jane@example.org", required(first(card, "assignee"), "jid"));
            assertEquals("urgent", childText(card, "priority"));
            assertEquals(bugLabel, required(first(first(card, "labels"), "label"), "id"));
            assertFalse(childText(card, "rank").isBlank());

            result = owner.command(IQ.Type.set, "add-member",
                Map.of("board-id", boardId, "jid", "jane@example.org/phone", "role", "editor",
                    "expected-revision", boardRevision), "");
            boardRevision = required(result, "board-revision");
            editor.subscribeEventually(cardsNode);
            editor.subscribeEventually(activityNode);
            assertNotNull(findById(editor.command(IQ.Type.get, "list-boards", Map.of(), ""), "board", boardId));

            result = owner.command(IQ.Type.set, "update-card",
                Map.of("card-id", firstCard, "expected-revision", cardRevision),
                "<priority>normal</priority><labels><label id='" + bugLabel + "'/><label id='"
                    + docsLabel + "'/></labels>");
            required(result, "revision");
            editor.awaitEvent(cardsNode, firstCard);
            Element editorSnapshot = editor.command(IQ.Type.get, "get-board", Map.of("board-id", boardId), "");
            Element editorCardView = findById(editorSnapshot, "card", firstCard);
            assertEquals("normal", childText(editorCardView, "priority"));
            assertEquals(2, elements(first(editorCardView, "labels"), "label").getLength());

            result = owner.command(IQ.Type.set, "delete-label",
                Map.of("board-id", boardId, "label-id", bugLabel, "expected-revision", boardRevision), "");
            boardRevision = required(result, "board-revision");
            editor.awaitEvent(cardsNode, firstCard);
            editorSnapshot = editor.command(IQ.Type.get, "get-board", Map.of("board-id", boardId), "");
            editorCardView = findById(editorSnapshot, "card", firstCard);
            assertEquals(docsLabel, required(first(first(editorCardView, "labels"), "label"), "id"));
            assertEquals(1, elements(first(editorCardView, "labels"), "label").getLength());

            result = editor.command(IQ.Type.set, "create-card",
                Map.of("board-id", boardId, "column-id", todoId, "expected-revision", boardRevision),
                "<title>Editor's card</title>");
            final String editorCard = required(result, "id");
            boardRevision = required(result, "board-revision");
            final String revisionForForbiddenMemberChange = boardRevision;
            assertXmppError("forbidden", () -> editor.command(IQ.Type.set, "add-member",
                Map.of("board-id", boardId, "jid", "bob@example.org", "role", "viewer",
                    "expected-revision", revisionForForbiddenMemberChange), ""));

            result = owner.command(IQ.Type.set, "add-member",
                Map.of("board-id", boardId, "jid", "bob@example.org", "role", "viewer",
                    "expected-revision", boardRevision), "");
            boardRevision = required(result, "board-revision");
            assertNotNull(viewer.command(IQ.Type.get, "get-board", Map.of("board-id", boardId), ""));
            assertXmppError("forbidden", () -> viewer.command(IQ.Type.set, "create-card",
                Map.of("board-id", boardId, "column-id", doneId, "expected-revision", "0"),
                "<title>Not allowed</title>"));
            assertXmppError("forbidden", () -> outsider.command(IQ.Type.get, "get-board",
                Map.of("board-id", boardId), ""));

            final String revisionBeforeLastOwnerCheck = boardRevision;
            assertXmppError("final-owner", () -> owner.command(IQ.Type.set, "update-member-role",
                Map.of("board-id", boardId, "jid", "john@example.org", "role", "viewer",
                    "expected-revision", revisionBeforeLastOwnerCheck), ""));

            result = owner.command(IQ.Type.set, "update-member-role",
                Map.of("board-id", boardId, "jid", "jane@example.org", "role", "viewer",
                    "expected-revision", boardRevision), "");
            boardRevision = required(result, "board-revision");
            assertXmppError("forbidden", () -> editor.command(IQ.Type.set, "create-card",
                Map.of("board-id", boardId, "column-id", doneId, "expected-revision", "0"),
                "<title>No longer editor</title>"));

            result = owner.command(IQ.Type.set, "remove-member",
                Map.of("board-id", boardId, "jid", "jane@example.org", "expected-revision", boardRevision), "");
            required(result, "board-revision");
            assertXmppError("forbidden", () -> editor.command(IQ.Type.get, "get-board", Map.of("board-id", boardId), ""));
            awaitXmppError("not-allowed", () -> editor.subscribe(cardsNode));

            owner.command(IQ.Type.set, "delete-card",
                Map.of("card-id", editorCard, "expected-revision", "1"), "");
            owner.awaitEvent(cardsNode, "card-tombstone");
            owner.awaitEvent(activityNode, "CardDeleted");

            authoritative = owner.command(IQ.Type.get, "get-board", Map.of("board-id", boardId), "");
            assertNotNull(findById(authoritative, "card", firstCard));
            assertEquals(1, elements(authoritative, "card").getLength());
        }

        restart(container, adminPort);

        try (Client owner = connectWithRetry("john", xmppPort, Duration.ofSeconds(60))) {
            verifyDiscovery(owner);
            assertNotNull(findById(owner.command(IQ.Type.get, "list-boards", Map.of(), ""), "board", boardId));
            final Element restored = owner.command(IQ.Type.get, "get-board", Map.of("board-id", boardId), "");
            assertEquals("Docker E2E", childText(first(restored, "board"), "name"));
            owner.subscribe(required(restored, "cards-node"));
        }
    }

    private static void verifyDiscovery(Client client) throws Exception {
        final ServiceDiscoveryManager discovery = ServiceDiscoveryManager.getInstanceFor(client.connection);
        final DiscoverItems items = discovery.discoverItems(JidCreate.domainBareFrom(DOMAIN));
        assertTrue(items.getItems().stream().anyMatch(item -> COMPONENT.equals(item.getEntityID().toString())),
            "domain disco#items must advertise the Kanban component");
        final DiscoverInfo info = discovery.discoverInfo(JidCreate.domainBareFrom(COMPONENT));
        assertTrue(info.containsFeature(KANBAN));
        assertTrue(info.containsFeature(COMMANDS));
        assertTrue(info.getIdentities().stream().anyMatch(identity ->
            "collaboration".equals(identity.getCategory()) && "kanban".equals(identity.getType())));
    }

    private static void verifyAdminConsole(int port) throws Exception {
        final CookieManager cookies = new CookieManager();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
        final URI login = URI.create("http://127.0.0.1:" + port + "/login.jsp");
        final HttpResponse<String> loginPage = http.send(HttpRequest.newBuilder(login).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginPage.statusCode());
        final String csrf = cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> "csrf".equals(cookie.getName())).map(HttpCookie::getValue).findFirst().orElseThrow();
        final String form = "login=true&username=admin&password=admin&csrf="
            + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        final HttpResponse<String> authenticated = http.send(HttpRequest.newBuilder(login)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, authenticated.statusCode());
        final URI settingsUri = URI.create(
            "http://127.0.0.1:" + port + "/plugins/kanban/kanban-settings.jsp");
        final HttpResponse<String> settings = http.send(HttpRequest.newBuilder(settingsUri).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, settings.statusCode());
        assertTrue(settings.body().contains("Kanban Settings"));
        assertTrue(settings.body().contains("Diagnostics"));
        assertFalse(settings.body().contains("kanban.sidebar.settings"));
        assertTrue(settings.body().contains("jive-sidebar-container"),
            "decorated page must retain the Admin Console sidebar");

        final String settingsCsrf = htmlInputValue(settings.body(), "csrf");
        final String saveForm = "save=true&defaultWipLimit=4&activityRetentionDays=7&csrf="
            + URLEncoder.encode(settingsCsrf, StandardCharsets.UTF_8);
        final HttpResponse<String> saved = http.send(HttpRequest.newBuilder(settingsUri)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(saveForm)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, saved.statusCode());
        assertTrue(saved.body().contains("value=\"4\""));
        assertTrue(saved.body().contains("value=\"7\""));

        final String retryForm = "retry=true&csrf="
            + URLEncoder.encode(htmlInputValue(saved.body(), "csrf"), StandardCharsets.UTF_8);
        final HttpResponse<String> retried = http.send(HttpRequest.newBuilder(settingsUri)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(retryForm)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, retried.statusCode());
        assertTrue(retried.body().contains("Diagnostics"));
    }

    private static void restart(String container, int adminPort) throws Exception {
        final Process process = new ProcessBuilder("docker", "restart", container).inheritIO().start();
        assertEquals(0, process.waitFor(), "docker restart failed");
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final URI login = URI.create("http://127.0.0.1:" + adminPort + "/login.jsp");
        final long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (http.send(HttpRequest.newBuilder(login).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // Container is between shutdown and startup.
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError("Openfire did not recover after restart");
    }

    private static String htmlInputValue(String html, String name) {
        final String marker = "name=\"" + name + "\" value=\"";
        final int start = html.indexOf(marker);
        assertTrue(start >= 0, "Missing input named " + name);
        final int valueStart = start + marker.length();
        final int end = html.indexOf('"', valueStart);
        assertTrue(end >= valueStart, "Unterminated input named " + name);
        return html.substring(valueStart, end);
    }

    private static Client connect(String user, int port) throws Exception {
        final XMPPTCPConnectionConfiguration configuration = XMPPTCPConnectionConfiguration.builder()
            .setXmppDomain(DOMAIN).setHostAddress(InetAddress.getLoopbackAddress()).setPort(port)
            .setUsernameAndPassword(user, "secret").setResource("kanban-e2e")
            .setSecurityMode(ConnectionConfiguration.SecurityMode.disabled).setCompressionEnabled(false).build();
        final XMPPTCPConnection connection = new XMPPTCPConnection(configuration);
        connection.connect().login();
        return new Client(connection);
    }

    private static Client connectWithRetry(String user, int port, Duration timeout) throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return connect(user, port);
            } catch (Exception error) {
                last = error;
                Thread.sleep(1_000);
            }
        }
        throw new AssertionError("XMPP did not recover after restart", last);
    }

    private static void assertXmppError(String marker, ThrowingCall call) {
        final XMPPErrorException error = assertThrows(XMPPErrorException.class, call::run);
        assertTrue(error.getStanzaError().toXML().toString().contains(marker), error.getMessage());
    }

    private static void awaitXmppError(String marker, ThrowingCall call) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        AssertionError last = null;
        while (System.nanoTime() < deadline) {
            try {
                assertXmppError(marker, call);
                return;
            } catch (AssertionError error) {
                last = error;
                Thread.sleep(250);
            }
        }
        throw last == null ? new AssertionError("Expected XMPP error " + marker) : last;
    }

    private static Document parse(Stanza stanza) throws Exception {
        return parse(stanza.toXML().toString());
    }

    private static Document parse(CharSequence xml) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml.toString())));
    }

    private static NodeList elements(Element root, String name) {
        return root.getElementsByTagName(name);
    }

    private static Element first(Element root, String name) {
        final NodeList matches = elements(root, name);
        assertTrue(matches.getLength() > 0, "Missing <" + name + "> in " + root.getTextContent());
        return (Element) matches.item(0);
    }

    private static Element findById(Element root, String name, String id) {
        final NodeList matches = elements(root, name);
        for (int i = 0; i < matches.getLength(); i++) {
            final Element element = (Element) matches.item(i);
            if (id.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        return null;
    }

    private static String required(Element element, String attribute) {
        final String value = element.getAttribute(attribute);
        assertFalse(value.isBlank(), "Missing " + attribute + " on <" + element.getTagName() + ">");
        return value;
    }

    private static String childText(Element root, String name) {
        return first(root, name).getTextContent();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static final class Client implements AutoCloseable {
        private final XMPPTCPConnection connection;
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        private Client(XMPPTCPConnection connection) {
            this.connection = connection;
            connection.addAsyncStanzaListener(stanza -> messages.offer(stanza.toXML().toString()),
                stanza -> "message".equals(stanza.getElementName()));
        }

        private Element command(IQ.Type type, String name, Map<String, String> attributes, String content)
                throws Exception {
            return payload(request(
                new RawIq(type, name, COMMANDS, JidCreate.domainBareFrom(COMPONENT), attributes, content)));
        }

        private void subscribe(String node) throws Exception {
            request(new RawIq(IQ.Type.set, "pubsub", PUBSUB_NS, JidCreate.domainBareFrom(PUBSUB), Map.of(),
                "<subscribe node='" + node + "' jid='" + connection.getUser().asBareJid() + "'/>"));
        }

        private void subscribeEventually(String node) throws Exception {
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            XMPPErrorException last = null;
            while (System.nanoTime() < deadline) {
                try {
                    subscribe(node);
                    return;
                } catch (XMPPErrorException error) {
                    last = error;
                    Thread.sleep(250);
                }
            }
            throw new AssertionError("PubSub access was not granted for " + node, last);
        }

        private void publish(String node) throws Exception {
            request(new RawIq(IQ.Type.set, "pubsub", PUBSUB_NS, JidCreate.domainBareFrom(PUBSUB), Map.of(),
                "<publish node='" + node + "'><item id='forbidden'><card xmlns='" + KANBAN
                    + "' id='forbidden'/></item></publish>"));
        }

        private IQ request(RawIq request) throws Exception {
            return connection.sendIqRequestAndWaitForResponse(request);
        }

        private static Element payload(IQ response) throws Exception {
            if (response instanceof UnparsedIQ unparsed) {
                return parse(unparsed.getContent()).getDocumentElement();
            }
            final Element iq = parse(response).getDocumentElement();
            for (Node child = iq.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    return (Element) child;
                }
            }
            throw new AssertionError("IQ result has no payload: " + response.toXML());
        }

        private void awaitEvent(String node, String marker) throws Exception {
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                final String message = messages.poll(250, TimeUnit.MILLISECONDS);
                if (message != null && message.contains(node) && message.contains(marker)) {
                    return;
                }
            }
            throw new AssertionError("No PubSub event for node " + node + " containing " + marker);
        }

        @Override
        public void close() {
            connection.disconnect();
        }
    }

    private static final class RawIq extends IQ {
        private final Map<String, String> attributes;
        private final String content;

        private RawIq(Type type, String name, String namespace, Jid to,
                      Map<String, String> attributes, String content) {
            super(name, namespace);
            setType(type);
            setTo(to);
            this.attributes = new LinkedHashMap<>(attributes);
            this.content = content;
        }

        @Override
        protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
            attributes.forEach(xml::attribute);
            xml.rightAngleBracket();
            xml.append(content);
            return xml;
        }
    }
}
