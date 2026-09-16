plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localEnvFile = rootProject.file("../.env")
val localEnvText = localEnvFile
    .takeIf { it.isFile }
    ?.readText()
    .orEmpty()

fun readLocalEnv(name: String): String {
    val pattern = Regex(
        """(?m)^\s*${Regex.escape(name)}\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s#]+))\s*(?:#.*)?$""",
    )
    val match = pattern.find(localEnvText) ?: return ""
    return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
}

val groqApiKey = providers.environmentVariable("GROQ_API_KEY").orNull
    ?: readLocalEnv("GROQ_API_KEY")
val geminiApiKey = providers.environmentVariable("GEMINI_API_KEY").orNull
    ?: readLocalEnv("GEMINI_API_KEY")
val offlineOpusModelUrl = providers.environmentVariable("OFFLINE_OPUS_MODEL_URL").orNull
    ?: readLocalEnv("OFFLINE_OPUS_MODEL_URL")
val offlineOpusIneModelUrl = providers.environmentVariable("OFFLINE_OPUS_INE_MODEL_URL").orNull
    ?: readLocalEnv("OFFLINE_OPUS_INE_MODEL_URL")
val offlineWhisperModelUrl = providers.environmentVariable("OFFLINE_WHISPER_MODEL_URL").orNull
    ?: readLocalEnv("OFFLINE_WHISPER_MODEL_URL")

fun String.asBuildConfigString(): String = "\"" +
    replace("\\", "\\\\").replace("\"", "\\\"") +
    "\""

android {
    namespace = "com.sayit.translator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sayit.translator"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.10.0"

        buildConfigField("String", "GROQ_API_KEY", groqApiKey.asBuildConfigString())
        buildConfigField("String", "GEMINI_API_KEY", geminiApiKey.asBuildConfigString())
        buildConfigField(
            "String",
            "OFFLINE_OPUS_MODEL_URL",
            offlineOpusModelUrl.asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "OFFLINE_OPUS_INE_MODEL_URL",
            offlineOpusIneModelUrl.asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "OFFLINE_WHISPER_MODEL_URL",
            offlineWhisperModelUrl.asBuildConfigString(),
        )

    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files("libs/translate-kit-android-0.1.0-arm64.aar"))
    implementation(files("libs/whisperlib-release.aar"))

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
