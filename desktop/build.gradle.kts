import org.gradle.api.tasks.util.PatternSet
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "com.ketvoice"
version = "1.0.0"

val ffmpegVersion = "8.0.1-1.5.13" // LGPL build; 8.1.2-1.5.14 is GPL-only on Windows
val javacppVersion = "1.5.13"
// Coil 3.5+ is built with Kotlin 2.4 (stdlib 2.4 metadata), which the 2.2.21
// compiler shared with the Android app cannot read; 3.4.0 is the newest that fits.
val coilVersion = "3.4.0"

// Natives: the app ships the Windows ones; tests and `run` on another host use
// that host's (`-PnativePlatform=linux-x86_64` is the default off Windows).
val nativePlatform: String = providers.gradleProperty("nativePlatform").orNull
    ?: if (System.getProperty("os.name").startsWith("Windows")) "windows-x86_64" else "linux-x86_64"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose-jvm:$coilVersion")
    implementation("io.coil-kt.coil3:coil-network-okhttp-jvm:$coilVersion")
    implementation("org.jetbrains.androidx.navigation:navigation-compose-desktop:2.10.0")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    implementation("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:javacpp:$javacppVersion")
    runtimeOnly("org.bytedeco:ffmpeg:$ffmpegVersion:$nativePlatform")
    runtimeOnly("org.bytedeco:javacpp:$javacppVersion:$nativePlatform")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.32.0")
    // android.icu (lyric romanization) is ICU4J itself; the compat shim forwards to it.
    implementation("com.ibm.icu:icu4j:77.1")
    implementation("com.google.code.gson:gson:2.11.0") // same as the app (listening history)

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation(compose.desktop.uiTestJUnit4) // runComposeUiTest, headless
}

// Shared sources stay in the Android app and are compiled here in place. Each
// manifest in shared/ lists one path or glob per line, relative to the app's
// com/ketvoice/app package; `#` starts a comment.
fun manifest(vararg names: String): List<String> = names.flatMap { name ->
    file("shared/$name.txt").readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { "com/ketvoice/app/$it" }
}

// The app's source roots are added as they are (no copies): a file under an app
// root is compiled only when a manifest lists it; desktop/src is always in.
fun shared(root: File, patterns: List<String>): (FileTreeElement) -> Boolean {
    val spec = PatternSet().include(patterns).asSpec
    return { e -> e.isDirectory || !e.file.startsWith(root) || spec.isSatisfiedBy(e) }
}
val appMain = file("../app/src/main/kotlin")
val appTest = file("../app/src/test/kotlin")

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(appMain)
            kotlin.srcDir("src/compat/kotlin")
            val keep = shared(appMain, manifest("audio", "automix", "stems", "data", "lyrics", "sync", "ui"))
            kotlin.include { keep(it) }
        }
        test {
            kotlin.srcDir(appTest)
            val keep = shared(appTest, manifest("tests"))
            kotlin.include { keep(it) }
        }
    }
}

// The app's assets the shared code opens through Context.assets.
tasks.processResources {
    from("../app/src/main/assets") { include("provider_registry.json", "translations.json", "visual-refresh-translations.json"); into("assets") }
    // Ket 27's typeface (Inter, SIL OFL), read by the desktop theme.
    from("../app/src/main/res/font") { include("inter_variable.ttf"); into("font") }
}

tasks.test {
    useJUnit()
    // Some reused tests write fixtures through the compat Context: keep them in build/.
    systemProperty("ketvoice.dataDir", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
    maxHeapSize = "2g"
    // As in the app: host tests never reach the network (a dead local proxy).
    for (scheme in listOf("http", "https")) { systemProperty("$scheme.proxyHost", "127.0.0.1"); systemProperty("$scheme.proxyPort", "9") }
    systemProperty("http.nonProxyHosts", "localhost|127.*|[::1]")
}

compose.desktop {
    application {
        mainClass = "com.ketvoice.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Ketvoice"
            packageVersion = "1.0.0"
            vendor = "Ketvoice"
            description = "Ketvoice for Windows"
            modules("java.desktop", "java.net.http", "jdk.crypto.ec", "jdk.unsupported", "java.sql")
            windows {
                // Fixed for the product's whole life: MSI upgrades find the old install by it.
                upgradeUuid = "5319BA3F-92FE-494B-97B3-BDF702D3D01E"
                perUserInstall = true
                menu = true
                menuGroup = "Ketvoice"
                shortcut = true
                iconFile.set(project.file("icons/ketvoice.ico"))
            }
            linux {
                iconFile.set(project.file("icons/ketvoice.png"))
            }
        }
    }
}
