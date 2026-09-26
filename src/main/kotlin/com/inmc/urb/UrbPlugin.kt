package com.inmc.urb

import com.inmc.urb.command.UrbCommand
import com.inmc.urb.listener.BoxInteractListener
import com.inmc.urb.listener.BoxProtectListener
import com.inmc.urb.listener.IntegrationListener
import kr.inmc.core.listener.MenuListener
import com.inmc.urb.listener.PlayerSessionListener
import com.inmc.urb.scheduler.Ticker
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/**
 * INMC Ultimate Random Box.
 *
 * A rewrite of UltimateRandomBox on Paper 26.2 with no required plugin dependencies. Vault,
 * PlaceholderAPI, MMOItems/MythicLib, WorldGuard and Lands are all optional; the plugin boots
 * and works with none of them installed.
 */
class UrbPlugin : JavaPlugin() {

    lateinit var urb: Urb
        private set

    private lateinit var ticker: Ticker

    override fun onEnable() {
        urb = Urb(this)
        ticker = Ticker(urb)

        // Commands register through the lifecycle manager, which must be called from onEnable.
        UrbCommand(urb).register(this)

        Bukkit.getPluginManager().let { pm ->
            pm.registerEvents(MenuListener(urb), this)
            pm.registerEvents(BoxInteractListener(urb), this)
            pm.registerEvents(BoxProtectListener(urb), this)
            pm.registerEvents(PlayerSessionListener(urb), this)
            pm.registerEvents(IntegrationListener(urb), this)
        }

        registerSignalCatalog()
        // 커스텀아이템에 "상자 캡슐·열쇠" 역할을 내놓는다. 목록은 람다라 나중에 만든 상자도 보인다.
        for (role in com.inmc.urb.box.UrbRoles.roles(urb)) kr.inmc.core.integration.ItemRoles.register(role)
        kr.inmc.core.integration.ItemRoles.listen(com.inmc.urb.box.UrbRoles.OWNER) { role ->
            if (role == null || role in com.inmc.urb.box.UrbRoles.ALL) com.inmc.urb.box.UrbRoles.sync(urb)
        }

        // Config, boxes and persisted world state all load off the main thread; the ticker
        // and every listener no-op until `urb.ready` flips.
        urb.enable {
            logger.info("inmc-urb 활성화 완료")
        }
        ticker.start()
    }

    /**
     * "이 플러그인이 쏘는 신호에는 이런 값이 온다" 를 core 에 알린다.
     *
     * 업적 같은 소비자가 편집 화면에 **진짜 상자 목록**을 그릴 수 있게 된다. 이게 없으면
     * 관리자가 상자 이름을 손으로 치고, 오타가 나면 그 조건이 조용히 영영 안 맞는다.
     *
     * 목록을 람다로 넘기는 것이 중요하다 — 지금은 아직 비어 있고(비동기 적재), 관리자가
     * 나중에 만든 상자도 나와야 한다.
     */
    private fun registerSignalCatalog() {
        kr.inmc.core.event.SignalCatalog.register(
            source = SOURCE, type = "open",
            subjectLabel = "상자",
            subjects = { urb.boxes.all().map { it.name to it.name } },
            dataKeys = listOf("rewards" to "받은 보상 수"),
            description = "랜덤박스를 열었을 때",
        )
    }

    override fun onDisable() {
        if (!::urb.isInitialized) return
        // 람다가 이 플러그인의 객체와 클래스로더를 붙들고 있다.
        kr.inmc.core.event.SignalCatalog.unregisterAll(SOURCE)
        kr.inmc.core.integration.ItemRoles.unregisterAll(com.inmc.urb.box.UrbRoles.OWNER)
        ticker.stop()
        urb.shutdown()
        logger.info("inmc-urb 비활성화")
    }

    private companion object {
        /** core 의 신호 목록에서 이 플러그인을 가리키는 이름. */
        const val SOURCE = "urb"
    }
}
