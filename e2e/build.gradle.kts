import java.time.Duration

plugins {
    java
}

repositories { mavenCentral() }

/*
Nothing from the server is on this classpath. The suite drives the jar a release ships, over the
MCP endpoint, through the SDK's own client -- so what it holds is the contract and not the code
behind it, and a refactor that keeps the contract does not touch a line here.
*/
dependencies {
    testImplementation(platform("io.modelcontextprotocol.sdk:mcp-bom:2.0.1"))
    testImplementation("io.modelcontextprotocol.sdk:mcp-core")
    /* The SDK finds its JSON mapper by service loader, and this is the one that supplies it. */
    testRuntimeOnly("io.modelcontextprotocol.sdk:mcp-json-jackson3")
    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

tasks.test {
    useJUnitPlatform()

    /* The jar a release ships, run as the process a release runs. */
    dependsOn(":bootJar")
    systemProperty("e2e.server.jar", rootProject.tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
        .get().archiveFile.get().asFile.absolutePath)
    systemProperty("e2e.fixture", rootProject.file("dev/fixture").absolutePath)

    /* Which bot to drive. CI passes the tag it has just published. */
    systemProperty("e2e.bot.image", providers.gradleProperty("e2e.bot.image")
        .orElse(providers.environmentVariable("E2E_BOT_IMAGE"))
        .getOrElse("bot-fabric:arm64-mc26.1.2"))
    systemProperty("e2e.bot.kind", providers.gradleProperty("e2e.bot.kind")
        .orElse(providers.environmentVariable("E2E_BOT_KIND"))
        .getOrElse("fabric"))
    systemProperty("e2e.minecraft.version", providers.gradleProperty("e2e.minecraft.version")
        .orElse(providers.environmentVariable("E2E_MINECRAFT_VERSION"))
        .getOrElse("26.1.2"))

    /* Minutes, not seconds: a server boots, a bot downloads a client and joins a world. */
    timeout = Duration.ofMinutes(30)
    outputs.upToDateWhen { false }
    /* The diagnosis a failure prints has to reach whoever reads the job. */
    testLogging { showStandardStreams = true }
}

/*
Not part of `check`. This needs Docker, a published bot image and a minute, none of which a build
should insist on; CI asks for it by name and so does anybody running it locally.

Nor part of a bare `test`. Gradle runs a task named on the command line in every project that has
one, so `./gradlew test` at the root reached this too and started a Paper server and a bot for
somebody who meant the unit tests -- four people did it in one afternoon. It runs only when asked
for by its path.
*/
tasks.named("check") { setDependsOn(emptyList<Any>()) }

val askedForByPath = gradle.startParameter.taskNames.any { it.removePrefix(":") == "e2e:test" }

tasks.test {
    onlyIf("the end-to-end suite runs only as :e2e:test") { askedForByPath }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror"))
}
