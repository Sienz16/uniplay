plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.uniplay.androidauto"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        // raw/privkey is the public Android Auto head-unit TLS key the protocol requires,
        // not a secret of ours.
        disable += "PackagedPrivateKey"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)
    testImplementation(libs.junit)
}
