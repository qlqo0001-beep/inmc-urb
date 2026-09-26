package com.inmc.urb.listener

import com.inmc.urb.Urb
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent

/**
 * Re-probes the soft integrations when one of them enables after us.
 *
 * Load order is deliberately unconstrained (`load: OMIT` in paper-plugin.yml) to avoid the
 * dependency cycle that previously cost us ItemsAdder access. The cost of that is that an
 * integration may not be enabled yet when we first look for it - so we look again the moment
 * it announces itself, and the hook comes up without needing a reload.
 */
class IntegrationListener(private val urb: Urb) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginEnable(event: PluginEnableEvent) {
        val name = event.plugin.name
        if (name !in WATCHED) return
        urb.logger.info("$name 가 활성화되어 연동을 다시 확인합니다")
        urb.refreshIntegrations()
    }

    companion object {
        private val WATCHED = setOf(
            "Vault",
            "PlaceholderAPI",
            "MMOItems",
            "MythicLib",
            "WorldGuard",
            "Lands",
            "ItemsAdder",
            "Nexo",
            "Oraxen",
            "EcoItems",
        )
    }
}
