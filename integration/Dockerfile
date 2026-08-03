ARG OPENFIRE_BASE_IMAGE=openfire-kanban-e2e-base:local
FROM ${OPENFIRE_BASE_IMAGE}

# The Openfire entrypoint seeds /opt/plugins into a new data volume.
COPY target/kanban.jar /opt/plugins/kanban.jar
