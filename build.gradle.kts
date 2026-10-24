plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

repositories {
    maven("https://artifacts.openmicroscopy.org/artifactory/ome.releases/")
}

qupathExtension {
    name = "qupath-extension-calibrated-region-export"
    group = "io.github.koalalive.qupath"
    version = "1.2.0"
    description = "Create a calibrated region of interest and export it as OME-TIFF at native resolution"
    automaticModule = "io.github.koalalive.qupath.calibratedregion"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.qupath.ext.bioformats)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    testImplementation(libs.bundles.qupath)
    testImplementation(libs.qupath.ext.bioformats) {
        isTransitive = false
    }
    testImplementation("ome:formats-bsd:8.4.0") {
        exclude(group = "cisd", module = "jhdf5")
    }
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform)
}
