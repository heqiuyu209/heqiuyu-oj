#!/bin/bash
# Generate strong random secrets for HOJ deployment
# Usage: bash generate-secrets.sh [--apply]

set -e

ENV_FILE="$(dirname "$0")/.env"

generate() {
  if command -v openssl &>/dev/null; then
    openssl rand -base64 48
  else
    cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w 64 | head -n 1
  fi
}

JWT_SECRET=$(generate)
JUDGE_TOKEN=$(generate)
BEHAVIOR_KEY=$(generate)
REDIS_PW=$(generate | cut -c1-24)
MYSQL_PW=$(generate | cut -c1-24)

echo "=== Generated Secrets ==="
echo "JWT_TOKEN_SECRET: $JWT_SECRET"
echo "JUDGE_TOKEN: $JUDGE_TOKEN"
echo "BEHAVIOR_API_KEY: $BEHAVIOR_KEY"
echo "REDIS_PASSWORD: $REDIS_PW"
echo "MYSQL_ROOT_PASSWORD: $MYSQL_PW"
echo ""

if [ "$1" = "--apply" ]; then
  if [ ! -f "$ENV_FILE" ]; then
    echo "Error: $ENV_FILE not found"
    exit 1
  fi
  sed -i "s/^JWT_TOKEN_SECRET=.*/JWT_TOKEN_SECRET=$JWT_SECRET/" "$ENV_FILE"
  sed -i "s/^JUDGE_TOKEN=.*/JUDGE_TOKEN=$JUDGE_TOKEN/" "$ENV_FILE"
  sed -i "s/^BEHAVIOR_API_KEY=.*/BEHAVIOR_API_KEY=$BEHAVIOR_KEY/" "$ENV_FILE"
  sed -i "s/^REDIS_PASSWORD=.*/REDIS_PASSWORD=$REDIS_PW/" "$ENV_FILE"
  sed -i "s/^MYSQL_ROOT_PASSWORD=.*/MYSQL_ROOT_PASSWORD=$MYSQL_PW/" "$ENV_FILE"
  echo "Secrets applied to $ENV_FILE"
else
  echo "Run with --apply to update $ENV_FILE"
fi
