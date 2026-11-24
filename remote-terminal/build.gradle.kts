plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val terminalSource = layout.projectDirectory.dir("terminal")
val terminalPackages = terminalSource.dir("node_modules")
val bundledAssets = layout.buildDirectory.dir("generated/terminalAssets")
val installTerminalPackages by tasks.registering(Exec::class) {
    workingDir(terminalSource)
    inputs.files(terminalSource.file("package.json"), terminalSource.file("package-lock.json"))
    outputs.dir(terminalPackages)
    commandLine("npm", "ci", "--no-audit", "--no-fund")
}
val bundleTerminalAssets by tasks.registering(Exec::class) {
    dependsOn(installTerminalPackages)
    workingDir(terminalSource)
    inputs.files(terminalSource.file("build.mjs"), terminalSource.file("editor.mjs"), terminalSource.file("package-lock.json"))
    outputs.dir(bundledAssets)
    commandLine("node", "build.mjs", bundledAssets.get().asFile.absolutePath)
}

android {
    namespace = "kr.flownote.remote"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    sourceSets["main"].assets.srcDir(bundledAssets)
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.named("preBuild") { dependsOn(bundleTerminalAssets) }

dependencies {
    api("androidx.activity:activity:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
