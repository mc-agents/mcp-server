plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "kr.junhyung.mcagents"
version = file("VERSION").readText().trim()

repositories { mavenCentral() }

dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:2.0.1"))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    /*
    join-server creates a MinecraftBot when none is running under that name, which is the one
    thing here that talks to Kubernetes. Absent a cluster the client simply reports there is
    none, and join-server says a bot has to be started by hand.
    */
    implementation("io.fabric8:kubernetes-client:7.9.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.networknt:json-schema-validator:3.0.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

// The catalogue ships from where it is reviewed. Copying it under src/main/resources would make a
// second copy that can drift from the one the other repositories read. The include has to sit on
// the copy and not on the source set: on the source set it filters every resource, which silently
// left application.yaml out of the jar.
tasks.processResources {
    from("catalog") { include("*.json") }

    // initialize reports the version from VERSION. ReplaceTokens rather than expand(), because
    // expand() is Groovy templating and reads every ${ENV:default} placeholder as its own. The
    // version is an input of its own: the filter's tokens are not, and a bump alone left the old
    // number in the jar.
    inputs.property("version", project.version.toString())
    filesMatching("application.yaml") {
        filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to mapOf("version" to project.version.toString()))
    }
}

tasks.test {
    useJUnitPlatform()
    // A failure in CI has only this to go on, so it carries the whole exception and its causes.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
    }
}

/* The plain jar is a library artifact nothing here consumes. */
tasks.bootJar { archiveFileName = "app.jar" }
tasks.jar { enabled = false }

/*
The image comes from Paketo buildpacks rather than a Dockerfile of ours. What that buys is the
parts a hand-written Dockerfile gets wrong quietly: the JVM's heap is sized from the container's
real limit by the memory calculator instead of a guessed percentage, the layers split so a code
change does not re-push the dependencies, and an SBOM comes with it.

One invocation produces one architecture. CI runs this on a native runner per architecture and
joins the two with a manifest list, because a cross-build here means running the whole builder
under emulation.
*/
tasks.bootBuildImage {
    imageName = "${project.findProperty("imageName") ?: "junhyung.cloud/library/mcp-server"}:${project.version}"
    environment = mapOf("BP_JVM_VERSION" to "25")

    /* Credentials come from the environment so nothing has to be passed on a command line. */
    docker {
        publishRegistry {
            url = System.getenv("REGISTRY_URL") ?: "https://junhyung.cloud"
            username = System.getenv("REGISTRY_USERNAME") ?: ""
            password = System.getenv("REGISTRY_PASSWORD") ?: ""
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror"))
}

// docs/tools.md is the catalogue rendered as a page, and the test suite fails when the two differ.
tasks.register<JavaExec>("renderToolReference") {
    description = "Rewrite docs/tools.md from catalog/catalog.json."
    group = "documentation"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.junhyung.mcagents.docs.ToolReference"
    workingDir = projectDir
}
