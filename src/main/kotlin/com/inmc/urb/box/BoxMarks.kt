package com.inmc.urb.box

import com.inmc.urb.util.BlockKey
import kr.inmc.core.item.BlockRef
import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType

/**
 * 놓은 상자를 **그 청크의 PDC** 에 적어 둔다(`inmcurb:boxes` — 자리마다 `x/y/z|원래 블록|블록 참조`).
 *
 * `state.yml` 은 1초 안에 디스크에 닿지만 월드 청크는 자동 저장(기본 5분)·청크가 내려갈 때·정상 종료 때만 닿는다.
 * 그 사이에 서버가 강제로 꺼지면 둘이 어긋난다 — 사라진 상자를 파일은 잊었는데 블록은 디스크에 남는다(고아 블록,
 * 2026-10-06 테섭에서 20개). 표시는 블록과 **같은 청크 기록**에 같이 저장되므로 둘은 늘 함께 남거나 함께 사라진다.
 * 그래서 청크가 올라올 때 표시만 보면 어긋남을 안다 — [BoxSpawnService.settleChunk].
 */
object BoxMarks {

    val KEY = NamespacedKey("inmcurb", "boxes")
    private val TYPE = PersistentDataType.LIST.strings()

    /** 좌표는 절대값이다 — 월드는 청크가 정한다. [ref] 는 커스텀 블록의 곁것(엔티티 모델·청크 표시)을 치울 때 쓴다. */
    data class Mark(val x: Int, val y: Int, val z: Int, val blockData: String, val ref: String?)

    fun encode(mark: Mark): String = "${mark.x}/${mark.y}/${mark.z}|${mark.blockData}|${mark.ref.orEmpty()}"

    fun decode(raw: String): Mark? {
        val parts = raw.split('|', limit = 3)
        if (parts.size < 2 || parts[1].isEmpty()) return null
        val pos = parts[0].split('/')
        if (pos.size != 3) return null
        val x = pos[0].toIntOrNull() ?: return null
        val y = pos[1].toIntOrNull() ?: return null
        val z = pos[2].toIntOrNull() ?: return null
        return Mark(x, y, z, parts[1], parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }

    /** 같은 자리의 표시는 바꾼다 — 한 자리에 상자는 하나다. */
    fun with(list: List<String>, mark: Mark): List<String> = without(list, mark.x, mark.y, mark.z) + encode(mark)

    fun without(list: List<String>, x: Int, y: Int, z: Int): List<String> {
        val prefix = "$x/$y/$z|"
        return list.filterNot { it.startsWith(prefix) }
    }

    // --- 청크 -------------------------------------------------------------------

    fun read(chunk: Chunk): List<Mark> =
        chunk.persistentDataContainer.get(KEY, TYPE).orEmpty().mapNotNull(::decode)

    fun add(chunk: Chunk, key: BlockKey, blockData: String, ref: BlockRef?) {
        val pdc = chunk.persistentDataContainer
        pdc.set(KEY, TYPE, with(pdc.get(KEY, TYPE).orEmpty(), Mark(key.x, key.y, key.z, blockData, ref?.serialize())))
    }

    fun remove(chunk: Chunk, key: BlockKey) {
        val pdc = chunk.persistentDataContainer
        val current = pdc.get(KEY, TYPE) ?: return
        val rest = without(current, key.x, key.y, key.z)
        if (rest.size == current.size) return
        if (rest.isEmpty()) pdc.remove(KEY) else pdc.set(KEY, TYPE, rest)
    }
}
