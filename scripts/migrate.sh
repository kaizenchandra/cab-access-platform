#!/usr/bin/env sh
set -eu
: "${DATABASE_URL:?Required}"
: "${MIGRATION_USER:?Required}"
: "${MIGRATION_PASSWORD:?Required}"
exec java -Dloader.main=com.cabaccess.MigrationMain -cp "${APP_JAR:-target/cab-access-platform-0.1.0-SNAPSHOT.jar}" org.springframework.boot.loader.launch.PropertiesLauncher
