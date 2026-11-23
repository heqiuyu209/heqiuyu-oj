# Repository Guidelines

## Project Structure & Module Organization

The Git root wraps the application in `HOJ-master/`. Read the local handoff document at `C:\Users\lenovo\Documents\Obsidian Vault\HOJ项目\项目记忆.md` for requirements, verified checkpoints, and ownership boundaries. The user keeps project memory outside this repository; do not recreate `PROJECT_MEMORY.md` here. If the external document is unavailable, ask the user to provide it.

- `HOJ-master/hoj-springboot/`: Java 8 Maven reactor. `DataBackup` is the business backend, `api` contains shared entities/contracts, and `JudgeServer` handles judging. Tests reside in each module’s `src/test/java`.
- `HOJ-master/hoj-vue/`: Vue 2 frontend; application code and assets live under `src/`.
- `HOJ-master/hoj-scrollBoard/`: separate scoreboard assets.
- `HOJ-master/hoj-deploy/` and `HOJ-master/compose.yaml`: image definitions, Nginx configuration, and local services.
- `HOJ-master/sqlAndsetting/`: database initialization; `HOJ-master/docs/docs/ai/`: design documents, not implementation evidence.

## Build, Test, and Development Commands

Run from the Git root unless noted:

```powershell
docker compose -f .\HOJ-master\compose.yaml config --quiet
docker compose -f .\HOJ-master\compose.yaml build hoj-backend hoj-frontend
docker compose -f .\HOJ-master\compose.yaml up -d
mvn -f HOJ-master/hoj-springboot/pom.xml -pl DataBackup -am package -DskipTests
```

These validate configuration, build images, start configured services, and package the backend. Nacos initialization may require additional setup.

Inside `HOJ-master/hoj-vue`, run `npm ci`, then `npm run serve` or `npm run build`. With Node 22, set PowerShell `$env:NODE_OPTIONS='--openssl-legacy-provider'` for the legacy Webpack build.

## Coding Style & Naming Conventions

Match surrounding code: four-space Java indentation, two-space Vue/JavaScript indentation. Use PascalCase classes/components, camelCase methods/variables, and existing `Controller`, `Service`, `Manager`, and `Mapper` suffixes. Preserve Java 8 compatibility. No frontend lint/format script is configured; avoid unrelated reformatting.

## Testing Guidelines

Existing tests use Spring Boot Test and JUnit Jupiter. Name tests `*Test.java` or `*Tests.java`. The parent Surefire configuration explicitly skips tests: enable execution in an approved configuration change and verify executed test counts. Packaging success is not test coverage.

No coverage threshold or frontend test script is configured. Add focused regression tests; inspect integration tests before running against databases or external judges. Verify judging changes with known AC/WA/TLE submissions.

## Commit & Pull Request Guidelines

History uses `docs:` and `chore:` prefixes; follow `type: concise description`. Keep changes focused. PRs should include purpose, affected modules, validation results, configuration/migration notes, linked issues when applicable, and screenshots for UI changes.

## Security & Agent Collaboration

Teach first; modify business code/configuration only with explicit authorization. Use separate worktrees for parallel contributors. Coordinate shared contracts and Compose edits. Never commit real credentials, delete persistent volumes casually, or expose model keys to browsers. Enforce contest AI restrictions server-side. The root ignore rule `docs` excludes new documentation there; check tracking before committing.
