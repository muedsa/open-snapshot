val osName: String = System.getProperty("os.name")
val targetOs = when {
    osName == "Mac OS X" -> "macos"
    osName.startsWith("Win") -> "windows"
    osName.startsWith("Linux") -> "linux"
    else -> error("Unsupported OS: $osName")
}
val targetArch = when (val osArch: String = System.getProperty("os.arch")) {
    "x86_64", "amd64" -> "x64"
    "aarch64" -> "arm64"
    else -> error("Unsupported arch: $osArch")
}
val target = "$targetOs-$targetArch"

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
}

group = "com.muedsa.snapshot"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

// 文档随 JAR 一起发布；容器运行时不依赖仓库目录或额外挂载。
tasks.processResources {
    from("docs") {
        include("openapi.yaml", "ai-guide.md")
        into("public-docs")
    }
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.rateLimit)
    implementation(libs.logback.classic)

    implementation(libs.snapshot.parser)
    implementation(versionCatalog.findLibrary("skiko-$targetOs-$targetArch").get())

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}
