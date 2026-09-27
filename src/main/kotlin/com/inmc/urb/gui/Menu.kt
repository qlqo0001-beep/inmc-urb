package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.RandomBox
import kr.inmc.core.gui.Icon
import kr.inmc.core.item.BlockRef
import kr.inmc.core.item.ItemRef
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack

/**
 * core 의 [kr.inmc.core.gui.Menu] 에 이 플러그인의 서비스 로케이터를 다시 붙인 얇은 층.
 *
 * core 는 `Urb` 를 알지 못하고 알 필요도 없다. 반대로 이 플러그인의 메뉴들은 `urb` 로
 * 레지스트리·설정에 닿아야 한다. 그 둘을 잇는 것이 이 파일의 전부이며, 덕분에 기존
 * 메뉴 구현들은 한 줄도 바뀌지 않았다.
 */
abstract class Menu(
    protected val urb: Urb,
    size: Int,
    title: Component,
) : kr.inmc.core.gui.Menu(size, title) {

    /** 리로드 때 열린 화면을 닫는 청소가 이 값으로 우리 것을 가려낸다. */
    override val owner: Any get() = urb

    /**
     * 상자 블록 모양의 아이콘. 커스텀 블록은 [BlockRef.iconMaterial] 이 손에 들었던 아이템의
     * 바탕 재료(대개 종이)라서, 그 플러그인에서 실제 아이템을 받아 모델을 그대로 보여 준다.
     */
    protected fun blockIcon(box: RandomBox, name: String, lore: List<String>): ItemStack {
        val ref = box.block
        val custom = (ref as? BlockRef.Custom)?.let { urb.customItems.create(ItemRef.Namespaced(it.namespace, it.id)) }
        return if (custom != null) Icon.relabel(custom, name, lore) else Icon.of(ref.iconMaterial, name, lore)
    }
}
