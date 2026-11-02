plugins {
    `java-library`
}

description = "Java SDK that workflow and activity authors depend on (built in Phase 5)."

dependencies {
    api(project(":revenant-core"))
}
