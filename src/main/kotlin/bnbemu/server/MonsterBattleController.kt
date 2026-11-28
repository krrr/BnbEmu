package bnbemu.server

import bnbemu.data.MapCatalog
import bnbemu.data.MapRef
import bnbemu.data.MonsterStageSpec
import bnbemu.data.MonsterTemplate
import bnbemu.debugMode
import bnbemu.netty.ClientSocket
import bnbemu.netty.PKT
import bnbemu.packet.battle.BOMB_SHAPE_BEAM
import bnbemu.packet.battle.BOMB_SHAPE_HOLLOW_RECT
import bnbemu.packet.battle.BOMB_SHAPE_NORMAL
import bnbemu.packet.battle.GameResult
import bnbemu.packet.battle.GameStart
import bnbemu.packet.battle.GameStartPeer
import bnbemu.packet.battle.InitGameResult
import bnbemu.packet.battle.MONSTER_BOMB_SLOT
import bnbemu.packet.battle.MonsterBossLocked
import bnbemu.packet.battle.MonsterControl
import bnbemu.packet.battle.MonsterCreate
import bnbemu.packet.battle.MonsterDespawn
import bnbemu.packet.battle.MonsterDropItem
import bnbemu.packet.battle.MonsterHealth
import bnbemu.packet.battle.MonsterLaser
import bnbemu.packet.battle.MonsterNextBoard
import bnbemu.packet.battle.MonsterSpeed
import bnbemu.packet.battle.MonsterStageEvent
import bnbemu.packet.battle.SetBombState
import bnbemu.packet.battle.SetBombStateBatch
import bnbemu.packet.battle.SetBomberEvent
import bnbemu.packet.battle.WINNER_TEAM_HUMAN
import bnbemu.packet.battle.bombFlagLaunched
import org.tinylog.kotlin.Logger
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * 怪物模式（StageClear / Mode 14）局内状态机，作为 [BattleSession] 的可选组件存在，
 * 随 [BattleSession.destroy] 自动释放，零全局状态泄漏。
 *
 * 覆盖：
 * - `CNetObjectMan` 生命周期（S178 授权、C136→S177 动态刷怪与槽位复用、C137→S179 销毁）
 * - C114 伤害 / Boss 狂暴 / 锁泡 (`S151 event 1` 无载荷) / C115 终结 / 冰怪自伤豁免
 * - C116 ↔ S151 完整 21 种事件映射（修正 event 14 `valid` / 15 `combine`，补全 16..20）
 * - C117 5 种怪物攻击（kind 1/2 采用原生 `S139 shape=1/2`，kind 3/4/6 完整支持）
 * - C155 → S268 机器人激光、C168 → S151 event 22 海星踢壳、C141 → S183 怪物掉道具
 * - 机器人 Boss 双半身两阶段合体状态机
 * - 中间小关通关无缝换盘（`S169 mode=2` ↔ `C131` → `S136` + `S178`）与最终关通关结算
 */
class MonsterBattleController(
    private val battle: BattleSession,
    initialMapId: Int,
    initialStage: MonsterStageSpec,
    private val chiefSlotProvider: () -> Int,
    private val resolveHumanSlotProvider: (ClientSocket, Int) -> Int?,
    private val onBoardChanged: (MapRef) -> Unit,
) {
    private val lock = Any()

    @Volatile
    var currentMapId: Int = initialMapId
        private set

    @Volatile
    var currentStage: MonsterStageSpec = initialStage
        private set

    @Volatile
    var controlGranted: Boolean = false
        private set

    @Volatile
    var transitionPending: Boolean = false
        private set

    @Volatile
    var boardLoadPending: Boolean = false
        private set

    @Volatile
    var boardStartSent: Boolean = false
        private set

    /** 换盘过渡或等待新棋盘加载期间，忽略迟到的玩家阵亡/超时判定。 */
    val isTransitioningOrLoading: Boolean
        get() = transitionPending || boardLoadPending

    private val releasedObjects = mutableSetOf<Int>()
    private val frozenObjects = mutableSetOf<Int>()
    private val shrunkenObjects = mutableSetOf<Int>()
    private val defeatedObjects = mutableSetOf<Int>()
    private val detonatedMissiles = mutableSetOf<Int>()
    private val angryBossObjects = mutableSetOf<Int>()
    private val lockedBossObjects = mutableSetOf<Int>()
    private val spawnedObjects = mutableMapOf<Int, MonsterTemplate>()
    private val projectileObjects = mutableSetOf<Int>()
    private val monsterHealth = mutableMapOf<Int, Int>()
    private val stageObjectives = mutableSetOf<Int>()
    private val robotReady = mutableMapOf<Int, Int>()
    private val robotReadyHealth = mutableMapOf<Int, Int>()
    private var robotMergeAnnounced = false
    private var robotTransformed = false
    private val kickRequests = mutableMapOf<Int, Pair<ByteArray, Long>>()
    private var nextDropItemSeq = MONSTER_DROP_ITEM_SEQ_FIRST

    init {
        stageObjectives.addAll(initialStage.objectives)
    }

    /** 生成并标记本关初始怪物的 `S→C op 178 MonsterControl` 授权包（幂等）。 */
    fun grantInitialControlPackets(): List<PKT> = synchronized(lock) {
        if (battle.isDestroyed || battle.gameOverSent || controlGranted) return emptyList()
        val controllerSlot = chiefSlotProvider()
        if (controllerSlot !in battle.slotStates.indices) return emptyList()
        controlGranted = true
        val count = currentStage.initialCount.coerceIn(0, MONSTER_OBJECT_CAPACITY)
        Logger.info("[Monster] 授予初始怪控权 map=${currentStage.mapName} slot=$controllerSlot initialCount=$count")
        (0 until count).map { MonsterControl(it, controllerSlot) }
    }

    /** 立即通过当前战斗会话广播初始怪控授权。 */
    fun grantInitialControl() {
        val packets = grantInitialControlPackets()
        if (packets.isNotEmpty()) {
            battle.broadcast(*packets.toTypedArray())
        }
    }

    /** 解析客户端上报的真人槽位（支持 2P 同连接多槽位及单人房 `255`/`65535` 兼容映射）。 */
    fun resolveHumanSlot(client: ClientSocket, reportedSlot: Int): Int? =
        resolveHumanSlotProvider(client, reportedSlot)

    private fun isHumanSlot(slotId: Int): Boolean =
        slotId in battle.slotStates.indices && battle.slotStates[slotId].isHuman

    private fun validCell(col: Int, row: Int): Boolean =
        MapCatalog.of(currentMapId)?.containsCell(col, row) ?: (col in 0..14 && row in 0..12)

    private fun mapDimensions(): Pair<Int, Int> {
        val tpl = MapCatalog.of(currentMapId)
        return (tpl?.width ?: 15) to (tpl?.height ?: 13)
    }

    private fun acceptsEventContextLocked(objectId: Int): Boolean =
        !battle.isDestroyed && !battle.gameOverSent && !transitionPending && !boardLoadPending &&
            controlGranted && objectId in 0 until MONSTER_OBJECT_CAPACITY && objectId !in releasedObjects

    private fun acceptsEventLocked(objectId: Int): Boolean =
        acceptsEventContextLocked(objectId) && objectId !in defeatedObjects

    private fun definitionOfLocked(objectId: Int): MonsterTemplate? =
        spawnedObjects[objectId] ?: currentStage.monsters[objectId]

    /** C→S op 136 (`CNetObjectMan::RequestCreate`) → S→C op 177 (`MonsterCreate`)。 */
    fun onCreate(type: Int, encodedInit: ByteArray, ownerSlot: Int) {
        val packet: PKT = synchronized(lock) {
            if (battle.isDestroyed || battle.gameOverSent || transitionPending || boardLoadPending || !controlGranted) return
            if (type !in 3..22 || encodedInit.size != 24 || !isHumanSlot(ownerSlot)) return

            val isProjectile = type == 9
            val id = (DYNAMIC_OBJECT_ID_START until MONSTER_OBJECT_CAPACITY).firstOrNull {
                it !in spawnedObjects && it !in projectileObjects
            } ?: return

            val definition = if (isProjectile) {
                null
            } else {
                val scriptClass = ByteBuffer.wrap(encodedInit, 16, 4).int xor XOR_INT_MASK
                currentStage.findByScriptClass(scriptClass) ?: return
            }

            // 复用网络对象 ID 时必须彻底清理上一代残留状态（防止无限刷新怪继承已死目标的通关标志）。
            stageObjectives.remove(id)
            releasedObjects.remove(id)
            defeatedObjects.remove(id)
            detonatedMissiles.remove(id)
            frozenObjects.remove(id)
            shrunkenObjects.remove(id)
            angryBossObjects.remove(id)
            lockedBossObjects.remove(id)
            monsterHealth.remove(id)

            if (isProjectile) {
                projectileObjects.add(id)
            } else {
                val actor = checkNotNull(definition)
                spawnedObjects[id] = actor.copy(id = id, initial = false)
                if (!currentStage.hasBossObjective && actor.influencesClear) {
                    stageObjectives.add(id)
                }
            }
            if (debugMode) {
                Logger.debug("[Monster] 动态创建 id=$id type=$type owner=$ownerSlot projectile=$isProjectile")
            }
            MonsterCreate(id, ownerSlot, type, encodedInit.copyOf())
        }
        battle.broadcast(packet)
    }

    /** C→S op 137 (`CNetObjectMan::RequestDestroy`) → S→C op 179 (`MonsterDespawn`) + 通关判定。 */
    fun onDestroy(objectId: Int, param: Int) {
        val shouldCheckClear: Boolean
        synchronized(lock) {
            if (battle.isDestroyed || battle.gameOverSent || transitionPending || boardLoadPending || !controlGranted) return
            if (objectId !in 0 until MONSTER_OBJECT_CAPACITY || !releasedObjects.add(objectId)) return
            spawnedObjects.remove(objectId)
            projectileObjects.remove(objectId)
            shouldCheckClear = true
        }
        battle.broadcast(MonsterDespawn(objectId, param))
        if (shouldCheckClear) {
            checkStageClear()
        }
    }

    /** C→S op 114 (`MonsterDamageReq`)：扣血、Boss 狂暴/锁泡、冰怪两段击杀与自伤豁免。 */
    fun onDamage(objectId: Int, damage: Int, playerSlot: Int) {
        val toSend = mutableListOf<PKT>()
        var checkClearAfter = false
        synchronized(lock) {
            if (!acceptsEventLocked(objectId) || damage <= 0 || objectId in lockedBossObjects) return
            if (playerSlot != SCRIPT_SELF_SLOT && !isHumanSlot(playerSlot)) return

            val definition = definitionOfLocked(objectId)
            if (definition?.boss == true && playerSlot == SCRIPT_SELF_SLOT) return

            val isIceMon = definition?.type.equals("iceMon", ignoreCase = true)
            val scriptedIceDamage = playerSlot == SCRIPT_SELF_SLOT && isIceMon &&
                definition != null && damage in 1..definition.maxSelfDamage

            val wasFrozen = frozenObjects.remove(objectId)
            var died = wasFrozen
            if (!wasFrozen) {
                if (definition == null || (isIceMon && !scriptedIceDamage)) return
                val remaining = ((monsterHealth[objectId] ?: definition.hp) - damage).coerceAtLeast(0)
                monsterHealth[objectId] = remaining
                if (definition.boss || definition.type.equals("bossRobot", ignoreCase = true)) {
                    toSend += MonsterHealth(objectId, remaining)
                    if (remaining > 0 && definition.angerHp > 0 && remaining <= definition.angerHp &&
                        angryBossObjects.add(objectId)
                    ) {
                        toSend += MonsterStageEvent(objectId, 0)
                        Logger.info("[Monster] Boss 狂暴 id=$objectId hp=$remaining angerHp=${definition.angerHp}")
                    }
                }
                if (remaining == 0) {
                    if (definition.boss) {
                        lockedBossObjects.add(objectId)
                        toSend += MonsterBossLocked(objectId)
                        Logger.info("[Monster] Boss 锁泡 id=$objectId slot=$playerSlot")
                    } else {
                        died = true
                    }
                }
            }

            if (died) {
                defeatedObjects.add(objectId)
                toSend += MonsterStageEvent(objectId, 4)
                if (debugMode) {
                    Logger.debug("[Monster] 怪物阵亡 id=$objectId slot=$playerSlot scriptedIce=$scriptedIceDamage")
                }
                if (spawnedObjects[objectId]?.settlesOnDefeat == true) {
                    checkClearAfter = true
                }
            }
        }
        if (toSend.isNotEmpty()) {
            battle.broadcast(*toSend.toTypedArray())
        }
        if (checkClearAfter) {
            checkStageClear()
        }
    }

    /** C→S op 115 (`MonsterFinishReq`)：Boss 锁泡 (`state=32`) 后玩家撞破终结。 */
    fun onFinish(objectId: Int, playerSlot: Int) {
        synchronized(lock) {
            if (!acceptsEventLocked(objectId) || objectId !in lockedBossObjects) return
            if (!isHumanSlot(playerSlot)) return
            lockedBossObjects.remove(objectId)
            defeatedObjects.add(objectId)
            Logger.info("[Monster] Boss 终结 id=$objectId slot=$playerSlot")
        }
        battle.broadcast(MonsterStageEvent(objectId, 4))
    }

    /** C→S op 169 (`MonsterHitReq`, `sub_586107`)：水柱命中怪物通知（静默消费）。 */
    fun onHit(objectId: Int, attackerSlot: Int) {
        if (debugMode) {
            Logger.debug("[Monster] C169 MonsterHit id=$objectId attackerSlot=$attackerSlot")
        }
    }

    /**
     * C→S op 116 (`MonsterStateReq`) → S→C op 151 (`MonsterStageEvent`) 完整 21 事件分发。
     *
     * 事件对照经 `CA.exe.c` 逐分支证实：
     * - 0: 位置同步（忽略）
     * - 1 → S6 (`speed`)
     * - 2 → S7 (`AttackMon` / `SealMon` 动画动作)
     * - 4 → S1 (`freeze` + 坐标)
     * - 5 → S8 (`thaw`)
     * - 6 → S9 (`PudadakMon shrink`)
     * - 7 → S10 (`PudadakMon unshrink`)
     * - 8/9/10 → S11/12/13 (`collisionbase`/`collisionsize`/`centerbase`)
     * - 11 → S14 (`collisionAttr`)
     * - 12 → S15 (机器人合体阶段 1)
     * - 13 → S16 (机器人合体阶段 2)
     * - **14 → S17 (`valid`, 1B)**
     * - **15 → S18 (`combine`, 0B)**
     * - **16 → S19 (`trigger`, 1B)**
     * - **17 → S20 (`vanish`, 0B)**
     * - **18 → S21 (`disappear`, 0B)**
     * - **19 → S24 (`AttackMon` end, 0B)**
     * - **20 → S5 (`talk`, `EStr`)**
     */
    fun onStateEvent(objectId: Int, event: Int, rawPayload: ByteArray) {
        if (event == 0) return // 本地移动同步，单机房忽略

        val packet: PKT = synchronized(lock) {
            when (event) {
                1 -> {
                    if (!acceptsEventLocked(objectId) || objectId in lockedBossObjects || rawPayload.size != 1) return
                    val speed = decode1(rawPayload[0])
                    if (speed !in 1..255) return
                    MonsterSpeed(objectId, speed)
                }

                4 -> {
                    if (!acceptsEventLocked(objectId) || rawPayload.size != 8) return
                    if (!frozenObjects.add(objectId)) return
                    val x = decode4(rawPayload, 0)
                    val y = decode4(rawPayload, 4)
                    MonsterStageEvent(objectId, 1, x, y)
                }

                5 -> {
                    if (!acceptsEventLocked(objectId) || rawPayload.isNotEmpty()) return
                    if (!frozenObjects.remove(objectId)) return
                    MonsterStageEvent(objectId, 8)
                }

                12 -> {
                    if (rawPayload.size != 2) return
                    val partHp = decode2(rawPayload, 0)
                    handleRobotReadyLocked(objectId, phase = 1, hp = partHp) ?: return
                }

                13 -> {
                    if (rawPayload.isNotEmpty()) return
                    handleRobotReadyLocked(objectId, phase = 2, hp = null) ?: return
                }

                else -> handleNativeStateLocked(objectId, event, rawPayload) ?: return
            }
        }
        battle.broadcast(packet)
    }

    private fun handleRobotReadyLocked(objectId: Int, phase: Int, hp: Int?): PKT? {
        if (!acceptsEventLocked(objectId) || phase !in 1..2) return null
        val parts = currentStage.monsters.values.filter {
            it.initial && !it.boss && it.type.equals("bossRobot", ignoreCase = true)
        }
        if (parts.size != 2 || parts.none { it.id == objectId }) return null
        val boss = currentStage.monsters.values.singleOrNull {
            it.boss && it.type.equals("bossRobot", ignoreCase = true)
        } ?: return null

        if (phase == 1) {
            if (robotMergeAnnounced || robotReady[objectId] == 2) return null
            val part = parts.single { it.id == objectId }
            if (hp == null || hp !in 1..part.hp) return null
            robotReadyHealth[objectId] = hp
        } else if (!robotMergeAnnounced || robotReady[objectId] != 1) {
            return null
        }

        robotReady[objectId] = phase
        return when {
            phase == 1 && !robotMergeAnnounced && parts.all { (robotReady[it.id] ?: 0) >= 1 } -> {
                robotMergeAnnounced = true
                val sumHp = parts.sumOf { robotReadyHealth.getValue(it.id) }.coerceIn(1, 255)
                Logger.info("[Monster] 机器人合体阶段 1 就绪 sumHp=$sumHp")
                MonsterStageEvent(parts.first().id, 15, encodedPayload = byteArrayOf((sumHp xor XOR_BYTE_MASK).toByte()))
            }

            phase == 2 && !robotTransformed && parts.all { robotReady[it.id] == 2 } -> {
                robotTransformed = true
                val sumHp = parts.sumOf { robotReadyHealth.getValue(it.id) }.coerceIn(1, 255)
                monsterHealth[boss.id] = sumHp
                Logger.info("[Monster] 机器人合体变身 boss=${boss.id} hp=$sumHp")
                MonsterStageEvent(parts.first().id, 16)
            }

            else -> null
        }
    }

    private fun handleNativeStateLocked(objectId: Int, request: Int, payload: ByteArray): PKT? {
        if (!acceptsEventLocked(objectId) || objectId in lockedBossObjects) return null
        val definition = definitionOfLocked(objectId) ?: return null

        return when (request) {
            2 -> {
                if (payload.size !in setOf(6, 10)) return null
                val action = decode2(payload, 4)
                if (definition.type.equals("bossMon2", ignoreCase = true) && action == 6) {
                    if (payload.size != 10) return null
                } else if (payload.size != 6) {
                    return null
                }
                MonsterStageEvent(objectId, 7, encodedPayload = payload.copyOf())
            }

            6 -> {
                if (!definition.type.equals("pudadakMon", ignoreCase = true) ||
                    payload.isNotEmpty() || !shrunkenObjects.add(objectId)
                ) return null
                MonsterStageEvent(objectId, 9)
            }

            7 -> {
                if (!definition.type.equals("pudadakMon", ignoreCase = true) ||
                    payload.isNotEmpty() || !shrunkenObjects.remove(objectId)
                ) return null
                MonsterStageEvent(objectId, 10)
            }

            8, 9, 10 -> {
                if (payload.size != 4) return null
                val a = decode2(payload, 0).toShort().toInt()
                val b = decode2(payload, 2).toShort().toInt()
                if (a !in -512..512 || b !in -512..512) return null
                val sEvent = when (request) {
                    8 -> 11
                    9 -> 12
                    else -> 13
                }
                MonsterStageEvent(objectId, sEvent, encodedPayload = payload.copyOf())
            }

            11 -> {
                if (payload.size != 2) return null
                val idx = decode1(payload[0])
                val value = decode1(payload[1])
                if (idx !in 0..3 || value !in 0..3) return null
                MonsterStageEvent(objectId, 14, encodedPayload = payload.copyOf())
            }

            // C116 event 14 = valid (1B E1 0/1) -> S151 event 17 (BasicMon::SetValid, sub_586BEC L336499)
            14 -> {
                if (payload.size != 1 || decode1(payload[0]) !in 0..1) return null
                MonsterStageEvent(objectId, 17, encodedPayload = payload.copyOf())
            }

            // C116 event 15 = combine (0B) -> S151 event 18 (RobotMon 拷贝合体血量+音效 190, sub_5895DC L339041)
            15 -> {
                if (payload.isNotEmpty()) return null
                if (!definition.type.equals("bossRobot", ignoreCase = true) || !definition.boss || !robotTransformed) {
                    return null
                }
                MonsterStageEvent(objectId, 18)
            }

            // C116 event 16 = trigger (1B E1 val) -> S151 event 19 (BasicMon::vtable+236, L336493)
            16 -> {
                if (payload.size != 1) return null
                MonsterStageEvent(objectId, 19, encodedPayload = payload.copyOf())
            }

            // C116 event 17 = vanish (0B) -> S151 event 20 (sub_586484, L336488)
            17 -> {
                if (payload.isNotEmpty()) return null
                MonsterStageEvent(objectId, 20)
            }

            // C116 event 18 = disappear (0B) -> S151 event 21 (vtable+284, L336483)
            18 -> {
                if (payload.isNotEmpty()) return null
                MonsterStageEvent(objectId, 21)
            }

            // C116 event 19 = AttackMon 状态结束 (0B) -> S151 event 24 (L334778 / L324957)
            19 -> {
                if (payload.isNotEmpty()) return null
                MonsterStageEvent(objectId, 24)
            }

            // C116 event 20 = CMapActionTalk (EStr) -> S151 event 5 (头顶 3s 对话气泡, L336575)
            20 -> {
                if (payload.size < 2) return null
                val strLen = decode2(payload, 0)
                if (strLen != payload.size - 2) return null
                MonsterStageEvent(objectId, 5, encodedPayload = payload.copyOf())
            }

            else -> {
                if (debugMode) {
                    Logger.debug("[Monster] 未处理的 C116 状态事件 id=$objectId event=$request bytes=${payload.size}")
                }
                null
            }
        }
    }

    /**
     * C→S op 117 kind=1 (`directionDest` 定向水柱)：
     * 使用原生 `S139 (slot=9, isSquareExplosion=0, shape=1, shapeParam=direction, bombFlags=power)` + 立即 `S138 state=0`，
     * 由客户端 `sub_564622` (L303930) 原生沿单向发射带阻挡判定的水柱。
     */
    fun onAttackDirectional(objectId: Int, power: Int, direction: Int, row: Int, col: Int) {
        val id: Int = synchronized(lock) {
            if (!acceptsEventLocked(objectId) || objectId in lockedBossObjects) return
            if (definitionOfLocked(objectId) == null) return
            if (direction !in 0..3 || power !in 0..14 || !validCell(col, row)) return
            battle.allocateMonsterBombId() ?: return
        }
        battle.broadcast(
            SetBomberEvent(
                slotId = MONSTER_BOMB_SLOT,
                originGridX = row,
                originGridY = col,
                bombToken = id,
                bombId = id,
                contextSlot = battle.contextSlot,
                isSquareExplosion = 0,
                bombFlags = power.coerceIn(0, 15),
                shape = BOMB_SHAPE_BEAM,
                shapeParam = direction,
            ),
            SetBombState(id, state = 0),
        )
    }

    /**
     * C→S op 117 kind=2 (`rectangle` Boss 空心矩形水波)：
     * 使用原生 `S139 (slot=9, isSquareExplosion=0, shape=2, shapeParam=distance)` + 350ms 短引信，
     * 由客户端 `sub_564622` (L303886) 原生绘制空心矩形框并自动钳制棋盘边界。
     */
    fun onAttackRectangle(objectId: Int, distance: Int, row: Int, col: Int) {
        val id: Int = synchronized(lock) {
            if (!acceptsEventLocked(objectId) || objectId in lockedBossObjects) return
            val definition = definitionOfLocked(objectId) ?: return
            if (!definition.boss || distance !in 1..14 || !validCell(col, row)) return
            battle.registerMonsterBomb(
                row = row,
                col = col,
                isMoving = false,
                fuseMs = RECTANGLE_FUSE_MS,
            ) ?: return
        }
        battle.broadcast(
            SetBomberEvent(
                slotId = MONSTER_BOMB_SLOT,
                originGridX = row,
                originGridY = col,
                bombToken = id,
                bombId = id,
                contextSlot = battle.contextSlot,
                isSquareExplosion = 0,
                bombFlags = 0,
                shape = BOMB_SHAPE_HOLLOW_RECT,
                shapeParam = distance,
            )
        )
    }

    /**
     * C→S op 117 kind=3 (`autokick` 飞行水泡)：
     * 下发带落点的 `S139 (bombFlags = power | 0x20)`，飞行 `durationMs` 后由 tick 发 `state=1` 落地；
     * 海豹系 (`MonSeal*`) 设方块爆炸且落地同 tick 立即引爆 (`landingFuseMs = 0`)，其余落地后续 2s 引爆。
     */
    fun onAttackProjectile(
        objectId: Int,
        power: Int,
        srcRow: Int,
        srcCol: Int,
        dstRow: Int,
        dstCol: Int,
        durationMs: Int,
        direction: Int,
    ) {
        val packet: PKT = synchronized(lock) {
            if (!acceptsEventLocked(objectId) || objectId in lockedBossObjects) return
            if (definitionOfLocked(objectId) == null) return
            if (power !in 0..14 || durationMs !in 1..10_000 || direction !in 0..3) return
            if (!validCell(srcCol, srcRow) || !validCell(dstCol, dstRow)) return

            val isSeal = currentStage.mapName.startsWith("MonSeal")
            val effectivePower = if (isSeal) power.coerceIn(0, 15) else (power + 1).coerceIn(1, 15)
            val id = battle.registerMonsterBomb(
                row = dstRow,
                col = dstCol,
                isMoving = true,
                fuseMs = durationMs.toLong(),
                landingFuseMs = if (isSeal) 0L else 2000L,
            ) ?: return

            SetBomberEvent(
                slotId = MONSTER_BOMB_SLOT,
                originGridX = srcRow,
                originGridY = srcCol,
                bombToken = id,
                bombId = id,
                contextSlot = battle.contextSlot,
                isSquareExplosion = if (isSeal) 1 else 0,
                bombFlags = (effectivePower and 0x0F) or bombFlagLaunched,
                landingGridX = dstRow,
                landingGridY = dstCol,
                launchDirection = direction,
                shape = BOMB_SHAPE_NORMAL,
            )
        }
        battle.broadcast(packet)
    }

    /** C→S op 117 kind=4 (`CGOSealBomb` `type=9` 坠落触地)。 */
    fun onProjectileImpact(objectId: Int, power: Int, row: Int, col: Int) {
        val packets = mutableListOf<PKT>()
        synchronized(lock) {
            if (!acceptsEventLocked(objectId) || objectId !in projectileObjects) return
            if (power !in 0..14 || !validCell(col, row)) return

            projectileObjects.remove(objectId)
            releasedObjects.add(objectId)
            packets += MonsterDespawn(objectId, 1)

            val isSeal = currentStage.mapName.startsWith("MonSeal")
            val effectivePower = if (isSeal) power.coerceIn(0, 15) else (power + 1).coerceIn(1, 15)
            if (isSeal) {
                val id = battle.allocateMonsterBombId()
                if (id != null) {
                    packets += SetBomberEvent(
                        slotId = MONSTER_BOMB_SLOT,
                        originGridX = row,
                        originGridY = col,
                        bombToken = id,
                        bombId = id,
                        contextSlot = battle.contextSlot,
                        isSquareExplosion = 1,
                        bombFlags = effectivePower,
                    )
                    packets += SetBombStateBatch(listOf(id))
                }
            } else {
                val id = battle.registerMonsterBomb(row = row, col = col, isMoving = false, fuseMs = battle.config.bombFuseMs)
                if (id != null) {
                    packets += SetBomberEvent(
                        slotId = MONSTER_BOMB_SLOT,
                        originGridX = row,
                        originGridY = col,
                        bombToken = id,
                        bombId = id,
                        contextSlot = battle.contextSlot,
                        isSquareExplosion = 0,
                        bombFlags = effectivePower,
                    )
                }
            }
        }
        battle.broadcast(*packets.toTypedArray())
    }

    /**
     * C→S op 117 kind=6 (`bombMissile` / `bombMissile2` 碰撞自爆)：
     * 实测客户端顺序为 `C114(自伤死) → C117(kind=6) → C137(销毁)`，故必须允许已 `defeated` 但尚未 `released` 的导弹触发单次瞬爆。
     */
    fun onMissileImpact(objectId: Int, power: Int, row: Int, col: Int) {
        val packets = mutableListOf<PKT>()
        synchronized(lock) {
            if (!acceptsEventContextLocked(objectId) || power !in 0..14 || !validCell(col, row)) return
            val actor = spawnedObjects[objectId] ?: return
            if (!actor.type.equals("bombMissile", ignoreCase = true) &&
                !actor.type.equals("bombMissile2", ignoreCase = true)
            ) return
            if (!detonatedMissiles.add(objectId)) return

            val id = battle.allocateMonsterBombId() ?: return
            val needsDeath = defeatedObjects.add(objectId)
            packets += SetBomberEvent(
                slotId = MONSTER_BOMB_SLOT,
                originGridX = row,
                originGridY = col,
                bombToken = id,
                bombId = id,
                contextSlot = battle.contextSlot,
                isSquareExplosion = 1,
                bombFlags = power.coerceIn(0, 15),
            )
            packets += SetBombStateBatch(listOf(id))
            if (needsDeath) {
                packets += MonsterStageEvent(objectId, 4)
            }
        }
        battle.broadcast(*packets.toTypedArray())
    }

    /** C→S op 155 (`MonsterLaserReq`) → S→C op 268 (`MonsterLaser`)。 */
    fun onLaser(
        objectId: Int,
        row: Int,
        col: Int,
        kind: Int,
        direction: Int,
        color: Int,
        width: Int,
        length: Int,
        durationMs: Int,
    ) {
        val packet: PKT = synchronized(lock) {
            if (!acceptsEventLocked(objectId) || objectId in lockedBossObjects) return
            val definition = definitionOfLocked(objectId) ?: return
            if (!definition.type.equals("bossRobot", ignoreCase = true) || !validCell(col, row)) return
            if (kind !in 1..2 || direction !in 1..3 || color !in 1..2 ||
                width !in 1..15 || length !in 1..15 || durationMs !in 1..10_000
            ) return
            MonsterLaser(row, col, kind, direction, color, width, length, durationMs)
        }
        battle.broadcast(packet)
    }

    /** C→S op 168 (`MonsterKickReq`) → S→C op 151 event 22（海星壳踢出，80ms 去重 + 清冰冻态）。 */
    fun onKick(objectId: Int, rawPayload18: ByteArray, client: ClientSocket) {
        val packet: PKT = synchronized(lock) {
            if (!acceptsEventLocked(objectId) || rawPayload18.size != 18) return
            val definition = definitionOfLocked(objectId) ?: return
            if (!definition.type.equals("starFishMon", ignoreCase = true)) return

            val direction = decode1(rawPayload18[0])
            val reportedSlot = decode1(rawPayload18[17])
            val slot = resolveHumanSlot(client, reportedSlot) ?: return
            if (direction !in 0..3 || !isHumanSlot(slot)) return

            val (mapW, mapH) = mapDimensions()
            val maxPxX = mapW * 40
            val maxPxY = mapH * 40
            val c0 = decode4(rawPayload18, 1)
            val c1 = decode4(rawPayload18, 5)
            val c2 = decode4(rawPayload18, 9)
            val c3 = decode4(rawPayload18, 13)
            if (c0 !in 0 until maxPxX || c1 !in 0 until maxPxY) return
            if (c2 != -1 && c2 !in 0 until maxPxX) return
            if (c3 != -1 && c3 !in 0 until maxPxY) return

            val now = System.currentTimeMillis()
            val prev = kickRequests[objectId]
            if (prev != null && prev.first.contentEquals(rawPayload18) && now - prev.second < KICK_DEDUPE_WINDOW_MS) {
                return
            }
            kickRequests[objectId] = rawPayload18.copyOf() to now
            // 踢出滚动脱离锁壳态，停下后被炸必须允许重新冰冻
            frozenObjects.remove(objectId)

            val response = rawPayload18.copyOf()
            response[17] = (slot xor XOR_BYTE_MASK).toByte()
            MonsterStageEvent(objectId, 22, encodedPayload = response)
        }
        battle.broadcast(packet)
    }

    /** C→S op 141 (`MonsterDropItemReq`) → S→C op 183 (`MonsterDropItem`)，坐标序 `(row, col)`。 */
    fun onDropItem(itemType: Int, row: Int, col: Int) {
        val packet: PKT = synchronized(lock) {
            if (battle.isDestroyed || battle.gameOverSent || transitionPending || boardLoadPending || !controlGranted) return
            if (!validCell(col, row)) return
            val itemSeq = nextDropItemSeq
            nextDropItemSeq = if (itemSeq >= MONSTER_DROP_ITEM_SEQ_LAST) MONSTER_DROP_ITEM_SEQ_FIRST else itemSeq + 1
            MonsterDropItem(itemSeq = itemSeq, itemType = itemType, row = row, col = col)
        }
        battle.broadcast(packet)
    }

    /** C→S op 131 (`MonsterBoardLoadedReq`, `E1=1`)：客户端换盘加载完成，下发 `S136` + `S178`。 */
    fun onBoardLoaded(client: ClientSocket) {
        val packets = mutableListOf<PKT>()
        val playTimeMs: Long
        synchronized(lock) {
            if (battle.isDestroyed || !boardLoadPending || boardStartSent) return
            if (!battle.isActiveClient(client)) return
            boardStartSent = true
            boardLoadPending = false
            playTimeMs = currentStage.playTimeMs

            val activeSlots = battle.slotStates.indices.filter { battle.slotStates[it].hasSeat }
                .ifEmpty { listOf(chiefSlotProvider()) }
            packets += GameStart(
                timerASec = (playTimeMs / 1000L).toInt(),
                timerBSec = 0,
                peers = activeSlots.map { GameStartPeer(it) },
            )
            packets += grantInitialControlPackets()
        }
        battle.resumeAfterStageTransition(playTimeMs)
        battle.broadcast(*packets.toTypedArray())
        Logger.info("[Monster] 换盘加载完成，已发 GameStart + MonsterControl map=${currentStage.mapName}")
    }

    /** 检查当前小关全部目标是否清空：末关触发最终胜利，中间关触发无缝换盘。 */
    private fun checkStageClear() {
        val nextBoardRef: MapRef?
        val stageSnapshot: MonsterStageSpec
        val winnerSlot: Int
        synchronized(lock) {
            if (battle.isDestroyed || battle.gameOverSent || transitionPending || boardLoadPending) return
            if (stageObjectives.isEmpty()) return
            val allCleared = stageObjectives.all { id ->
                id in defeatedObjects && (id in releasedObjects || spawnedObjects[id]?.settlesOnDefeat == true)
            }
            if (!allCleared) return

            stageSnapshot = currentStage
            nextBoardRef = MapCatalog.nextMonsterBoard(stageSnapshot.mapName)
            winnerSlot = battle.slotStates.indices.firstOrNull { battle.slotStates[it].isHuman } ?: chiefSlotProvider()
            if (nextBoardRef != null) {
                transitionPending = true
            }
        }

        if (nextBoardRef == null) {
            Logger.info("[Monster] 最终关通关: ${stageSnapshot.mapName}")
            battle.finishMonsterVictory(winnerSlot, detail = "怪物关卡全部通关(${stageSnapshot.mapName})")
            return
        }

        val nextStageSpec = MapCatalog.of(nextBoardRef.mapId)?.monsterStage
        if (nextStageSpec == null) {
            Logger.warn("[Monster] 下一关 ${nextBoardRef.name} 缺少 StageClearScript，改按最终关结算")
            synchronized(lock) { transitionPending = false }
            battle.finishMonsterVictory(winnerSlot, detail = "怪物关通关(${stageSnapshot.mapName})")
            return
        }

        Logger.info("[Monster] 中间关通关: ${stageSnapshot.mapName} -> 即将切换至 ${nextBoardRef.name} (nativeMapId=${nextBoardRef.themeId})")
        battle.pauseForStageTransition()
        val settlement = battle.settleIntermediateStage()
        battle.broadcast(
            InitGameResult(mode = 0, mapId = 0),
            GameResult(
                winnerTeam = WINNER_TEAM_HUMAN,
                delayMs = 500,
                expBefore = settlement?.expBefore ?: 0,
                expAfter = settlement?.expAfter ?: battle.config.winExpAfter,
                lucciGained = settlement?.lucciGained ?: battle.config.winLucci,
                slots = battle.resultSlots(),
                missionClear = false,
            ),
        )

        val waitMs = if (battle.config.monsterStageTransitionDelayOverrideMs >= 0L) {
            battle.config.monsterStageTransitionDelayOverrideMs
        } else {
            stageSnapshot.gameEndWaitMs
        }
        battle.scheduleManagedTask(waitMs) {
            val proceed = synchronized(lock) {
                if (battle.isDestroyed || !transitionPending) {
                    false
                } else {
                    resetForNextBoardLocked(nextBoardRef.mapId, nextStageSpec)
                    true
                }
            }
            if (!proceed) return@scheduleManagedTask
            onBoardChanged(nextBoardRef)
            battle.broadcast(
                MonsterNextBoard(
                    nativeMapId = nextBoardRef.themeId,
                    randomSeed = Random.nextInt(0x7FFFFFFF),
                )
            )
            Logger.info("[Monster] 已下发 S169 mode=2 MonsterNextBoard map=${nextBoardRef.name} nativeId=${nextBoardRef.themeId}")
        }
    }

    private fun resetForNextBoardLocked(nextMapId: Int, nextStage: MonsterStageSpec) {
        currentMapId = nextMapId
        currentStage = nextStage
        controlGranted = false
        transitionPending = false
        boardLoadPending = true
        boardStartSent = false

        releasedObjects.clear()
        frozenObjects.clear()
        shrunkenObjects.clear()
        defeatedObjects.clear()
        detonatedMissiles.clear()
        angryBossObjects.clear()
        lockedBossObjects.clear()
        spawnedObjects.clear()
        projectileObjects.clear()
        monsterHealth.clear()
        stageObjectives.clear()
        stageObjectives.addAll(nextStage.objectives)
        robotReady.clear()
        robotReadyHealth.clear()
        robotMergeAnnounced = false
        robotTransformed = false
        kickRequests.clear()
    }

    companion object {
        /** 客户端 `CNetObjectMan` 最大对象容量 (`0x78` = 120)。 */
        const val MONSTER_OBJECT_CAPACITY = 120

        /** 动态刷怪与投射物起始网络对象 ID（初始怪占据 `0..48`）。 */
        const val DYNAMIC_OBJECT_ID_START = 64

        /** 脚本自伤/未绑定玩家的槽位哨兵值 (`(uint16)-1`)。 */
        const val SCRIPT_SELF_SLOT = 65535

        /** 海星踢壳相同载荷去重窗口 (ms)。 */
        const val KICK_DEDUPE_WINDOW_MS = 80L

        /** Boss 空心矩形水波（C117 kind=2）短引信时长 (ms)。 */
        const val RECTANGLE_FUSE_MS = 350L

        /** 怪物掉落道具序号循环区间（避开普通箱子道具区间）。 */
        const val MONSTER_DROP_ITEM_SEQ_FIRST = 0x4000
        const val MONSTER_DROP_ITEM_SEQ_LAST = 0x7FFF

        private const val XOR_BYTE_MASK = 0x5A
        private const val XOR_SHORT_MASK = 0xA569
        private const val XOR_INT_MASK = 0x96CA5395.toInt()

        private fun decode1(b: Byte): Int = (b.toInt() and 0xFF) xor XOR_BYTE_MASK

        private fun decode2(buf: ByteArray, offset: Int): Int =
            (((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)) xor XOR_SHORT_MASK

        private fun decode4(buf: ByteArray, offset: Int): Int =
            ByteBuffer.wrap(buf, offset, 4).int xor XOR_INT_MASK
    }
}
