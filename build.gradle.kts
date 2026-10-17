// Root build file — plugin versions are declared once here, applied in :app.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    // KSP must match the Kotlin version exactly.
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}
