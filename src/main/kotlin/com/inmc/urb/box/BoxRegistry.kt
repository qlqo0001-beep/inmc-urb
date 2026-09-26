package com.inmc.urb.box

import com.inmc.urb.config.BoxDefaults
import kr.inmc.core.config.ConfigService
import kr.inmc.core.item.ItemMatcher
import kr.inmc.core.store.YamlFolder
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * In-memory cache of every box, backed by one `boxes/<name>.yml` per box (spec §94).
 *
 * Nothing here reads from disk on a lookup - the map is the source of truth at runtime and
 * writes are queued to [ConfigService]'s worker thread. Saves are debounced through a dirty
 * set so a GUI session that flips ten toggles still produces one file write.
 */
class BoxRegistry(
    private val io: ConfigService,
    private val matcher: ItemMatcher,
    private val logger: Logger,
) {

    private val boxes = ConcurrentHashMap<String, RandomBox>()

    /** 폴더 저장 경로. dirty 집합과 파일 쓰기를 여기에 맡긴다. */
    private val files = YamlFolder(io, logger, "boxes", HEADER, "상자")

    /** Material -> boxes whose capsule item uses it, so a right-click is a single map hit. */
    private val capsuleIndex = ConcurrentHashMap<Material, MutableList<RandomBox>>()

    val size: Int get() = boxes.size

    fun all(): List<RandomBox> = boxes.values.sortedBy { it.name.lowercase() }

    fun names(): List<String> = boxes.keys.sorted()

    fun get(name: String?): RandomBox? {
        if (name == null) return null
        return boxes[name] ?: boxes.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    fun exists(name: String): Boolean = get(name) != null

    // --- loading ---------------------------------------------------------------

    /** Reads every box file off-thread, then swaps the cache on the main thread. */
    fun loadAll(defaults: BoxDefaults, then: (Int) -> Unit) {
        io.async({
            files.readAll { name, config -> RandomBox.load(name, config, defaults) }
        }) { loaded ->
            boxes.clear()
            files.clearDirty()
            loaded.forEach { (name, box) -> boxes[name] = box }
            rebuildCapsuleIndex()
            then(loaded.size)
        }
    }

    // --- mutation --------------------------------------------------------------

    fun create(name: String, defaults: BoxDefaults): RandomBox? {
        if (!isValidName(name) || exists(name)) return null
        val box = RandomBox.create(name, defaults)
        boxes[name] = box
        rebuildCapsuleIndex()
        markDirty(box)
        return box
    }

    /**
     * Duplicates a box under a new name, settings and loot table included.
     *
     * Goes through save/load rather than copying fields by hand: the YAML round trip is the
     * definition of what a box *is*, so a clone made this way can never drift out of sync when
     * a new setting is added - if it persists, it copies.
     */
    fun copy(source: RandomBox, newName: String, defaults: BoxDefaults): RandomBox? {
        if (!isValidName(newName) || exists(newName)) return null

        val config = YamlConfiguration()
        val clone = try {
            source.save(config)
            RandomBox.load(newName, config, defaults)
        } catch (t: Throwable) {
            logger.severe("상자 복제 실패 (${source.name} -> $newName): ${t.message}")
            return null
        }

        // A box that never got a custom display name should show its own name, not the source's.
        if (source.displayName == source.name) clone.displayName = newName
        // The clone starts its own schedule rather than inheriting the source's countdown.
        clone.nextSpawnAt = 0L

        boxes[newName] = clone
        rebuildCapsuleIndex()
        markDirty(clone)
        return clone
    }

    fun delete(name: String): Boolean {
        val box = boxes.remove(name) ?: return false
        rebuildCapsuleIndex()
        files.deleteFile(box.name)
        return true
    }

    fun deleteAll(): Int {
        val removed = boxes.keys.toList()
        boxes.clear()
        capsuleIndex.clear()
        removed.forEach { files.deleteFile(it) }
        return removed.size
    }

    /**
     * Queues a box for saving.
     *
     * Refuses a box object that is no longer the registered one. A reload swaps every instance,
     * so a menu opened beforehand keeps editing an orphan: the screen showed the new value, the
     * save wrote the *reloaded* box, and the admin's change vanished without a word. Menus are
     * closed on reload now, but this stays as the backstop that makes the case audible instead
     * of silent.
     */
    fun markDirty(box: RandomBox) {
        val current = boxes[box.name]
        if (current !== box) {
            if (current != null) {
                logger.warning(
                    "'${box.name}' 상자의 오래된 화면에서 수정이 들어왔습니다 - 무시합니다. " +
                        "설정을 다시 읽은 뒤에는 GUI 를 다시 열어주세요."
                )
            }
            return
        }
        files.markDirty(box.name)
    }

    /** Capsule lookups are cached by material; call after any capsule/key edit. */
    fun rebuildCapsuleIndex() {
        capsuleIndex.clear()
        for (box in boxes.values) {
            for (capsule in listOfNotNull(box.capsuleItem, box.capsuleLegacy).distinctBy { it.material }) {
                capsuleIndex.computeIfAbsent(capsule.material) { mutableListOf() }.add(box)
            }
        }
    }

    /** Box whose capsule item matches the stack in hand (spec §75), or null. */
    fun byCapsule(stack: ItemStack?): RandomBox? {
        if (stack == null || stack.type.isAir) return null
        val candidates = capsuleIndex[stack.type] ?: return null
        return candidates.firstOrNull { box ->
            box.enabled && (matcher.matches(stack, box.capsuleItem) || box.capsuleLegacy?.let { matcher.matches(stack, it) } == true)
        }
    }

    // --- persistence -----------------------------------------------------------

    /**
     * Serialises pending boxes on the calling (main) thread - cheap, in-memory - and hands
     * the finished YAML off to the I/O worker. Called once a second by the ticker.
     */
    fun flushDirty() = files.flushDirty(::render)

    /** Blocking flush used on shutdown, where the worker is about to stop. */
    fun flushDirtyBlocking() = files.flushDirtyBlocking(::render)

    /** 메인 스레드에서 돈다. 직렬화가 실패하면 남기고 건너뛴다. */
    private fun render(name: String): YamlConfiguration? {
        val box = boxes[name] ?: return null
        val config = YamlConfiguration()
        return try {
            box.save(config)
            config
        } catch (t: Throwable) {
            logger.severe("상자 직렬화 실패 ($name): ${t.message}")
            null
        }
    }

    companion object {
        private val NAME_PATTERN = Regex("[A-Za-z0-9가-힣_-]{1,32}")

        private const val HEADER =
            "# inmc-urb 상자 설정 - 대부분의 값은 /urb GUI 에서 편집할 수 있습니다.\n" +
                "# MiniMessage 형식: https://webui.advntr.dev/\n\n"

        fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name)
    }
}
