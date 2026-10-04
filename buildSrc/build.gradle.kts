plugins { `java-library` }

repositories { mavenCentral() }

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    testImplementation("junit:junit:4.13.2")
}

// Keep the license validation regressions on the existing CI build path.
tasks.jar { dependsOn(tasks.test) }
