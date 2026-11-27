package bnbemu.netty

import bnbemu.data.MapCatalog
import bnbemu.domain.PlayerProfile
import bnbemu.handler.battle.decodeLucciTileHitRequest
import bnbemu.handler.battle.decodeMapScriptBombRequest
import bnbemu.handler.room.StartGameReq
import bnbemu.packet.battle.MAP_SCRIPT_BOMB_ID_FIRST
import bnbemu.packet.battle.MAP_SCRIPT_BOMB_ID_LAST
import bnbemu.packet.battle.MAP_SCRIPT_SLOT
import bnbemu.packet.battle.MapScriptBomb
import bnbemu.packet.room.SetPlayer
import bnbemu.server.BattleSession
import bnbemu.server.FLAG_CHIEF
import bnbemu.server.FLAG_READY
import bnbemu.server.Room
import bnbemu.server.Slot
import bnbemu.server.state
import bnbemu.service.PlayerService
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 金银岛 / 地图脚本炮台 (C→S op 133 → S→C op 139 → op 138) 的回归测试。
 *
 * 逐字节基准: PangLand15 的真实请求
 * `5B A5 DB A4 75 5B A5 6A 52 59` → count=1 y=178 x=284 power=1 state=3 cell=(row8,col3)
 * 与 `resource/fx/Map/PangLand15.bin` 的
 * `<bomb x="284" y="178" count="1" power="1" state="3" targetcol="3" targetrow="8">` 逐项吻合。
 */
class MapScriptBombTest {

    private lateinit var channel: EmbeddedChannel
    private lateinit var client: ClientSocket
    private lateinit var room: Room
    private lateinit var battle: BattleSession

    @BeforeEach
    fun setUp() {
        channel = EmbeddedChannel()
        client = ClientSocket()
        channel.pipeline().addLast(client)

        room = Room.enter(Room())
        val user = PlayerService.registerProfile(PlayerProfile(9301, "cannon_tester", "cannon"))
        client.playerId = user.userId
        room.slots[0].assignHuman(user.userId, client)
        // 真实流程 (CreateSessionReq / Room.begin) 会把 0 号槽设成房主; 这一位局内就是地图脚本控制权
        room.slots[0].state = FLAG_CHIEF

        battle = room.startGame()
    }

    @AfterEach
    fun tearDown() {
        Room.leave()
        channel.finishAndReleaseAll()
    }

    private fun e1(v: Int) = byteArrayOf((v xor 0x5A).toByte())
    private fun e2(v: Int) = byteArrayOf(
        (((v shr 8) and 0xFF) xor 0xA5).toByte(),
        ((v and 0xFF) xor 0x69).toByte(),
    )
    private fun e4(v: Int) = byteArrayOf(
        ((v ushr 24) xor 0x96).toByte(),
        (((v ushr 16) and 0xFF) xor 0xCA).toByte(),
        (((v ushr 8) and 0xFF) xor 0x53).toByte(),
        ((v and 0xFF) xor 0x95).toByte(),
    )

    private fun hexToBody(s: String) =
        s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun body(pkt: PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf))
        return ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
    }

    /** 只认 PangLand15 的 mapId（协议 mapId = flag==0 记录的下标）。 */
    private fun pangLand15Id(): Int =
        (0 until 2000).firstOrNull { MapCatalog.ref(it)?.name == "PangLand15" } ?: 0

    /** BattleSession 的登记走 EventLoop, 测试这边等到可见为止。 */
    private fun awaitBombs(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (synchronized(battle.bombs) { battle.bombs.size } >= count) return
            Thread.sleep(5)
        }
        fail<Unit>("等不到 $count 颗泡 (实际 ${synchronized(battle.bombs) { battle.bombs.size }})")
    }

    // ------------------------------------------------------------------
    // C→S op 133 解码
    // ------------------------------------------------------------------

    @Test
    fun decodeRealPangLand15Request() {
        val buf = Unpooled.wrappedBuffer(hexToBody("5B A5 DB A4 75 5B A5 6A 52 59"))
        val req = buf.decodeMapScriptBombRequest()
        assertNotNull(req, "真实 PangLand15 请求必须能解出")
        req!!
        assertEquals(1, req.power)
        assertEquals(284, req.sourceX, "第 1 栏 E2 (紧跟 count) 是炮口像素 X")
        assertEquals(178, req.sourceY, "第 2 栏 E2 是炮口像素 Y")
        assertEquals(3, req.state)
        assertEquals(listOf(8 to 3), req.cells, "落点是 (row=8, col=3)")
        assertEquals(0, buf.readableBytes(), "op 133 必须被完整消费")
    }

    @Test
    fun decodeMultiCellRequest() {
        val body = e1(2) + e2(10) + e2(20) + e1(5) + e2(4) + e1(1) + e1(2) + e1(3) + e1(4)
        val buf = Unpooled.wrappedBuffer(body)
        val req = buf.decodeMapScriptBombRequest()
        assertNotNull(req)
        req!!
        assertEquals(2, req.cells.size)
        assertEquals(listOf(1 to 2, 3 to 4), req.cells)
        assertEquals(5, req.power)
    }

    @Test
    fun decodeRejectsMalformed() {
        val cases = listOf(
            e1(1) + e2(1) + e2(1) + e1(1) + e2(3),                    // 少格子 (8B, 应为 10B)
            e1(0) + e2(1) + e2(1) + e1(1) + e2(3),                    // count=0
            e1(1) + e2(1) + e2(1) + e1(0) + e2(3) + e1(0) + e1(0),    // power=0
            e1(1) + e2(1) + e2(1) + e1(1) + e2(5) + e1(0) + e1(0),    // state=5
            e1(1) + e2(1) + e2(1) + e1(1) + e2(3) + e1(15) + e1(0),   // row=15 越界
            e1(1) + e2(1) + e2(1) + e1(1) + e2(3) + e1(0) + e1(15),   // col=15 越界
        )
        cases.forEach { body ->
            val buf = Unpooled.wrappedBuffer(body)
            assertNull(
                buf.decodeMapScriptBombRequest(),
                "非法包不该被接受: ${body.joinToString(" ") { "%02X".format(it) }}"
            )
        }
    }

    // ------------------------------------------------------------------
    // S→C op 139 (MapScriptBomb) 编码
    // ------------------------------------------------------------------

    @Test
    fun mapScriptBombCarriesSourcePixelsAndSlot8() {
        val got = body(
            MapScriptBomb(
                power = 1, bombId = 600,
                sourceX = 284, sourceY = 178,
                row = 3, col = 8,
                initialState = 3,
            )
        )
        assertEquals(24, got.size, "不带 0x20 落点扩展时是 24B")

        fun d1(off: Int) = (got[off].toInt() and 0xFF) xor 0x5A
        fun d2(off: Int) = (((got[off].toInt() and 0xFF) shl 8) or (got[off + 1].toInt() and 0xFF)) xor 0xA569
        fun d4(off: Int): Int {
            val raw = ((got[off].toInt() and 0xFF) shl 24) or ((got[off + 1].toInt() and 0xFF) shl 16) or
                ((got[off + 2].toInt() and 0xFF) shl 8) or (got[off + 3].toInt() and 0xFF)
            return raw xor 0x96CA5395.toInt()
        }

        assertEquals(MAP_SCRIPT_SLOT, d1(2), "slotId 必须是 8 (CGOBombSprinkler)")
        assertEquals(-9, d4(3), "bombToken 用负数哨兵, 不与玩家 token (>=0) 冲突")
        assertEquals(284, d4(7), "第 4 栏 E4 = 炮口像素 X (sub_534C3D -> 虚表+68)")
        assertEquals(178, d4(11), "第 5 栏 E4 = 炮口像素 Y")
        assertEquals(3, d1(15), "第 6 栏 E1 = 落点行 row (sub_45AB56 -> +156 -> SetPos y)")
        assertEquals(8, d1(16), "第 7 栏 E1 = 落点列 col (sub_45AB56 -> +168 -> SetPos x)")
        assertEquals(1, d1(18), "bombFlags 低 4 位 = power, 且不得置 0x20")
        assertEquals(600, d2(19), "bombId 落在 600..999 动态段")
        assertEquals(3, d2(21), "initialState 透传 <bomb state>")
    }

    // ------------------------------------------------------------------
    // BattleSession 落地
    // ------------------------------------------------------------------

    @Test
    fun onMapScriptBombsRegistersOneBombPerCell() {
        // 丢掉开局链留在 outbound 里的包, 后面只看得见这次炮击的 op 139
        while (channel.readOutbound<PKT>() != null) { /* drain */ }

        val cells = listOf(0 to 1, 2 to 3, 8 to 3)
        battle.onMapScriptBombs(
            client = client, mapId = pangLand15Id(),
            power = 1, sourceX = 284, sourceY = 178, initialState = 3,
            cells = cells,
        )
        awaitBombs(3)

        val bombs = synchronized(battle.bombs) { battle.bombs.toList() }
        assertEquals(3, bombs.size, "每格一颗泡")
        bombs.forEach {
            assertEquals(MAP_SCRIPT_SLOT, it.ownerSlotId, "炮台泡归属槽 8")
            assertFalse(it.isSpecial, "炮台泡不是发射型 (不走 sub_4598AB)")
        }
        assertEquals(
            cells.toSet(), bombs.map { it.gridX to it.gridY }.toSet(),
            "服务端内部 gridX = 行, gridY = 列 (与 op108/op124 的 0..12 判定同一套)"
        )
        assertTrue(bombs.all { it.id in MAP_SCRIPT_BOMB_ID_FIRST..MAP_SCRIPT_BOMB_ID_LAST },
            "bombId 必须落在 600..999 (客户端 CMapPublicValue 的动态段)")

        // 线序闸门: op 139 第 6/7 栏必须原样是 (row, col)。转置会让泡落到对角翻转的格子,
        // 那一格客户端没筛过 (常是墙/箱子), 泡会画在方块上并在引爆时把客户端带崩。
        val bodies = generateSequence { channel.readOutbound<PKT>() }
            .map { body(it) }
            .filter { it.size == 24 }
            .toList()
        val wireCells = bodies.map { b ->
            ((b[15].toInt() and 0xFF) xor 0x5A) to ((b[16].toInt() and 0xFF) xor 0x5A)
        }.toList()
        assertEquals(cells.toSet(), wireCells.toSet(), "op 139 第 6/7 栏 = (row, col), 不得转置")
        bodies.forEach { b ->
            // 第 1 栏 E2 = 对局级 context 槽号 (客户端 CGameContext 下标), 普通对局恒 0
            val contextSlot = (((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)) xor 0xA569
            assertEquals(0, contextSlot, "第 1 栏 = 对局 context 槽号, 普通对局恒 0")
        }
    }

    @Test
    fun scriptBombIdsStayInReservedRange() {
        repeat(400) {
            battle.onMapScriptBombs(
                client = client, mapId = pangLand15Id(),
                power = 1, sourceX = 0, sourceY = 0, initialState = 0,
                cells = listOf(0 to 0),
            )
        }
        awaitBombs(400)

        val ids = synchronized(battle.bombs) { battle.bombs.map { it.id } }
        assertEquals((MAP_SCRIPT_BOMB_ID_FIRST..MAP_SCRIPT_BOMB_ID_LAST).toSet(), ids.toSet(),
            "600..999 整段循环用满")

        // 第 401 颗 id 回卷到 600, 复用同一 id 会顶掉旧的那颗, 总数仍是 400
        battle.onMapScriptBombs(
            client = client, mapId = pangLand15Id(),
            power = 1, sourceX = 0, sourceY = 0, initialState = 0,
            cells = listOf(1 to 1),
        )
        Thread.sleep(200)
        val after = synchronized(battle.bombs) { battle.bombs.toList() }
        assertEquals(400, after.size, "id 回卷复用 600, 顶掉旧的同 id 泡")
        assertEquals(1, after.count { it.id == MAP_SCRIPT_BOMB_ID_FIRST }, "回卷到 600")
        assertEquals(1 to 1, after.last().let { it.gridX to it.gridY }, "顶替的是新落点那颗")

        // 玩家泡的 id 来自 bombToken (slotId + 8*k), 不该被炮台 id 顶到
        val playerBombId = battle.addBomb(
            slotId = 0, gridX = 1, gridY = 1, bombId = 7, broadcastInitialState = false
        )
        assertEquals(7, playerBombId, "玩家泡 id 仍取自己的 bombToken")
    }

    @Test
    fun outOfRangeCellsAreDropped() {
        battle.onMapScriptBombs(
            client = client, mapId = pangLand15Id(),
            power = 1, sourceX = 0, sourceY = 0, initialState = 0,
            // PangLand15 是 15x13: row 0..12 / col 0..14
            cells = listOf(13 to 0, 0 to 15, 5 to 5),
        )
        awaitBombs(1)
        val bombs = synchronized(battle.bombs) { battle.bombs.toList() }
        assertEquals(1, bombs.size, "越界格必须被丢弃, 只留 (row=5,col=5)")
        assertEquals(5, bombs[0].gridX, "gridX 存的是行")
        assertEquals(5, bombs[0].gridY, "gridY 存的是列")
    }

    @Test
    fun foreignClientCannotSpawnScriptBombs() {
        val stranger = ClientSocket()
        val ch2 = EmbeddedChannel()
        ch2.pipeline().addLast(stranger)
        try {
            battle.onMapScriptBombs(
                client = stranger, mapId = pangLand15Id(),
                power = 1, sourceX = 0, sourceY = 0, initialState = 0,
                cells = listOf(5 to 5),
            )
            Thread.sleep(200)   // 给 EventLoop 机会把「不该有的」错误泡插进来
            assertTrue(synchronized(battle.bombs) { battle.bombs.isEmpty() }, "不在本局的连接不得造泡")
        } finally {
            ch2.finishAndReleaseAll()
        }
    }

    // ------------------------------------------------------------------
    // 开局的「地图脚本控制权」bit1
    // ------------------------------------------------------------------

    @Test
    fun startPacketKeepsChiefBitForMapScriptControl() {
        /** ssdEncode 的 3B meta 解码 (与 CA.exe sub_78E290 -> sub_75DAF0 一致)。 */
        fun meta(b: ByteArray): List<Int> {
            val raw = b.copyOfRange(9, 12)   // isStart=true 无 enterEffectId, meta 在 9..11
            val w = (((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)) xor 0xA569
            return listOf(w and 0xFF, (w ushr 8) and 0xFF, (raw[2].toInt() and 0xFF) xor 0x5A)
        }

        // 无 slot: 退化成纯 READY, 与 77B 抓包基准一致
        assertEquals(listOf(FLAG_READY, 5, 0), meta(body(SetPlayer(slotId = 0, level = 1, isStart = true))))

        // 房主槽: 必须保住 bit1 —— 它局内就是地图脚本控制权 (sub_68848D 的判据)
        val chief = Slot(meta = byteArrayOf(FLAG_CHIEF.toByte(), 5, 0))
        assertEquals(
            listOf(FLAG_READY or FLAG_CHIEF, 5, 0),
            meta(body(SetPlayer(slotId = 0, slot = chief, level = 1, isStart = true))),
            "开局包必须给房主保留 bit1, 否则炮台没人发 op 133"
        )

        // 非房主槽: 只补 READY, 不凭空造出控制权
        val plain = Slot(meta = byteArrayOf(0, 5, 0))
        assertEquals(
            listOf(FLAG_READY, 5, 0),
            meta(body(SetPlayer(slotId = 1, slot = plain, level = 1, isStart = true)))
        )
    }

    /**
     * 端到端: 真的打一遍 StartGameReq, 检查它发出的 op 100 有没有把 bit1 带上。
     *
     * 这条是「忘了把 slot 传进 SetPlayer」的回归闸门 —— 那种错编译期发现不了，
     * 只有真的看开局包的 meta 才抓得到（历史上正是这一处把炮台关掉了）。
     */
    @Test
    fun startGameRequestEmitsOp100WithChiefBit() {
        // PangLand15: mapId=5 / themeId=554
        val mapId = pangLand15Id()
        val req = e4(mapId) + e2(554) + e1(0) + e4(mapId) + e1(0) + e4(0)
        val buf = Unpooled.wrappedBuffer(req)
        with(StartGameReq) { buf.handle(client) }
        assertEquals(0, buf.readableBytes(), "op 92 必须被完整消费")

        val packets = generateSequence { channel.readOutbound<PKT>() }.toList()
        val setPlayer = packets.firstOrNull { OpcodeManager.getSendOp(it::class.java) == 100 }
        assertNotNull(setPlayer, "开局链必须包含 SetPlayer (op 100)")

        val meta = body(setPlayer!!).copyOfRange(9, 12)
        val w = (((meta[0].toInt() and 0xFF) shl 8) or (meta[1].toInt() and 0xFF)) xor 0xA569
        assertEquals(FLAG_READY or FLAG_CHIEF, w and 0xFF,
            "开局 op100 的 meta[0] 必须带上 bit1 (地图脚本控制权), 否则炮台全程哑火")
    }

    // ------------------------------------------------------------------
    // 地图脚本识别
    // ------------------------------------------------------------------

    @Test
    fun mapTemplateDetectsBombScript() {
        val pangLand15 = MapCatalog.byName["PangLand15"]
        assertNotNull(pangLand15)
        assertTrue(pangLand15!!.hasMapScriptBombs, "PangLand15 必须识别出 <bomb> 脚本")
        assertEquals(15, pangLand15.width)
        assertEquals(13, pangLand15.height)

        val ordinary = MapCatalog.byName.entries.firstOrNull { !it.value.hasMapScriptBombs }
        assertNotNull(ordinary, "必须存在没有炮台脚本的地图")
        assertFalse(ordinary!!.value.hasMapScriptBombs)

        val cannonMaps = MapCatalog.byName.filterValues { it.hasMapScriptBombs }.keys
        assertEquals(30, cannonMaps.size, "30 张图含 <bomb> 脚本")
        listOf("PangLand00", "PangLand03", "PangLand11", "PangLand16", "Clover01", "SnowyFields16", "Monster36", "MonSeal99")
            .forEach { assertTrue(it in cannonMaps, "$it 必须识别为炮台图") }
    }

    // ------------------------------------------------------------------
    // C→S op 134: 金钱方块滚金币上报 (与炮台无关, 旧名 SprinklerShotReq 是误判)
    // ------------------------------------------------------------------

    @Test
    fun decodeRealLucciTileHitRequest() {
        // 实机样本: itemId=38, 瓦片像素 (0,79), 落点 (row1,col2)
        val buf = Unpooled.wrappedBuffer(hexToBody("7C 96 CA 53 95 96 CA 53 DA 5B 58"))
        val req = buf.decodeLucciTileHitRequest()
        assertNotNull(req, "实机 op 134 必须能解出")
        req!!
        assertEquals(38, req.itemId, "第 1 栏 E1 = 金币物品 id (Lucci 奖励表 rand()%100)")
        assertEquals(0, req.sourceX, "第 2 栏 E4 = 瓦片像素 X")
        assertEquals(79, req.sourceY, "第 3 栏 E4 = 瓦片像素 Y (40*row+39)")
        assertEquals(1, req.tileRow, "瓦片行 = (79-39)/40")
        assertEquals(0, req.tileCol, "瓦片列 = 0/40")
        assertEquals(listOf(1 to 2), req.cells, "落点 (row1,col2)")
        assertEquals(0, buf.readableBytes(), "op 134 必须被完整消费")
    }

    @Test
    fun lucciTileBreakParsesMultiCell() {
        val body = e1(41) + e4(160) + e4(119) + e1(2) + e1(3) + e1(4) + e1(5)
        val req = Unpooled.wrappedBuffer(body).decodeLucciTileHitRequest()
        assertNotNull(req)
        req!!
        assertEquals(41, req.itemId)
        assertEquals(160, req.sourceX)
        assertEquals(119, req.sourceY)
        assertEquals(2, req.tileRow)
        assertEquals(4, req.tileCol)
        assertEquals(listOf(2 to 3, 4 to 5), req.cells)
    }

    @Test
    fun lucciTileBreakRejectsWhenLayoutIsOff() {
        val cases = listOf(
            e1(38) + e4(40),                               // 5B 太短
            e1(0) + e4(40) + e4(80) + e1(1) + e1(1),       // itemId = 0
            e1(38) + e4(4000) + e4(80) + e1(1) + e1(1),    // 像素越界
            e1(38) + e4(40) + e4(80) + e1(1),              // 奇数剩余 (半个格子)
            e1(38) + e4(40) + e4(80) + e1(15) + e1(1),     // 格子越界
        )
        cases.forEach { body ->
            val buf = Unpooled.wrappedBuffer(body)
            assertNull(buf.decodeLucciTileHitRequest(), "布局不符不该被接受")
        }
        // 9B 无格子是合法的 (客户端 5×5 里一个空格都没有时不发包, 但布局本身成立)
        assertNotNull(Unpooled.wrappedBuffer(e1(38) + e4(40) + e4(80)).decodeLucciTileHitRequest())
    }
}
