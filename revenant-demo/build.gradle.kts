plugins {
    application
}

description = "Demo: an order workflow and a worker you can kill with kill -9 and restart (Phase 5b)."

dependencies {
    implementation(project(":revenant-worker-sdk"))
}

application {
    mainClass.set("io.github.amitreddy14.revenant.demo.DemoWorker")
}
