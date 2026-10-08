plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is supplied at build time from an external keystore. Never commit signing values.
val releaseSigningEnvironment = mapOf(
    "CRAFTMIND_RELEASE_STORE_FILE" to providers.environmentVariable("CRAFTMIND_RELEASE_STORE_FILE").orNull,
    "CRAFTMIND_RELEASE_STORE_PASSWORD" to providers.environmentVariable("CRAFTMIND_RELEASE_STORE_PASSWORD").orNull,
    "CRAFTMIND_RELEASE_KEY_ALIAS" to providers.environmentVariable("CRAFTMIND_RELEASE_KEY_ALIAS").orNull,
    "CRAFTMIND_RELEASE_KEY_PASSWORD" to providers.environmentVariable("CRAFTMIND_RELEASE_KEY_PASSWORD").orNull,
)
val releaseSigningConfigured = releaseSigningEnvironment.values.all { !it.isNullOrBlank() }
val hasAnyReleaseSigningInput = releaseSigningEnvironment.values.any { !it.isNullOrBlank() }
if (hasAnyReleaseSigningInput && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing is incomplete. Set all four CRAFTMIND_RELEASE_* environment variables; values are never read from the repository.",
    )
}

val releaseStoreFile = releaseSigningEnvironment["CRAFTMIND_RELEASE_STORE_FILE"]
    ?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }
if (releaseSigningConfigured) {
    val store = releaseStoreFile
        ?: throw GradleException("CRAFTMIND_RELEASE_STORE_FILE must point to an external release keystore.")
    if (!store.isFile) {
        throw GradleException("CRAFTMIND_RELEASE_STORE_FILE must point to an existing release keystore outside the repository.")
    }
    val repositoryPath = rootProject.projectDir.canonicalFile.toPath()
    val storePath = store.canonicalFile.toPath()
    if (storePath.startsWith(repositoryPath)) {
        throw GradleException("The release keystore must be stored outside the repository checkout.")
    }
}

android {
    namespace = "com.craftmind.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.craftmind.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 10000
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // The address of the CraftMind account service (Phase 17).
        //
        // Supplied per build — `-PcraftmindAccountBaseUrl=…` or `CRAFTMIND_ACCOUNT_BASE_URL` — and never committed: the
        // repository contains no service address and no credential. Deliberately empty by default, because an empty
        // value is a real configuration meaning "this build has no account service"; the app then reports that it has
        // none instead of offering a sign-in that cannot work. A supplied value must be absolute HTTPS, which
        // AccountServiceConfiguration enforces before the first request is built.
        val accountBaseUrl = (project.findProperty("craftmindAccountBaseUrl") as String?)
            ?: System.getenv("CRAFTMIND_ACCOUNT_BASE_URL")
            ?: ""
        buildConfigField("String", "CRAFTMIND_ACCOUNT_BASE_URL", "\"$accountBaseUrl\"")
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = requireNotNull(releaseStoreFile)
                storePassword = releaseSigningEnvironment.getValue("CRAFTMIND_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("CRAFTMIND_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("CRAFTMIND_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        // BuildConfig generation is off by default in AGP 8, and the app reads BuildConfig.VERSION_NAME in the bridge
        // wire codec and the execution pipeline, plus VERSION_NAME / VERSION_CODE / APPLICATION_ID on the About screen.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Do not allow AGP to silently create an unsigned release artifact when signing inputs are absent.
tasks.configureEach {
    if (name == "packageRelease" || name == "signReleaseBundle") {
        doFirst {
            if (!releaseSigningConfigured) {
                throw GradleException(
                    "A release artifact must be signed. Configure all four CRAFTMIND_RELEASE_* environment variables as documented in RELEASE_CHECKLIST.md.",
                )
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(project(":bridge-protocol"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
