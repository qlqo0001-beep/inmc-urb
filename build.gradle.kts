plugins {
    id("inmc.paper-plugin")
}

group = "com.inmc.urb"
version = "1.0.0"

inmc {
    paper = "26.2"
    pluginName = "inmc-urb"
}

dependencies {
    compileOnly(libs.placeholderapi) { isTransitive = false }
    compileOnly(libs.vault.api) { isTransitive = false }
    compileOnly(libs.worldguard.bukkit) { isTransitive = false }
    compileOnly(libs.worldguard.core) { isTransitive = false }
    compileOnly(libs.worldedit.core) { isTransitive = false }
    compileOnly(libs.worldedit.bukkit) { isTransitive = false }
    // MMOItems / MythicLib 는 100% 리플렉션.
}
