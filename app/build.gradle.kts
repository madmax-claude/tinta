import java.util.Properties

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

    // Clave de subida a Google Play: fuera del repositorio, en ~/tinta-claves/subida.properties
    // (storeFile, storePassword, keyAlias, keyPassword). Play vuelve a firmar la app con su propia
    // clave antes de distribuirla. Sin ese archivo, la release se firma con la de depuración.
    val uploadFile = File(System.getProperty("user.home"), "tinta-claves/subida.properties")
    val upload: Properties? = if (!uploadFile.isFile) null else
        Properties().also { p -> uploadFile.inputStream().use { p.load(it) } }
    signingConfigs {
        if (upload != null) {
            create("subida") {
                storeFile = file(upload.getProperty("storeFile"))
                storePassword = upload.getProperty("storePassword")
                keyAlias = upload.getProperty("keyAlias")
                keyPassword = upload.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Sin R8: achicando, borraba clases que se buscan por reflexión (WorkDatabase de
            // WorkManager, que trae ML Kit) y la app se cerraba al abrir. Así es el mismo código
            // que la versión de depuración, que es la que se prueba.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (upload != null) "subida" else "debug")
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
    // Y contra una carpeta entera de notas: -PjnotesDir=/ruta/a/la/carpeta
    systemProperty("jnotes.dir", providers.gradleProperty("jnotesDir").getOrElse(""))
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
