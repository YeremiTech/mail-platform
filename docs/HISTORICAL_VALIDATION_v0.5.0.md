# Validation report — 0.5.0-SNAPSHOT

## Executed in this workspace

- Input archive `mail-platform.zip` was extracted successfully and preserved in a separate working directory.
- JDK 21 compiled all Java sources in the dependency-free `mail-domain` and `mail-application` modules, including backwards-compatible constructors for scheduled mail.
- The standalone `AttachmentSignatureVerifier` compiled and a Java smoke runner passed valid PDF / spoofed PDF, valid DOCX / wrong DOCX-as-XLSX, and invalid UTF-8 checks.
- The nine Maven POMs parse as XML, V1–V9 migrations are present in order, both Python smoke scripts pass syntax checks, and the GitHub Actions YAML contains both smoke steps. A Java syntax pass on API, infrastructure, worker and starter sources found **no syntax diagnostics**, but it yielded missing Spring/Jackson/Micrometer dependency errors and does **not** constitute compilation.
- The Maven Wrapper could not bootstrap because this environment has no Maven, no JDK 25 and cannot resolve `repo.maven.apache.org`. Therefore the **full build, all JUnit tests, Flyway migrations, HTTP contract and RabbitMQ/SMTP integrations have not been executed or verified here**.

## Required in a Java 25 / PostgreSQL environment

```bash
./mvnw -B clean verify
./mvnw -pl mail-api -am spring-boot:run
python3 scripts/e2e_smoke.py
python3 scripts/upgrade_smoke.py
```

The GitHub Actions workflow sets up JDK 25 and PostgreSQL/RabbitMQ/Mailpit services and runs both smoke scripts. Do not publish a production maturity score until all tests, failover drills, an SMTP provider acceptance test and a restore drill pass.

See `docs/HISTORICAL_VALIDATION_v0.4.1.md` for the previous archive's results. Historical results must not be represented as tests run on v0.5.0.
