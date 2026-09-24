#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Uses only the JDK; it is not a replacement for the full Maven and database suites.
CLASSES="$(mktemp -d)"
trap 'rm -rf "$CLASSES"' EXIT
find mail-domain/src/main/java mail-application/src/main/java -name '*.java' -print0 \
  | xargs -0 javac -d "$CLASSES"
javac -cp "$CLASSES" -d "$CLASSES" \
  mail-api/src/main/java/com/yeremitech/mailplatform/api/service/CampaignCsvParser.java \
  mail-api/src/main/java/com/yeremitech/mailplatform/api/service/ClamAvInstream.java \
  mail-api/src/main/java/com/yeremitech/mailplatform/api/service/AttachmentSignatureVerifier.java \
  mail-api/src/main/java/com/yeremitech/mailplatform/api/security/ClientPermissions.java \
  mail-api/src/main/java/com/yeremitech/mailplatform/api/service/WebhookAddressPolicy.java \
  scripts/offline/FeatureSmoke.java
java -cp "$CLASSES" FeatureSmoke
javac -cp "$CLASSES" -d "$CLASSES" \
  mail-api/src/main/java/com/yeremitech/mailplatform/api/service/PinnedWebhookTransport.java \
  scripts/offline/TransportSmoke.java
java -cp "$CLASSES" com.yeremitech.mailplatform.api.service.TransportSmoke

# Pure startup-security invariants are independently compiled/executed; this
# does not replace the Spring Boot integration suite.
javac -cp "$CLASSES" -d "$CLASSES" \
  mail-api/src/main/java/com/yeremitech/mailplatform/api/config/ProductionConfigurationValidator.java \
  scripts/offline/ProductionSmoke.java
java -cp "$CLASSES" ProductionSmoke

# Parser-only verification of every Java file; still not a Maven dependency build.
javac -d "$CLASSES" scripts/offline/ParseAllJava.java
java -cp "$CLASSES" ParseAllJava
