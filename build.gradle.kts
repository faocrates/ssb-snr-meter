/*
 SSB SNR Meter - A software tool for estimating the audio-band signal-to-noise ratio of live receiver audio.
 Copyright (C) 2026  Dr Christos Bohoris

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}
group = "uk.co.connectina"
version = "1.0.0"
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
javafx { version = "25"; modules = listOf("javafx.controls", "javafx.graphics") }
// ✅ Configurable JavaFX library path
val javafxLibPath: String = "../ssb-snr-meter-jfx-lib"
application {
    applicationName = "ssb-snr-meter"
    // Define the main class for the application.
    mainClass.set("uk.co.connectina.ssbsnr.Main")

    // ✅ Configure default JVM args here
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8",
        "--enable-native-access=ALL-UNNAMED",
        "--enable-native-access=javafx.graphics",
        "--module-path", javafxLibPath,
        "--add-modules", "javafx.controls,javafx.graphics")
}
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test {
    useJUnitPlatform()
}