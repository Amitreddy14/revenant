plugins {
    `java-library`
}

description = "PostgreSQL storage: schema migrations and repositories (built in Phase 1)."

dependencies {
    api(project(":revenant-core"))
}
