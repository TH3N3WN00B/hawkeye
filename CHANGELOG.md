# Changelog

All notable changes to this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Plugin skeleton from `moonrise-studios/plugin-template`: Paper 26.3 and Folia, Java 25, the
  Moonrise plugin-engine 1.10.1 (shaded and relocated) with its own Cloud command registry.
- `/hawkeye`, `/hawkeye reload` and `/hawkeye greet <player>` with the permission `hawkeye.admin`.
- `config.yml` with `greetings-enabled` and every player-facing message in MiniMessage; players are
  greeted on join.
- MockBukkit tests, an ArchUnit layer check and `verifyPluginJar`.
- GitHub Actions CI (jar artifact, release asset, optional Nexus publish), Dependabot and Jenkins.
