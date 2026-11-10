import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 비밀 값은 루트의 secrets.properties 에만 둔다 (git에 올리지 않음). anthropic.apiKey는 여기서 읽지 않는다.
val local = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use(::load)
}

android {
    namespace = "com.askthecar"
    compileSdk = 35
    useLibrary("android.car") // AAOS CarPropertyManager (속도·기어)

    defaultConfig {
        applicationId = "com.askthecar"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") } // 실차(arm64)·에뮬레이터(x86_64)만 — MediaPipe 네이티브 라이브러리 크기 줄이기
        // -Ppublic: GitHub 공개 APK 는 relay·Pleos 키 없이 (APK 를 풀면 보임). 경위서는 사실 목록 그대로, 안내 음성은 키를 넣어 빌드해야 나옴
        val relay = !project.hasProperty("public")
        buildConfigField("String", "RELAY_URL", "\"${if (relay) local.getProperty("relay.url", "") else ""}\"")
        buildConfigField("String", "RELAY_TOKEN", "\"${if (relay) local.getProperty("relay.token", "") else ""}\"")
        // 차·휴대폰 동기화 서버 주소: secrets.properties 의 sync.url (12/03 전 https 실제 서버). release 는 없으면 빈 값 = 휴대폰 연동 안 함
        buildConfigField("String", "SYNC_URL", "\"${local.getProperty("sync.url", "")}\"")
        buildConfigField("String", "PLEOS_CLIENT_ID", "\"${if (relay) local.getProperty("pleos.clientId", "") else ""}\"")
        buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"${if (relay) local.getProperty("pleos.clientSecret", "") else ""}\"")
    }
    buildTypes {
        // 시연(debug)만: 서버 주소가 없으면 에뮬레이터에서 본 PC 의 시제품 서버(game/bridge/sync_server.py)
        debug { if (!local.containsKey("sync.url")) buildConfigField("String", "SYNC_URL", "\"http://10.0.2.2:8767\"") }
        // shortcut: 제출 전까지 디버그 키로 서명(설치·시험용). 앱마켓 배포 때 전용 키로 교체
        release { signingConfig = signingConfigs.getByName("debug") }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // 휴대폰 앱(:phone)과 같이 쓰는 코드·글꼴 (디자인 토큰, 넘기기 형식)
    sourceSets["main"].java.srcDir("../shared/java")
    sourceSets["main"].res.srcDir("../shared/res")
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("com.google.mediapipe:tasks-vision:1.2.0") // 영상 차량 인식 (Apache-2.0)
    implementation("androidx.webkit:webkit:1.12.1") // WebViewAssetLoader: 3D 화면(three.js 모듈)을 https 로 앱 안에서 띄움
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // 단위 테스트에서 넘기기 형식(JSON) 확인 — 안드로이드 org.json 은 테스트에선 빈 껍데기
}
