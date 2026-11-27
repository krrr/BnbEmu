package bnbemu.packet.battle

import bnbemu.netty.PKT

/**
 * 组合标志位 bit5 (0x20) = 发射型水泡 (坦克炮泡 / 带落点的飞行水泡)。
 *
 * 客户端发送侧 sub_539A4B 在该位为 1 时会追加 E1(落点X) E1(落点Y) E1(方向) E2(射程) 共 5B;
 * 接收侧 sub_54A3EA 在 S→C op 139 读到该位时也会额外解码 3 个 E1 (v18/v19/v20),
 * 交给 sub_534C3D → sub_4598AB 写入水泡对象的格子落点与方向。
 * 因此本包必须跟着补写 3B, 否则客户端会把 bombId 及之后的字段全部错位读取。
 */
const val bombFlagLaunched = 0x20

// 局内角色/AI 放置水泡确认广播 (S→C op 139, CA.exe 0x0054A3EA) — tst.pcap stream4 sq=367~397 逐包 CRC8 验证
// 客户端局内每送出一个 C→S op 106 (SetBomberEventReq) 后, 服务器必须回显本包,
// 且两者严格一问一答 (客户端收到 op 139 才完成待确认水泡的 bombToken 匹配与 bombId 赋值)。
//
// 定长部分 (24B) = E2(contextSlot) E1(slotId) E4(bombToken) E4(sourceX) E4(sourceY)
//                   E1(originGridX) E1(originGridY) E1(reservedField) E1(bombFlags)
//                   E2(bombId) E2(initialState) E1(0)
// 变长部分 (仅 bombFlags & 0x20 时, +3B → 27B) = E1(landingGridX) E1(landingGridY) E1(launchDirection)
//   · 位置: 紧跟 bombFlags 之后、bombId 之前, 与 sub_54A3EA 的 v18/v19/v20 读序严格对齐。
//   · C→S op 106 的第 4 个扩展字段 E2(flightRange) 客户端在 op 139 不读, 无需回显。
//   - contextSlot   (第1栏 E2): 客户端 `CGameContext` 在 `CGameStage` 里的槽号 (0/1/2), 客户端
//                              `sub_54A3EA` 开头用 `sub_532BC3(ctx)` 校验, 不符整包丢弃。
//                              ⚠️ 不是房间号 (`Room.sessionId`) 也不是"场次 id"; 没有任何报文能改它,
//                              只能靠开局按玩法推导 (见 BattleSession.contextSlot)。有请求包的场合原样回显。
//   - bombToken     (第3栏 E4): 必须原样回显客户端 op 106 传来的 bombToken (slotId + 8*k), 供 CA.exe 0x0044BE05 匹配待确认水泡
//   - sourceX/Y     (第4/5栏 E4): **水泡起飞点的像素坐标** (sub_54A3EA 的 v15/v16, 0x00534C3D 原样
//                              交给槽位对象的虚表 +68)。玩家放泡恒 0 —— 0x00578AC1 找不到"像素=0"
//                              的空闲子对象就跳过起飞; **地图脚本炮台 (C→S op 133) 必须带炮口像素**,
//                              否则客户端只会在目标格凭空冒一颗泡, 没有从炮口飞进棋盘的动画
//                              (链路: +68 → sub_578AC1 → sub_4597B2 存 +252/+256 并置 +260=飞行)。
//   - bombId        (定长第10栏 / 变长第11栏 E2): 服务端分配的水泡 ID (真实服直接取 bombToken),
//                              供 CA.exe 0x0045976E 写入 bomb+0x90,
//                              后续 S→C op 138 SetBombState(bombId, state=0) 引爆时按此 ID 查找水泡 (0x00534338)
//   - bombFlags     (第9栏 E1): 低 4 位 = 威力, 0x80/0x40/0x20 为特性位; 0x20 决定本包是否带落点扩展
//   - reservedField (第8栏 E1): C→S op 106 的 v6+36 保留栏, 客户端 0x0054A3EA 读出后丢弃 (v10), 原样回显即可
//   - initialState  (bombId 后的 E2): 客户端 sub_54A3EA 读进 v11 后不参与建泡 (仅消费);
//                              地图脚本把它当 `<bomb state>`(3/4) 原样透传, 玩家泡恒 0
fun SetBomberEvent(
    slotId: Int,
    originGridX: Int,
    originGridY: Int,
    bombToken: Int = slotId,
    bombId: Int = bombToken,
    contextSlot: Int = 0,
    reservedField: Int = 0,
    bombFlags: Int = 1,
    landingGridX: Int = originGridX,
    landingGridY: Int = originGridY,
    launchDirection: Int = 0,
    sourceX: Int = 0,
    sourceY: Int = 0,
    initialState: Int = 0
) = PKT {
    Encode2(contextSlot)
    Encode1(slotId)
    Encode4(bombToken)
    Encode4(sourceX)
    Encode4(sourceY)
    Encode1(originGridX)
    Encode1(originGridY)
    Encode1(reservedField)
    Encode1(bombFlags)
    if (bombFlags and bombFlagLaunched != 0) {
        Encode1(landingGridX)
        Encode1(landingGridY)
        Encode1(launchDirection)
    }
    Encode2(bombId)
    Encode2(initialState)
    Encode1(0)
}
