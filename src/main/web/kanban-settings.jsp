<%@ page contentType="text/html; charset=UTF-8" %>
<%@ page errorPage="/error.jsp" %>
<%@ page import="org.igniterealtime.openfire.plugins.kanban.KanbanPlugin" %>
<%@ page import="org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository.Diagnostics" %>
<%@ page import="org.jivesoftware.openfire.XMPPServer" %>
<%@ page import="org.jivesoftware.util.ParamUtils" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib uri="admin" prefix="admin" %>
<jsp:useBean id="webManager" class="org.jivesoftware.util.WebManager" />
<% webManager.init(request, response, session, application, out); %>
<%
    final KanbanPlugin plugin = (KanbanPlugin) XMPPServer.getInstance().getPluginManager()
        .getPluginByName("Kanban").orElseThrow();
    boolean saved = false;
    if ("POST".equals(request.getMethod()) && request.getParameter("save") != null) {
        KanbanPlugin.ADMINS_ONLY_BOARD_CREATION.setValue(
            ParamUtils.getBooleanParameter(request, "adminsOnlyBoardCreation"));
        KanbanPlugin.DEFAULT_WIP_LIMIT.setValue(
            Math.max(0, ParamUtils.getIntParameter(request, "defaultWipLimit", 0)));
        KanbanPlugin.ACTIVITY_RETENTION_DAYS.setValue(
            Math.max(1, ParamUtils.getIntParameter(request, "activityRetentionDays", 90)));
        webManager.logEvent("Changed Kanban settings", "adminsOnlyBoardCreation="
            + KanbanPlugin.ADMINS_ONLY_BOARD_CREATION.getValue() + ", defaultWipLimit="
            + KanbanPlugin.DEFAULT_WIP_LIMIT.getValue() + ", activityRetentionDays="
            + KanbanPlugin.ACTIVITY_RETENTION_DAYS.getValue());
        saved = true;
    } else if ("POST".equals(request.getMethod()) && request.getParameter("retry") != null) {
        plugin.retryOutbox();
        webManager.logEvent("Retried Kanban outbox", null);
    }
    final Diagnostics diagnostics = plugin.diagnostics();
    request.setAttribute("saved", saved);
    request.setAttribute("adminsOnly", KanbanPlugin.ADMINS_ONLY_BOARD_CREATION.getValue());
    request.setAttribute("defaultWipLimit", KanbanPlugin.DEFAULT_WIP_LIMIT.getValue());
    request.setAttribute("activityRetentionDays", KanbanPlugin.ACTIVITY_RETENTION_DAYS.getValue());
    request.setAttribute("diagnostics", diagnostics);
    request.setAttribute("xmppDomain", XMPPServer.getInstance().getServerInfo().getXMPPDomain());
%>
<html>
<head>
    <title><fmt:message key="kanban.settings.title" /></title>
    <meta name="pageID" content="kanban-settings" />
</head>
<body>
<admin:FlashMessage />
<c:if test="${saved}"><admin:infobox type="success"><fmt:message key="kanban.settings.saved" /></admin:infobox></c:if>
<p><fmt:message key="kanban.settings.description"><fmt:param value="${xmppDomain}" /></fmt:message></p>

<form action="kanban-settings.jsp" method="post">
    <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>" />
    <admin:contentBox title="Board creation and defaults">
        <table>
            <tr><td><label for="adminsOnlyBoardCreation">Administrators only</label></td><td><input id="adminsOnlyBoardCreation" name="adminsOnlyBoardCreation" type="checkbox" <c:if test="${adminsOnly}">checked</c:if> /></td></tr>
            <tr><td><label for="defaultWipLimit">Default WIP limit</label></td><td><input id="defaultWipLimit" name="defaultWipLimit" type="number" min="0" value="${defaultWipLimit}" /> (0 is unlimited)</td></tr>
            <tr><td><label for="activityRetentionDays">Activity retention (days)</label></td><td><input id="activityRetentionDays" name="activityRetentionDays" type="number" min="1" value="${activityRetentionDays}" /></td></tr>
        </table>
        <input type="submit" name="save" value="Save settings" />
    </admin:contentBox>
</form>

<admin:contentBox title="Diagnostics">
    <table>
        <tr><td>Boards</td><td>${diagnostics.boards}</td></tr>
        <tr><td>Active cards</td><td>${diagnostics.cards}</td></tr>
        <tr><td>Pending publications</td><td>${diagnostics.pending}</td></tr>
        <tr><td>Publishing</td><td>${diagnostics.publishing}</td></tr>
        <tr><td>Delayed retries</td><td>${diagnostics.retrying}</td></tr>
    </table>
    <form action="kanban-settings.jsp" method="post">
        <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>" />
        <input type="submit" name="retry" value="Retry pending publications" />
    </form>
</admin:contentBox>
</body>
</html>
