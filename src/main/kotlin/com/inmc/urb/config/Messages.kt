package com.inmc.urb.config

import com.inmc.urb.util.Ph
import kr.inmc.core.config.MessageCatalog
import org.bukkit.configuration.file.YamlConfiguration

/**
 * Message catalogue backed by `messages.yml`.
 *
 * Every key has a built-in Korean fallback so an admin deleting a line - or a config file
 * that predates a new key - degrades to a sensible default instead of an empty message.
 */
class Messages(values: Map<String, String>) : MessageCatalog<Ph>(values, DEFAULTS) {

    companion object {

        fun from(config: YamlConfiguration): Messages = Messages(merge(DEFAULTS, config))

        /**
         * Fallbacks. `src/main/resources/messages.yml` ships the same content - this map is
         * what keeps a partially edited file working.
         */
        val DEFAULTS: Map<String, String> = mapOf(
            PREFIX to "<gradient:#ff9019:#ffd1a1>[ 랜덤박스 ]</gradient> ",

            "no-permission" to "<red>권한이 없습니다.</red>",
            "player-only" to "<red>이 명령어는 플레이어만 사용할 수 있습니다.</red>",
            "unknown-box" to "<red>'{상자이름}' 이라는 상자를 찾을 수 없습니다.</red>",
            "usage" to "<gray>/urb <white>[create|copy|remove|spawn|spawnnow|open|reset|simulate|testarea|stats|log|reload]</white></gray>",
            "usage-player" to "<gray>사용 가능: <white>/urb info</white>, <white>/urb search</white>, <white>/urb notice</white>, <white>/urb ranking</white></gray>",
            "reloading" to "<gray>설정을 다시 읽는 중...</gray>",
            "reloaded" to "<green>설정을 다시 읽었습니다. (상자 {개수}개)</green>",
            "verify-done" to "<gold>상자 검증</gold> <gray>— 통과 <green>{수량}</green> · 실패 <red>{개수}</red>{아이템}</gray>",
            "verify-failure" to "<red> ✘ {아이템}</red>",
            "verify-skipped" to "<gray> – {아이템}</gray>",
            "verify-report" to "<gray>결과 파일: <white>{아이템}</white></gray>",
            "holograms-swept" to "<green>상자 없이 떠 있던 홀로그램 {개수}개를 치웠습니다.</green>",
            "reload-menu-closed" to "<gray>설정을 다시 읽어 열려 있던 창을 닫았습니다. 다시 열어주세요.</gray>",
            "not-ready" to "<gray>플러그인이 아직 준비 중입니다. 잠시 후 다시 시도해주세요.</gray>",

            "box-created" to "<green>'{상자이름}' 상자를 만들었습니다.</green>",
            "box-exists" to "<red>'{상자이름}' 상자가 이미 존재합니다.</red>",
            "box-invalid-name" to "<red>상자 이름은 영문/숫자/_/- 만 사용할 수 있습니다.</red>",
            "box-deleted" to "<yellow>'{상자이름}' 상자를 삭제했습니다.</yellow>",
            "box-deleted-all" to "<yellow>모든 상자({개수}개)를 삭제했습니다.</yellow>",
            "box-disabled" to "<red>'{상자이름}' 상자는 비활성화 상태입니다.</red>",
            "box-no-rewards" to "<red>'{상자이름}' 상자에 등록된 아이템이 없습니다.</red>",
            "box-copied" to "<green>'{상자이름}' 상자를 '{새이름}' (으)로 복제했습니다.</green> <gray>생성 위치와 캡슐/열쇠가 원본과 똑같으니 꼭 다시 확인하세요.</gray>",
            "copy-usage" to "<gray>사용법: <white>/urb copy [원본이름] [새이름]</white></gray>",
            "copy-failed" to "<red>상자를 복제하지 못했습니다. 콘솔 로그를 확인해주세요.</red>",

            "spawn-queued" to "<gray>청크가 로드되지 않아 대기열에 넣었습니다. 해당 좌표에 접근하면 상자가 나타납니다.</gray>",
            "spawn-failed" to "<red>상자를 소환할 위치를 찾지 못했습니다.</red>",
            "spawn-limit" to "<red>'{상자이름}' 상자는 이미 최대 {개수}개가 생성되어 있습니다.</red>",
            "spawn-success" to "<green>'{상자이름}' 상자를 {좌표} 에 소환했습니다.</green>",
            "reset-done" to "<yellow>소환된 상자 {개수}개를 제거했습니다.</yellow>",
            "spawn-track-button" to " <click:run_command:'{command}'><hover:show_text:'<gray>클릭하면 나침반이 이 상자를 가리킵니다.</gray>'><aqua>[추적하기]</aqua></hover></click>",

            "testarea-header" to "<yellow>{상자이름}</yellow><gray> 생성 위치 {개수}곳을 시험했습니다.</gray>",
            "testarea-no-world" to "<red>'{world}' 월드를 찾을 수 없습니다.</red>",
            "testarea-no-area" to "<red>{상자이름} 상자의 생성 영역이 설정되어 있지 않습니다.</red>",
            "testarea-no-points" to "<red>{상자이름} 상자에 등록된 좌표가 없습니다.</red>",
            "testarea-good" to "<green>이 설정이면 상자가 정상적으로 생성됩니다.</green>",
            "testarea-poor" to "<yellow>절반 이상이 실패합니다. 영역을 옮기거나 Y 범위를 넓혀보세요.</yellow>",
            "testarea-bad" to "<red>거의 모든 위치가 실패합니다. 바다·용암 지대이거나 보호구역일 수 있습니다.</red>",
            "testarea-unloaded" to "<gray>로드된 청크가 없어 판단할 수 없습니다. 해당 지역 근처에서 다시 시도해주세요.</gray>",

            "open-progress-title" to "<white>상자를 여는중...</white>",
            "open-done-title" to "<green>상자가 열렸습니다!</green>",
            "open-cancel-title" to "<red>상자 열기 취소</red>",
            "open-progress-filled" to "<green>●</green>",
            "open-progress-empty" to "<dark_gray>○</dark_gray>",
            "open-cancelled" to "<red>움직여서 상자 열기가 취소되었습니다.</red>",
            "open-cooldown" to "<red>{시간} 후에 다시 시도할 수 있습니다.</red>",
            "open-busy" to "<red>이미 다른 상자를 여는 중입니다.</red>",
            "open-taken" to "<gray>누군가 먼저 상자를 열었습니다.</gray>",
            "open-inventory-full" to "<yellow>인벤토리가 가득 차서 일부 아이템을 바닥에 떨어뜨렸습니다.</yellow>",

            "open-personal-cooldown" to "<red>{상자이름} 상자는 {시간} 후에 다시 열 수 있습니다.</red>",
            "open-limit-reached" to "<red>{상자이름} 상자는 최대 {개수}번까지만 열 수 있습니다.</red>",
            "open-limit-reached-timed" to "<red>{상자이름} 상자는 {개수}번까지 열 수 있습니다. <yellow>{시간}</yellow> 후에 초기화됩니다.</red>",
            "open-already-found" to "<gray>{상자이름}은(는) 이미 찾은 상자입니다.</gray>",
            "permanent-no-cost" to "<red>{상자이름} 상자는 열쇠나 비용이 설정되어 있지 않아 열 수 없습니다.</red>",

            "key-required" to "<red>{상자이름} 상자를 여시려면 {열쇠이름} 이(가) 필요합니다.</red>",
            "key-consumed" to "<green>{열쇠이름}을(를) 소모하여 {상자이름} 상자를 엽니다!</green>",
            "money-required" to "<red>상자를 여시려면 {필요한돈}원이 필요합니다. 나의 소지금: {돈보유}원</red>",
            "money-consumed" to "<green>{필요한돈}원을 소모하여 {상자이름} 상자를 엽니다!</green>",
            "economy-missing" to "<red>경제 플러그인(Vault)이 없어 돈 조건을 처리할 수 없습니다.</red>",

            "reward-received" to "<gray>{상자이름}</gray> <gray>»</gray> {아이템}",
            "reward-received-window" to "<gray>{상자이름}</gray> <gray>»</gray> {아이템} <dark_gray>(창을 닫으면 가방으로 들어갑니다)</dark_gray>",
            "reward-announce" to "<gold>★</gold> <yellow>{플레이어네임}</yellow>님이 <yellow>{상자이름}</yellow>에서 <aqua>{아이템}</aqua>을(를) 획득했습니다!",

            "search-cooldown" to "<red>{시간} 후에 다시 검색할 수 있습니다.</red>",
            "search-empty" to "<gray>현재 생성된 상자가 없습니다.</gray>",
            "track-set" to "<green>{상자이름} 상자를 추적합니다. 나침반과 안내 파티클을 따라가세요.</green>",
            "track-set-other-world" to "<yellow>{상자이름} 상자를 추적합니다. <white>{world}</white> 월드로 이동하면 나침반이 가리킵니다.</yellow>",
            "track-untrackable" to "<red>이 상자는 추적할 수 없습니다.</red>",
            "track-cleared" to "<gray>상자 추적을 중지했습니다.</gray>",
            "track-bossbar" to "<yellow>{상자이름}</yellow> <gray>|</gray> <white>{거리}m</white> <gray>|</gray> <gray>{좌표}</gray>",
            "track-bossbar-away" to "<yellow>{상자이름}</yellow> <gray>|</gray> <white>{world}</white> <gray>월드로 이동하세요</gray>",
            "track-arrived" to "<green>추적 중인 상자에 도착했습니다!</green>",
            "track-gone" to "<gray>그 상자는 이미 사라졌습니다.</gray>",
            "track-opened" to "<yellow>{플레이어} 님이 {상자이름} 상자를 열어 추적을 멈춥니다.</yellow>",

            "preview-disabled" to "<red>이 상자의 확률표는 공개되어 있지 않습니다.</red>",

            "simulate-started" to "<gray>{상자이름} 시뮬레이션을 {개수}회 실행합니다...</gray>",
            "simulate-progress" to "<yellow>시뮬레이션 {상자이름}</yellow> <gray>|</gray> <white>{개수}%</white> <dark_gray>({수량}회)</dark_gray>",
            "simulate-done" to "<green>시뮬레이션 완료 - {개수}회, {시간}</green>",
            "simulate-cancelled" to "<yellow>시뮬레이션이 중단되었습니다. ({개수}회까지의 결과)</yellow>",
            "simulate-busy" to "<red>이미 '{상자이름}' 시뮬레이션이 실행 중입니다.</red>",

            "prompt-cancelled" to "<gray>입력을 취소했습니다.</gray>",
            "prompt-timeout" to "<gray>입력 시간이 초과되었습니다.</gray>",
            "prompt-invalid-number" to "<red>숫자를 입력해주세요.</red>",
            "prompt-enter" to "<yellow>채팅으로 값을 입력하세요. <gray>('취소' 입력 시 중단)</gray></yellow>",
        )
    }
}
