# Contribution and commit guidelines

Only JDK 17 is supported and verified. Use JDK 17 (including keytool) and Maven 3.9+. This is a single-module regular JAR under package com.datamigration, with Maven coordinates com.datamigration:data_migration.

## Local checks

```sh
mvn -B -ntp clean verify
git diff --check
git status --short
```

Surefire runs unit tests and Failsafe runs *IT tests. JaCoCo measures unit tests only; both line and branch coverage must be at least 85%. Tests use synthetic data and isolated H2 databases. TLS tests generate temporary certificates. Reports are in target/surefire-reports, target/failsafe-reports, and target/site/jacoco-ut.

## What to commit

Commit src, pom.xml, configuration templates, docs, build workflows, and maintenance documentation. Each fix should include a test reproducing the actual issue and update affected examples or instructions. Do not lower coverage thresholds or skip failing tests to pass checks.

Do not commit:

- target, IDE settings, logs, JARs/archives, or configuration containing local absolute paths.
- H2 database files, real data exports, production audit contents, or full error logs.
- Passwords, access tokens, AWS credentials, private keys, certificate truststores, or local YAML / .env files populated with real connection information.

.gitignore is a fallback; still review `git diff --cached` before committing. Examples retain environment-variable placeholders. The sa/empty passwords and test-password values in tests are only for isolated test databases and temporary certificates.

## Reporting issues and validation boundaries

Include Java/database/driver versions, redacted configuration, expected behavior, actual status, and reproducible synthetic data. Do not upload database passwords, tokens, raw business rows, or credential-bearing JDBC URLs to public issues or PRs.

Changes involving unknown commit outcomes, checkpoints, target clearing, concurrency locks, skip budgets, or sequences must verify that recovery neither duplicates nor loses data. Record real Oracle TCPS, PostgreSQL, IAM, and ECS validation separately from local H2 tests. The host DataSource entirely manages database connections and credentials; the host implements IAM token handling. See docs/database-credentials.md.

See the [test index](docs/test-index.md) for test directories and package-access exceptions. New recovery scenarios must follow the [resilience testing rules](docs/resilience-testing.md); do not automatically clean or create DEV business/audit tables.
