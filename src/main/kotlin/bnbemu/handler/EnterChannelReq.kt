package bnbemu.handler

import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.ChannelsExtraInfo
import bnbemu.packet.ChannelsState
import org.tinylog.kotlin.Logger

// 進頻道請求 (EN 真實 opcode 為 11, 舊 TW_5 表的 EnterChannelStageReq=9 不適用)
// login.pcap +0.570s: C 發 op 11 (4B) → S 回 [11 ChannelsExtraInfo, 13 ChannelsState]
// (login.pcap 中 [11,13,12] 已由 StringTableRevisionReq 提前推送, 故此處只補 [11,13])
// server = CCenterProcess::OnEnterChannel
//
// 4B 字段的语义 (2026-11-26 逆向, **不是频道 hash**):
//   = 客户端本地「频道表缓存文件」channel2_%03d%s.ssd 的**块校验和/版本号**
//   (与 op 55 StringTableRevisionReq 上报 3 份 SSD 版本号是同一套「本地缓存版本 → 服务端按需下发」机制)
//   证据链:
//   1. 发包点 sub_4F800E (0x004F800E, S→C op 78 handler):
//        sub_64EDE9(v3, 11); Packet_Write_v76(v3, sub_6C95E4(dword_994A50)); Send;
//      随后紧跟发 op 81 空包 —— 与「收齐 78 批次后自动发 op 11 + op 81」的抓包时序吻合。
//   2. sub_6C95E4 = *(CChannelCache+12); 该字段唯一写入点 sub_6C9565 (0x006C9565):
//        wsprintfA(buf, "channel2_%03d%s.ssd", a2, a3) → 读盘 → *(obj+12) = sub_78BB50(ssdObj)
//      sub_78BB50 返回 SSD 解析对象 +20 的校验和 (sub_78BB60 里 sub_74EE50 算的 Adler-32 变体,
//      与 op 2/51 资料块校验同一套; 见 docs/client/ssd-file.md)。
//      a2 = 频道索引, 来自 CShareContext+284 (dword_993BB0+284)。
//   3. 配套响应 handler sub_4F7FCA (S→C op 79):
//        v2 = Packet_ReadUInt32(p); if (v2 == sub_6C95E4(...)) sub_6CA346(解析频道列表);
//      即「服务端回的版本 == 本地缓存版本」才走解析, 版本号握手语义明确。
//   4. channel2_000.ssd 本身就是频道表缓存 (解压后 = 5 条频道: PopTag-Forever / Ranked /
//      Baby-Channel / Town / SquareCompat, 与 op 11 响应同构), 客户端收到新表后写盘。
//
// 模拟器策略: 忽略该值, 每次都回频道表 (客户端会重写 channel2_000.ssd, 131B);
// 想更真实可对比校验和, 一致时跳过下发 —— 但省不了多少流量, 没做。
// 自证方法: 删掉游戏目录的 channel2_000.ssd 再启动, sub_6C9565 读盘失败会把该值置 0,
//   于是 op 11 发 0x0; 有缓存时发的是文件校验和 (本 handler 的 debug 日志可对照)。
val EnterChannelReq = PKTHandler { c ->
    val cacheChecksum = Decode4 // channel2_XXX.ssd 缓存校验和 (版本号)
    Logger.debug("[EnterChannelReq] channel2 缓存校验和=0x${cacheChecksum.toString(16)}")
    c.send(
        ChannelsExtraInfo(),
        ChannelsState(),
    )
}
