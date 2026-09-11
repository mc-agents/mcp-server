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

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

// The catalogue ships from where it is reviewed. Copying it under src/main/resources would make a
// second copy that can drift from the one the other repositories read. The include has to sit on
// the copy and not on the source set: on the source set it filters every resource, which silently
// left application.yaml out of the jar.
tasks.processResources { from("catalog") { include("*.json") } }

tasks.test { useJUnitPlatform() }

/*
One jar, always at the same path. The Dockerfile copies it by name, and a glob over build/libs
picks up whatever earlier versions are still lying there. The plain jar is a library artifact
nothing here consumes.
*/
tasks.bootJar { archiveFileName = "app.jar" }
tasks.jar { enabled = false }

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror"))
}
