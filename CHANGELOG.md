# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `CHANGELOG.md`.

### Changed

- README: when-to-use, failure scenarios, hot-path cost, metrics table,
  testing, demo responses, and a link to the production-like
  [spring-transactional-outbox-kafka](https://github.com/KHolodilin/spring-transactional-outbox-kafka)
  reference.

## [1.0.0] - 2026-09-18

First stable release. Same feature set as 0.4.0: servlet/JDBC and
WebFlux/R2DBC starters, PostgreSQL as the source of truth, optional
Caffeine L1 and Redis L2 (`fail-open` by default).

[GitHub release](https://github.com/KHolodilin/spring-boot-idempotency-starter/releases/tag/v1.0.0)

## [0.4.0] - 2026-09-18

### Added

- WebFlux / R2DBC starter (`spring-boot-idempotency-starter-reactive`),
  reactive persistence, reactive Redis cache, and a runnable reactive
  demo ([#36](https://github.com/KHolodilin/spring-boot-idempotency-starter/pull/36)).

## [0.3.6] - 2026-09-18

### Changed

- Spotless 3.10.2.

## [0.3.5] - 2026-09-10

### Changed

- Surefire 3.6.0, Lombok 1.18.48, Maven Compiler Plugin 3.16.0.

## [0.3.4] - 2026-09-02

### Fixed

- Explicit Project URL on every published module
  ([#30](https://github.com/KHolodilin/spring-boot-idempotency-starter/pull/30)).

## [0.3.3] - 2026-09-02

### Fixed

- Maven Central project URL
  ([#29](https://github.com/KHolodilin/spring-boot-idempotency-starter/pull/29)).

## [0.3.2] - 2026-08-31

### Changed

- Spotless 3.10.1, `actions/setup-java` v6.

## [0.3.1] - 2026-08-24

### Changed

- Spring Boot 4.1.1, JSpecify 1.0.1, Maven Jar Plugin 3.5.1, Spotless
  3.10.0.

## [0.3.0] - 2026-08-13

### Added

- Fluent `IdempotencyCall` API with a per-call `.ttl(...)`
  ([#22](https://github.com/KHolodilin/spring-boot-idempotency-starter/pull/22)).
- Publish artifacts to GitHub Packages
  ([#18](https://github.com/KHolodilin/spring-boot-idempotency-starter/pull/18)).

## [0.2.0] - 2026-08-10

### Changed

- Lombok on the starter modules
  ([#16](https://github.com/KHolodilin/spring-boot-idempotency-starter/pull/16)).
- Testcontainers 2.0.5
  ([#17](https://github.com/KHolodilin/spring-boot-idempotency-starter/pull/17)).

## [0.1.1] - 2026-08-09

### Added

- CI, Maven Central publishing, quality gates, and community files
  ([#1](https://github.com/KHolodilin/spring-boot-idempotency-starter/pull/1)).

## [0.1.0] - 2026-08-09

### Added

- Initial public release: JDBC starter, PostgreSQL persistence,
  Caffeine L1, Redis L2, servlet demo.

[Unreleased]: https://github.com/KHolodilin/spring-boot-idempotency-starter/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/KHolodilin/spring-boot-idempotency-starter/compare/v0.4.0...v1.0.0
[0.4.0]: https://github.com/KHolodilin/spring-boot-idempotency-starter/compare/v0.3.6...v0.4.0
[0.3.6]: https://github.com/KHolodilin/spring-boot-idempotency-starter/compare/v0.3.5...v0.3.6
[0.3.5]: https://github.com/KHolodilin/spring-boot-idempotency-starter/compare/v0.3.4...v0.3.5
[0.3.4]: https://github.com/KHolodilin/spring-boot-idempotency-starter/compare/v0.3.3...v0.3.4
[0.3.3]: https://github.com/KHolodilin/spring-boot-idempotency-starter/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/KHolodilin/spring-boot-idempotency-starter/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/KHolodilin/spring-boot-idempotency-starter/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/KHolodilin/spring-boot-idempotency-starter/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/KHolodilin/spring-boot-idempotency-starter/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/KHolodilin/spring-boot-idempotency-starter/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/KHolodilin/spring-boot-idempotency-starter/releases/tag/v0.1.0
