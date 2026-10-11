// ================================================================
//  Superb Horizons — NeoForge 1.21.1 版
//  ビルド: gradlew build   / 動作確認: gradlew runClient
// ================================================================
plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.+"
}

val modId = "superbhorizons"
group = "com.velleagle.superbhorizons"
version = "1.0.0"

base {
    archivesName.set("superbhorizons-neoforge-1.21.1")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

neoForge {
    version = "21.1.257"

    parchment {
        mappingsVersion = "2024.11.17"
        minecraftVersion = "1.21.1"
    }

    mods {
        create(modId) {
            sourceSet(sourceSets["main"])
        }
    }

    runs {
        create("client") {
            client()
        }
        create("server") {
            server()
        }
    }
}

repositories {
    mavenCentral()
    // Superb Warfare 本体
    exclusiveContent {
        forRepository { maven("https://cursemaven.com") }
        filter { includeGroup("curse.maven") }
    }
    // Distant Horizons (API と本体)
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") }
        filter { includeGroup("maven.modrinth") }
    }
    // Kotlin for Forge（Superb Warfare の動作に必要）
    maven("https://thedarkcolour.github.io/KotlinForForge/") {
        content { includeGroup("thedarkcolour") }
    }
}

dependencies {
    // Distant Horizons の API（コンパイル用。1.20.1 版と同じ 4.0.1。実行時は DH 本体に同梱されているものが使われる）
    compileOnly("maven.modrinth:distanthorizonsapi:4.0.1")

    // ---- ここから下は runClient で動作確認するためのもの（完成した jar には含まれません）----

    // Superb Warfare 0.8.9.2 (NeoForge 1.21.1)
    // Superb Horizons は SBW のクラスを名前で呼び出すため、コンパイルには不要です
    runtimeOnly("curse.maven:superb-warfare-1218165:9019824")

    // Superb Warfare の動作に必要
    runtimeOnly("thedarkcolour:kotlinforforge-neoforge:5.10.0")

    // Distant Horizons 3.3.3 (NeoForge 1.21.1)
    runtimeOnly("maven.modrinth:distanthorizons:3.3.3-1.21.1")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
