package bnbemu.netty

import bnbemu.domain.PlayerProfile
import bnbemu.handler.battle.BombHitReq
import bnbemu.handler.battle.SetBomberEventReq
import bnbemu.packet.battle.SetBombState
import bnbemu.packet.battle.bombFlagLaunched
import bnbemu.server.BattleSession
import bnbemu.server.Bomb
import bnbemu.server.Room
import bnbemu.service.PlayerService
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DartDetonationTest {

    private lateinit var room: Room
    private lateinit var battle: BattleSession

    @BeforeEach
    fun setUp() {
        room = Room.enter(Room())
        battle = room.startGame()
    }

    @AfterEach
    fun tearDown() {
        Room.leave()
    }

    private fun addBomb(
        id: Int,
        x: Int,
        y: Int,
        isSpecial: Boolean = false,
        isMoving: Boolean = false,
        targetBattle: BattleSession = battle,
    ) {
        synchronized(targetBattle.bombs) {
            targetBattle.bombs += Bomb(id, 0, 0, 0, isSpecial, isMoving = isMoving).apply {
                gridX = x
                gridY = y
                state = 1
            }
        }
    }

    private fun encode(pkt: PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf))
        return ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
    }

    private fun encodeOp108Body(targetObjectId: Int, gridX: Int, gridY: Int, delayMs: Int): ByteArray {
        val buf = Unpooled.buffer()
        SendPacketBase(buf).apply {
            Encode2(targetObjectId)
            Encode1(gridX)
            Encode1(gridY)
            Encode2(delayMs)
        }
        return ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
    }

    private fun encodeOp106LaunchedBody(
        slotId: Int,
        bombToken: Int,
        originX: Int,
        originY: Int,
        bombFlags: Int,
        landingX: Int,
        landingY: Int,
        launchDir: Int,
        flightRange: Int,
    ): ByteArray {
        val buf = Unpooled.buffer()
        SendPacketBase(buf).apply {
            Encode2(0) // contextSlot
            Encode1(slotId)
            Encode4(bombToken)
            Encode1(originX)
            Encode1(originY)
            Encode1(0) // isSquareExplosion
            Encode1(bombFlags)
            Encode1(landingX)
            Encode1(landingY)
            Encode1(launchDir)
            Encode2(flightRange)
        }
        return ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
    }

    private fun awaitBomb(targetBattle: BattleSession, bombId: Int): Bomb {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < deadline) {
            targetBattle.getBomb(bombId)?.let { return it }
            Thread.sleep(5)
        }
        error("BattleSession.addBomb 异步注册未生效: bombId=$bombId")
    }

    @Test
    fun hitDetonatesBombAndRemovesIt() {
        addBomb(540, 6, 5)

        val detonated = battle.onBombHit(540, 6, 5, 96, broadcast = false)

        assertTrue(detonated)
        val state = battle.dartDetonationState(540)
        assertNotNull(state)
        state!!
        assertEquals(540, state.targetObjectId)
        assertEquals(6, state.gridX)
        assertEquals(5, state.gridY)
        assertEquals(96, state.delayMs)
        assertFalse(battle.bombs.any { it.id == 540 }, "引爆后立刻移出 bombs, 防 onTick 2.5s 二次引爆")
    }

    @Test
    fun duplicateReportIsIgnored() {
        addBomb(541, 6, 5)
        assertTrue(battle.onBombHit(540, 6, 5, 96, broadcast = false))
        assertFalse(
            battle.onBombHit(540, 6, 5, 96, broadcast = false),
            "实测连发两镖会收到两条完全相同的 op108, 第二条不得重复引爆"
        )
    }

    @Test
    fun missDoesNotDetonate() {
        addBomb(542, 6, 5)
        assertFalse(battle.onBombHit(540, 6, 4, 96, broadcast = false), "命中格无水泡时不应引爆")
        assertTrue(battle.bombs.any { it.id == 542 }, "未命中的水泡必须留在 bombs 里等 2.5s 定时")
    }

    @Test
    fun outOfRangeGridIgnored() {
        addBomb(544, 6, 5)
        assertFalse(battle.onBombHit(540, 200, 5, 96, broadcast = false))
        assertTrue(battle.bombs.any { it.id == 544 })
    }

    @Test
    fun handlerTriggersDetonationEndToEnd() {
        addBomb(545, 6, 5)
        val raw = byteArrayOf(
            0xA7.toByte(), 0x75.toByte(), 0x5C.toByte(),
            0x5F.toByte(), 0xA5.toByte(), 0x09.toByte()
        )
        val buf = Unpooled.wrappedBuffer(raw)

        with(BombHitReq) {
            buf.handle(ClientSocket())
        }

        assertEquals(0, buf.readableBytes())
        assertNotNull(battle.dartDetonationState(545))
        assertEquals(96, battle.dartDetonationState(545)!!.delayMs)
        assertFalse(battle.bombs.any { it.id == 545 })
    }

    private fun withHumanClient(
        activationDelayMs: Long = room.battleConfig.launchedBombActivationDelayMs,
        block: (ClientSocket, EmbeddedChannel, BattleSession) -> Unit,
    ) {
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        try {
            val tester = PlayerService.registerProfile(PlayerProfile(userId = 1, username = "tester", charName = "tester"))
            room.slots[0].assignHuman(tester.userId, cs)
            room.battleConfig.launchedBombActivationDelayMs = activationDelayMs
            val activeBattle = room.startGame()
            ch.outboundMessages().clear()
            block(cs, ch, activeBattle)
        } finally {
            ch.finish()
        }
    }

    @Test
    fun zeroDelayBroadcastsSetBombStateToSender() = withHumanClient { cs, ch, activeBattle ->
        addBomb(543, 6, 5, targetBattle = activeBattle)

        val buf = Unpooled.wrappedBuffer(encodeOp108Body(targetObjectId = 543, gridX = 6, gridY = 5, delayMs = 0))
        with(BombHitReq) { buf.handle(cs) }

        assertEquals(0, buf.readableBytes())
        assertFalse(activeBattle.bombs.any { it.id == 543 }, "命中后必须立即从 bombs 移除")
        val sent = ch.outboundMessages().toList()
        assertEquals(1, sent.size, "delay=0 也必须向发送者本人回发一包 op138 state=0")
        val pkt = sent[0] as PKT
        assertEquals(138, OpcodeManager.getSendOp(pkt::class.java))
        assertEquals(encode(SetBombState(543, state = 0)).toHexString(), encode(pkt).toHexString())
    }

    @Test
    fun launchedBombMidflightPinHitDetonatesByIdAndCancelsActivation() = withHumanClient(activationDelayMs = 60_000L) { cs, ch, activeBattle ->
        val op106Body = encodeOp106LaunchedBody(
            slotId = 0,
            bombToken = 40,
            originX = 6,
            originY = 1,
            bombFlags = 1 or bombFlagLaunched,
            landingX = 6,
            landingY = 10,
            launchDir = 2,
            flightRange = 2000
        )
        with(SetBomberEventReq) { Unpooled.wrappedBuffer(op106Body).handle(cs) }
        awaitBomb(activeBattle, 40)
        assertTrue(activeBattle.launchedBombActivationPending(40))
        ch.outboundMessages().clear()

        val op108Body = encodeOp108Body(targetObjectId = 40, gridX = 6, gridY = 4, delayMs = 0)
        val hitBuf = Unpooled.wrappedBuffer(op108Body)
        with(BombHitReq) { hitBuf.handle(cs) }

        assertEquals(0, hitBuf.readableBytes())
        assertFalse(activeBattle.bombs.any { it.id == 40 }, "半路撞图钉应按 bombId 命中并从 bombs 移除")
        assertFalse(activeBattle.launchedBombActivationPending(40), "提前引爆必须取消待执行的落地激活排程")
        assertFalse(activeBattle.activateLaunchedBomb(40), "取消后不得再补发 state=1")

        val sent = ch.outboundMessages().toList()
        assertEquals(1, sent.size, "发送者必须立刻收到 op138 state=0 解锁 bomb+240")
        val pkt = sent[0] as PKT
        assertEquals(138, OpcodeManager.getSendOp(pkt::class.java))
        assertEquals(encode(SetBombState(40, state = 0)).toHexString(), encode(pkt).toHexString())
    }

    @Test
    fun movingBombMidflightHitDetonatesById() = withHumanClient { cs, ch, activeBattle ->
        addBomb(547, 6, 10, isMoving = true, targetBattle = activeBattle)

        val hitBuf = Unpooled.wrappedBuffer(encodeOp108Body(targetObjectId = 547, gridX = 6, gridY = 4, delayMs = 0))
        with(BombHitReq) { hitBuf.handle(cs) }

        assertEquals(0, hitBuf.readableBytes())
        assertFalse(activeBattle.bombs.any { it.id == 547 }, "滑行泡半路早爆应按 bombId 命中并从 bombs 移除")
        val sent = ch.outboundMessages().toList()
        assertEquals(1, sent.size)
        assertEquals(encode(SetBombState(547, state = 0)).toHexString(), encode(sent[0] as PKT).toHexString())
    }

    @Test
    fun launchedBombMidflightPinHitPrefersIdOverOtherBombOnSameGrid() = withHumanClient(activationDelayMs = 60_000L) { cs, ch, activeBattle ->
        // 半路格 (6,4) 恰好停着另一颗静止泡 id=41; 飞过 (6,4) 的发射泡 id=40 必须先按 id 命中自己, 不能误炸 41
        addBomb(41, 6, 4, targetBattle = activeBattle)
        val op106Body = encodeOp106LaunchedBody(
            slotId = 0,
            bombToken = 40,
            originX = 6,
            originY = 1,
            bombFlags = 1 or bombFlagLaunched,
            landingX = 6,
            landingY = 10,
            launchDir = 2,
            flightRange = 2000
        )
        with(SetBomberEventReq) { Unpooled.wrappedBuffer(op106Body).handle(cs) }
        awaitBomb(activeBattle, 40)
        ch.outboundMessages().clear()

        val hitBuf = Unpooled.wrappedBuffer(encodeOp108Body(targetObjectId = 40, gridX = 6, gridY = 4, delayMs = 0))
        with(BombHitReq) { hitBuf.handle(cs) }

        assertEquals(0, hitBuf.readableBytes())
        assertFalse(activeBattle.bombs.any { it.id == 40 }, "必须先按 id 命中半路早爆的发射泡 40")
        assertTrue(activeBattle.bombs.any { it.id == 41 }, "途经格上另一颗静止泡 41 不得被误炸")
        assertFalse(activeBattle.launchedBombActivationPending(40))
        val sent = ch.outboundMessages().toList()
        assertEquals(1, sent.size)
        assertEquals(encode(SetBombState(40, state = 0)).toHexString(), encode(sent[0] as PKT).toHexString())
    }

    @Test
    fun unconfirmedBombWithSentinelIdFallsBackToGrid() = withHumanClient { cs, ch, activeBattle ->
        addBomb(42, 6, 4, targetBattle = activeBattle)

        val hitBuf = Unpooled.wrappedBuffer(encodeOp108Body(targetObjectId = 0xFFFF, gridX = 6, gridY = 4, delayMs = 0))
        with(BombHitReq) { hitBuf.handle(cs) }

        assertEquals(0, hitBuf.readableBytes())
        assertFalse(activeBattle.bombs.any { it.id == 42 }, "op139 未确认前的 0xFFFF 早爆必须按坐标兜底清理")
        val sent = ch.outboundMessages().toList()
        assertEquals(1, sent.size)
        assertEquals(encode(SetBombState(42, state = 0)).toHexString(), encode(sent[0] as PKT).toHexString())
    }
}
