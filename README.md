# Ytd

## JitPack

Publish a Git tag, then consume the library module from JitPack:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

```kotlin
dependencies {
    implementation("com.github.mzgs.Ytd:ytdlib:<tag>")
}
```

JitPack builds only the `ytdlib` module through `jitpack.yml` using Java 17.
