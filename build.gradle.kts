plugins { java }
group = "fr.ivalona"
version = "2.0.0"
repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/"); maven("https://jitpack.io"); maven("https://repo.extendedclip.com/releases/") }
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.87-stable")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7")
    compileOnly("me.clip:placeholderapi:2.11.7")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
