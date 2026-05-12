plugins {
    id("io.spring.dependency-management")
    kotlin("jvm")
}

dependencies {
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))

    testImplementation("io.rest-assured:rest-assured:5.4.0")
    testImplementation("io.rest-assured:json-path:5.4.0")
    testImplementation("io.jsonwebtoken:jjwt-api:0.11.5")
    testRuntimeOnly("io.jsonwebtoken:jjwt-impl:0.11.5")
    testRuntimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("base.url",    System.getProperty("base.url",    "http://localhost"))
    systemProperty("JWT_SECRET",  System.getProperty("JWT_SECRET",  ""))
    systemProperty("QR_SECRET",   System.getProperty("QR_SECRET",   ""))
}
