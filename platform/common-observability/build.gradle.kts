plugins {
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("io.micrometer:micrometer-tracing")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
}
