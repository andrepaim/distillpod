import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val firebaseConfigPath = providers.gradleProperty("distillpodFirebaseConfig").orNull
val firebaseConfig = firebaseConfigPath?.let { path ->
    @Suppress("UNCHECKED_CAST")
    JsonSlurper().parse(file(path)) as Map<String, Any>
}
@Suppress("UNCHECKED_CAST")
val firebaseClient = (firebaseConfig?.get("client") as? List<Map<String, Any>>)?.firstOrNull {
    ((it["client_info"] as? Map<*, *>)?.get("android_client_info") as? Map<*, *>)?.get("package_name") == "dev.distillpod.android"
}
fun clientString(key: String): String = when(key) {
    "bucket" -> (firebaseConfig?.get("project_info") as? Map<*, *>)?.get("storage_bucket") as? String ?: ""
    "project" -> (firebaseConfig?.get("project_info") as? Map<*, *>)?.get("project_id") as? String ?: ""
    "app" -> (firebaseClient?.get("client_info") as? Map<*, *>)?.get("mobilesdk_app_id") as? String ?: ""
    "api" -> ((firebaseClient?.get("api_key") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("current_key") as? String ?: ""
    "web" -> ((firebaseClient?.get("oauth_client") as? List<*>)?.firstOrNull { (it as? Map<*, *>)?.get("client_type") == 3 } as? Map<*, *>)?.get("client_id") as? String ?: ""
    else -> ""
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
android {
    namespace = "dev.distillpod.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    defaultConfig {
        // Owner-selected Spark deployment: paid content storage must remain disabled.
        resValue("bool", "sync_content_enabled", "false")
        for ((resource, key) in listOf("sync_storage_bucket" to "bucket", "sync_project_id" to "project", "sync_app_id" to "app", "sync_api_key" to "api", "sync_web_client_id" to "web")) {
            val value = clientString(key)
            require(value.matches(Regex("[A-Za-z0-9_.:/-]*"))) { "Invalid Firebase client configuration" }
            resValue("string", resource, value)
        }
        val voiceEnabled = providers.gradleProperty("distillpodVoiceEnabled").getOrElse("false").toBooleanStrict()
        val voiceBroker = providers.gradleProperty("distillpodVoiceBroker").getOrElse("")
        require(voiceBroker.isEmpty() || voiceBroker.matches(Regex("https://[A-Za-z0-9./:_-]+")))
        resValue("bool", "voice_enabled", voiceEnabled.toString())
        resValue("string", "voice_broker_url", voiceBroker)
        manifestPlaceholders["voiceEnabled"] = voiceEnabled.toString()
        manifestPlaceholders["voiceMinCarApi"] = if (voiceEnabled) "8" else "1"
        manifestPlaceholders["automotiveDescriptor"] = if (voiceEnabled) "@xml/automotive_voice_desc" else "@xml/automotive_app_desc"
        applicationId = "dev.distillpod.android"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "dev.distillpod.android.IsolatedTestRunner"
        versionCode = providers.gradleProperty("distillpodVersionCode").get().toInt()
        versionName = providers.gradleProperty("distillpodVersionName").get()
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildTypes.getByName("release") { isDebuggable = false }
    sourceSets.getByName("androidTest").assets.srcDir(providers.gradleProperty("distillpodFixtureDir").getOrElse("src/androidTest/assets"))
    sourceSets.getByName("androidTest").assets.srcDir("schemas")
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
    sourceSets.getByName("test").resources.srcDir("schemas")
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.car.app:app:1.7.0")
    implementation(platform("com.google.firebase:firebase-bom:34.8.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.nimbusds:nimbus-jose-jwt:10.0.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.room:room-testing:2.7.2")
    testImplementation("androidx.work:work-testing:2.10.1")
}
