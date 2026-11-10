plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 휴대폰 앱: 차와 같은 계정으로 로그인해 사고 안내를 실시간으로 이어 받고(game/bridge/sync_server.py), 각도별 사진을 코칭한다
android {
    namespace = "com.askthecar" // shared/Theme.kt 의 R.font.asta_sans 가 com.askthecar.R 을 씀
    compileSdk = 35

    defaultConfig {
        applicationId = "com.askthecar.phone"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // 동기화 서버 기본 주소: 에뮬레이터 → PC(10.0.2.2). 실제 휴대폰은 앱에서 PC 주소를 넣음
        buildConfigField("String", "SYNC_URL", "\"http://10.0.2.2:8767\"")
    }
    // shortcut: 제출 전까지 디버그 키로 서명(서명 없이는 release APK 를 설치할 수 없음). 앱마켓 배포 때 전용 키로 교체
    buildTypes { release { signingConfig = signingConfigs.getByName("debug") } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // 차 앱(:app)과 같이 쓰는 코드·글꼴 (디자인 토큰, 넘기기 형식)
    sourceSets["main"].java.srcDir("../shared/java")
    sourceSets["main"].res.srcDir("../shared/res")
    // 3D 재현(crash3d)·라이선스 원문은 차 앱과 같은 폴더를 쓴다. 차량 인식 모델은 차에서만 쓰므로 뺀다
    sourceSets["main"].assets.srcDir("../app/src/main/assets")
    // 기본 제외 규칙(.* = 숨김 파일·폴더, 작업 도구의 .omc 등)을 그대로 두고 모델만 더 뺀다. 직접 쓰면 기본 규칙이 사라져 숨김 파일이 APK 에 들어갔음
    androidResources { ignoreAssetsPattern = "!efficientdet_lite0.tflite:!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~" }
}

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.webkit:webkit:1.12.1") // WebViewAssetLoader: 3D 화면(three.js 모듈)을 https 로 앱 안에서 띄움
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // 안드로이드 org.json 은 단위 테스트에선 빈 껍데기
}
