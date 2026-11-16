plugins { id("com.android.application") }

android {
    namespace = "dev.seferaki.bridge"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig { applicationId = "dev.seferaki.bridge"; minSdk = 29; targetSdk = 37; versionCode = 2; versionName = "1.0.0" }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
}
