plugins {
    id("com.android.application")
}

android {
    namespace = "com.octavio.tinta"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.octavio.tinta"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // App personal: se firma con la clave de depuración para instalarla por adb.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Test opcional contra una nota real: ./gradlew testDebugUnitTest -PjnotesSample=/ruta/a/nota.Jnotes
tasks.withType<Test>().configureEach {
    systemProperty("jnotes.sample", providers.gradleProperty("jnotesSample").getOrElse(""))
}

dependencies {
    val ink = "1.0.0"
    implementation("androidx.ink:ink-authoring:$ink")
    implementation("androidx.ink:ink-brush:$ink")
    implementation("androidx.ink:ink-geometry:$ink")
    implementation("androidx.ink:ink-rendering:$ink")
    implementation("androidx.ink:ink-strokes:$ink")
    implementation("androidx.ink:ink-storage:$ink")
    implementation("androidx.input:input-motionprediction:1.0.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // Reconocer la letra para buscar en lo escrito (el modelo del idioma se baja una vez).
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")

    testImplementation("junit:junit:4.13.2")
    // org.json de Android no funciona en los tests de la JVM: se usa la biblioteca original.
    testImplementation("org.json:json:20240303")
}
