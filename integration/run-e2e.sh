#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OPENFIRE_SOURCE="${OPENFIRE_SOURCE:-${ROOT}/../Openfire}"
MAVEN="${MAVEN:-/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn}"
BASE_IMAGE="${OPENFIRE_BASE_IMAGE:-openfire-kanban-e2e-base:local}"
IMAGE="${KANBAN_E2E_IMAGE:-openfire-kanban-e2e:local}"
CONTAINER="${KANBAN_E2E_CONTAINER:-openfire-kanban-e2e}"
VOLUME="${KANBAN_E2E_VOLUME:-openfire-kanban-e2e-data}"
XMPP_PORT="${KANBAN_E2E_XMPP_PORT:-25222}"
ADMIN_PORT="${KANBAN_E2E_ADMIN_PORT:-29090}"

cleanup() {
  docker logs "${CONTAINER}" > "${ROOT}/target/docker-e2e-openfire.log" 2>&1 || true
  docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
  if [[ "${KANBAN_E2E_KEEP_VOLUME:-0}" != "1" ]]; then
    docker volume rm "${VOLUME}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
docker volume rm "${VOLUME}" >/dev/null 2>&1 || true

"${MAVEN}" -f "${ROOT}/pom.xml" clean verify

if [[ "${KANBAN_E2E_SKIP_BASE_BUILD:-0}" != "1" ]]; then
  docker build -f "${OPENFIRE_SOURCE}/Dockerfile" -t "${BASE_IMAGE}" "${OPENFIRE_SOURCE}"
fi

docker build \
  --build-arg "OPENFIRE_BASE_IMAGE=${BASE_IMAGE}" \
  -f "${ROOT}/integration/Dockerfile" \
  -t "${IMAGE}" \
  "${ROOT}"

docker volume create "${VOLUME}" >/dev/null
docker run -d \
  --name "${CONTAINER}" \
  -p "127.0.0.1:${XMPP_PORT}:5222" \
  -p "127.0.0.1:${ADMIN_PORT}:9090" \
  -v "${VOLUME}:/var/lib/openfire" \
  -v "${ROOT}/integration/openfire-demoboot.xml:/usr/local/openfire/conf_org/openfire-demoboot.xml:ro" \
  "${IMAGE}" -demoboot >/dev/null

deadline=$((SECONDS + 180))
until curl --fail --silent "http://127.0.0.1:${ADMIN_PORT}/login.jsp" >/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "Openfire did not become ready within 180 seconds" >&2
    exit 1
  fi
  sleep 2
done

KANBAN_E2E=1 \
KANBAN_E2E_XMPP_PORT="${XMPP_PORT}" \
KANBAN_E2E_ADMIN_PORT="${ADMIN_PORT}" \
KANBAN_E2E_CONTAINER="${CONTAINER}" \
  "${MAVEN}" -f "${ROOT}/pom.xml" -Dtest=KanbanDockerE2E test

echo "Kanban Docker E2E passed. Openfire log: ${ROOT}/target/docker-e2e-openfire.log"
