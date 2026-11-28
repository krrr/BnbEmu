package bnbemu.handler.battle

import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler

// ============================================================================
// C→S op 149: 局内同步异常上报
// ============================================================================
// 逆向结论 (reference/CA.exe.c)：
//   全客户端仅 3 处构造 CSendPacket(149)，共 8 条触发路径，全部用于上报
//   「服务端下发的 S→C 局内包与客户端本地状态不一致 / 越界 / 找不到目标对象」：
//
//   一、统一辅助函数 sub_549C35(slotId, errorCode, param) (CA.exe.c L279953-L279975)：
//     1) errorCode = 0  | sub_553AA0 (L288565) — 收到 S→C op 136 GameStart 时，名册表某槽位
//                         occupied != 0 但客户端本地该槽无人 (v30 == 0)，上报 (-1, selfUserId, 0, -1)。
//                         ⚠️ 旧版/参考分支因 S136 回放固定 8 槽 occupied=1，单人房会连发 7 次本包，
//                            被误当成「棋盘加载就绪回执 (Ready Ack)」；当前主干 S136 已按真实名册
//                            精确编码，正常开局不会再触发此包，严禁把任何开局/怪控逻辑挂在这里！
//     2) errorCode = 1  | sub_54A3EA (L280548) — 收到 S→C op 139 SetBomberEvent，slotId 越界。
//     3) errorCode = 2  | sub_54A5F9 (L280609) — 收到 S→C op 142 MovableBoxMove，推箱校验失败 (sub_565CE0 == 0)。
//     4) errorCode = 3  | sub_54C0xx (L282097) — 收到 S→C op 143 EatItemResult，目标格道具校验失败。
//     5) errorCode = 4  | sub_54A684 (L280637) — 收到 S→C op 144 EatObstacleResult，目标格为空 (sub_5607AB == null)。
//     6) errorCode = 10 | sub_549Dxx (L280053) — 局内本地玩家槽位索引映射与槽位号不一致 (sub_687CA5 != i)。
//
//   二、水泡背包 CBombBag::sub_44BD9B 内联构造 (CA.exe.c L66283 & L66332)：
//     7) errorCode = 0  | L66283 — 非玩家泡槽收到 S139 时泡池已满，找不到空闲泡对象。
//     8) errorCode = 0  | L66332 — 玩家泡槽收到 S139 时找不到匹配 bombToken 的待确认水泡。
//
// 载荷结构（固定 10 字节）：
//   - Decode1 -> slotId    : 异常槽位（有符号字节；-1 / 0xFF 表示无特定槽位，取本机玩家）
//   - Decode4 -> userId    : slotId == -1 时为本机玩家 userId，否则为该槽位玩家 userId
//   - Decode1 -> errorCode : 异常码（0 / 1 / 2 / 3 / 4 / 10）
//   - Decode4 -> param     : 附加参数（全客户端调用点当前均填 -1）
// ============================================================================
val SyncErrorReportReq = PKTHandler { _ ->
    if (readableBytes() < 10) {
        skipBytes(readableBytes())
        return@PKTHandler
    }

    val slotId = Decode1.toByte().toInt()
    val userId = Decode4
    val errorCode = Decode1
    val param = Decode4
    if (readableBytes() > 0) skipBytes(readableBytes())

    // 占位实现：局内异常上报暂不做回滚或惩罚处理（保留解析字段以便后续调试/审计接入）
    @Suppress("UNUSED_VARIABLE")
    val ignored = slotId + userId + errorCode + param
}
