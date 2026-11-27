package bnbemu.handler.battle

import bnbemu.debugMode
import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.server.FLAG_CHIEF
import bnbemu.server.Room
import bnbemu.server.state
import io.netty.buffer.ByteBuf
import org.tinylog.kotlin.Logger

/**
 * 金银岛的金钱堆格子被到时，客户端上报 (C→S op 134)。
 *
 * 真身是 `CGOTileLucci`
 * （0x00578Bxx~0x00578D5A 那一族，类里带 `LucciBronze/Silver/Gold/Pocket*` 的 100 项
 * 奖励表）的被炸回调 `sub_578D5A`：
 *
 * ```c
 * if (template+24 == 1 && GetState() == 1) {      // 是金钱瓦片, 且处于「翻开」状态
 *     SetState(2);                                 // 变成空壳（不再重复触发）
 *     if (sub_68848D() != 0) {                     // 只有「地图脚本控制权」(房主) 才发
 *         cells = sub_5364F3(session, 1, y/40, x/40, 2, 2, 0);   // 以瓦片为中心 5×5 挑 1 格
 *         itemId = sub_578BAA(this);               // rand()%100 取奖励表里的一枚金币
 *         send op134: E1(itemId) E4(GetX()) E4(GetY()) cells
 *     }
 *     sub_4DEDAD(..., 145, ...);                   // 播 Wave/Obj_SpitOutLucci（金币吐出音效）
 * }
 * ```
 *
 * 客户端**本地不建任何东西**（不调任何道具/瓦片工厂）。
 * 载荷（实机样本 `7C 96 CA 53 95 96 CA 53 DA 5B 58`，10B）：
 * ```
 * E1  itemId     金币物品 id（LucciBronze / LucciSilver / LucciGold / LucciPocket*）
 * E4  sourceX    瓦片像素 X = 40*col
 * E4  sourceY    瓦片像素 Y = 40*row+39
 * N × { E1 row, E1 col }   金币落点（客户端在瓦片周围 5×5 里扫空格，count=1）
 * ```
 * 解出来是 `itemId=38 / 瓦片像素 (0,79) → 瓦片格 (row1,col0) / 落点 (row1,col2)`。
 *
 * ⚠️ **服务端不能把它翻成 S→C op 134 回传**：S→C 空间的 134 是另一个包（1 字节，
 * `S2C_GamePacket_Dispatch` 未识别后落到 `sub_4F5924` → `sub_68605C(CMainSystem+1444)`，
 * 帧循环里会拿它去播 UI 序列/弹窗），回声只会让所有客户端弹一次莫名的界面。
 * 更不能按「炮台开火」翻译成 op 139 —— 那会在金币格上凭空造一颗水泡
 *
 */
data class LucciTileHitRequest(
    val itemId: Int,
    val sourceX: Int,
    val sourceY: Int,
    val cells: List<Pair<Int, Int>>,
) {
    /** 瓦片所在行（像素 → 格，`SetPos` 的 y 带 +39 偏移）。 */
    val tileRow: Int get() = ((sourceY - 39).coerceAtLeast(0)) / 40

    /** 瓦片所在列。 */
    val tileCol: Int get() = sourceX / 40
}

// server = CGOTileLucci::sub_578D5A (仅本地「地图脚本控制权」持有者发, 每次被炸发一次)
val LucciTileHitReq = PKTHandler { c ->
    val request = decodeLucciTileHitRequest()
    if (request == null) {
        Logger.warn("[LucciTileHitReq] op 134 包体不符 (剩余 ${readableBytes()}B), 只消费不下发")
        skipBytes(readableBytes())
        return@PKTHandler
    }
    if (readableBytes() > 0) skipBytes(readableBytes())

    val room = Room.current ?: return@PKTHandler
    val slotIdx = room.slots.indexOfFirst { it.client === c }
    if (slotIdx < 0) return@PKTHandler
    if ((room.slots[slotIdx].state.toInt() and FLAG_CHIEF) == 0 && debugMode) {
        Logger.debug("[LucciTileHitReq] 非地图脚本控制权的槽位 $slotIdx 上报金币, 照记不改状态")
    }

    Logger.info(
        "[LucciTileHitReq] slot=$slotIdx lucciItem=${request.itemId} " +
            "tile=(${request.tileRow},${request.tileCol}) px=(${request.sourceX},${request.sourceY}) " +
            "cells=${request.cells}"
    )
}

/**
 * 解 C→S op 134；布局不符返回 null（调用方跳掉剩余字节，绝不因坏包断线）。
 *
 * 宽松校验：itemId 1..255、像素 0..799（40*14=560、40*12+39=519，留余量）、
 * 格子 0..14（精确棋盘边界由 `MapTemplate.containsCell` 那套另行约束）。
 */
fun ByteBuf.decodeLucciTileHitRequest(): LucciTileHitRequest? {
    if (readableBytes() < 9) return null
    val itemId = Decode1
    if (itemId == 0) return null
    val sourceX = Decode4
    val sourceY = Decode4
    if (sourceX !in 0..799 || sourceY !in 0..799) return null
    val rest = readableBytes()
    if (rest % 2 != 0) return null
    val cells = List(rest / 2) { Decode1 to Decode1 }
    if (cells.any { (row, col) -> row !in 0..14 || col !in 0..14 }) return null
    return LucciTileHitRequest(itemId, sourceX, sourceY, cells)
}
