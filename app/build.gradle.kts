import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Discord Social SDK is owner-supplied and never redistributed through Git.
// Without the AAR the app still builds; the Discord integration is compiled out
// and the account UI stays hidden.
val discordSdk = file("libs/discord/discord_partner_sdk.aar")
val discordSdkAvailable = discordSdk.isFile &&
    !providers.gradleProperty("ketvoiceWithoutDiscord").map { it.equals("true", ignoreCase = true) }.getOrElse(false)

android {
    namespace = "com.ketvoice.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ketvoice.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 139
        versionName = "6.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("fr", "en", "hi", "ja", "zh", "ru", "it", "es", "de", "pt", "ko")
        ndkVersion = "27.0.12077973"
        if (discordSdkAvailable) {
            // Presence and account linking only need arm64 on phones; Quest keeps
            // x86_64 for the emulator via its flavor filters.
            ndk { abiFilters += listOf("arm64-v8a") }
            externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
        }
    }

    if (discordSdkAvailable) {
        externalNativeBuild {
            cmake {
                path = file("src/discord/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    if (discordSdkAvailable) {
        sourceSets["main"].java.srcDir("src/discord/kotlin")
    }

    flavorDimensions += "device"
    productFlavors {
        create("mobile") {
            dimension = "device"
            buildConfigField("boolean", "IS_QUEST", "false")
            buildConfigField("boolean", "IS_LITE", "false")
        }
        create("quest") {
            dimension = "device"
            minSdk = 32
            targetSdk = 34
            buildConfigField("boolean", "IS_QUEST", "true")
            buildConfigField("boolean", "IS_LITE", "false")
            // Quest uses arm64; x86_64 permits the same panel build to be
            // exercised on an Android emulator without shipping 32-bit ABIs.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        // Ketvoice Lite (6.1): the phone app, same features and effects, lighter. Same application id and
        // signature as mobile: one replaces the other in place, keeping the library, settings and downloads.
        // The launcher says « Ketvoice Lite » (src/lite/res). See the « Ketvoice Lite » section below android {}.
        create("lite") {
            dimension = "device"
            buildConfigField("boolean", "IS_QUEST", "false")
            buildConfigField("boolean", "IS_LITE", "true")
            // Android 11 too (owner, 2026-11-18: « compatible Android 11 »): every API 31+ call is guarded and has
            // a fallback that looks the same (glass blur in software, see glass/SoftBlur.kt). Mobile stays at 31.
            minSdk = 30
            // 64-bit ARM only, with or without the Discord SDK (LiteRT alone ships ~20 MB for the other three ABIs).
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }
    // Lite is a phone edition: it takes the mobile source set too (Android Auto manifest, car resources, any
    // mobile-only code added later), then its own src/lite.
    sourceSets["lite"].apply {
        manifest.srcFile("src/mobile/AndroidManifest.xml")
        java.srcDirs("src/mobile/java", "src/mobile/kotlin")
        kotlin.srcDirs("src/mobile/kotlin")
        res.srcDirs("src/mobile/res")
        assets.srcDirs("src/mobile/assets")
    }
    // Full-size assets of the mobile and Quest APKs; Lite has its own lighter copies in src/lite
    // (tools/lite/make_lite_resources.py).
    sourceSets["mobile"].assets.srcDirs("src/standard/assets")
    sourceSets["quest"].assets.srcDirs("src/standard/assets")

    signingConfigs {
        create("sideload") {
            // Priority order for the signing material:
            //  1) KETVOICE_KEYSTORE_B64 — base64 keystore body (GitHub secret, CI)
            //  2) KETVOICE_KEYSTORE     — path to a keystore file (local dev)
            //  3) fallback              — the well-known debug keystore
            // A stable keystore is what prevents "app conflicts with an existing
            // package" — every CI run must sign with the SAME key.
            val b64 = providers.environmentVariable("KETVOICE_KEYSTORE_B64").orNull
            val customStore = providers.environmentVariable("KETVOICE_KEYSTORE").orNull

            val resolvedStoreFile = when {
                !b64.isNullOrBlank() -> {
                    val decoded = File(layout.buildDirectory.asFile.get(), "signing/ketvoice-release.jks")
                    decoded.parentFile.mkdirs()
                    // Secrets are often pasted with line wrapping; the strict
                    // decoder throws on any whitespace, so strip it first.
                    val body = b64.filterNot { it.isWhitespace() }
                    decoded.writeBytes(Base64.getDecoder().decode(body))
                    decoded
                }
                !customStore.isNullOrBlank() -> file(customStore)
                else -> file("${System.getProperty("user.home")}/.android/debug.keystore")
            }
            val usingFallback = b64.isNullOrBlank() && customStore.isNullOrBlank()

            storeFile = resolvedStoreFile
            storePassword = if (usingFallback) "android"
                else providers.environmentVariable("KETVOICE_STORE_PASSWORD").orNull
            keyAlias = if (usingFallback) "androiddebugkey"
                else (providers.environmentVariable("KETVOICE_KEY_ALIAS").orNull ?: "ketvoice")
            keyPassword = if (usingFallback) "android"
                else providers.environmentVariable("KETVOICE_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        debug {
            // Instrumented fixture tests must never replace or clear the owner's real app.
            applicationIdSuffix = ".qa"
        }
        release {
            signingConfig = signingConfigs.getByName("sideload")
            // No R8: the optimised build of 5.8.0 was never run on a device and the owner found playback
            // broken in it. proguard-rules.pro stays for a later attempt, only once a device has run it.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
        prefab = discordSdkAvailable
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Host renders of tablet windows (6.6) outgrew Gradle's default 512 MB test heap on CI.
        unitTests.all { it.maxHeapSize = "2g" }
        // Host tests never reach the network (6.7): a lyrics or cover answer from a real service changed what a
        // screen showed and kept Compose busy, so the suite passed or failed with the hour. A dead local proxy
        // refuses every outside connection at once; loopback servers of the tests stay reachable.
        unitTests.all {
            for (scheme in listOf("http", "https")) { it.systemProperty("$scheme.proxyHost", "127.0.0.1"); it.systemProperty("$scheme.proxyPort", "9") }
            // Maven Central stays reachable: Robolectric fetches its Android runtimes there on a machine without them.
            it.systemProperty("http.nonProxyHosts", "localhost|127.*|[::1]|repo1.maven.org|*.maven.org")
        }
        // -ProbolectricSdks=31 runs only those host SDKs (Lite's Android 11 tests also declare sdk 30, whose host
        // runtime Robolectric fetches on first use).
        val robolectricSdks = providers.gradleProperty("robolectricSdks").orNull
        if (robolectricSdks != null) unitTests.all { it.systemProperty("robolectric.enabledSdks", robolectricSdks) }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// ============================================================================ Ketvoice Lite
// Owner, 2026-11-17: « App plus rapide et légère sera un autre apk que tu va créer et qui aura les meme effet
// mais sera adapté ». Lite runs the same code. Its APK is lighter: arm64 only, compressed dex, lossless WebP
// images (src/lite, tools/lite/make_lite_resources.py), and above all the Material icon set (about 11 000
// classes, the largest library in the APK) is reduced, in the Lite APK only, to the icons Ketvoice draws.
// This is not R8 (still off, see AGENTS.md): no class is rewritten, renamed or optimised — whole icon classes
// are copied untouched or left out, and verifyLite<Variant>Icons fails the build when the compiled app refers
// to an icon class that was left out.

object LiteIconTools {
    const val ROOT = "androidx/compose/material/icons/"
    val classRef = Regex("""androidx/compose/material/icons/(?:automirrored/)?(?:filled|outlined|rounded|sharp|twotone)/[A-Za-z0-9_]+Kt""")
    fun entries(jar: File): Map<String, ByteArray> = ZipFile(jar).use { zip ->
        zip.entries().asSequence().filter { !it.isDirectory }.associate { it.name to zip.getInputStream(it).readBytes() }
    }
}

fun liteIconSet(project: Project, variant: String) = project.files(project.provider {
    project.configurations.getByName("${variant}CompileClasspath").incoming.artifactView {
        attributes { attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar") }
        componentFilter { id ->
            id is org.gradle.api.artifacts.component.ModuleComponentIdentifier &&
                id.group == "androidx.compose.material" && id.module == "material-icons-extended-android"
        }
    }.files
})

/** Keeps the icon classes the sources name (Icons.Filled.X, Icons.AutoMirrored.Filled.X, their imports) and what they use. */
abstract class LiteIconsJar : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Classpath abstract val icons: ConfigurableFileCollection
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction fun prune() {
        val use = Regex("""\bIcons\s*\.\s*(AutoMirrored\s*\.\s*)?(Default|Filled|Outlined|Rounded|Sharp|TwoTone)\s*\.\s*([A-Z][A-Za-z0-9_]*)""")
        val import = Regex("""import\s+androidx\.compose\.material\.icons\.(automirrored\.)?(filled|outlined|rounded|sharp|twotone)\.([A-Z][A-Za-z0-9_]*)""")
        val wanted = sortedSetOf<String>()
        sources.files.filter { it.isFile }.forEach { file ->
            val text = file.readText()
            use.findAll(text).forEach { m ->
                val style = m.groupValues[2].lowercase().let { if (it == "default") "filled" else it }
                wanted += "androidx/compose/material/icons/" + (if (m.groupValues[1].isNotEmpty()) "automirrored/" else "") + "$style/${m.groupValues[3]}Kt"
            }
            import.findAll(text).forEach { m ->
                wanted += "androidx/compose/material/icons/" + (if (m.groupValues[1].isNotEmpty()) "automirrored/" else "") + "${m.groupValues[2]}/${m.groupValues[3]}Kt"
            }
        }
        val jar = icons.files.singleOrNull() ?: throw GradleException("Ketvoice Lite: expected one material-icons-extended jar, got ${icons.files}")
        val entries = LiteIconTools.entries(jar)
        val classes = entries.keys.filter { it.endsWith(".class") }.map { it.removeSuffix(".class") }.toSet()
        val kept = sortedSetOf<String>()
        var frontier = classes.filter { c -> wanted.any { c == it || c.startsWith("$it$") } }
        while (frontier.isNotEmpty()) {
            kept += frontier
            frontier = frontier.flatMap { c ->
                Regex("""androidx/compose/material/icons/[A-Za-z0-9_/$]+""")
                    .findAll(String(entries.getValue("$c.class"), Charsets.ISO_8859_1)).map { it.value }.toList()
            }.filter { it in classes && it !in kept }.distinct()
        }
        val out = output.get().asFile
        out.parentFile.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            // Fixed entry times: the same sources give the same jar, byte for byte.
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name).apply { time = 315532800000L })
                zip.write(bytes); zip.closeEntry()
            }
            entries.keys.filter { it.startsWith("META-INF/") && !it.endsWith(".SF") && !it.endsWith(".RSA") }.sorted()
                .forEach { put(it, entries.getValue(it)) }
            kept.forEach { put("$it.class", entries.getValue("$it.class")) }
        }
        logger.lifecycle("Ketvoice Lite icons: ${kept.size} of ${classes.size} icon classes packaged (${wanted.size} named in the sources).")
    }
}

/** The safety net: every icon class the compiled app refers to must be in the Lite APK. */
abstract class LiteIconsCheck : DefaultTask() {
    @get:InputFiles abstract val classJars: ListProperty<RegularFile>
    @get:InputFiles abstract val classDirs: ListProperty<Directory>
    @get:Classpath abstract val icons: ConfigurableFileCollection
    @get:InputFile abstract val pruned: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun check() {
        val referenced = sortedSetOf<String>()
        fun scan(bytes: ByteArray) {
            LiteIconTools.classRef.findAll(String(bytes, Charsets.ISO_8859_1)).forEach { referenced += it.value }
        }
        classDirs.get().forEach { dir -> dir.asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { scan(it.readBytes()) } }
        classJars.get().forEach { jar -> LiteIconTools.entries(jar.asFile).filterKeys { it.endsWith(".class") }.values.forEach(::scan) }
        val extended = icons.files.flatMap { LiteIconTools.entries(it).keys }.map { it.removeSuffix(".class") }.toSet()
        val packaged = LiteIconTools.entries(pruned.get().asFile).keys.map { it.removeSuffix(".class") }.toSet()
        val missing = referenced.filter { it in extended && it !in packaged }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(
            "referenced icon classes: ${referenced.size}\npackaged: ${packaged.count { it.startsWith(LiteIconTools.ROOT) }}\nmissing: ${missing.size}\n" +
                missing.joinToString("") { "  $it\n" })
        if (missing.isNotEmpty()) throw GradleException("Ketvoice Lite would crash: icon classes used but not packaged: $missing")
    }
}

val liteIcons = tasks.register<LiteIconsJar>("liteIconsJar") {
    description = "Material icons used by Ketvoice, for the Lite APK."
    sources.from(fileTree("src") {
        include("**/*.kt", "**/*.java")
        exclude("test*/**", "androidTest*/**")
    })
    icons.from(liteIconSet(project, "liteRelease"))
    output.set(layout.buildDirectory.file("lite/material-icons-used.jar"))
}

androidComponents {
    onVariants(selector().withFlavor("device" to "lite")) { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register<LiteIconsCheck>("verify${name}Icons") {
            description = "Checks that every icon the compiled ${variant.name} uses is in its APK."
            icons.from(liteIconSet(project, variant.name))
            pruned.set(liteIcons.flatMap { it.output })
            report.set(layout.buildDirectory.file("lite/${variant.name}-icons.txt"))
        }
        variant.artifacts.forScope(com.android.build.api.variant.ScopedArtifacts.Scope.PROJECT).use(check)
            .toGet(com.android.build.api.artifact.ScopedArtifact.CLASSES, LiteIconsCheck::classJars, LiteIconsCheck::classDirs)
        tasks.matching { it.name == "assemble$name" }.configureEach { dependsOn(check) }
        // The code (about 40 MB of dex without R8) is compressed in the Lite APK: about half the download.
        // Android unpacks it once at install, so the installed app takes a little more storage than the APK.
        variant.packaging.dex.useLegacyPackaging.set(true)
    }
}
// ========================================================================== end Ketvoice Lite

dependencies {
    if (discordSdkAvailable) {
        implementation(files(discordSdk))
        implementation("androidx.browser:browser:1.8.0")
    }
    val composeBom = platform("androidx.compose:compose-bom:2025.11.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    // Voice separation for Ketmix (5.9.3): LiteRT runs the optional Spleeter model on the processor.
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    // Fold posture (tabletop player), home-screen widget, and startup profile installation for sideloads.
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // Already shipped through Glance; declared for the weekly discoveries worker (same version, nothing new downloaded).
    implementation("androidx.work:work-runtime-ktx:2.7.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-unit")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    // Ketvoice Lite compiles against the full icon set but packages only the icons it draws (liteIconsJar below).
    "mobileImplementation"("androidx.compose.material:material-icons-extended")
    "questImplementation"("androidx.compose.material:material-icons-extended")
    "liteCompileOnly"("androidx.compose.material:material-icons-extended")
    "liteImplementation"("androidx.compose.material:material-icons-core")
    "liteRuntimeOnly"(files(liteIcons.flatMap { it.output }).builtBy(liteIcons))

    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media:media:1.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.code.gson:gson:2.11.0")
    // Ketogether (6.1): the WebSocket of Supabase Realtime. Same version Coil already brings in.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.kyant0:backdrop:1.0.0-rc02")
    implementation("androidx.media3:media3-datasource:1.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
