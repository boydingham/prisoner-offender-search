plugins {
  id("uk.gov.justice.hmpps.gradle-spring-boot") version "3.3.0"
  kotlin("plugin.spring") version "1.5.10"
}

configurations {
  testImplementation { exclude(group = "org.junit.vintage") }
}

dependencyCheck {
  suppressionFiles.add("elasticsearch-suppressions.xml")
}

dependencies {
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.data:spring-data-elasticsearch")

  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

  implementation("org.springdoc:springdoc-openapi-ui:1.5.8")
  implementation("org.springdoc:springdoc-openapi-kotlin:1.5.8")

  implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("org.apache.commons:commons-lang3:3.12.0")
  implementation("com.google.code.gson:gson:2.8.6")
  implementation("com.google.guava:guava:30.1.1-jre")

  implementation("org.springframework:spring-jms")
  implementation(platform("com.amazonaws:aws-java-sdk-bom:1.11.1020"))
  implementation("com.amazonaws:amazon-sqs-java-messaging-lib:1.0.8")
  implementation("uk.gov.justice.service.hmpps:hmpps-spring-boot-sqs:0.3.2")
  implementation("com.amazonaws:aws-java-sdk-elasticsearch:1.11.1020")
  implementation("org.awaitility:awaitility-kotlin:4.1.0")

  testImplementation("com.github.tomakehurst:wiremock-standalone:2.27.2")
  testImplementation("org.testcontainers:localstack:1.15.3")
  testImplementation("org.testcontainers:elasticsearch:1.15.3")
  testImplementation("io.jsonwebtoken:jjwt:0.9.1")
  testImplementation("org.junit.jupiter:junit-jupiter-params:5.7.2")
  testImplementation("org.mockito:mockito-inline:3.10.0")
}

tasks {
  compileKotlin {
    kotlinOptions {
      jvmTarget = "16"
    }
  }
}
