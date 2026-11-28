package bnbemu.handler.battle

import bnbemu.debugMode
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.server.Room
import org.tinylog.kotlin.Logger

// C→S (EN op 108 / 0x6C) —— 局内「对象命中 / 延时事件」上报请求。
//
// 逆向结论 (CA.exe, Locale=EN / v18):
//   * EN 客户端全部 5 个 C→S 108 发包点共用同一 6B 结构 (CSendPacket_CtorWithOp(v, 108)):
//       0x00465EC8 飞镖命中: delay = 1200*射程px/1000 - 2*ping, 命中格 = 射线 trace 到的那一格
//       0x0046D546 C2S_Op108_And_Op154_Action1: delay 恒 0, 同函数还发 op154 action=1
//       0x0046DE5B 通用上报: 参数 (对象, delayMs), 坐标取对象 +156/+168
//       0x0054CABA 碰撞/推挤路径: delay=300
//       0x0046DFAD 起的水泡命中路径: delay=0, 发完随即 sub_565E9F 本地引爆炸弹
//     字段宽度用 op126 DestroyBoxReq (3×Packet_Write_v68_Alias + 1×Packet_Write_v72 = 5B)
//     交叉验证: v68_Alias = E1(1B), v72 = E2(2B)。
//   * 载荷 6B = E2(targetObjectId) E1(gridX) E1(gridY) E2(delayMs)
//     - targetObjectId = sub_44B67A(obj+144)。对于已确认水泡，它正是 S→C op 139 (sub_45AB56)
//       回填进 bomb+144 的 bombId（未确认泡为 0xFFFF / -1）。
//     - gridX/gridY = obj+156 / obj+168 的格子坐标, 飞镖路径即命中格 (水泡) 所在格;
//       字段序与 op106/op107 的 (gridX 行 0..12, gridY 列 0..14) 一致
//       (sub_534520(map, gridX, gridY) 与方向增量表 dword_9886E4/9886E8 交叉验证)。
//     - delayMs = 事件延时; 飞镖路径可按 1200px/s - 2*ping 计算（贴脸飞镖可能钳位成 0）;
//       水泡自身早爆路径（CGOBomb::Update 命中图钉/火焰/发射泡落地邻火）固定传 0。
//   * 实测样本 A7 75 5C 5F A5 09
//     → objId=540, grid=(6,5), delay=96ms (≈80px = 2 格射程, ping≈0)。
//   * ⚠️ 二次逆向修正:
//     - 飞镖命中**不会**在客户端本地引爆: 0x00465EC8 命中分支只有 CSendPacket_CtorWithOp(v13, 108) + Send,
//       全函数没有 sub_565E9F (引爆) 调用; CGODart 只是本地飞行动画 + 射线命中判定。
//     - 水泡本地早爆 (CGOBomb::Update sub_45A633 命中图钉/连锁火焰等) 发 op 108 (delay=0) 后会本地引爆,
//       但同时将 bomb+240 置 1 锁住 CBombBag::AllocBomb (sub_44C056) 槽位, 必须收到服务端回发的
//       S→C op138 SetBombState(bombId, state=0) (sub_534B18) 才会清 bomb+240 解锁 (且见 bomb+8==0 不重复引爆)。
//       因此无论 delayMs > 0 还是 delayMs == 0，都必须给包含发送者在内的全体客户端广播 op138 state=0。
//     - 服务端查找策略: 先按 targetObjectId 精确匹配 BattleSession.bombs 中的活跃水泡,
//       未命中 (如 op139 尚未确认的 0xFFFF 早爆泡) 再按 gridX/gridY 兜底。
//   * ⚠️ 真端 TS 的 OnPlayEmotion: 108 → 118 (转发给除发送者之外的人) 是另一版本语义;
//     EN v18 客户端没有表情走 108 的发包点, 故 opcode_recv.yaml 的 PlayEmotionReq 已注释。
//   * 引爆判定与广播全在 BattleSession.onBombHit: 未匹配到水泡 / 重复上报会被跳过,
//     本 handler 只负责解析并转发参数, 不直接发包。
val BombHitReq = PKTHandler { _ ->
    if (readableBytes() < 6) {
        skipBytes(readableBytes())
        return@PKTHandler
    }

    val targetObjectId = Decode2
    val gridX = Decode1
    val gridY = Decode1
    val delayMs = Decode2

    // 仅飞镖命中路径成立: 飞镖速度 1200px/s, 40px = 1 格
    val estimatedRangePx = delayMs * 1000 / 1200
    val estimatedRangeGrids = estimatedRangePx / 40

    if (debugMode) {
        Logger.debug(
            "[op108] hit: objId=$targetObjectId grid=($gridX,$gridY) delay=${delayMs}ms " +
                "~range=${estimatedRangePx}px/${estimatedRangeGrids}grid"
        )
    }

    // 命中水泡 → BattleSession 延时 delayMs 广播 S→C op138 state=0 引爆 (含发射者本人，以解锁 bomb+240 槽位);
    // 未匹配到 / 重复上报 → 内部跳过。优先按 objId 匹配，0xFFFF 等未命中场景按坐标兜底。
    Room.current?.battle?.onBombHit(targetObjectId, gridX, gridY, delayMs)

    // 防御式消费: 未来客户端追加字段时不影响下一包解析。
    if (readableBytes() > 0) {
        skipBytes(readableBytes())
    }
}
