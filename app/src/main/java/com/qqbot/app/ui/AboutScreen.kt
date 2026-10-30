package com.qqbot.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 关于页：版本 / 这是什么 / 各功能怎么实现的 / 空间与费用 / 免责声明 / 其它 */
@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    val version = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        GroupLabel("版本")
        GroupCard {
            ItemRow("当前版本", value = version)
            RowDivider()
            ItemRow("机器人运行时", value = "Node 24（arm64，内置）")
            RowDivider()
            ItemRow("可选环境", value = "Linux(Ubuntu) / NapCat / Piper")
            RowDivider()
            ItemRow("支持系统", value = "Android 8.0+ / arm64")
        }

        GroupLabel("这是什么")
        GroupCard {
            Para(
                "一个把聊天机器人装进手机的 App：机器人逻辑跑在本机的 Node 运行时里，" +
                    "对话、人设、记忆、插件都保存在这台手机上，不经过任何中转服务器。\n\n" +
                    "它本身不提供 AI 能力，也不代收任何费用——你填自己的模型 Key 和机器人凭证，" +
                    "App 只是把这些接口串起来，并加上主动消息、表情包、语音、插件这些能力。"
            )
            Para(
                "接入方式有三种：\n" +
                    "· QQ 官方机器人（默认）：用 q.qq.com 申请的 AppID / AppSecret，任何人都能申请。\n" +
                    "· 真实QQ（电脑/服务器上跑协议端）：App 只当 OneBot v11 客户端，连你自己跑好的 NapCat / Lagrange。\n" +
                    "· 真实QQ（手机内跑协议端）：在本 App 里一键下载安装 Linux 环境 + NapCat，" +
                    "不需要电脑；装好后扫码登录。"
            )
        }

        GroupLabel("各功能是怎么实现的")
        GroupCard {
            Para(
                "· 对话：直连你填的 OpenAI 兼容接口（/chat/completions），不经过本应用作者的服务器；" +
                    "接口地址、模型名都可以自己改，不限某一家。\n" +
                    "· 人设 / 记忆：本机私有目录里的文本文件，卸载即删；记忆长度、是否长期记忆可调。\n" +
                    "· 语音：三种方案——现成音色 / 云端复刻（用你授权的声音）/ 手机本地 Piper 小模型" +
                    "（免费离线，固定音色，可自己导入音色包）；合成结果按 QQ 语音消息上传。\n" +
                    "· 听语音：收到语音时先用本机解码，再送语音识别服务（接口可自填）。\n" +
                    "· 看图：图片上传到你自己的模型服务做多模态理解。\n" +
                    "· 表情包 / 图片：从本机 emoji/、image/ 目录里挑；打了标签的会按语境选，否则随机。\n" +
                    "· 链接：对方发链接会先抓正文再回；也可以收藏到「链接库」，回复或主动消息时按概率附带。\n" +
                    "· 联网搜索：模型需要时输出检索标记，App 去你填的搜索 API 查一次再答。\n" +
                    "· 主动消息：本机定时器触发，结合记忆、模板、时间生成；类型、条数、时段、目标、带语音/链接概率都可调。\n" +
                    "· 技能（插件）：两种——纯 JS 跑在内置 Node 里；进程技能（Python/shell）跑在本机的 Linux 环境里。\n" +
                    "· 真实QQ：App 里不含任何 QQ 协议实现，只做 OneBot 客户端或在本机 Linux 环境里跑第三方协议端。"
            )
        }

        GroupLabel("空间与费用参考")
        GroupCard {
            Para(
                "本应用免费且不代收任何费用，也不赠送额度。下面只是常见花费的量级参考，" +
                    "随时可能调整，一切以对应平台的实际计费为准："
            )
            Para(
                "· 只聊天（官方机器人 + 云端语音）：App 本体约 24MB 安装包，装完约 60~100MB。\n" +
                    "· 手机内跑真实QQ：额外下载约 470MB，装完占 1.5~1.8GB（Ubuntu + LinuxQQ + NapCat）。\n" +
                    "· 手机本地语音（Piper）：额外下载约 120MB，占 250~300MB；语音合成本身免费、离线。\n" +
                    "· 模型 API：按 token 计费，日常聊天一次约几百到上千 token；" +
                    "主流文本模型每百万 token 几元到几十元不等，看图、长记忆会明显更贵。\n" +
                    "· 语音合成（云端）：按字符计费，通常每万字几毛到几块。\n" +
                    "· 声音复刻：一般按音色开通费 + 合成量计费，各家从几十元起，需实名开通。\n" +
                    "· 搜索 API：多数服务有免费额度，用超了按次计费，以你选的服务商为准。\n" +
                    "· 电脑/服务器跑协议端：云服务器常见每月几十元，用自家电脑则只有电费。\n" +
                    "· 流量：机器人跑在手机上，走的是这台手机的 Wi-Fi 或流量（首次装环境会下几百 MB）。"
            )
        }

        GroupLabel("免责声明")
        GroupCard {
            Para(
                "1. 本应用是第三方工具，与腾讯、阿里及其他平台均无关联，未获其授权、认可或背书。\n" +
                    "2. 你只能使用自己合法持有的账号、自己申请的接口凭证。不得用于骚扰、诈骗、" +
                    "冒充他人、伪造身份、批量营销、传播违法信息或任何违反法律法规与平台规则的用途。\n" +
                    "3. 真实QQ 接入依赖第三方协议端（NapCat / Lagrange 等），属于非官方途径，可能违反腾讯用户协议，" +
                    "存在账号被限制或封禁的风险，风险与后果由使用者自行承担，作者不承担任何责任。\n" +
                    "4. 声音复刻、变声等功能：录音必须是你本人的声音，或已取得权利人的明确授权；" +
                    "不得用于冒充他人或伪造他人言论。\n" +
                    "5. AI 生成的内容可能不准确、不适当或有偏见，是否发出、发给谁由使用者自行判断并负责。\n" +
                    "6. 技能插件拥有与本 App 相同的文件读写和联网权限（进程技能还能执行程序），" +
                    "请只安装你自己写的或可信来源的插件。\n" +
                    "7. 手机上跑协议端依赖前台服务保活，系统省电策略仍可能杀掉它；日志与状态仅供参考，" +
                    "不保证 7×24 稳定在线。\n" +
                    "8. 配置与记忆存放在本机；但对话文本、图片、语音会发送到你配置的模型或语音平台，" +
                    "须同时遵守这些平台的隐私政策与服务条款。\n" +
                    "9. 软件按“现状”提供，不承诺无缺陷或持续可用；因使用本应用产生的任何直接或间接损失，" +
                    "作者不承担责任。\n" +
                    "10. 请勿将本应用用于商业分发；如需上架或商用，请自行完成合规评估。"
            )
        }

        GroupLabel("其它")
        GroupCard {
            Para(
                "· 卸载 App 会连同配置、人设、记忆、插件、图片、下载的 Linux 环境一起删除，请自行备份。\n" +
                    "· 机器人代码每次升级会自动重铺，人设、记忆、素材、技能保留。\n" +
                    "· 下载环境支持断点续传、镜像切换和「下载源自选」，装到一半失败可以直接重试，已下好的包会跳过。\n" +
                    "· 本应用未上架应用商店，用调试证书签名。\n" +
                    "· 仅供学习与自用。"
            )
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Para(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = appInk2(),
        lineHeight = 20.sp,
        modifier = Modifier.padding(vertical = 5.dp),
    )
}