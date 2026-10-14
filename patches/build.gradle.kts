group = "local.dialer"
val dexVerifierClasspath = configurations.create("dexVerifierClasspath")
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")
val adbExtensionClasspath = configurations.create("adbExtensionClasspath")
adbExtensionClasspath.isTransitive = false
val repositorySlug = providers.environmentVariable("GITHUB_REPOSITORY").orNull

patches {
    about {
        name = "Google Phone call recording patches"
        description = "Conservative Google Phone call recording availability patches"
        source = repositorySlug?.let { "https://github.com/$it" } ?: "local workspace"
        author = providers.environmentVariable("GITHUB_REPOSITORY_OWNER").orNull ?: "Local project"
        contact = "na"
        website = "na"
        license = "GPLv3"
    }
}

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
    dexVerifierClasspath("com.android.tools.build:builder:9.1.0")
    adbExtensionClasspath("com.google.android:android:4.1.1.4")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

val adbExtensionClasses = layout.buildDirectory.dir("adb-extension/classes")
val adbExtensionDex = layout.buildDirectory.dir("adb-extension/dex")
val adbExtensionResources = layout.buildDirectory.dir("generated/adb-resources")
val compileAdbExtension = tasks.register<JavaCompile>("compileAdbExtension") {
    source(rootProject.fileTree("extensions/adb/src/main/java") { include("**/*.java") })
    classpath = adbExtensionClasspath
    destinationDirectory.set(adbExtensionClasses)
    options.release.set(8)
}
val dexAdbExtension = tasks.register<JavaExec>("dexAdbExtension") {
    dependsOn(compileAdbExtension)
    classpath = dexVerifierClasspath
    mainClass.set("com.android.tools.r8.D8")
    inputs.dir(adbExtensionClasses)
    outputs.dir(adbExtensionDex)
    doFirst {
        adbExtensionDex.get().asFile.mkdirs()
        args("--min-api", "30", "--lib", adbExtensionClasspath.singleFile.absolutePath,
            "--output", adbExtensionDex.get().asFile.absolutePath)
        args(fileTree(adbExtensionClasses).matching { include("**/*.class") }.files.sorted().map { it.absolutePath })
    }
}
val packageAdbExtension = tasks.register<Copy>("packageAdbExtension") {
    dependsOn(dexAdbExtension)
    from(adbExtensionDex) { include("classes.dex"); rename("classes.dex", "adb-audio.dex") }
    into(adbExtensionResources.map { it.dir("extensions") })
}
sourceSets["main"].resources.srcDir(adbExtensionResources)
tasks.processResources { dependsOn(packageAdbExtension) }

tasks.register<JavaExec>("generatePatchesList") {
    description = "Generate the Morphe patch catalog from the compiled bundle"
    dependsOn(tasks.build)
    classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
    mainClass.set("util.PatchListGeneratorKt")
}

tasks.named("publish") {
    dependsOn("generatePatchesList")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("originalApk", rootProject.file(
        providers.gradleProperty("verificationApk").getOrElse("GooglePhone-241-base.apk"),
    ).absolutePath)
}

tasks.register<JavaExec>("inspectApk") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("local.dialer.callrecording.VerifyApkKt")
    args(rootProject.file("GooglePhone-241-base.apk").absolutePath, rootProject.file("verification").absolutePath, "inspect")
    maxHeapSize = "3g"
}

tasks.register<JavaExec>("inspectClone") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("local.dialer.callrecording.InspectCloneKt")
    args(rootProject.file(".ignore/morphe.apk").absolutePath,
        rootProject.file("verification/clone").absolutePath)
    maxHeapSize = "3g"
}

tasks.register<JavaExec>("repairClone") {
    dependsOn(tasks.testClasses, "buildAndroid")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("local.dialer.callrecording.VerifyCloneKt")
    args(rootProject.file(".ignore/morphe.apk").absolutePath,
        layout.buildDirectory.file("libs/patches-${project.version}.mpp").get().asFile.absolutePath,
        rootProject.file("verification/clone").absolutePath,
        rootProject.file("dist/morphe-droidguard-fixed-unsigned.apk").absolutePath)
    maxHeapSize = "3g"
}

tasks.register<JavaExec>("verifyCloneDex") {
    dependsOn("repairClone")
    classpath = dexVerifierClasspath
    mainClass.set("com.android.tools.r8.D8")
    val output = rootProject.file("verification/clone/d8-roundtrip")
    doFirst { output.mkdirs() }
    args("--min-api", "30", "--output", output.absolutePath,
        rootProject.file("verification/clone/modified-class.dex").absolutePath)
}

tasks.register<JavaExec>("prepareAdbClone") {
    dependsOn(tasks.testClasses, "buildAndroid")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("local.dialer.callrecording.VerifyAdbCloneKt")
    args(rootProject.file(".ignore/morphe.apk").absolutePath,
        layout.buildDirectory.file("libs/patches-${project.version}.mpp").get().asFile.absolutePath,
        rootProject.file("verification/adb-audio/apk").absolutePath,
        rootProject.file("dist/morphe-adb-audio-unsigned.apk").absolutePath)
    maxHeapSize = "3g"
}

tasks.register<JavaExec>("verifyAdbCloneDex") {
    dependsOn("prepareAdbClone")
    classpath = dexVerifierClasspath
    mainClass.set("com.android.tools.r8.D8")
    val output = rootProject.file("verification/adb-audio/d8-roundtrip")
    doFirst {
        output.mkdirs()
        args("--min-api", "30", "--output", output.absolutePath)
        args(rootProject.fileTree("verification/adb-audio/apk") { include("classes*.dex") }.files.sorted().map { it.absolutePath })
    }
}

tasks.register<JavaExec>("verifyApk") {
    dependsOn(tasks.testClasses, "buildAndroid")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("local.dialer.callrecording.VerifyApkKt")
    args(rootProject.file("GooglePhone-241-base.apk").absolutePath, rootProject.file("verification").absolutePath,
        "patch", layout.buildDirectory.file("libs/patches-${project.version}.mpp").get().asFile.absolutePath)
    maxHeapSize = "3g"
}

tasks.register<JavaExec>("verifyDex") {
    dependsOn("verifyApk")
    classpath = dexVerifierClasspath
    mainClass.set("com.android.tools.r8.D8")
    val output = rootProject.file("verification/d8-roundtrip")
    doFirst { output.mkdirs() }
    args("--min-api", "26", "--output", output.absolutePath,
        rootProject.file("verification/patched-dex/modified-class.dex").absolutePath)
}
