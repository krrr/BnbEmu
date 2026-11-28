package bnbemu.netty

import bnbemu.data.FxdynMapInfo
import bnbemu.data.MapCatalog
import bnbemu.data.MonsterStageSpec
import bnbemu.domain.PlayerProfile
import bnbemu.handler.battle.MonsterAttackReq
import bnbemu.handler.battle.MonsterBoardLoadedReq
import bnbemu.handler.battle.MonsterCreateReq
import bnbemu.handler.battle.MonsterDamageReq
import bnbemu.handler.battle.MonsterDestroyReq
import bnbemu.handler.battle.MonsterDropItemReq
import bnbemu.handler.battle.MonsterFinishReq
import bnbemu.handler.battle.MonsterHitReq
import bnbemu.handler.battle.MonsterKickReq
import bnbemu.handler.battle.MonsterLaserReq
import bnbemu.handler.battle.MonsterStateReq
import bnbemu.handler.battle.StageClearAliveReq
import bnbemu.handler.lobby.CreateSessionReq
import bnbemu.handler.room.OpenCloseSlotReq
import bnbemu.handler.room.StartGameReq
import bnbemu.packet.battle.BOMB_SHAPE_BEAM
import bnbemu.packet.battle.BOMB_SHAPE_HOLLOW_RECT
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
import bnbemu.packet.battle.SetBomberEvent
import bnbemu.packet.lobby.SESSION_TYPE_MONSTER
import bnbemu.server.BATTLE_CONTEXT_MONSTER
import bnbemu.server.Bomb
import bnbemu.server.FLAG_CLOSED
import bnbemu.server.MonsterBattleController
import bnbemu.server.Room
import bnbemu.server.state
import bnbemu.service.PlayerService
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class MonsterBattleTest {

    private lateinit var ch: EmbeddedChannel
    private lateinit var cs: ClientSocket
    private lateinit var room: Room
    private lateinit var tester: PlayerProfile

    @BeforeEach
    fun setUp() {
        cs = ClientSocket()
        ch = EmbeddedChannel(cs)
        tester = PlayerService.registerProfile(
            PlayerProfile(userId = 9701, username = "mon_tester", charName = "mon_tester", bomber = 0)
        )
        cs.playerId = tester.userId
        room = Room.enter(Room())
        room.sessionType = SESSION_TYPE_MONSTER
        room.battleConfig.gameOverDelayMs = 0L
        room.battleConfig.resultReturnDelayMs = -1L
        room.battleConfig.monsterStageTransitionDelayOverrideMs = 20L
        room.setupMonsterSlots(tester, cs)
    }

    @AfterEach
    fun tearDown() {
        Room.leave()
        ch.finish()
    }

    private fun mapIdOf(name: String): Int =
        requireNotNull(MapCatalog.refByName(name)) { "Missing map $name in fxdynmapinfo.json" }.mapId

    private fun startMonsterBoard(mapName: String): MonsterBattleController {
        val ref = requireNotNull(MapCatalog.refByName(mapName))
        room.mapId = ref.mapId
        room.themeId = ref.themeId
        val battle = room.startGame(ref.mapId)
        val controller = requireNotNull(battle.monster) { "Map $mapName has no MonsterStageSpec" }
        controller.grantInitialControl()
        ch.outboundMessages().clear()
        return controller
    }

    private fun drain(): List<PKT> {
        @Suppress("UNCHECKED_CAST")
        return ch.outboundMessages().toList() as List<PKT>
    }

    private fun collectUntilOp(op: Int, minCount: Int = 1, timeoutMs: Long = 2000L): List<PKT> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val list = drain()
            if (list.count { opcodeOf(it) == op } >= minCount) return list
            Thread.sleep(10)
        }
        return drain()
    }

    private fun encode(pkt: PKT): ByteBuf {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf))
        return buf
    }

    private fun encodeReq(block: SendPacketBase.() -> Unit): ByteBuf {
        val buf = Unpooled.buffer()
        SendPacketBase(buf).block()
        return buf
    }

    private fun handleHex(hex: String, handler: PKTHandler) {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val buf = Unpooled.wrappedBuffer(bytes)
        try {
            with(handler) { buf.handle(cs) }
        } finally {
            buf.release()
        }
    }

    private fun handleBuf(buf: ByteBuf, handler: PKTHandler) {
        try {
            with(handler) { buf.handle(cs) }
        } finally {
            buf.release()
        }
    }

    private fun opcodeOf(pkt: PKT): Int = OpcodeManager.getSendOp(pkt::class.java)

    private fun scriptInitBytes(scriptClass: Int): ByteArray =
        ByteBuffer.allocate(24).apply {
            for (v in listOf(-1, 1, 0, 0, scriptClass, -1)) {
                putInt(v xor 0x96CA5395.toInt())
            }
        }.array()

    // ========================================================================
    // 1. Opcode 绑定与 XML 数据源
    // ========================================================================

    @Test
    fun `all monster recv handlers and send packets are registered`() {
        val expectedRecv = mapOf(
            114 to ("MonsterDamageReq" to MonsterDamageReq),
            115 to ("MonsterFinishReq" to MonsterFinishReq),
            116 to ("MonsterStateReq" to MonsterStateReq),
            117 to ("MonsterAttackReq" to MonsterAttackReq),
            127 to ("StageClearAliveReq" to StageClearAliveReq),
            131 to ("MonsterBoardLoadedReq" to MonsterBoardLoadedReq),
            136 to ("MonsterCreateReq" to MonsterCreateReq),
            137 to ("MonsterDestroyReq" to MonsterDestroyReq),
            141 to ("MonsterDropItemReq" to MonsterDropItemReq),
            155 to ("MonsterLaserReq" to MonsterLaserReq),
            168 to ("MonsterKickReq" to MonsterKickReq),
            169 to ("MonsterHitReq" to MonsterHitReq),
        )
        for ((op, pair) in expectedRecv) {
            assertEquals(pair.first, OpcodeManager.recvOps[op], "recv op $op name mismatch")
            assertSame(pair.second, handlerArray[op], "recv op $op handler mismatch")
        }

        assertEquals(151, opcodeOf(MonsterStageEvent(0, 4)))
        assertEquals(151, opcodeOf(MonsterHealth(0, 10)))
        assertEquals(151, opcodeOf(MonsterBossLocked(0)))
        assertEquals(151, opcodeOf(MonsterSpeed(0, 5)))
        assertEquals(169, opcodeOf(MonsterNextBoard(318, 12345)))
        assertEquals(177, opcodeOf(MonsterCreate(64, 0, 5, ByteArray(24))))
        assertEquals(178, opcodeOf(MonsterControl(0, 0)))
        assertEquals(179, opcodeOf(MonsterDespawn(0, 1)))
        assertEquals(183, opcodeOf(MonsterDropItem(0x4000, 1, 2, 3)))
        assertEquals(268, opcodeOf(MonsterLaser(1, 2, 1, 1, 1, 1, 1, 1000)))
    }

    @Test
    fun `xml parser extracts all monster stages accurately directly from map bins`() {
        val monStages = MapCatalog.all.mapNotNull { it.monsterStage }
        assertTrue(monStages.size >= 190, "Expected >=190 parsed monster stages, got ${monStages.size}")

        // XML 注释剥离：MonPen11=10, MonSeal21=3, MonTurtle15=14
        assertEquals(10, MapCatalog.byName.getValue("MonPen11").monsterStage!!.monsters.size)
        assertEquals(3, MapCatalog.byName.getValue("MonSeal21").monsterStage!!.monsters.size)
        assertEquals(14, MapCatalog.byName.getValue("MonTurtle15").monsterStage!!.monsters.size)

        // 单引号属性 NoInfluenceGameEnd='1'：MonTurtle16 仅 12..17 六只幽灵计入通关目标
        val turtle16 = MapCatalog.byName.getValue("MonTurtle16").monsterStage!!
        assertEquals((12..17).toSet(), turtle16.objectives)

        // MonTurtle02 终结型动态海星 (class=1081 无 selfdie; class=108 有 selfdie)
        val turtle02 = MapCatalog.byName.getValue("MonTurtle02").monsterStage!!
        assertTrue(turtle02.findByScriptClass(1081)!!.settlesOnDefeat)
        assertFalse(turtle02.findByScriptClass(108)!!.settlesOnDefeat)

        // MonPen08 波次生成器声明 selfDamage="1"
        val pen08 = MapCatalog.byName.getValue("MonPen08").monsterStage!!
        assertEquals(1, pen08.monsters.getValue(0).maxSelfDamage)
        assertEquals(0, MapCatalog.byName.getValue("MonPen01").monsterStage!!.monsters.values.first { it.type == "iceMon" }.maxSelfDamage)

        // 三关组推导与真实 EN sort_order (MonPen01 = 318)
        assertEquals("MonPen02", MapCatalog.nextMonsterBoard("MonPen01")?.name)
        assertEquals("MonPen03", MapCatalog.nextMonsterBoard("MonPen02")?.name)
        assertNull(MapCatalog.nextMonsterBoard("MonPen03"))
        assertEquals("MonPen01", MapCatalog.firstMonsterBoard("MonPen03")?.name)
        assertEquals(318, MapCatalog.refByName("MonPen01")?.themeId)

        // 每张 Mon* 关卡初始怪均按 0..K-1 密集编号且存在通关目标
        for (stage in monStages.filter { it.mapName.startsWith("Mon") }) {
            val initialIds = stage.monsters.values.filter { it.initial }.map { it.id }
            assertEquals((0 until initialIds.size).toList(), initialIds, stage.mapName)
            assertTrue(stage.objectives.isNotEmpty(), "${stage.mapName} must have objectives")
        }

        // fxdynmapinfo.json 已完整开放全部 5 大怪物主题与随机池（含 Monster37~39、MonSeal/MonTurtle/MonRobot）
        val playable = FxdynMapInfo.records.filter { it.flag == 0 }
        val byName = playable.associateBy { it.name }
        for ((name, expectedSort, expectedStage, expectedTail1) in listOf(
            listOf("Monster37", 449, "npc", 1),
            listOf("Monster38", 450, "npc", 0),
            listOf("Monster39", 451, "boss", 0),
        )) {
            val r = byName.getValue(name as String)
            assertEquals("Monster", r.group)
            assertEquals(expectedStage, r.stage)
            assertEquals(expectedSort, r.sortOrder)
            assertEquals(expectedTail1, r.tail1)
            assertNotNull(MapCatalog.monsterStageOf(MapCatalog.refByName(name)!!.mapId))
        }
        for ((group, count, randomName) in listOf(
            Triple("MonSeal", 27, "MonSealRandom"),
            Triple("MonTurtle", 24, "MonTurtleRandom"),
            Triple("MonRobot", 18, "MonRobotRandom"),
        )) {
            val groupRecs = playable.filter { it.group == group && it.subtype == 0 }
            assertEquals(count, groupRecs.size, "$group playable count")
            for (i in 1..count) {
                val r = byName.getValue(group + i.toString().padStart(2, '0'))
                assertEquals(group, r.group)
                assertEquals("npc", r.stage)
                assertEquals(if (i % 3 == 1) 1 else 0, r.tail1)
                assertTrue(r.sortOrder > 0)
            }
            val rand = byName.getValue(randomName)
            assertEquals(group, rand.group)
            assertEquals(6, rand.subtype)
            assertEquals(0, rand.sortOrder)
        }
    }

    // ========================================================================
    // 2. 建房、槽位管理与开局立即授权 S178（坑 1 验证）
    // ========================================================================

    @Test
    fun `CreateSessionReq mode 14 initializes human-only room and StartGameReq grants control immediately`() {
        val createReq = encodeReq {
            EncodeCipherStr("Monster Raid")
            EncodeCipherStr("")
            Encode1(0)
            Encode1(1)
            Encode1(0)
            Encode1(SESSION_TYPE_MONSTER)
            Encode4(3)
        }
        handleBuf(createReq, CreateSessionReq)
        val createdRoom = Room.current!!
        assertEquals(SESSION_TYPE_MONSTER, createdRoom.sessionType)
        assertEquals("MonPen01", MapCatalog.ref(createdRoom.mapId)?.name)
        assertEquals(318, createdRoom.themeId)
        assertTrue(createdRoom.slots[0].isHuman)
        for (i in 1..3) {
            assertFalse(createdRoom.slots[i].isAI, "Monster mode must never spawn AI bots")
            assertEquals(FLAG_CLOSED, createdRoom.slots[i].state.toInt())
        }
        val createOps = drain().map(::opcodeOf)
        assertTrue(83 in createOps)
        assertFalse(103 in createOps, "Monster room entry chain must not emit PracticeSetSlotAI")

        // OpenCloseSlotReq in monster mode opens/closes slot without spawning AI
        ch.outboundMessages().clear()
        handleBuf(encodeReq { Encode1((2 shl 4) or 0) }, OpenCloseSlotReq)
        assertFalse(createdRoom.slots[2].isAI)
        assertEquals(0, createdRoom.slots[2].state.toInt())
        assertEquals(listOf(106), drain().map(::opcodeOf))

        // StartGameReq passing MonPen02 normalizes back to MonPen01 & immediately emits MonsterControl (178)
        ch.outboundMessages().clear()
        val pen02Ref = MapCatalog.refByName("MonPen02")!!
        val startReq = encodeReq {
            Encode4(pen02Ref.mapId)
            Encode2(pen02Ref.themeId)
            Encode1(0)
            Encode4(pen02Ref.mapId)
            Encode1(0)
            Encode4(0)
        }
        handleBuf(startReq, StartGameReq)

        val startPackets = drain()
        val startOps = startPackets.map(::opcodeOf)
        val pen01Ref = MapCatalog.refByName("MonPen01")!!
        val pen01Stage = MapCatalog.monsterStageOf(pen01Ref.mapId)!!
        assertEquals(pen01Ref.mapId, createdRoom.mapId, "Must normalize to part 1")
        assertEquals(BATTLE_CONTEXT_MONSTER, createdRoom.battle!!.contextSlot)
        assertEquals(pen01Stage.playTimeMs, createdRoom.battle!!.config.matchTimeoutMs)
        assertTrue(createdRoom.battle!!.monster!!.controlGranted)

        // Verify [111, 112, 100, 136] followed immediately by initialCount x 178 (NO waiting for C149!)
        assertEquals(listOf(111, 112, 100, 136), startOps.take(4))
        val controlPkts = startPackets.drop(4)
        assertEquals(pen01Stage.initialCount, controlPkts.size)
        controlPkts.forEachIndexed { expectedId, pkt ->
            assertEquals(178, opcodeOf(pkt))
            val b = encode(pkt)
            try {
                assertEquals(expectedId, b.Decode2)
                assertEquals(0, b.Decode1)
                assertEquals(0, b.readableBytes())
            } finally {
                b.release()
            }
        }
    }

    // ========================================================================
    // 3. C116 ↔ S151 完整事件表（含坑 2 修正：14→17, 15→18, 16→19, 17→20, 18→21, 19→24, 20→5）
    // ========================================================================

    @Test
    fun `C116 event 14 valid and event 15 combine plus events 16 through 20 map to verified S151 events`() {
        startMonsterBoard("MonRobot18")

        // C116 event 14 (valid=1) -> S151 event 17 with 1B payload (fixed from reference branch!)
        handleBuf(encodeReq { Encode2(0); Encode2(14); Encode1(1) }, MonsterStateReq)
        val p17 = encode(drain().single { opcodeOf(it) == 151 })
        try {
            assertEquals(14, p17.Decode2); assertEquals(0, p17.Decode2); assertEquals(17, p17.Decode1)
            assertEquals(1, p17.Decode1); assertEquals(0, p17.readableBytes())
        } finally { p17.release() }
        ch.outboundMessages().clear()

        // Robot two-phase ready: C116 event 12 (both halves) -> S151 event 15 (sumHp=8)
        handleBuf(encodeReq { Encode2(0); Encode2(12); Encode2(4) }, MonsterStateReq)
        assertTrue(drain().isEmpty())
        handleBuf(encodeReq { Encode2(1); Encode2(12); Encode2(4) }, MonsterStateReq)
        val p15 = encode(drain().single { opcodeOf(it) == 151 })
        try {
            assertEquals(14, p15.Decode2); assertEquals(0, p15.Decode2); assertEquals(15, p15.Decode1)
            assertEquals(8, p15.Decode1); assertEquals(0, p15.readableBytes())
        } finally { p15.release() }
        ch.outboundMessages().clear()

        // Phase 2: C116 event 13 (both halves) -> S151 event 16 (0B)
        handleBuf(encodeReq { Encode2(0); Encode2(13) }, MonsterStateReq)
        assertTrue(drain().isEmpty())
        handleBuf(encodeReq { Encode2(1); Encode2(13) }, MonsterStateReq)
        val p16 = encode(drain().single { opcodeOf(it) == 151 })
        try {
            assertEquals(14, p16.Decode2); assertEquals(0, p16.Decode2); assertEquals(16, p16.Decode1)
            assertEquals(0, p16.readableBytes())
        } finally { p16.release() }
        ch.outboundMessages().clear()

        // C116 event 15 (combine, 0B!) -> S151 event 18 (0B!) (fixed from reference branch!)
        handleBuf(encodeReq { Encode2(2); Encode2(15) }, MonsterStateReq)
        val p18 = encode(drain().single { opcodeOf(it) == 151 })
        try {
            assertEquals(14, p18.Decode2); assertEquals(2, p18.Decode2); assertEquals(18, p18.Decode1)
            assertEquals(0, p18.readableBytes())
        } finally { p18.release() }
        ch.outboundMessages().clear()

        // C116 event 16 (trigger, 1B) -> S151 event 19 (1B)
        handleBuf(encodeReq { Encode2(2); Encode2(16); Encode1(7) }, MonsterStateReq)
        val p19 = encode(drain().single { opcodeOf(it) == 151 })
        try {
            assertEquals(14, p19.Decode2); assertEquals(2, p19.Decode2); assertEquals(19, p19.Decode1)
            assertEquals(7, p19.Decode1); assertEquals(0, p19.readableBytes())
        } finally { p19.release() }
        ch.outboundMessages().clear()

        // C116 event 17 (vanish, 0B) -> S151 event 20 (0B)
        handleBuf(encodeReq { Encode2(2); Encode2(17) }, MonsterStateReq)
        val p20 = encode(drain().single { opcodeOf(it) == 151 })
        try {
            assertEquals(14, p20.Decode2); assertEquals(2, p20.Decode2); assertEquals(20, p20.Decode1)
            assertEquals(0, p20.readableBytes())
        } finally { p20.release() }
        ch.outboundMessages().clear()

        // C116 event 18 (disappear, 0B) -> S151 event 21 (0B)
        handleBuf(encodeReq { Encode2(2); Encode2(18) }, MonsterStateReq)
        val p21 = encode(drain().single { opcodeOf(it) == 151 })
        try {
            assertEquals(14, p21.Decode2); assertEquals(2, p21.Decode2); assertEquals(21, p21.Decode1)
            assertEquals(0, p21.readableBytes())
        } finally { p21.release() }
        ch.outboundMessages().clear()

        // C116 event 19 (AttackMon end, 0B) -> S151 event 24 (0B)
        handleBuf(encodeReq { Encode2(2); Encode2(19) }, MonsterStateReq)
        val p24 = encode(drain().single { opcodeOf(it) == 151 })
        try {
            assertEquals(14, p24.Decode2); assertEquals(2, p24.Decode2); assertEquals(24, p24.Decode1)
            assertEquals(0, p24.readableBytes())
        } finally { p24.release() }
        ch.outboundMessages().clear()

        // C116 event 20 (talk, EStr) -> S151 event 5 (EStr verbatim)
        handleBuf(encodeReq { Encode2(2); Encode2(20); EncodeCipherStr("BOSS_TALK_01") }, MonsterStateReq)
        val p5 = encode(drain().single { opcodeOf(it) == 151 })
        try {
            assertEquals(14, p5.Decode2); assertEquals(2, p5.Decode2); assertEquals(5, p5.Decode1)
            assertEquals("BOSS_TALK_01", p5.DecodeCipherStr)
            assertEquals(0, p5.readableBytes())
        } finally { p5.release() }
    }

    @Test
    fun `C116 crocodile shrink, seal roll, and turtle collision fields echo native S151 events`() {
        // Crocodile shrink/unshrink (6->9, 7->10)
        startMonsterBoard("MonSeal01")
        for ((req, expectedS151) in listOf(6 to 9, 7 to 10)) {
            handleBuf(encodeReq { Encode2(0); Encode2(req) }, MonsterStateReq)
            val b = encode(drain().single { opcodeOf(it) == 151 })
            try {
                assertEquals(14, b.Decode2); assertEquals(0, b.Decode2); assertEquals(expectedS151, b.Decode1)
                assertEquals(0, b.readableBytes())
            } finally { b.release() }
            ch.outboundMessages().clear()
        }

        // Seal roll (event 2, action=6, 10B payload -> S151 event 7)
        startMonsterBoard("MonSeal03")
        handleBuf(encodeReq { Encode2(0); Encode2(2); Encode4(123); Encode2(6); Encode2(3); Encode2(2) }, MonsterStateReq)
        val roll = encode(drain().single { opcodeOf(it) == 151 })
        try {
            assertEquals(14, roll.Decode2); assertEquals(0, roll.Decode2); assertEquals(7, roll.Decode1)
            assertEquals(123, roll.Decode4); assertEquals(6, roll.Decode2); assertEquals(3, roll.Decode2); assertEquals(2, roll.Decode2)
            assertEquals(0, roll.readableBytes())
        } finally { roll.release() }

        // Turtle collisionbase/collisionsize (8->11, 9->12)
        startMonsterBoard("MonTurtle01")
        for ((rawHex, expected) in listOf(
            "a56ba561a56ba56a" to listOf(11, 2, 3),
            "a56ba560a54aa548" to listOf(12, 35, 33),
        )) {
            handleHex(rawHex, MonsterStateReq)
            val b = encode(drain().single { opcodeOf(it) == 151 })
            try {
                assertEquals(14, b.Decode2); assertEquals(2, b.Decode2); assertEquals(expected[0], b.Decode1)
                assertEquals(expected[1], b.Decode2); assertEquals(expected[2], b.Decode2)
                assertEquals(0, b.readableBytes())
            } finally { b.release() }
            ch.outboundMessages().clear()
        }
    }

    // ========================================================================
    // 4. C117 5 种怪物攻击（kind 1/2 使用原生 S139 shape=1/2）
    // ========================================================================

    @Test
    fun `C117 kind 1 directional beam uses single native S139 shape 1 plus immediate S138`() {
        startMonsterBoard("MonPen09")
        // C117: objId=0, kind=1, power=5, dir=2, row=6, col=7
        handleBuf(encodeReq { Encode2(0); Encode1(1); Encode1(5); Encode1(2); Encode1(6); Encode1(7) }, MonsterAttackReq)
        val packets = drain()
        assertEquals(listOf(139, 138), packets.map(::opcodeOf))
        val b139 = encode(packets[0])
        val bombId: Int
        try {
            assertEquals(BATTLE_CONTEXT_MONSTER, b139.Decode2)
            assertEquals(MONSTER_BOMB_SLOT, b139.Decode1)
            bombId = b139.Decode4
            assertEquals(0, b139.Decode4); assertEquals(0, b139.Decode4)
            assertEquals(6, b139.Decode1); assertEquals(7, b139.Decode1)
            assertEquals(0, b139.Decode1, "isSquareExplosion must be 0 so native beam branch runs")
            assertEquals(5, b139.Decode1, "bombFlags low nibble carries beam power")
            assertEquals(bombId, b139.Decode2)
            assertEquals(0, b139.Decode2)
            assertEquals(BOMB_SHAPE_BEAM, b139.Decode1)
            assertEquals(2, b139.Decode1, "beamDirection")
            assertEquals(0, b139.readableBytes())
        } finally { b139.release() }

        val b138 = encode(packets[1])
        try {
            assertEquals(1, b138.Decode1); assertEquals(bombId, b138.Decode2)
            assertEquals(0, b138.Decode1); assertEquals(0, b138.Decode4)
        } finally { b138.release() }
        assertTrue(room.battle!!.bombs.isEmpty(), "Instant directional beam leaves no fuse in bombs")
    }

    @Test
    fun `C117 kind 2 boss hollow rectangle uses single native S139 shape 2 with 350ms fuse`() {
        startMonsterBoard("MonPen09")
        // Non-boss (objId=0 basicMon) must be rejected
        handleBuf(encodeReq { Encode2(0); Encode1(2); Encode1(3); Encode1(10); Encode1(8) }, MonsterAttackReq)
        assertTrue(drain().isEmpty(), "Non-boss cannot issue rectangle attack")

        // Real captured MonPen09 boss rectangle C117: a5 68 58 59 50 52 -> id=1 (bossMon1), kind=2, dist=3, row=10, col=8
        handleHex("a56858595052", MonsterAttackReq)
        val packets = drain()
        assertEquals(listOf(139), packets.map(::opcodeOf), "Native hollow rectangle emits exactly 1 S139")
        val b139 = encode(packets[0])
        val bombId: Int
        try {
            assertEquals(BATTLE_CONTEXT_MONSTER, b139.Decode2)
            assertEquals(MONSTER_BOMB_SLOT, b139.Decode1)
            bombId = b139.Decode4
            assertEquals(0, b139.Decode4); assertEquals(0, b139.Decode4)
            assertEquals(10, b139.Decode1); assertEquals(8, b139.Decode1)
            assertEquals(0, b139.Decode1, "isSquareExplosion must be 0 so native hollow rect runs")
            assertEquals(0, b139.Decode1)
            assertEquals(bombId, b139.Decode2)
            assertEquals(0, b139.Decode2)
            assertEquals(BOMB_SHAPE_HOLLOW_RECT, b139.Decode1)
            assertEquals(3, b139.Decode1, "rectDistance")
            assertEquals(0, b139.readableBytes())
        } finally { b139.release() }
        assertEquals(1, room.battle!!.bombs.count { it.id == bombId && it.ownerSlotId == MONSTER_BOMB_SLOT })

        // Out-of-bounds cell (row 13 on 13-row board) must be rejected
        ch.outboundMessages().clear()
        handleBuf(encodeReq { Encode2(1); Encode1(2); Encode1(3); Encode1(13); Encode1(8) }, MonsterAttackReq)
        assertTrue(drain().isEmpty())
    }

    @Test
    fun `C117 kind 3 flying projectile sets launched flag and seal impact fuse`() {
        // MonSeal03: square explosion + landingFuseMs = 0
        startMonsterBoard("MonSeal03")
        handleBuf(
            encodeReq {
                Encode2(0); Encode1(3); Encode1(2 or 0x20)
                Encode1(7); Encode1(8); Encode1(1); Encode2(600); Encode1(4); Encode1(5)
            },
            MonsterAttackReq,
        )
        val sealPkt = encode(drain().single { opcodeOf(it) == 139 })
        val sealBombId: Int
        try {
            assertEquals(BATTLE_CONTEXT_MONSTER, sealPkt.Decode2)
            assertEquals(MONSTER_BOMB_SLOT, sealPkt.Decode1)
            sealBombId = sealPkt.Decode4
            assertEquals(0, sealPkt.Decode4); assertEquals(0, sealPkt.Decode4)
            assertEquals(4, sealPkt.Decode1); assertEquals(5, sealPkt.Decode1)
            assertEquals(1, sealPkt.Decode1, "MonSeal flying bomb uses square explosion")
            assertEquals(2 or 0x20, sealPkt.Decode1)
            assertEquals(7, sealPkt.Decode1); assertEquals(8, sealPkt.Decode1); assertEquals(1, sealPkt.Decode1)
            assertEquals(sealBombId, sealPkt.Decode2); assertEquals(0, sealPkt.Decode2); assertEquals(0, sealPkt.Decode1)
            assertEquals(0, sealPkt.readableBytes())
        } finally { sealPkt.release() }
        val sealBomb = room.battle!!.getBomb(sealBombId)!!
        assertTrue(sealBomb.isMoving)
        assertEquals(0L, sealBomb.landingFuseMs)

        // Non-seal (MonPen09): normal cross + power+1 + landingFuseMs = 2000
        startMonsterBoard("MonPen09")
        handleBuf(
            encodeReq {
                Encode2(1); Encode1(3); Encode1(2 or 0x20)
                Encode1(7); Encode1(8); Encode1(2); Encode2(600); Encode1(4); Encode1(5)
            },
            MonsterAttackReq,
        )
        val penPkt = encode(drain().single { opcodeOf(it) == 139 })
        val penBombId: Int
        try {
            penPkt.Decode2; penPkt.Decode1
            penBombId = penPkt.Decode4
            repeat(2) { penPkt.Decode4 }
            penPkt.Decode1; penPkt.Decode1
            assertEquals(0, penPkt.Decode1)
            assertEquals(3 or 0x20, penPkt.Decode1, "Non-seal flying bomb uses power + 1")
        } finally { penPkt.release() }
        assertEquals(2000L, room.battle!!.getBomb(penBombId)!!.landingFuseMs)
    }

    @Test
    fun `C117 kind 4 seal falling bomb is square and ignites immediately on impact`() {
        startMonsterBoard("MonSeal03")
        handleBuf(encodeReq { Encode2(9); EncodeBuffer(ByteArray(24)) }, MonsterCreateReq)
        ch.outboundMessages().clear()

        handleBuf(encodeReq { Encode2(64); Encode1(4); Encode1(1); Encode1(7); Encode1(6) }, MonsterAttackReq)
        val packets = drain()
        assertEquals(listOf(179, 139, 138), packets.map(::opcodeOf))
        val b139 = encode(packets[1])
        try {
            assertEquals(BATTLE_CONTEXT_MONSTER, b139.Decode2)
            assertEquals(MONSTER_BOMB_SLOT, b139.Decode1)
            repeat(3) { b139.Decode4 }
            assertEquals(7, b139.Decode1); assertEquals(6, b139.Decode1)
            assertEquals(1, b139.Decode1, "MonSeal uses square explosion")
            assertEquals(1, b139.Decode1, "MonSeal power does not add +1")
        } finally { b139.release() }
        assertTrue(room.battle!!.bombs.isEmpty())
    }

    @Test
    fun `C117 kind 6 mechanical missile self-death before impact explodes once and works across ID reuse`() {
        for ((map, nativeType, scriptClass) in listOf(Triple("MonRobot03", 14, 10), Triple("MonRobot13", 15, 101))) {
            startMonsterBoard(map)
            val init = scriptInitBytes(scriptClass)
            repeat(2) {
                handleBuf(encodeReq { Encode2(nativeType); EncodeBuffer(init) }, MonsterCreateReq)
                assertEquals(1, drain().count { opcodeOf(it) == 177 })
                ch.outboundMessages().clear()

                // Captured sequence: C114(id=64, dmg=1, slot=65535) -> C117(id=64, kind=6, power=1, row=0, col=5) -> C137
                handleHex("a529a5685a96", MonsterDamageReq)
                assertEquals(1, drain().count { opcodeOf(it) == 151 })
                ch.outboundMessages().clear()

                handleHex("a5295c5b5a5f", MonsterAttackReq)
                val packets = drain()
                assertEquals(listOf(139, 138), packets.map(::opcodeOf), "Explode once without repeating S151 death")
                assertTrue(room.battle!!.bombs.isEmpty())
                ch.outboundMessages().clear()

                // Duplicate impact rejected
                handleHex("a5295c5b5a5f", MonsterAttackReq)
                assertTrue(drain().isEmpty())

                // Destroyed missile rejects stale impacts
                handleBuf(encodeReq { Encode2(64); Encode4(1) }, MonsterDestroyReq)
                ch.outboundMessages().clear()
                handleHex("a5295c5b5a5f", MonsterAttackReq)
                assertTrue(drain().isEmpty())
            }
        }
    }

    @Test
    fun `impact-fused seal bomb lands with state 1 before immediate state 0 ignition`() {
        val instant = Bomb(1, MONSTER_BOMB_SLOT, 0, 1, false, isMoving = true, expireAt = 100L, landingFuseMs = 0L)
        assertTrue(instant.updateState(100L)); assertEquals(1, instant.state)
        assertTrue(instant.updateState(100L)); assertEquals(0, instant.state)

        val normal = Bomb(2, 0, 0, 0, false, isMoving = true, expireAt = 100L)
        assertTrue(normal.updateState(100L)); assertEquals(1, normal.state)
        assertFalse(normal.updateState(101L)); assertEquals(2100L, normal.expireAt)
    }

    // ========================================================================
    // 5. C141 掉道具→S183、C155 激光→S268、C168 海星踢壳、C169/C127 消费
    // ========================================================================

    @Test
    fun `C141 MonsterDropItemReq replies with S183 preserving row col order`() {
        startMonsterBoard("MonPen01")
        handleBuf(encodeReq { Encode1(7); Encode1(5); Encode1(11) }, MonsterDropItemReq)
        val p183 = encode(drain().single { opcodeOf(it) == 183 })
        try {
            val seq = p183.Decode2
            assertTrue(seq in MonsterBattleController.MONSTER_DROP_ITEM_SEQ_FIRST..MonsterBattleController.MONSTER_DROP_ITEM_SEQ_LAST)
            assertEquals(7, p183.Decode1)
            assertEquals(5, p183.Decode1, "row")
            assertEquals(11, p183.Decode1, "col")
            assertEquals(0, p183.readableBytes())
        } finally { p183.release() }

        // Out-of-bounds cell rejected
        ch.outboundMessages().clear()
        handleBuf(encodeReq { Encode1(7); Encode1(13); Encode1(5) }, MonsterDropItemReq)
        assertTrue(drain().isEmpty())
    }

    @Test
    fun `C155 robot laser emits native S268 without creating bombs`() {
        startMonsterBoard("MonRobot03")
        handleHex("a568565d53585858585996ca5649", MonsterLaserReq)
        val out = encode(drain().single { opcodeOf(it) == 268 })
        try {
            assertEquals(9, out.Decode1); assertEquals(12, out.Decode1); assertEquals(7, out.Decode1)
            assertEquals(2, out.Decode1); assertEquals(2, out.Decode1); assertEquals(2, out.Decode1)
            assertEquals(2, out.Decode1); assertEquals(3, out.Decode1); assertEquals(1500, out.Decode4)
            assertEquals(0, out.readableBytes())
        } finally { out.release() }
        assertTrue(room.battle!!.bombs.isEmpty())
    }

    @Test
    fun `C168 starfish shell kick echoes S151 event 22 with 80ms dedupe and allows refreeze`() {
        startMonsterBoard("MonTurtle01")
        // Freeze shell 0 first
        handleBuf(encodeReq { Encode2(0); Encode2(4); Encode4(100); Encode4(100) }, MonsterStateReq)
        assertEquals(1, drain().count { opcodeOf(it) == 151 })
        ch.outboundMessages().clear()

        // Captured kick on id=9
        val kickHex = "a5605896ca51a596ca53c56935ac6a6935ac6aa5"
        handleHex(kickHex, MonsterKickReq)
        val pkt = encode(drain().single { opcodeOf(it) == 151 })
        try {
            assertEquals(14, pkt.Decode2); assertEquals(9, pkt.Decode2); assertEquals(22, pkt.Decode1)
            assertEquals(2, pkt.Decode1); assertEquals(560, pkt.Decode4); assertEquals(80, pkt.Decode4)
            assertEquals(-1, pkt.Decode4); assertEquals(-1, pkt.Decode4); assertEquals(0, pkt.Decode1)
            assertEquals(0, pkt.readableBytes())
        } finally { pkt.release() }
        ch.outboundMessages().clear()

        // Immediate duplicate within 80ms suppressed
        handleHex(kickHex, MonsterKickReq)
        assertTrue(drain().isEmpty())

        // Kick shell 0 clears frozen state so shell 0 can be frozen again
        handleBuf(encodeReq {
            Encode2(0); Encode1(2); Encode4(240); Encode4(200); Encode4(280); Encode4(200); Encode1(0)
        }, MonsterKickReq)
        ch.outboundMessages().clear()
        handleBuf(encodeReq { Encode2(0); Encode2(4); Encode4(280); Encode4(200) }, MonsterStateReq)
        assertEquals(1, drain().count { opcodeOf(it) == 151 }, "Kicked shell must accept next freeze")
    }

    @Test
    fun `C169 MonsterHitReq and C127 StageClearAliveReq are cleanly consumed`() {
        startMonsterBoard("MonPen01")
        handleBuf(encodeReq { Encode2(0); Encode1(0) }, MonsterHitReq)
        handleBuf(encodeReq { Encode1(0) }, StageClearAliveReq)
        assertTrue(drain().isEmpty())
    }

    // ========================================================================
    // 6. 冰怪自伤豁免、Boss 血量/狂暴/锁泡/终结、动态波次目标与槽位复用
    // ========================================================================

    @Test
    fun `ice monster requires freeze except declared scripted self-damage`() {
        startMonsterBoard("MonPen08")
        // MonPen08 id=0 declares selfDamage=1: dmg=2 rejected, dmg=1 accepted once
        handleBuf(encodeReq { Encode2(0); Encode2(2); Encode2(65535) }, MonsterDamageReq)
        assertTrue(drain().isEmpty())
        handleBuf(encodeReq { Encode2(0); Encode2(1); Encode2(65535) }, MonsterDamageReq)
        assertEquals(1, drain().count { opcodeOf(it) == 151 })
        ch.outboundMessages().clear()
        handleBuf(encodeReq { Encode2(0); Encode2(1); Encode2(65535) }, MonsterDamageReq)
        assertTrue(drain().isEmpty())

        // Ordinary iceMon in MonPen01 rejects un-frozen self-damage
        val ctrl = startMonsterBoard("MonPen01")
        val iceId = ctrl.currentStage.monsters.values.first { it.type == "iceMon" }.id
        handleBuf(encodeReq { Encode2(iceId); Encode2(1); Encode2(65535) }, MonsterDamageReq)
        assertTrue(drain().isEmpty())
    }

    @Test
    fun `boss health anger lock bubble and C115 finish with legacy slot 65535`() {
        val ctrl = startMonsterBoard("MonSeal03")
        val boss = ctrl.currentStage.monsters.values.first { it.boss }

        // Drop HP into anger threshold -> MonsterHealth (event 2) + Anger (event 0)
        val toAnger = boss.hp - boss.angerHp
        handleBuf(encodeReq { Encode2(boss.id); Encode2(toAnger); Encode2(0) }, MonsterDamageReq)
        val angerEvents = drain().filter { opcodeOf(it) == 151 }.map {
            val b = encode(it); try { b.Decode2; b.Decode2; b.Decode1 } finally { b.release() }
        }
        assertEquals(listOf(2, 0), angerEvents)
        ch.outboundMessages().clear()

        // Drop HP to 0 -> MonsterHealth(0) + MonsterBossLocked (event 1, 0B payload!)
        handleBuf(encodeReq { Encode2(boss.id); Encode2(boss.angerHp); Encode2(0) }, MonsterDamageReq)
        val lockPkts = drain().filter { opcodeOf(it) == 151 }
        assertEquals(2, lockPkts.size)
        val lockBody = encode(lockPkts[1])
        try {
            assertEquals(14, lockBody.Decode2); assertEquals(boss.id, lockBody.Decode2)
            assertEquals(1, lockBody.Decode1); assertEquals(0, lockBody.readableBytes(), "Boss lock has 0B payload")
        } finally { lockBody.release() }
        ch.outboundMessages().clear()

        // Finish boss with reportedSlot=65535 (0x5a96) -> S151 event 4
        handleBuf(encodeReq { Encode2(boss.id); Encode2(65535) }, MonsterFinishReq)
        val finBody = encode(drain().single { opcodeOf(it) == 151 })
        try {
            assertEquals(14, finBody.Decode2); assertEquals(boss.id, finBody.Decode2); assertEquals(4, finBody.Decode1)
        } finally { finBody.release() }
    }

    @Test
    fun `turtle02 reused dynamic slot drops old purple objective and terminal purple clears without C137`() {
        val ctrl = startMonsterBoard("MonTurtle02")
        val targets = ctrl.currentStage.objectives.sorted()

        // Spawn purple replacement (class 1081, settlesOnDefeat=true) in slot 64, kill & destroy it
        handleBuf(encodeReq { Encode2(19); EncodeBuffer(scriptInitBytes(1081)) }, MonsterCreateReq)
        for (id in targets.dropLast(1)) {
            ctrl.onDamage(id, 1, 0); ctrl.onDestroy(id, 1)
        }
        assertFalse(drain().any { opcodeOf(it) == 150 })
        ctrl.onDamage(64, 1, 0); ctrl.onDestroy(64, 1)

        // Reuse slot 64 for recurring red starfish (class 12, NoInfluenceGameEnd=1)
        handleBuf(encodeReq { Encode2(19); EncodeBuffer(scriptInitBytes(12)) }, MonsterCreateReq)
        ch.outboundMessages().clear()

        // Killing final initial target must now clear without waiting for recurring red in slot 64
        ctrl.onDamage(targets.last(), 1, 0); ctrl.onDestroy(targets.last(), 1)
        assertEquals(1, drain().count { opcodeOf(it) == 150 })

        // Verify terminal dynamic starfish settles on defeat even without C137
        val ctrl2 = startMonsterBoard("MonTurtle02")
        handleBuf(encodeReq { Encode2(19); EncodeBuffer(scriptInitBytes(1081)) }, MonsterCreateReq)
        for (id in ctrl2.currentStage.objectives) {
            ctrl2.onDamage(id, 1, 0); ctrl2.onDestroy(id, 1)
        }
        assertFalse(drain().any { opcodeOf(it) == 150 })
        ch.outboundMessages().clear()
        ctrl2.onDamage(64, 1, 0) // No C137!
        assertEquals(1, drain().count { opcodeOf(it) == 150 })
    }

    // ========================================================================
    // 7. 无缝换盘（S169 mode=2 ↔ C131）、最终关通关印章、阵亡/超时结算
    // ========================================================================

    @Test
    fun `intermediate stage clear transitions via S169 mode 2 and C131 without BackToRoom`() {
        val pen01Id = mapIdOf("MonPen01")
        val pen02Ref = MapCatalog.refByName("MonPen02")!!
        val pen02Stage = MapCatalog.monsterStageOf(pen02Ref.mapId)!!
        val ctrl = startMonsterBoard("MonPen01")

        // Clear all MonPen01 monsters
        for (m in ctrl.currentStage.monsters.values.filter { it.initial }) {
            if (m.type.equals("iceMon", true)) {
                handleBuf(encodeReq { Encode2(m.id); Encode2(4); Encode4(40); Encode4(40) }, MonsterStateReq)
            }
            ctrl.onDamage(m.id, m.hp, 0)
            ctrl.onDestroy(m.id, 1)
        }

        // Intermediate settlement: InitGameResult(mode=0) + GameResult(winnerTeam=0, missionClear=false)
        val firstPkts = drain()
        assertEquals(1, firstPkts.count { opcodeOf(it) == 150 })
        val resBody = encode(firstPkts.single { opcodeOf(it) == 150 })
        try {
            assertEquals(0, resBody.Decode1, "Intermediate stage must not show MISSION PERFECT stamp")
            assertEquals(0, resBody.Decode1, "winnerTeam = 0")
        } finally { resBody.release() }

        // Late player death during transition must be ignored
        room.battle!!.onBomberDie(0)
        assertFalse(room.battle!!.gameOverSent)

        // Wait for scheduled S169 mode=2 MonsterNextBoard
        val afterWait = collectUntilOp(169, minCount = 2)
        val ops = afterWait.map(::opcodeOf)
        assertFalse(155 in ops, "Must never send BackToRoom between monster boards")
        assertFalse(136 in ops, "Must wait for C131 before sending GameStart")
        assertEquals(pen02Ref.mapId, room.activeMapId)
        assertEquals(pen01Id, room.mapId, "Selected room.mapId must remain stage 1")

        val nextBoardBody = encode(afterWait.last { opcodeOf(it) == 169 })
        try {
            assertEquals(2, nextBoardBody.Decode1, "mode = 2")
            nextBoardBody.Decode4 // randomSeed
            assertEquals(pen02Ref.themeId, nextBoardBody.Decode2, "nativeMapId must be true EN sort_order (319)")
            assertEquals(0, nextBoardBody.Decode2)
            assertEquals(0, nextBoardBody.Decode1)
            assertEquals(0, nextBoardBody.readableBytes())
        } finally { nextBoardBody.release() }

        // Client acknowledges board loaded (C131 E1=1) -> GameStart (136) + initialCount x MonsterControl (178)
        ch.outboundMessages().clear()
        handleBuf(encodeReq { Encode1(1) }, MonsterBoardLoadedReq)
        val loadedPkts = drain()
        assertEquals(136, opcodeOf(loadedPkts.first()))
        assertEquals(pen02Stage.initialCount, loadedPkts.count { opcodeOf(it) == 178 })

        // Duplicate C131 must be ignored
        ch.outboundMessages().clear()
        handleBuf(encodeReq { Encode1(1) }, MonsterBoardLoadedReq)
        assertTrue(drain().isEmpty())
    }

    @Test
    fun `final boss clear emits missionClear true while player death and timeout emit loss without crashing`() {
        // 1. Final boss clear (MonPen03) -> missionClear = 1, winnerTeam = 0
        val ctrl = startMonsterBoard("MonPen03")
        val boss = ctrl.currentStage.monsters.values.single { it.boss }
        ctrl.onDamage(boss.id, boss.hp, 0)
        ctrl.onFinish(boss.id, 0)
        ctrl.onDestroy(boss.id, 1)
        val winPkts = collectUntilOp(150)
        val winBody = encode(winPkts.single { opcodeOf(it) == 150 })
        try {
            assertEquals(1, winBody.Decode1, "Final boss clear sets missionClear=1")
            assertEquals(0, winBody.Decode1, "winnerTeam = 0")
        } finally { winBody.release() }

        // 2. Solo player death (0 AI slots!) must NOT throw NoSuchElementException and emits loss (winnerTeam=1, missionClear=0)
        startMonsterBoard("MonPen01")
        room.battle!!.onLastTeamStanding(0) // Must be ignored in monster mode
        assertFalse(room.battle!!.gameOverSent)
        room.battle!!.onBomberDie(0)
        val losePkts = collectUntilOp(150)
        val loseBody = encode(losePkts.single { opcodeOf(it) == 150 })
        try {
            assertEquals(0, loseBody.Decode1, "missionClear = 0 on loss")
            assertEquals(1, loseBody.Decode1, "winnerTeam = 1 on loss")
        } finally { loseBody.release() }

        // 3. Timeout in monster mode emits loss (winnerTeam=1, missionClear=0), not draw
        startMonsterBoard("MonPen01")
        room.battle!!.onMatchTimeout()
        val timeoutPkts = collectUntilOp(150)
        val timeoutBody = encode(timeoutPkts.single { opcodeOf(it) == 150 })
        try {
            assertEquals(0, timeoutBody.Decode1, "missionClear = 0 on timeout")
            assertEquals(1, timeoutBody.Decode1, "winnerTeam = 1 on monster timeout")
        } finally { timeoutBody.release() }
    }
}
