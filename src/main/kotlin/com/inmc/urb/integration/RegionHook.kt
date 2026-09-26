package com.inmc.urb.integration

import com.inmc.urb.box.ListMode
import com.inmc.urb.box.RandomBox
import kr.inmc.core.integration.PluginClasses
import org.bukkit.Bukkit
import org.bukkit.Location
import java.lang.reflect.Method
import java.util.logging.Logger

/**
 * Region filtering through WorldGuard and Lands, both optional.
 *
 * WorldGuard is compile-only (its API has been stable for years). Lands is reached purely by
 * reflection because its API method names have moved between major versions, and a soft
 * integration should not be able to break the build.
 *
 * Filter semantics, fixed from the original plugin: an empty list means "no filtering", a
 * whitelist only permits locations *inside* one of the listed regions, and a blacklist
 * rejects them. The original returned early in a way that let a whitelist miss fall through
 * and spawn anyway.
 */
class RegionHook(private val logger: Logger) {

    private var worldGuardEnabled = false
    private var landsEnabled = false

    // Lands, via reflection: LandsIntegration.of(plugin) -> getArea/getAreaByLoc(Location)
    private var landsApi: Any? = null
    private var landsGetArea: Method? = null
    private var areaGetName: Method? = null

    fun setup(plugin: org.bukkit.plugin.Plugin) {
        setupWorldGuard()
        setupLands(plugin)
    }

    private fun setupWorldGuard() {
        worldGuardEnabled = false
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            logger.info("WorldGuard 미설치 - region 필터는 무시됩니다")
            return
        }
        try {
            PluginClasses.require("WorldGuard", "com.sk89q.worldguard.WorldGuard")
            PluginClasses.require("WorldGuard", "com.sk89q.worldedit.bukkit.BukkitAdapter")
            worldGuardEnabled = true
            logger.info("WorldGuard 연동 활성화")
        } catch (t: Throwable) {
            logger.warning("WorldGuard 연동 실패: ${t.message}")
        }
    }

    private fun setupLands(plugin: org.bukkit.plugin.Plugin) {
        landsEnabled = false
        if (!Bukkit.getPluginManager().isPluginEnabled("Lands")) {
            logger.info("Lands 미설치 - land 필터는 무시됩니다")
            return
        }
        try {
            val integrationClass = PluginClasses.require("Lands", "me.angeschossen.lands.api.LandsIntegration")
            val of = integrationClass.methods.first {
                it.name == "of" && it.parameterCount == 1
            }
            landsApi = of.invoke(null, plugin)

            landsGetArea = integrationClass.methods.firstOrNull {
                (it.name == "getArea" || it.name == "getAreaByLoc") &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Location::class.java
            } ?: error("getArea(Location) 를 찾을 수 없습니다")

            areaGetName = landsGetArea!!.returnType.methods.firstOrNull {
                it.name == "getName" && it.parameterCount == 0
            }

            landsEnabled = true
            logger.info("Lands 연동 활성화")
        } catch (t: Throwable) {
            landsApi = null
            logger.warning("Lands 연동 실패 (버전 불일치일 수 있습니다): ${t.message}")
        }
    }

    /** True when [box] is allowed to spawn at [location] under both filters. */
    fun isAllowed(box: RandomBox, location: Location): Boolean =
        passes(box.regionMode, box.regionList) { worldGuardRegions(location) } &&
            passes(box.landMode, box.landList) { landsAreas(location) }

    private inline fun passes(mode: ListMode, list: List<String>, regions: () -> Set<String>): Boolean {
        if (list.isEmpty()) return true
        val present = regions()
        if (present.isEmpty()) return mode == ListMode.BLACKLIST
        val hit = list.any { configured -> present.any { it.equals(configured, ignoreCase = true) } }
        return if (mode == ListMode.WHITELIST) hit else !hit
    }

    private fun worldGuardRegions(location: Location): Set<String> {
        if (!worldGuardEnabled) return emptySet()
        return try {
            val container = com.sk89q.worldguard.WorldGuard.getInstance()
                .platform.regionContainer
            val query = container.createQuery()
            val adapted = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(location)
            query.getApplicableRegions(adapted).regions.mapTo(HashSet()) { it.id }
        } catch (t: Throwable) {
            logger.warning("WorldGuard 지역 조회 실패: ${t.message}")
            emptySet()
        }
    }

    private fun landsAreas(location: Location): Set<String> {
        if (!landsEnabled) return emptySet()
        return try {
            val area = landsGetArea?.invoke(landsApi, location) ?: return emptySet()
            val name = areaGetName?.invoke(area) as? String ?: return emptySet()
            setOf(name)
        } catch (t: Throwable) {
            emptySet()
        }
    }
}
