package bnbemu.packet.battle

/**
 * 地图脚本炮台（金银岛 / Clover / SnowyFields / Monster / MonSeal）的水泡广播包装。
 *
 * 地图 `.bin` 尾部 `<mapscript>` 的 `<time ms><bomb .../></time>` 到点后，由**客户端**
 * 挑好空格子（`sub_5364F3` 扫描 + 洗牌 + 过滤）并发 C→S op 133 上报；服务端把它翻成
 * S→C op 139 广播给所有客户端（含发起者，发起者本地不预建泡），2.5s 后照常用 op 138 引爆。
 *
 * 与玩家放泡 (C→S op 106) 的三点不同：
 *  1. `slotId = [MAP_SCRIPT_SLOT]`（=8）—— 客户端会话里这个槽位放的是 `CGOBombSprinkler`
 *     （地图炮台对象），其虚表 +64 是泡工厂、+68 是 `sub_578AC1`（设起飞点像素）。
 *  2. 必须带 **炮口像素** `sourceX/sourceY`，否则只会在目标格凭空冒一颗泡，没有起飞动画。
 *  3. `bombFlags` 低 4 位是 `<bomb power>`，**不要**置 0x20（那是玩家坦克炮泡的落点扩展，
 *     炮台泡走的是 +68→sub_4597B2 的起飞路径，不走 sub_4598AB）。
 *
 * `bombToken` 用负数哨兵：玩家待确认泡的 token 是 `slotId + 8*k >= 0`，不会撞车。
 * `bombId` 取 [MAP_SCRIPT_BOMB_ID_FIRST]..[MAP_SCRIPT_BOMB_ID_LAST]（600..999）——
 * 客户端 `CMapPublicValue` 是 1000 条表（`sub_65DB41` 对 id > 999 直接 -1，0..599 是棋盘
 * 自带条目），这段是给动态对象预留的；玩家泡用的是 token，不会进这个区间。
 */
const val MAP_SCRIPT_SLOT = 8

/** 炮台泡的 bombToken 哨兵值（玩家 token 恒 >= 0，不会冲突）。 */
const val MAP_SCRIPT_BOMB_TOKEN = -9

/** 炮台泡 bombId 循环区间（含端点），见 [MapScriptBomb]。 */
const val MAP_SCRIPT_BOMB_ID_FIRST = 600

const val MAP_SCRIPT_BOMB_ID_LAST = 999

/**
 * @param power        `<bomb power>`，1..15（进 op 139 的 bombFlags 低 4 位）
 * @param bombId       服务端分配，取 [MAP_SCRIPT_BOMB_ID_FIRST]..[MAP_SCRIPT_BOMB_ID_LAST]
 * @param sourceX/Y    炮口像素坐标（`<bomb x y>`），起飞动画从这里开始
 * @param row          **落点行**（0..12），对应 op 139 第 6 栏 E1
 * @param col          **落点列**（0..14），对应 op 139 第 7 栏 E1
 * @param initialState `<bomb state>`（3/4），客户端读出即丢，原样透传便于对照
 * @param contextSlot  第 1 栏 E2 = 客户端 `CGameContext` 槽号（取 `BattleSession.contextSlot`，
 *                     普通对局 0）；炮台没有请求包可回显，所以只能由开局推导后带进来
 *
 * `row`/`col` 的顺序就是 C→S op 133 里格子的字节顺序，**不要按 `(col, row)` 填**：
 * 客户端 `sub_54A3EA` 把第 6/7 栏读进 `v8[2]`/`v8[3]` 后交给 `sub_45AB56`，分别落进水泡的
 * `+156`/`+168`，再 `SetPos(40 * [+168], 40 * [+156] + 39)` —— 即 **第 6 栏是行(y)、第 7 栏是列(x)**。
 */
fun MapScriptBomb(
    power: Int,
    bombId: Int,
    sourceX: Int,
    sourceY: Int,
    row: Int,
    col: Int,
    initialState: Int = 0,
    contextSlot: Int = 0,
) = SetBomberEvent(
    slotId = MAP_SCRIPT_SLOT,
    originGridX = row,
    originGridY = col,
    bombToken = MAP_SCRIPT_BOMB_TOKEN,
    bombId = bombId,
    contextSlot = contextSlot,
    bombFlags = power and 0x0F,
    sourceX = sourceX,
    sourceY = sourceY,
    initialState = initialState,
)
