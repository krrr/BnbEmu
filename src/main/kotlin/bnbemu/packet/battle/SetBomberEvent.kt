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

/** 怪物/非玩家泡的槽位编号（客户端 `CBombOwnerNotBomber`）。 */
const val MONSTER_BOMB_SLOT = 9

/** op 139 尾部 `shape` 字段（`sub_54A3EA` 的 `v12` → `bomb+107`）。 */
const val BOMB_SHAPE_NORMAL = 0
/** 单向定向水柱（`v12 == 1`，后跟 `E1(beamDirection)` → `sub_55F2A6`，`sub_564622` 原生阻挡判定）。 */
const val BOMB_SHAPE_BEAM = 1
/** 空心矩形水波（`v12 == 2`，后跟 `E1(rectDistance)` → `sub_55F2CA`，`sub_564622` 原生边界裁剪）。 */
const val BOMB_SHAPE_HOLLOW_RECT = 2

// 局内角色/AI/怪物 放置水泡确认广播 (S→C op 139, CA.exe 0x0054A3EA) — tst.pcap stream4 sq=367~397 逐包 CRC8 验证
// 客户端局内每送出一个 C→S op 106 (SetBomberEventReq) 后, 服务器必须回显本包,
// 且两者严格一问一答 (客户端收到 op 139 才完成待确认水泡的 bombToken 匹配与 bombId 赋值)。
//
// 定长部分 (24B) = E2(contextSlot) E1(slotId) E4(bombToken) E4(sourceX) E4(sourceY)
//                   E1(originGridX) E1(originGridY) E1(isSquareExplosion) E1(bombFlags)
//                   E2(bombId) E2(initialState) E1(shape)
// 变长部分 1 (仅 bombFlags & 0x20 时, +3B, 位于 bombFlags 与 bombId 之间):
//   E1(landingGridX) E1(landingGridY) E1(launchDirection)
// 变长部分 2 (仅 shape == 1 或 shape == 2 时, +1B, 位于 shape 之后):
//   E1(shapeParam) — shape==1 时为 beamDirection (0..3); shape==2 时为 rectDistance (1..14)
//
//   - contextSlot       (第1栏 E2): 客户端 `CGameContext` 在 `CGameStage` 里的槽号 (0/1/2), 客户端
//                                   `sub_54A3EA` 开头用 `sub_532BC3(ctx)` 校验, 不符整包丢弃。
//   - bombToken         (第3栏 E4): 必须原样回显客户端 op 106 传来的 bombToken (slotId + 8*k), 怪物泡复用 bombId
//   - sourceX/Y         (第4/5栏 E4): 水泡起飞点像素坐标 (地图脚本炮台用, 玩家/怪物泡为 0)
//   - isSquareExplosion (第8栏 E1): `sub_54A3EA` 的 `v10` → `sub_45AB56` 写入 `bomb+104`。
//                                   非 0 时 `sub_564622` (L303865) 走实心方块爆炸 (半径 = power),
//                                   优先级高于 `shape == 1/2` (故使用原生水柱/空心矩形时须为 0)。
//   - bombFlags         (第9栏 E1): 低 4 位 = 威力, 0x20 = 带落点飞行, 0x40 = 怪物泡贴图, 0x80 = 特殊
//   - bombId            (E2): 服务端分配的水泡 ID, 后续 S→C op 138 引爆时按此 ID 查找水泡
//   - initialState      (E2): 客户端读出后不参与建泡, 地图脚本原样透传, 玩家/怪物泡恒 0
//   - shape             (末尾 E1): 0 = 常规(十字/实心方块), 1 = 单向水柱, 2 = 空心矩形框
fun SetBomberEvent(
    slotId: Int,
    originGridX: Int,
    originGridY: Int,
    bombToken: Int = slotId,
    bombId: Int = bombToken,
    contextSlot: Int = 0,
    isSquareExplosion: Int = 0,
    bombFlags: Int = 1,
    landingGridX: Int = originGridX,
    landingGridY: Int = originGridY,
    launchDirection: Int = 0,
    sourceX: Int = 0,
    sourceY: Int = 0,
    initialState: Int = 0,
    shape: Int = BOMB_SHAPE_NORMAL,
    shapeParam: Int = 0,
) = PKT {
    Encode2(contextSlot)
    Encode1(slotId)
    Encode4(bombToken)
    Encode4(sourceX)
    Encode4(sourceY)
    Encode1(originGridX)
    Encode1(originGridY)
    Encode1(isSquareExplosion)
    Encode1(bombFlags)
    if (bombFlags and bombFlagLaunched != 0) {
        Encode1(landingGridX)
        Encode1(landingGridY)
        Encode1(launchDirection)
    }
    Encode2(bombId)
    Encode2(initialState)
    Encode1(shape)
    if (shape == BOMB_SHAPE_BEAM || shape == BOMB_SHAPE_HOLLOW_RECT) {
        Encode1(shapeParam)
    }
}
