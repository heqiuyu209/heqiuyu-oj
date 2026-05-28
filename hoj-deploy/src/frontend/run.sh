#!/usr/bin/env sh
set -eu
ENV_VARS='${SERVER_NAME} ${BACKEND_SERVER_HOST} ${BACKEND_SERVER_PORT} ${BEHAVIOR_SERVER_HOST} ${BEHAVIOR_SERVER_PORT} ${PROFILE_SERVER_HOST} ${PROFILE_SERVER_PORT} ${RECOMMEND_SERVER_HOST} ${RECOMMEND_SERVER_PORT} ${AGENT_SERVER_HOST} ${AGENT_SERVER_PORT} ${VJUDGE_SERVER_HOST} ${VJUDGE_SERVER_PORT}'
if [ "$USE_HTTPS" == "true" ]; then
	envsubst "$ENV_VARS" < /etc/nginx/conf.d/default.conf.ssl.template > /etc/nginx/conf.d/default.conf
else
	envsubst "$ENV_VARS" < /etc/nginx/conf.d/default.conf.template > /etc/nginx/conf.d/default.conf
fi
exec "$@"
