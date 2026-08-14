import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun stringProperty(name: String): Provider<String> =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orElse(providers.provider { localProperties.getProperty(name) })

fun Provider<String>.hasValue(): Boolean = orNull?.isNotBlank() == true

val helperVersionName = providers.gradleProperty("helperVersionName")
    .orElse(providers.environmentVariable("HELPER_VERSION_NAME"))
val helperVersionCode = providers.gradleProperty("helperVersionCode")
    .orElse(providers.environmentVariable("HELPER_VERSION_CODE"))
    .map(String::toInt)

val releaseStoreFile = stringProperty("HELPER_RELEASE_STORE_FILE")
val releaseStorePassword = stringProperty("HELPER_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = stringProperty("HELPER_RELEASE_KEY_ALIAS")
val releaseKeyPassword = stringProperty("HELPER_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.hasValue() }

android {
    namespace = "dev.rushi.apkdownloadhelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.rushi.apkdownloadhelper"
        minSdk = 26
        targetSdk = 36
        versionCode = helperVersionCode.get()
        versionName = helperVersionName.get()
    }

    val releaseSigningConfig = signingConfigs.create("release") {
        if (hasReleaseSigning) {
            storeFile = rootProject.file(releaseStoreFile.get())
            storePassword = releaseStorePassword.get()
            keyAlias = releaseKeyAlias.get()
            keyPassword = releaseKeyPassword.get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

gradle.taskGraph.whenReady {
    val releaseTaskRequested = allTasks.any { it.name.contains("Release", ignoreCase = true) }
    if (releaseTaskRequested && !hasReleaseSigning) {
        throw GradleException(
            "Release signing is required. Provide HELPER_RELEASE_STORE_FILE, " +
                "HELPER_RELEASE_STORE_PASSWORD, HELPER_RELEASE_KEY_ALIAS, and HELPER_RELEASE_KEY_PASSWORD.",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("com.auroraoss:gplayapi:3.5.8")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.google.re2j:re2j:1.8")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("org.jsoup:jsoup:1.22.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}
