plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}

check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
    "LiteRT-LM 0.13.1 requires JDK 21 for JVM checks. Set JAVA_HOME to a JDK 21 installation."
}
