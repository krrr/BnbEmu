package bnbemu.packet.battle

import bnbemu.netty.PKT

/** `CStageClearComponent` 的固定组件 ID（`sub_54A720` L280642 → `CStageClearComponent` L324916）。 */
const val STAGE_CLEAR_COMPONENT_ID = 14

/**
 * S→C op 178 (`CNetObjectMan::OnPacket` L508884 → `sub_64DD98` L508029)：
 * 授予指定网络对象本地控制权（将 `remote(+136)` 置 0）；不存在的 `objectId` 客户端安全忽略。
 */
fun MonsterControl(objectId: Int, ownerSlot: Int) = PKT {
    Encode2(objectId)
    Encode1(ownerSlot)
}

/**
 * S→C op 179 (`CNetObjectMan::OnPacket` → `sub_64DCE2` L507986)：
 * 销毁并从 `CNetObjectMan` 移除网络对象。
 */
fun MonsterDespawn(objectId: Int, param: Int) = PKT {
    Encode2(objectId)
    Encode4(param)
}

/**
 * S→C op 177 (`CNetObjectMan::OnPacket` L508884)：
 * 动态创建网络怪物或投射物（`type == 9` 为 `CGOSealBomb`），原样带回 24B 混淆初始化块。
 */
fun MonsterCreate(objectId: Int, ownerSlot: Int, type: Int, encodedInit: ByteArray) = PKT {
    require(encodedInit.size == 24) { "MonsterCreate encodedInit must be 24 bytes, got ${encodedInit.size}" }
    Encode2(objectId)
    Encode1(ownerSlot)
    Encode2(type)
    EncodeBuffer(encodedInit)
}

/**
 * S→C op 151 (`sub_54A720` → `CStageClearComponent` → `CGONpc*::OnPacket`)：
 * 怪物阶段通用事件包：`E2(14) E2(objectId) E1(event) + [payload]`。
 *
 * - 当 [encodedPayload] 不为 null 时直接写入原始编码字节（用于原样透传事件 5/7/11/12/13/14/15/17/19/22 等）；
 * - 当 [encodedPayload] 为 null 且 `event == 1` 时写入 `E4(x) E4(y)`（`IceMon`/`StarFishMon` 冰冻/缩壳坐标）；
 *   ⚠️ Boss 血量归 0 锁泡 (`AttackMon` event 1) 为 0B 无载荷，请使用 [MonsterBossLocked]。
 */
fun MonsterStageEvent(
    objectId: Int,
    event: Int,
    x: Int = 0,
    y: Int = 0,
    encodedPayload: ByteArray? = null,
) = PKT {
    Encode2(STAGE_CLEAR_COMPONENT_ID)
    Encode2(objectId)
    Encode1(event)
    if (encodedPayload != null) {
        EncodeBuffer(encodedPayload)
    } else if (event == 1) {
        Encode4(x)
        Encode4(y)
    }
}

/**
 * S→C op 151 event 2 (`AttackMon` / `RobotMon` 血量同步)：`E2(14) E2(objectId) E1(2) E2(hp)`。
 */
fun MonsterHealth(objectId: Int, hp: Int) = PKT {
    Encode2(STAGE_CLEAR_COMPONENT_ID)
    Encode2(objectId)
    Encode1(2)
    Encode2(hp)
}

/**
 * S→C op 151 event 1 无载荷变体（`AttackMon` L334785）：Boss 血量归 0 进入锁泡困住态 (`state = 32`)。
 */
fun MonsterBossLocked(objectId: Int) = PKT {
    Encode2(STAGE_CLEAR_COMPONENT_ID)
    Encode2(objectId)
    Encode1(1)
}

/**
 * S→C op 151 event 6 (`CGONpcBasicMon::SetSpeed`, `sub_586BEC` L336568)：`E2(14) E2(objectId) E1(6) E1(speed)`。
 */
fun MonsterSpeed(objectId: Int, speed: Int) = PKT {
    Encode2(STAGE_CLEAR_COMPONENT_ID)
    Encode2(objectId)
    Encode1(6)
    Encode1(speed)
}

/**
 * S→C op 183 (`sub_5486A9` L278454 → `sub_55C96F`)：怪物掉落道具通知。
 * 坐标序为 `(row, col)`，与 `C→S op 141` (`sub_65DCA0`) 一致。
 */
fun MonsterDropItem(itemSeq: Int, itemType: Int, row: Int, col: Int) = PKT {
    Encode2(itemSeq)
    Encode1(itemType)
    Encode1(row)
    Encode1(col)
}

/**
 * S→C op 268 (`sub_548817` L278560)：机器人 Boss 原生激光特效，客户端自理碰撞与生命周期。
 */
fun MonsterLaser(
    row: Int,
    col: Int,
    kind: Int,
    direction: Int,
    color: Int,
    width: Int,
    length: Int,
    durationMs: Int,
) = PKT {
    Encode1(MONSTER_BOMB_SLOT)
    Encode1(row)
    Encode1(col)
    Encode1(kind)
    Encode1(direction)
    Encode1(color)
    Encode1(width)
    Encode1(length)
    Encode4(durationMs)
}
