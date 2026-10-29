plugins {
    kotlin("jvm")
}

val kotlin_version: String ="2.4.21"
group = "read"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlin_version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // 规则相关
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("cn.wanghaomiao:JsoupXpath:2.5.3")
    implementation("com.jayway.jsonpath:json-path:3.0.0")

    // json
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.+")

    //log
    implementation("org.slf4j:slf4j-api:2.0.16")
    //implementation("org.slf4j:slf4j-simple:2.0.16")

    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.6.1")
    implementation("com.julienviet:retrofit-vertx:1.1.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.1")

    //加解密类库
    implementation("cn.hutool:hutool-crypto:5.8.22")

    //js
   // implementation(files("src/lib/rhino-1.7.14.jar"))
    // 1.9.2：修复 toFixed() DoS（GHSA-3w8q-xq97-5j7x）；VMBridge 已被移除，改用 Context.enter(Context)（见 RhinoContextElement）
    implementation("org.mozilla:rhino:1.9.2")
    implementation(files("src/lib/xmlpull-1.1.3.1.jar"))
    implementation("org.apache.commons:commons-text:1.13.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    implementation("com.github.liuyueyi:quick-transfer-core:0.2.16")
    testImplementation(kotlin("test"))
}
sourceSets {
    main {
        java.srcDir("src/main/kotlin")
    }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "768m"
    maxParallelForks = 1
}
kotlin {
    jvmToolchain(21)
}
