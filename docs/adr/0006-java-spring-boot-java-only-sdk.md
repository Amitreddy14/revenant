# ADR-0006: Java 21, Spring Boot 4, Java-only worker SDK

- Status: Accepted
- Date: 2026-11-03

## Context

The stack is Java with Spring Boot. A Spring Boot version has to be chosen, and the question of supporting several languages for workers has to be settled.

## Decision

- **Java 21 (LTS)** as the language level. Virtual threads suit workers that mostly wait on blocking calls and long polls.
- **Spring Boot 4.1.x** for the server. Spring Boot 3.5 is out of open-source support by now, so a new project starts on the current line. Boot 4 uses Jackson 3 and renamed starters (for example `spring-boot-starter-webmvc`).
- **Java-only worker SDK.** Enforcing determinism and implementing replay depends on the language runtime. A second SDK would double the scope and halve the quality.

## Consequences

- One language to test and document.
- Anyone who wants another language can talk to the server's HTTP API, but without replay support.
- Spring Boot 4 is new, so some blog posts and answers online still describe Boot 3; the official migration guide is the reference.
