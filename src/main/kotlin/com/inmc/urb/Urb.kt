package com.inmc.urb

import com.inmc.urb.box.BoxStatsNameImport
import com.inmc.urb.util.Ph
import kr.inmc.core.CorePlugin
import kr.inmc.core.InmcHost
import com.inmc.urb.box.BoxOpenService
import com.inmc.urb.box.BoxRegistry
import com.inmc.urb.box.BoxSpawnService
import com.inmc.urb.box.BoxStats
import com.inmc.urb.box.NoticeLog
import com.inmc.urb.box.OpenRecords
import com.inmc.urb.box.TrackingService
import kr.inmc.core.config.ConfigService
import com.inmc.urb.config.Messages
import com.inmc.urb.config.PluginConfig
import kr.inmc.core.input.ChatPrompt
import kr.inmc.core.integration.CustomItemHook
import com.inmc.urb.integration.DiscordHook
import kr.inmc.core.integration.EconomyHook
import kr.inmc.core.integration.MMOItemsHook
import com.inmc.urb.integration.PapiHook
import com.inmc.urb.integration.RegionHook
import kr.inmc.core.item.ItemMatcher
import kr.inmc.core.item.ItemResolver
import com.inmc.urb.loot.SafeLocationFinder
import com.inmc.urb.visual.BossBars
import com.inmc.urb.visual.BoxVisuals
import kr.inmc.core.util.Placeholders
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin

/**
 * Service locator wiring the plugin together.
 *
 * Everything is constructed once and reached through `urb.<service>`; a reload swaps the two
 * volatile config objects and re-reads the box files, but never rebuilds the services, so
 * listeners and menus never hold a stale reference.
 */
class Urb(override val plugin: JavaPlugin) : InmcHost {

    val logger: java.util.logging.Logger = plugin.logger
    override val io = ConfigService(plugin)

    /** core 의 ChatPrompt·MenuListener 가 메시지를 보낼 때 쓰는 통로. */
    override fun tell(target: CommandSender, key: String, ph: Placeholders?) =
        messages.send(target, key, ph as? Ph)

    // --- integrations (all optional) -------------------------------------------
    val mmoItems = MMOItemsHook(logger)
    val customItems = CustomItemHook(logger)
    val economy = EconomyHook(logger)
    val regions = RegionHook(logger)
    val discord = DiscordHook(logger)
    val papi = PapiHook(this)

    // --- item layer -------------------------------------------------------------
    val itemResolver = ItemResolver(mmoItems, customItems, logger)
    val itemMatcher = ItemMatcher(mmoItems, customItems)

    // --- configuration ----------------------------------------------------------
    @Volatile
    var config: PluginConfig = PluginConfig.from(YamlConfiguration())

    @Volatile
    var messages: Messages = Messages.from(YamlConfiguration())

    // --- domain services --------------------------------------------------------
    val finder = SafeLocationFinder()
    val boxes = BoxRegistry(io, itemMatcher, logger)
    val spawns = BoxSpawnService(this)
    val opens = BoxOpenService(this)
    val notices = NoticeLog(this)
    val openRecords = OpenRecords(this)
    val stats = BoxStats(this)
    val simulations = com.inmc.urb.box.SimulationEngine(this)
    val visuals = BoxVisuals(this)
    val bossBars = BossBars()
    val blockPlacer = kr.inmc.core.item.BlockPlacer(customItems, logger)
    val prompts = ChatPrompt(this)
    val tracking = TrackingService(this)

    /** False until persisted state has finished loading; interactions are held off until then. */
    @Volatile
    var ready: Boolean = false
        private set

    /**
     * `stats.yml` 의 `players.<uuid>.name` 을 core 의 `profile` 로 옮긴다.
     *
     * `enable` 에서만 부른다. 몇 번을 돌려도 결과가 같지만(더 오래된 기록은 항상 지므로)
     * 리로드마다 도는 것은 헛일이다.
     */
    private fun importPlayerNames() {
        val store = CorePlugin.get().players
        io.async({
            val file = io.file("data", "stats.yml")
            if (file.exists()) BoxStatsNameImport.parse(io.load(file)) else emptyList()
        }) { entries ->
            if (entries.isEmpty()) return@async
            val written = BoxStatsNameImport.apply(store, entries)
            if (written > 0) logger.info("플레이어 이름 " + written + "건을 inmc-core 로 옮겼습니다")
        }
    }

    /** Full startup: config, integrations, boxes, then persisted world state. */
    fun enable(then: () -> Unit) {
        reload { boxCount ->
            logger.info("상자 $boxCount 개를 불러왔습니다")
            spawns.loadState {
                notices.load {
                    openRecords.load {
                        stats.load {
                            // core 의 저장소가 실제로 준비된 뒤에야 ready 를 올린다.
                            // load: BEFORE 는 플러그인 enable 순서만 정하지 저장소 내용이
                            // 준비됐다는 뜻이 아니다 — 콜백이 첫 틱에야 돈다.
                            CorePlugin.get().players.whenReady {
                                importPlayerNames()
                                ready = true
                                // Permanent installations re-assert themselves on every boot.
                                boxes.all().forEach { spawns.ensurePermanent(it) }
                                // 종료 정리를 못 한 채 켜졌으면(크래시) 남아 있던 비고정 상자를 추적해 지운다.
                                // 깨끗한 종료 뒤에는 지울 것이 없어 조용하다. 메인 스레드 1회.
                                spawns.sweepStaleBoxes()
                                then()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Re-reads config.yml, messages.yml and every box file. Integration hooks are refreshed
     * too, so installing Vault and running `/urb reload` is enough to pick it up.
     */
    fun reload(then: (Int) -> Unit) {
        io.async({
            val configFile = io.file("config.yml")
            val messagesFile = io.file("messages.yml")
            io.copyDefault("config.yml", configFile)
            io.copyDefault("messages.yml", messagesFile)
            io.copyDefault("boxes/example.yml", io.file("boxes", "example.yml"))
            io.load(configFile) to io.load(messagesFile)
        }) { (rawConfig, rawMessages) ->
            config = PluginConfig.from(rawConfig)
            messages = Messages.from(rawMessages)
            discord.configure(config.discord)
            setupIntegrations()
            // Integrations may have come or gone, so cached menu icons are no longer trustworthy.
            itemResolver.clearIconCache()
            boxes.loadAll(config.defaults) { count ->
                // Every box object is replaced here, so any menu still open is now editing an
                // orphan: it would keep showing edits that never reach disk. Closing them is
                // the only way to make that impossible rather than merely detectable.
                closeOpenMenus()
                // 커스텀아이템이 있으면 캡슐·열쇠를 그 역할에 맞춘다(처음이면 옮긴다).
                com.inmc.urb.box.UrbRoles.sync(this)
                if (ready) boxes.all().forEach { spawns.ensurePermanent(it) }
                then(count)
            }
        }
    }

    /**
     * Re-runs integration discovery without touching config or boxes. Called when one of the
     * optional plugins enables after us (see [com.inmc.urb.listener.IntegrationListener]).
     */
    fun refreshIntegrations() {
        setupIntegrations()
        itemResolver.clearIconCache()
    }

    /**
     * Shuts every open plugin menu. Each menu's own close handler still runs, so a loot window
     * hands its contents back and a half-finished reward edit returns the staged items.
     */
    private fun closeOpenMenus() {
        var closed = 0
        for (player in org.bukkit.Bukkit.getOnlinePlayers()) {
            // core 가 소유한 화면(공용 확인창·설정 화면)도 잡아야 하므로 owner 로 가려낸다.
            val holder = player.openInventory.topInventory.holder
            if (holder !is kr.inmc.core.gui.Menu || holder.owner !== this) continue
            player.closeInventory()
            messages.send(player, "reload-menu-closed")
            closed++
        }
        if (closed > 0) logger.info("설정을 다시 읽어 열려 있던 GUI ${closed}개를 닫았습니다")
    }

    /** Must run on the main thread - all of these touch the plugin manager. */
    private fun setupIntegrations() {
        mmoItems.setup()
        customItems.setup()
        economy.setup()
        regions.setup(plugin)
        papi.setup()
    }

    fun shutdown() {
        papi.teardown()
        // Runs any reveal animation's parked hand-over first, so its loot is recorded before
        // the flushes below write the final state.
        opens.cancelAll()
        simulations.cancel()
        boxes.flushDirtyBlocking()
        notices.flush()
        openRecords.flushBlocking()
        stats.flushBlocking()
        visuals.removeAll()
        bossBars.clear()
        // 고정 설치 빼고 전부 치운다 — 놓인 것(원래 블록 복원)과 대기열. `cleanup-all` 이
        // 켜져 있으면 고정 설치까지 치운다.
        spawns.removeAllForShutdown(includePermanent = config.cleanupAllOnShutdown)
        spawns.flushStateBlocking()
        io.shutdown()
    }
}
