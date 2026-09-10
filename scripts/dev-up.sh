#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

for command in docker curl java node ffmpeg tesseract; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "Missing required command: $command" >&2
    exit 1
  }
done

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env. Set SILICONFLOW_API_KEY and replace the example passwords, then run this script again."
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

for variable in \
  DB_PASSWORD MYSQL_ROOT_PASSWORD REDIS_PASSWORD MINIO_SECRET_KEY QDRANT_API_KEY SILICONFLOW_API_KEY; do
  value="${!variable:-}"
  if [[ -z "$value" || "$value" == change-* ]]; then
    echo "Set a non-example value for $variable in .env" >&2
    exit 1
  fi
done

if [[ ! -d mysql/data/mysql && "${DB_USERNAME:-}" != "${MYSQL_APP_USER:-dovideo}" ]]; then
  echo "DB_USERNAME and MYSQL_APP_USER must match for a fresh database." >&2
  exit 1
fi

java_version="$(java -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')"
java_major="${java_version%%.*}"
[[ "$java_major" == "1" ]] && java_major="$(cut -d. -f2 <<<"$java_version")"
node_major="$(node --version | sed 's/^v//' | cut -d. -f1)"
(( java_major >= 21 )) || { echo "JDK 21+ is required; found $java_version" >&2; exit 1; }
(( node_major >= 22 )) || { echo "Node.js 22+ is required; found $(node --version)" >&2; exit 1; }

docker info >/dev/null
docker compose --env-file .env config --quiet

# RocketMQ 镜像以 uid 3000 运行，而 Docker 首次创建的命名卷属于 root，
# broker 会因写不了 commitlog 与日志而立即退出（exit 253）。首次启动前先修正属主。
docker compose --env-file .env run --rm --no-deps --user root --entrypoint sh rmqbroker -c \
  "mkdir -p /home/rocketmq/store /home/rocketmq/logs && chown -R 3000:3000 /home/rocketmq/store /home/rocketmq/logs"

docker compose --env-file .env up --wait --wait-timeout 120

curl --fail --silent --show-error --retry 20 --retry-connrefused --retry-delay 1 \
  --header "api-key: ${QDRANT_API_KEY}" \
  http://127.0.0.1:6333/healthz >/dev/null
curl --fail --silent --show-error --retry 20 --retry-connrefused --retry-delay 1 \
  http://127.0.0.1:9000/minio/health/live >/dev/null

echo "Waiting for RocketMQ broker to register..."
for _ in $(seq 1 30); do
  docker compose --env-file .env exec -T rmqbroker sh -c \
    '$ROCKETMQ_HOME/bin/mqadmin clusterList -n rmqnamesrv:9876' 2>/dev/null | grep -q 'broker-a' && break
  sleep 2
done

# 显式创建分析主题：消费者订阅的主题不存在时拿不到路由，会一直空转，
# 不能依赖 RocketMQ 的自动建主题。updateTopic 对已存在的主题是幂等的。
for topic in "${ROCKETMQ_ANALYSIS_TOPIC:-video-analysis-topic}" \
             "${ROCKETMQ_ANALYSIS_DEAD_TOPIC:-video-analysis-dead-topic}"; do
  docker compose --env-file .env exec -T rmqbroker sh -c \
    "\$ROCKETMQ_HOME/bin/mqadmin updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t $topic" >/dev/null || {
    echo "Failed to create RocketMQ topic: $topic" >&2
    exit 1
  }
  echo "RocketMQ topic ready: $topic"
done

docker compose --env-file .env ps
echo
echo "Infrastructure is ready. Start the backend with:"
echo "  set -a; source .env; set +a; cd server && ./mvnw spring-boot:run"
