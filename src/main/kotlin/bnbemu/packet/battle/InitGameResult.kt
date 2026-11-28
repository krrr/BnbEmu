package bnbemu.packet.battle

import bnbemu.netty.PKT

// S→C InitGameResult (Opcode 169 in EN / CA.exe 0x0055856A)
// 结构 (15 字节):
//   Decode1: mode (1 = 似乎会触发bonus地图。胜利/结算初始化, 触发创建 CGameResultDocument 与结算 UI 窗体)
//   Decode4: unk4 (0)
//   Decode2: mapId (66 / bonus地图 ID)
//   Decode1 × 8: 8 个槽位的阵营/分组标识 (0)
fun InitGameResult(
    mode: Int = 1,
    unk4: Int = 0,
    mapId: Int = 66,
    slotTeams: IntArray = IntArray(8)
) = PKT {
    Encode1(mode)
    Encode4(unk4)
    Encode2(mapId)
    for (i in 0 until 8) {
        Encode1(slotTeams.getOrElse(i) { 0 })
    }
}

/**
 * S→C op 169 `mode = 2`：怪物模式局内无缝切图换盘（`S2C_Op169_InitGameResult` L292081 → `sub_54CF5F` L282774）。
 *
 * 客户端不退回房间、不重建 `CGameStage`，直接在 `CGameStage` 内按 `nativeMapId`（`fxdynmapinfo.json` 的
 * `sort_order`）加载下一小关棋盘并维持 `CGameContext` 槽号为 2；加载完成后客户端自动发
 * `C→S op 131 MonsterBoardLoadedReq(E1=1)`，服务端再补发 `GameStart` (op 136) + `MonsterControl` (op 178)。
 */
fun MonsterNextBoard(
    nativeMapId: Int,
    randomSeed: Int,
    eventNpcFlag: Int = 0,
    flag2: Int = 0,
) = PKT {
    Encode1(2)
    Encode4(randomSeed)
    Encode2(nativeMapId)
    Encode2(eventNpcFlag)
    Encode1(flag2)
}
