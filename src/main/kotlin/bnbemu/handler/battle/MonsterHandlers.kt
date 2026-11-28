package bnbemu.handler.battle

import bnbemu.debugMode
import bnbemu.netty.ClientSocket
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.server.MonsterBattleController
import bnbemu.server.Room
import org.tinylog.kotlin.Logger

private fun activeMonsterController(c: ClientSocket): MonsterBattleController? {
    val room = Room.current ?: return null
    if (room.uniqueClients.none { it === c }) return null
    return room.battle?.monster
}

/**
 * C→S op 114 (`sub_586166` L335792)：怪物受到伤害（普通/Boss 扣血、冰怪碰破、脚本自伤 `playerSlot=65535`）。
 * 载荷：`E2(objId) E2(damage) E2(playerSlot)`（6B）。
 */
val MonsterDamageReq = PKTHandler { c ->
    if (readableBytes() != 6) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    val objectId = Decode2
    val damage = Decode2
    val playerSlot = Decode2
    activeMonsterController(c)?.onDamage(objectId, damage, playerSlot)
}

/**
 * C→S op 115 (`sub_58492F` L334192)：Boss 血量归 0 进锁泡 (`state=32`) 后玩家触碰终结。
 * 载荷：`E2(objId) E2(playerSlot)`（4B）。
 */
val MonsterFinishReq = PKTHandler { c ->
    if (readableBytes() != 4) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    val objectId = Decode2
    val reportedSlot = Decode2
    val monster = activeMonsterController(c) ?: return@PKTHandler
    val senderSlot = monster.resolveHumanSlot(c, reportedSlot) ?: return@PKTHandler
    monster.onFinish(objectId, senderSlot)
}

/**
 * C→S op 116 (`sub_58F12C` L344329 等 26 处)：怪物状态同步与脚本事件上报。
 * 载荷：`E2(objId) E2(event) + [payload]`。
 */
val MonsterStateReq = PKTHandler { c ->
    if (readableBytes() < 4) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    val objectId = Decode2
    val event = Decode2
    val payload = ByteArray(readableBytes())
    if (payload.isNotEmpty()) {
        readBytes(payload)
    }
    activeMonsterController(c)?.onStateEvent(objectId, event, payload)
}

/**
 * C→S op 117 (`sub_548441/4C1/54B/5D5`, `sub_5878xx` L337195)：怪物 5 种攻击请求。
 * 载荷：`E2(objId) E1(kind) + [payload]`：
 * - kind 1 (`directionDest` 定向水柱): `E1(power) E1(dir) E1(row) E1(col)` (4B)
 * - kind 2 (`rectangle` 空心矩形):     `E1(distance) E1(row) E1(col)` (3B)
 * - kind 3 (`autokick` 飞行水泡):      `E1(power|0x20) E1(dstRow) E1(dstCol) E1(dir) E2(durationMs) E1(srcRow) E1(srcCol)` (8B)
 * - kind 4 (`CGOSealBomb` 坠地):       `E1(power) E1(row) E1(col)` (3B)
 * - kind 6 (`bombMissile` 碰撞自爆):   `E1(power) E1(row) E1(col)` (3B)
 */
val MonsterAttackReq = PKTHandler { c ->
    if (readableBytes() !in setOf(6, 7, 11)) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    val objectId = Decode2
    val kind = Decode1
    val monster = activeMonsterController(c)
    if (monster == null) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    when {
        kind == 1 && readableBytes() == 4 -> {
            val power = Decode1
            val direction = Decode1
            val row = Decode1
            val col = Decode1
            monster.onAttackDirectional(objectId, power, direction, row, col)
        }

        kind == 2 && readableBytes() == 3 -> {
            val distance = Decode1
            val row = Decode1
            val col = Decode1
            monster.onAttackRectangle(objectId, distance, row, col)
        }

        kind == 3 && readableBytes() == 8 -> {
            val power = Decode1 and 0x1F
            val dstRow = Decode1
            val dstCol = Decode1
            val direction = Decode1
            val durationMs = Decode2
            val srcRow = Decode1
            val srcCol = Decode1
            monster.onAttackProjectile(objectId, power, srcRow, srcCol, dstRow, dstCol, durationMs, direction)
        }

        kind == 4 && readableBytes() == 3 -> {
            val power = Decode1
            val row = Decode1
            val col = Decode1
            monster.onProjectileImpact(objectId, power, row, col)
        }

        kind == 6 && readableBytes() == 3 -> {
            val power = Decode1
            val row = Decode1
            val col = Decode1
            monster.onMissileImpact(objectId, power, row, col)
        }

        else -> skipBytes(readableBytes())
    }
}

/**
 * C→S op 127 (`sub_57B0EC` L325316)：通关/阶段清理时按存活玩家槽位上报。
 * 载荷：`E1(slotId)`（1B）。静默消费，防止未知 opcode 告警。
 */
val StageClearAliveReq = PKTHandler { _ ->
    if (readableBytes() == 1) {
        val slotId = Decode1
        if (debugMode) Logger.debug("[Monster] C127 StageClearAlive slot=$slotId")
    } else {
        skipBytes(readableBytes())
    }
}

/**
 * C→S op 131 (`sub_54CF5F` L282815)：客户端收到 `S169 mode=2` 换盘并加载完成后的就绪回报。
 * 载荷：`E1(1)`（1B）。
 */
val MonsterBoardLoadedReq = PKTHandler { c ->
    if (readableBytes() == 1 && Decode1 == 1) {
        activeMonsterController(c)?.onBoardLoaded(c)
    } else {
        skipBytes(readableBytes())
    }
}

/**
 * C→S op 136 (`sub_64C5F4` L506172)：动态刷怪或创建海豹落弹投射物 (`type == 9`)。
 * 载荷：`E2(type) + 24B(rawEncodedInit)`（26B）。
 */
val MonsterCreateReq = PKTHandler { c ->
    if (readableBytes() != 26) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    val type = Decode2
    val encodedInit = ByteArray(24)
    readBytes(encodedInit)
    val room = Room.current ?: return@PKTHandler
    val ownerSlot = room.slots.indexOfFirst { it.isHuman && it.client === c }
    if (ownerSlot < 0) return@PKTHandler
    room.battle?.monster?.onCreate(type, encodedInit, ownerSlot)
}

/**
 * C→S op 137 (`sub_64D5D5` L507533)：本地控制器释放网络怪物/投射物。
 * 载荷：`E2(objId) E4(param)`（6B）。
 */
val MonsterDestroyReq = PKTHandler { c ->
    if (readableBytes() != 6) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    val objectId = Decode2
    val param = Decode4
    activeMonsterController(c)?.onDestroy(objectId, param)
}

/**
 * C→S op 141 (`sub_65DCA0` L523087)：怪物掉落道具请求，必须回 `S→C op 183` (`sub_5486A9`)。
 * 载荷：`E1(itemType) E1(row) E1(col)`（3B，注意坐标序为先 row 后 col）。
 */
val MonsterDropItemReq = PKTHandler { c ->
    if (readableBytes() != 3) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    val itemType = Decode1
    val row = Decode1
    val col = Decode1
    activeMonsterController(c)?.onDropItem(itemType, row, col)
}

/**
 * C→S op 155 (`sub_58DCC7` L343169)：机器人 Boss 发射激光请求 → 回 `S→C op 268`。
 * 载荷：`E2(objId) E1(row) E1(col) E1(9) E1(kind) E1(dir) E1(color) E1(width) E1(len) E4(durationMs)`（14B）。
 */
val MonsterLaserReq = PKTHandler { c ->
    if (readableBytes() != 14) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    val objectId = Decode2
    val row = Decode1
    val col = Decode1
    val ownerMarker = Decode1
    val kind = Decode1
    val direction = Decode1
    val color = Decode1
    val width = Decode1
    val length = Decode1
    val durationMs = Decode4
    if (ownerMarker != 9) return@PKTHandler
    activeMonsterController(c)?.onLaser(objectId, row, col, kind, direction, color, width, length, durationMs)
}

/**
 * C→S op 168 (`sub_58BA44` L341319)：海星壳被踢出 → 原样透传 18B 进 `S→C op 151 event 22`。
 * 载荷：`E2(objId) + 18B(E1(dir) E4(fromX) E4(fromY) E4(endX) E4(endY) E1(slot))`（20B）。
 */
val MonsterKickReq = PKTHandler { c ->
    if (readableBytes() != 20) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    val objectId = Decode2
    val payload = ByteArray(18)
    readBytes(payload)
    activeMonsterController(c)?.onKick(objectId, payload, c)
}

/**
 * C→S op 169 (`sub_586107` L335774)：怪物被玩家水柱命中通知（7 处调用，静默消费防未知包日志）。
 * 载荷：`E2(objId) E1(attackerSlot)`（3B）。
 */
val MonsterHitReq = PKTHandler { c ->
    if (readableBytes() != 3) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    val objectId = Decode2
    val attackerSlot = Decode1
    activeMonsterController(c)?.onHit(objectId, attackerSlot)
}
