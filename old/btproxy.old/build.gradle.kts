plugins {
    id("com.android.application") version "8.1.4" apply false
    kotlin("android") version "1.9.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}