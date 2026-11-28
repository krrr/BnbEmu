package bnbemu.handler.battle

import bnbemu.data.MapCatalog
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.server.Room
import bnbemu.server.FLAG_CHIEF
import bnbemu.server.state
import io.netty.buffer.ByteBuf
import org.tinylog.kotlin.Logger

/**
 * 一次地图脚本炮击请求：客户端已经挑好格子，服务端只做校验 + 广播，**不得自己编格子**。
 *
 * @param cells `(row, col)`，与 op 133 线序一致（先行后列）
 */
data class MapScriptBombRequest(
    val power: Int,
    val sourceX: Int,
    val sourceY: Int,
    val state: Int,
    val cells: List<Pair<Int, Int>>,
)

// ============================================================================
// C→S op 133: 地图脚本炮台炸弹请求 (CMapActionBomb::Execute -> sub_550134)
// ============================================================================
// 触发: 地图 `.bin` 尾部 `<mapscript>` 的 `<time ms="N"><bomb .../></time>` 到点。
//       每个 `<bomb>` 是一个 CMapActionBomb 对象，由**客户端**完成「挑格子」：
//         sub_5364F3 扫矩形 (targetrow±extendscope) 或全图 (extendscope/target 均缺省=-1),
//         sub_5345B3 过滤掉墙/箱子/道具/玩家/已有泡, 洗牌后取前 count 个 → 一格都不剩就整条不发。
//       所以服务端收到的 cells 就是「这一炮要落哪几格」的最终答案。
//
// 载荷 (PangLand15 count=1 时恰好 10B; 抓包样本 5B A5 DB A4 75 5B A5 6A 52 59 解出
//       count=1 y=178 x=284 power=1 state=3 cell=(row8,col3), 与 PangLand15.bin 的
//       `<bomb x="284" y="178" ... targetrow="8" targetcol="3">` 逐项吻合):
//   E1  count          本次要生成的泡数, 1..225 (15x15)
//   E2  y              炮口像素 Y (<bomb y>)
//   E2  x              炮口像素 X (<bomb x>)
//   E1  power          1..15 (<bomb power>), 进 op 139 bombFlags 低 4 位
//   E2  state          3/4 (<bomb state>), 客户端自身不消费, 原样透传
//   count × { E1 row, E1 col }     落点格
//
// 门控: 客户端只在 `sub_68848D() != 0` 时才发本包 —— 即本地某个玩家的槽位 meta[0] 带 bit1。
//       服务端这边同样只认房主 (FLAG_CHIEF = 0x2, 与「地图脚本控制权」是同一位),
//       见 SetPlayer.mapScriptController: 开局 op 100 必须给 0 号槽保留这一位, 否则没人发包。
//
// 回应: 对每一格广播一条 S→C op 139 (MapScriptBomb), slotId=8 + 炮口像素 + 落点格;
//       发起者本地不预建泡, 必须一并广播给它。2.5s 后由 BattleSession 的 tick 发 op 138 引爆。
// server = CGameSession::OnMapActionBomb (原始名未定)
val MapScriptBombReq = PKTHandler { c ->
    val request = decodeMapScriptBombRequest()
    if (request == null) {
        skipBytes(readableBytes())
        return@PKTHandler
    }

    val room = Room.current ?: return@PKTHandler
    val battle = room.battle ?: return@PKTHandler

    // 双重门控: 客户端已按 bit1 门控, 服务端再兜一层, 防止伪造包在非炮台图上造泡。
    val slotIdx = room.slots.indexOfFirst { it.client === c }
    if (slotIdx < 0) return@PKTHandler
    if ((room.slots[slotIdx].state.toInt() and FLAG_CHIEF) == 0) {
        Logger.warn("[MapScriptBombReq] 非地图脚本控制权的槽位 $slotIdx 尝试开炮, 忽略")
        return@PKTHandler
    }

    val activeMapId = room.activeMapId
    val template = MapCatalog.of(activeMapId)
    if (template != null && !template.hasMapScriptBombs) {
        Logger.warn("[MapScriptBombReq] ${template.name} 没有 <bomb> 脚本却收到 op 133, 忽略")
        return@PKTHandler
    }

    battle.onMapScriptBombs(
        client = c,
        mapId = activeMapId,
        power = request.power,
        sourceX = request.sourceX,
        sourceY = request.sourceY,
        initialState = request.state,
        cells = request.cells,
    )
}

/**
 * 解 C→S op 133；非法/越界一律返回 null（调用方跳过剩余字节，绝不因坏包断线）。
 *
 * 宽松下界取 0..14 是客户端自己的扫描上限；精确边界（15x13 之类）由
 * [MapCatalog] 的 [bnbemu.data.MapTemplate.containsCell] 在派发前再查一遍。
 */
fun ByteBuf.decodeMapScriptBombRequest(): MapScriptBombRequest? {
    if (readableBytes() < 8) return null
    val count = Decode1
    if (count !in 1..225) return null
    if (readableBytes() != 7 + count * 2) return null
    val y = Decode2
    val x = Decode2
    val power = Decode1
    val state = Decode2
    if (x !in 0..599 || y !in 0..599 || power !in 1..15 || state !in 0..4) return null
    val cells = List(count) { Decode1 to Decode1 }
    if (cells.any { (row, col) -> row !in 0..14 || col !in 0..14 }) return null
    return MapScriptBombRequest(power, x, y, state, cells)
}
