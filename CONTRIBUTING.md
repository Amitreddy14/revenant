# Contributing

## Workflow

1. Branch from `main`: `git switch -c phase-1-schema` (pattern `phase-N-topic`, or `fix-topic`, `docs-topic`).
2. Commit in small, meaningful steps using [Conventional Commits](https://www.conventionalcommits.org/):
   - `feat: add history_event table and migration`
   - `fix: reject duplicate history append`
   - `docs: add ADR for timer service`
   - `test:`, `refactor:`, `chore:`, `build:`, `ci:`
3. Run `.\gradlew.bat build` (Windows) or `./gradlew build` (Linux/macOS) before pushing.
4. Open a pull request into `main`. CI must be green. Squash-merge.
5. At the end of each phase, tag `main`: `phase-1`, `phase-2`, and so on.

## Decisions

Anything that changes the architecture needs an ADR in `docs/adr/`. Number it next in sequence and link it from `docs/adr/README.md`.

## Style

- Java 21, 4-space indentation, UTF-8, LF line endings (enforced by `.editorconfig` and `.gitattributes`).
- `revenant-core` must not gain framework dependencies (ADR-0007).
- Compiler runs with `-Xlint:all`; do not add new warnings.
