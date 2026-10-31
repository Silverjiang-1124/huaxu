plugins {
    id("com.android.application")
}

android {
    namespace = "com.engnotes.artistorders"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.engnotes.artistorders"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.activity:activity:1.12.4")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}

val buildWeb by tasks.registering(Exec::class) {
    workingDir(rootProject.file("web"))
    commandLine(if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm", "run", "build")
    inputs.files(fileTree(rootProject.file("web/src")), rootProject.file("web/index.html"), rootProject.file("web/package.json"), rootProject.file("web/package-lock.json"), rootProject.file("web/vite.config.mjs"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/web"))
}
tasks.named("preBuild").configure { dependsOn(buildWeb) }

