#!/usr/bin/env node
/**
 * QQ 机器人 × 通义千问
 * 入口：事件路由 + 对话编排 + 网关连接
 *
 * 功能：单聊 / 群 @ / 频道、图片理解、语音收发、四层记忆、主动消息
 */
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { cfg, LOG_FILE, DATA_DIR } from './lib/config.mjs';
import { log, rotateLog } from './lib/log.mjs';
import { sleep } from './lib/store.mjs';
import * as qq from './lib/transport.mjs';
import * as llm from './lib/llm.mjs';
import * as mem from './lib/memory.mjs';
import * as emoji from './lib/emoji.mjs';
import * as link from './lib/link.mjs';
import * as search from './lib/search.mjs';
import * as media from './lib/media.mjs';
import * as contacts from './lib/contacts.mjs';
import { loadSkills, matchSkill } from './lib/skill.mjs';

// ---------------------------------------------------------------- 单实例
// App 被系统杀掉 / 覆盖安装 / 连点两下启动时，旧的 node 可能还活着：
// 两个机器人进程同时连着同一个协议端 → 同一条消息被收两次、各回一次。
// 用 pid 锁保证同一时间只有一个机器人实例。

const PID_FILE = path.join(DATA_DIR, 'bot.pid');

const pidAlive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

function acquireInstanceLock() {
  try {
    const old = parseInt(fs.readFileSync(PID_FILE, 'utf8').trim(), 10);
    if (old > 0 && old !== process.pid && pidAlive(old)) {
      const msg = `已有一个机器人实例在运行（pid ${old}），本次不再启动，避免重复收发`;
      log(msg);
      console.error(msg);
      return false;
    }
  } catch {}
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(PID_FILE, String(process.pid));
  } catch {}
  return true;
}

function releaseInstanceLock() {
  try {
    const cur = parseInt(fs.readFileSync(PID_FILE, 'utf8').trim(), 10);
    if (cur === process.pid) fs.rmSync(PID_FILE, { force: true });
  } catch {}
}

// ---------------------------------------------------------------- 对话编排

const locks = new Map();
const withLock = (key, fn) => {
  const prev = locks.get(key) || Promise.resolve();
  const next = prev.then(fn, fn);
  locks.set(
    key,
    next.catch(() => {}).finally(() => {
      if (locks.get(key) === next) locks.delete(key);
    })
  );
  return next;
};

// 伤感情的表述：命中就重写
const HURTFUL = [
  /分手/, /不爱你了/, /不想理你/, /别烦我/, /不想跟你/, /随便你/, /无所谓/, /爱咋咋地/, /懒得理/,
  /废话/, /别问了/, /关你什么事/, /问这么多/, /别问这种/, /你管我/,
  /烦死/, /受不了你/, /够了啊/, /别烦/, /一边去/,
];
const isHurtful = (t) => HURTFUL.some((re) => re.test(String(t || '')));

// 发语音时常见的借口，一律重写
const EXCUSE = [/没网/, /没电/, /信号不好/, /网不好/, /不方便发/, /懒得动嘴/];
const isExcuse = (t) => EXCUSE.some((re) => re.test(String(t || '')));

async function askLLM(convKey, userText, imageUrls = []) {
  const history = mem.sessions.get(convKey) || [];

  // 链接理解：消息里有链接就先抓正文（最多 1 条），失败静默跳过
  let linkBody = '';
  if (cfg.linkOn && userText) {
    const url = link.firstUrl(userText);
    if (url) {
      linkBody = await link.fetchLinkText(url, cfg.linkMaxChars);
      if (linkBody) log(`链接正文：${url} → ${linkBody.length} 字`);
    }
  }

  // 有图就传多模态
  let content = userText;
  if (imageUrls.length) {
    const urls = (await Promise.all(imageUrls.map((u) => llm.imageToDataUrl(u)))).filter(Boolean);
    if (urls.length) {
      content = [
        ...urls.map((url) => ({ type: 'image_url', image_url: { url } })),
        { type: 'text', text: userText || '看看这张图' },
      ];
    }
  }

  // 把网页正文附在 user 消息后面（多模态时并到 text 段里）
  if (linkBody) {
    const block = `\n\n[链接正文]\n${linkBody}`;
    if (typeof content === 'string') content += block;
    else {
      const t = content.find((x) => x.type === 'text');
      if (t) t.text = `${t.text || ''}${block}`;
      else content.push({ type: 'text', text: block.trim() });
    }
  }

  // 时间跟在这条消息前面，而不是塞进 system——保护前缀缓存
  const now = new Date().toLocaleString('zh-CN', {
    timeZone: 'Asia/Shanghai',
    weekday: 'long',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
  const timedEntity = typeof content === 'string' ? `[现在 ${now}] ${content}` : content;
  let sysContent = mem.buildSystem(convKey, history);

  // AI 选素材：把 emoji/、image/ 的清单（可带标签）追加进 system
  const mediaPrompt = media.buildMediaPrompt();
  if (mediaPrompt) sysContent += mediaPrompt;

  // 如果上一轮发过语音，让模型知道自己说了什么；用完清掉，不写进历史
  const voiceKey = convKey.startsWith('c2c:') ? `users:${convKey.slice(4)}` : null;
  if (voiceKey && lastVoice.has(voiceKey)) {
    sysContent += `\n\n（你上一条是用语音说的，内容是：${lastVoice.get(voiceKey)}）`;
    lastVoice.delete(voiceKey);
  }

  const messages = [{ role: 'system', content: sysContent }, ...history, { role: 'user', content: timedEntity }];

  let reply = await llm.chat(messages, { who: convKey });

  // 联网搜索：模型单独一行写了 [SEARCH:查询词] → 搜一次、把结果喂回去再答（只补一轮）
  const asked = search.takeSearchMarker(reply);
  if (asked.query) {
    const items = search.searchEnabled() ? await search.search(asked.query) : [];
    if (items.length) {
      try {
        const answered = await llm.chat(
          [
            {
              role: 'system',
              content:
                `${sysContent}\n\n【联网搜索结果：${asked.query}】\n${search.formatResults(items)}\n\n` +
                '结合上面的资料回答对方。不要说"搜索到/资料显示"这类词，直接像你自己知道一样说；和对方问题无关的内容别硬塞。',
            },
            ...history,
            { role: 'user', content: timedEntity },
          ],
          { who: convKey }
        );
        if (answered) reply = search.takeSearchMarker(answered).text || answered;
      } catch (e) {
        log('联网搜索后重答失败', e.message);
      }
    } else {
      reply = asked.text; // 没开搜索或搜不到：去掉标记，正常回复
    }
  }
  // 兜底：搜索标记绝不能当普通文本发出去
  reply = search.takeSearchMarker(reply).text || '嗯嗯';

  // 兜底检查：命中任何一条就重写一次（最多多花一次模型调用）
  const problems = [];
  if (isHurtful(reply)) problems.push('你说的那句话会伤感情、推开对方');
  if (isExcuse(reply)) problems.push('你用"没网/没电"这类借口推脱了');
  if (llm.strip(reply) <= 1) problems.push('你只回了一个字，很敷衍');

  if (problems.length) {
    try {
      const fixed = await llm.chat([
        {
          role: 'system',
          content: `${problems.join('；')}。重写一遍：说完整的一句话，直接回应，不许找借口，不许伤人。`,
        },
        ...messages,
      ], { who: convKey });
      if (fixed && !isHurtful(fixed) && !isExcuse(fixed) && llm.strip(fixed) > 1) reply = fixed;
    } catch {}
  }

  // 存历史（图片只留文字占位，避免 base64 撑爆）
  const savedText = imageUrls.length ? `[发了${imageUrls.length}张图] ${userText}`.trim() : userText;
  const cutoff = [...history, { role: 'user', content: savedText }, { role: 'assistant', content: reply }].slice(-cfg.maxHistory);

  const trimmed = [];
  let chars = 0;
  for (let i = cutoff.length - 1; i >= 0; i--) {
    chars += String(cutoff[i].content || '').length;
    if (chars > cfg.maxChars && trimmed.length) break;
    trimmed.unshift(cutoff[i]);
  }
  mem.sessions.set(convKey, trimmed);
  mem.sessions.save();
  mem.bumpCount(convKey); // 累计一条，供长期记忆判断

  return reply;
}

/** 按权重数组挑一个下标（0 起），全 0 时返回 fallback */
function pickWeighted(weights, fallback = 0) {
  const arr = (weights || []).filter((w) => Number(w) > 0);
  if (!arr.length) return fallback;
  const total = arr.reduce((a, w) => a + Number(w), 0);
  let r = Math.random() * total;
  for (let i = 0; i < arr.length; i++) {
    r -= Number(arr[i]);
    if (r <= 0) return i;
  }
  return arr.length - 1;
}

/** 挑回复形态：
 *  用户填了 MSG_COUNT_W / SENT_W → 分别随机「回几条消息」和「每条几句」；
 *  没填就沿用旧的 MSG_PLAN 组合权重。 */
function pickPlan() {
  const cw = cfg.countWeights || [];
  const swRaw = cfg.sentWeights || [];
  // 只填了「回几条」没填「每条几句」时，按 65/25/10 兜底，别退化成每条只一句
  const sw = swRaw.some((w) => w > 0) ? swRaw : [65, 25, 10];
  if (cw.some((w) => w > 0)) {
    const m = pickWeighted(cw, 0) + 1; // 1~3 条
    let s = 0;
    for (let i = 0; i < m; i++) s += pickWeighted(sw, 0) + 1; // 每条 1~3 句
    return { m, s, w: 1 };
  }
  const total = cfg.plan.reduce((a, p) => a + p.w, 0) || 1;
  let r = Math.random() * total;
  for (const p of cfg.plan) {
    r -= p.w;
    if (r <= 0) return p;
  }
  return cfg.plan[0];
}

// 最近一次语音说了什么（只活一条，用来回答"你刚才说啥"）
const lastVoice = new Map();

/** 用户是不是在要图片 / 表情包（模型没写标记时用来兜底真的发一张） */
function requestedMedia(text) {
  const t = String(text || '').trim();
  if (!t) return null;
  const pic = /(照片|图片|相片|截图|发张?图|来张?图|自拍|图给我|看图|gif|GIF)/.test(t);
  const emoji = /(表情包|表情|贴图|emoji|表情图|发表情)/.test(t);
  if (emoji && !pic) return { pic: false, emoji: true };
  if (pic) return { pic: true, emoji: emoji };
  return null;
}

/** 生成"发送函数"：发文字 + 可选素材 + 可选语音 */
function makeSender(kind, id, msgId, userText = '') {
  return async (text, wantVoice = false, userLen = 20) => {
    // ---- 消息类型开关 ----
    const sendText = cfg.msgText;
    const sendVoice = cfg.msgVoice && cfg.ttsOn;
    const sendEmoji = cfg.msgEmoji;
    const sendPic = cfg.msgImage;

    if (!sendText && !sendVoice && !sendEmoji && !sendPic) {
      log('所有消息类型都关了，跳过发送');
      return '';
    }

    // AI 自己挑的素材：[EMOJI:文件名] / [PIC:文件名] 先摘出来，标记不能当文本发出去
    const marks = media.takeMediaMarkers(String(text ?? ''));
    const bodyText = marks.text;
    const hasMarkedMedia = marks.emojis.length > 0 || marks.pics.length > 0;
    const spokenText = bodyText || String(text || '');

    // 语音：被要求时一定发；没要求时有小概率随机发（文字为主，语音为辅）
    const randomVoice = sendVoice && !wantVoice && Math.random() < cfg.voiceChance;
    const withVoice = sendVoice && (wantVoice || randomVoice);
    log(`发送[${kind}] 文本=${sendText} 语音=${sendVoice} 图=${sendPic} 表情=${sendEmoji} 会发语音=${withVoice}`);

    // ---- 按预设形态挑：几句 + 几条 ----
    const hasText = !!String(bodyText).trim();
    const sentences = hasText
      ? String(bodyText)
          .split(/[\n。！？；]+/)
          .map((x) => x.trim())
          .filter(Boolean)
      : [];
    if (hasText && !sentences.length) sentences.push(String(bodyText).trim());

    const plan = pickPlan();
    const totalSentences = Math.min(sentences.length, plan.s);
    const useSentences = sentences.slice(0, totalSentences);

    const nMsg = Math.min(plan.m, totalSentences);
    const per = Math.ceil(totalSentences / Math.max(1, nMsg));
    const groups = [];
    for (let i = 0; i < totalSentences; i += per) {
      groups.push(useSentences.slice(i, i + per).join('，'));
    }

    // 发语音时上限降到 3 条（预留一个名额）
    const reserve = withVoice ? 1 : 0;
    const cap = Math.max(1, 4 - reserve);
    const finalGroups = groups.slice(0, cap);
    if (groups.length > cap) finalGroups[cap - 1] = groups.slice(cap - 1).join('，');

    // 加消息前缀
    const prefix = cfg.msgPrefix || '';
    const prefixed = prefix ? finalGroups.map((g) => prefix + g) : finalGroups;

    // 发语音时偶尔只发语音不发文字
    const onlyVoice = withVoice && Math.random() < cfg.voiceOnlyChance;
    let sentCount = 0;
    if (!onlyVoice && sendText && prefixed.length) {
      sentCount = await qq.sendList(kind, id, prefixed, msgId, reserve);
      log(`发送[${kind}] ${totalSentences} 句 / ${sentCount} 条`);
    }

    // 模型点名的表情包 / 图片（表情包按 image 发送，和随机表情同一套上传逻辑）
    if (!onlyVoice && hasMarkedMedia) {
      const n = await media
        .sendMarkedMedia(kind, id, msgId, marks, sentCount + 1, Math.max(0, cap - sentCount))
        .catch(() => 0);
      sentCount += n;
    }

    // 用户明确要图/表情包但模型没写标记：真的发一张
    // （否则会出现「你让它发照片，它回你『我相册是空的』」这种鬼话）
    const want = requestedMedia(userText);
    if (!onlyVoice && !hasMarkedMedia && want && sentCount < cap) {
      // 要「图片」优先 image/，其次表情包；要「表情包」优先表情包，其次图片
      const tries = want.pic
        ? [
            ['pic', sendPic, emoji.imageCount(), emoji.sendRandomImage],
            ['emoji', sendEmoji, emoji.emojiCount(), emoji.sendRandomEmoji],
          ]
        : [
            ['emoji', sendEmoji, emoji.emojiCount(), emoji.sendRandomEmoji],
            ['pic', sendPic, emoji.imageCount(), emoji.sendRandomImage],
          ];
      let tried = false;
      for (const [label, on, cnt, fn] of tries) {
        if (!on) continue;
        if (cnt <= 0) continue;
        tried = true;
        if (await fn.call(emoji, kind, id, msgId, sentCount + 1).catch(() => false)) {
          sentCount += 1;
          log(label === 'pic' ? '用户要图片：补发一张' : '用户要表情包：补发一张');
          break;
        }
      }
      if (!tried) {
        log(
          `用户要${want.pic ? '图片' : '表情包'}，但发不出：表情包 ${emoji.emojiCount()} 张 / 图片 ${emoji.imageCount()} 张，` +
            `图=${sendPic} 表情=${sendEmoji}（去「更多 → 素材」加图，或检查「消息类型」开关）`,
        );
      }
    }

    // 小概率补一张表情包（只在文字条数少、没点名素材、且不发语音时，避免超出 4 次上限）
    if (!withVoice && !onlyVoice && !hasMarkedMedia && !want && sentCount <= 2 && emoji.emojiCount() > 0 && sendEmoji && Math.random() < cfg.emojiChance) {
      if (await emoji.sendRandomEmoji(kind, id, msgId, sentCount + 1).catch(() => false)) sentCount += 1;
    }

    // 再小概率补一张图片（image/ 目录里的照片）
    if (!withVoice && !onlyVoice && !hasMarkedMedia && sentCount <= 2 && emoji.imageCount() > 0 && sendPic && Math.random() < cfg.imageChance) {
      if (await emoji.sendRandomImage(kind, id, msgId, sentCount + 1).catch(() => false)) sentCount += 1;
    }

    // 收藏链接：按 LINK_CHANCE 小概率在回复末尾单独发一条（标题 + 换行 + URL，不拆句）
    if (!onlyVoice && sendText && sentCount < cap) {
      const linked = await link
        .maybeSendLink({ chance: cfg.linkChance, kind, id, msgId, sentCount, maxMsgs: cap })
        .catch(() => false);
      if (linked) sentCount += 1;
    }

    if (!withVoice) return '';

    const script = await llm.voiceScript(spokenText);
    const url = await llm.textToSpeech(script);
    if (!url) return '';
    // 本地 TTS 返回 file:// 路径，走本地上传；云端返回 URL，走 URL 上传
    let fileInfo = null;
    if (url.startsWith('file://')) {
      const fs = await import('node:fs');
      const p = url.slice(7);
      fileInfo = await qq.uploadLocalFile(kind, id, fs.readFileSync(p), p.split(/[\\/]/).pop(), 3);
      try { fs.unlinkSync(p); } catch {}
    } else {
      fileInfo = await qq.uploadMedia(kind, id, url, 3);
    }
    if (!fileInfo) {
      log('语音：上传失败');
      return '';
    }
    await qq.sendMedia(kind, id, fileInfo, msgId, sentCount + 1);
    log(`语音：已发出（${script.slice(0, 20)}…）`);
    lastVoice.set(`${kind}:${id}`, script);
    return script;
  };
}

/** 内置命令：磁盘/状态/日志，由机器人自己回答 */
async function builtinCommand(cmd) {
  if (/^(磁盘|disk|空间|容量)$/i.test(cmd)) {
    try {
      const fs = await import('node:fs');
      const st = fs.statfsSync('/storage/emulated/0');
      const GB = (n) => `${(n / 1024 ** 3).toFixed(0)}G`;
      const total = st.blocks * st.bsize;
      const avail = st.bavail * st.bsize;
      const used = total - st.bfree * st.bsize;
      return `磁盘 总 ${GB(total)} · 已用 ${GB(used)}(${Math.round((used / total) * 100)}%) · 剩 ${GB(avail)}`;
    } catch (e) {
      return `读不到磁盘信息：${e.message}`;
    }
  }

  if (/^(状态|status|运行|在线)$/i.test(cmd)) {
    const up = Math.round(process.uptime() / 60);
    const u = llm.usage;
    return `在线 ${up} 分钟\n本次 ${u.calls} 次调用 · ${u.prompt}/${u.completion} tokens\n内存 ${Math.round(process.memoryUsage().rss / 1048576)}MB`;
  }

  if (/^(日志|log)$/i.test(cmd)) {
    try {
      const fs = await import('node:fs');
      const lines = fs.readFileSync(LOG_FILE, 'utf8').trim().split('\n').slice(-8);
      return lines.join('\n');
    } catch {
      return '读不到日志';
    }
  }

  return null;
}

/** 命令处理：返回 true 表示已处理，不再走聊天 */
async function handleCommand({ convKey, openid, text, send }) {
  const t = text.trim();

  // 管理指令（# 开头、/usage、/reset…）默认只给管理员用：普通聊天对象发了就当没看见，
  // 只在机器人日志里记一行，方便号主排查「有人乱试指令」。
  const admin = !cfg.adminOnly || (openid && cfg.adminIds.includes(String(openid)));
  const deny = () => log(`忽略非管理员指令（${openid || '未知'}）：${t.slice(0, 30)}`);

  if (/^#{1,3}/.test(t)) {
    if (!admin) {
      deny();
      return true;
    }
    const cmd = t.replace(/^#+/, '').trim();
    if (!cmd || /^(帮助|help|\?|指令|命令)$/i.test(cmd)) {
      await send(
        [
          '可用指令（仅管理员）：',
          '#状态　#磁盘　#日志',
          '/usage 调用次数与花费',
          '/reset 清空这段对话记忆　/resetall 连印象一起清',
        ].join('\n')
      ).catch(() => {});
      return true;
    }
    const built = await builtinCommand(cmd);
    await send(built || `不认识的指令：${cmd}（发 #帮助 看用法）`).catch(() => {});
    return true;
  }

  if (/^\//.test(t) && !admin) {
    deny();
    return true;
  }

  if (/^\/(usage|用量)$/i.test(t)) {
    const r = llm.usageReport();
    const money = (v) => (v === 0 ? '0' : v < 0.001 ? v.toFixed(5) : v.toFixed(4));

    // 群/频道按群聚合
    const normKey = (k) => {
      const p = k.split(':');
      return p.length >= 3 ? `${p[0]}:${p[1]}` : k;
    };
    const label = (k) => {
      if (!k.includes(':')) return '内部'; // 摘要/提炼这类后台调用
      const [kind, id] = k.split(':');
      const tag = { c2c: '单聊', group: '群' }[kind] || kind;
      return `${tag}${String(id || '').slice(0, 6)}`;
    };
    const grouped = {};
    for (const [k, u] of Object.entries(r.byUser || {})) {
      const nk = normKey(k);
      grouped[nk] = grouped[nk] || { calls: 0, cost: 0 };
      grouped[nk].calls += u.calls;
      grouped[nk].cost += u.cost;
    }
    // 拆出内部调用（摘要/提炼，没有 : 前缀）
    let innerCost = 0;
    for (const [k, u] of Object.entries(r.byUser || {})) {
      if (!k.includes(':')) innerCost += u.cost;
    }
    const chatCost = Math.max(0, r.total.cost - innerCost);

    const top = Object.entries(grouped)
      .sort((a, b) => b[1].calls - a[1].calls)
      .slice(0, 3)
      .map(([k, u]) => `${label(k)} ${u.calls}次`);

    await send(
      [
        `调用次数：本次 ${r.run.calls} / 今天 ${r.today.calls} / 累计 ${r.total.calls}`,
        `累计 tokens：输入 ${r.total.prompt} / 输出 ${r.total.completion}`,
        `累计花费：${money(r.total.cost)} 元（聊天 ${money(chatCost)} + 内部 ${money(innerCost)}）`,
        top.length ? `按人：${top.join(' · ')}` : '',
      ]
        .filter(Boolean)
        .join('\n')
    );
    return true;
  }

  const resetMatch = t.match(/^\/reset(\s*all)?$/i); // /reset、/resetall、/reset all 都认
  if (resetMatch) {
    const all = !!resetMatch[1];
    mem.sessions.delete(convKey);
    mem.summaries.delete(convKey);
    mem.counts.delete(convKey);
    mem.sessions.saveNow();
    mem.summaries.saveNow();
    mem.counts.saveNow();
    if (all) {
      mem.facts.delete(convKey);
      mem.facts.saveNow();
    }
    await send(all ? '全清了，连印象一起删了' : '记忆清空了，重新认识一下').catch(() => {});
    log(`[记忆] ${all ? '全部' : '对话'}清空：${convKey.slice(0, 20)}`);
    return true;
  }

  return false;
}

async function handleMessage({ convKey, text, images = [], wantVoice = false, send, label }) {
  log(`收到[${label}] ${text.replace(/\n/g, ' / ').slice(0, 80)}`);

  // 敏感词过滤：含任何敏感词的消息直接丢掉，不回复
  if (cfg.filterWords.length) {
    const t = String(text || '').toLowerCase();
    if (cfg.filterWords.some((w) => t.includes(w.toLowerCase()))) {
      log(`过滤：${label} 含敏感词，已跳过`);
      return;
    }
  }

  // Skill 插件优先
  if (cfg.skillsOn) {
    const skillReply = await matchSkill(convKey, text);
    if (skillReply) {
      await send(skillReply, false, 0);
      log(`插件回复[${label}] ${skillReply.replace(/\n/g, ' / ').slice(0, 60)}`);
      return;
    }
  }

  await withLock(convKey, async () => {
    try {
      const reply = await askLLM(convKey, text, images);
      if (cfg.humanize || cfg.typingDelayMaxMs > cfg.typingDelayMinMs) {
        const min = cfg.typingDelayMinMs || 400;
        const max = cfg.typingDelayMaxMs || Math.max(min + 1800, reply.length * 10 + 600);
        await sleep(min + Math.min(max - min, reply.length * 10) + Math.random() * 600);
      }
      await send(reply, wantVoice, text.length);
      log(`回复[${label}] ${reply.replace(/\n/g, ' / ').slice(0, 80)}`);

      mem.extractFacts(convKey).catch(() => {});
      mem.rollSummary(convKey).catch(() => {});
    } catch (e) {
      log(`处理失败[${label}]`, e.message);
      await send(`出错了：${e.message.slice(0, 120)}`).catch(() => {});
    }
  });
}

// 连发合并：1.5 秒内的多条合成一次回复
const pending = new Map();
function enqueue({ convKey, text, images = [], wantVoice = false, send, label }) {
  let p = pending.get(convKey);
  if (!p) {
    p = { texts: [], images: [], wantVoice: false, send, label, timer: null };
    pending.set(convKey, p);
  }
  p.texts.push(text);
  if (images.length) p.images.push(...images);
  if (wantVoice) p.wantVoice = true;
  p.send = send;
  clearTimeout(p.timer);
  p.timer = setTimeout(() => {
    pending.delete(convKey);
    handleMessage({ convKey, text: p.texts.join('\n'), images: p.images, wantVoice: p.wantVoice, send: p.send, label: p.label }).catch((e) =>
      log('合并处理失败', e.message)
    );
  }, 1500);
}

// ---------------------------------------------------------------- 事件

const seen = new Set();
const isDuplicate = (id) => {
  if (!id) return false;
  if (seen.has(id)) return true;
  seen.add(id);
  if (seen.size > 500) seen.delete(seen.values().next().value);
  return false;
};

// message_id 拿不到时（或对方重复推送同一条）按「谁+说了什么」兜底去重
const recentText = new Map();
const isDupText = (key) => {
  const now = Date.now();
  const prev = recentText.get(key);
  recentText.set(key, now);
  if (recentText.size > 400) recentText.delete(recentText.keys().next().value);
  return !!prev && now - prev < 2500;
};

const clean = (raw) =>
  String(raw || '')
    .replace(/<@!?\d+>/g, '')
    .replace(/<faceType=\d+,[^>]*>/g, '')
    .trim();

const pickImages = (d) =>
  (d?.attachments || [])
    .filter((a) => /image/i.test(a?.content_type || '') || /\.(png|jpe?g|gif|webp)$/i.test(a?.filename || ''))
    .map((a) => a.url)
    .filter(Boolean)
    .slice(0, 3);

const pickAudio = (d) =>
  (d?.attachments || []).find((a) => /audio|voice|silk|amr|mp3|wav/i.test(`${a?.content_type || ''} ${a?.filename || ''}`))?.url ||
  null;

// 想听声音的各种说法；带否定词时要排除（"不用发语音""别说话"）
const NEG = /不用|不要|别|算了|不必|无需/;
const asksVoice = (t) => {
  const s = String(t || '');
  return !NEG.test(s) && /语音|声音|说句话|说话|说.{0,3}句|讲.{0,3}句|讲讲|听听你|用嘴|口述|录一段|唱|念给我/i.test(s);
};

/** 统一解析：文本 + 图片 + 语音(转文字) + 是否要语音回复 */
async function parseMessage(d) {
  let text = clean(d.content);
  const images = cfg.visionOn ? pickImages(d) : [];
  const audioUrl = pickAudio(d);

  if (audioUrl) {
    const heard = await llm.speechToText(audioUrl);
    if (heard) text = text ? `${text}（语音：${heard}）` : heard;
  }

  return { text, images, wantVoice: !!audioUrl || asksVoice(text) };
}

async function onC2C(d) {
  if (isDuplicate(d.id)) return;
  const openid = d?.author?.user_openid;
  if (!openid) return;

  // 黑名单用户
  if (cfg.blacklistUsers.includes(openid)) return;

  // 记联系人（App 端「从最近聊过的人里选」用）
  contacts.touchContact({ id: openid, name: d?.author?.username || '', kind: 'private' });

  const m = await parseMessage(d);
  if (!m.text && !m.images.length) return;

  if (m.text && isDupText(`c2c:${openid}:${m.text}`)) return;

  const convKey = `c2c:${openid}`;
  const send = makeSender('users', openid, d.id, m.text);

  // 命令优先
  // 命令回复要原样发，不能走聊天的「拆句/截断」逻辑
  const rawSend = (t) => qq.sendText('users', openid, t, d.id);
  if (!m.images.length && (await handleCommand({ convKey, openid, text: m.text, send: rawSend }))) return;

  // 对方说"别主动找我" → 暂停主动消息
  if (mem.isNoProactive(m.text)) mem.pauseProactive(openid);

  mem.touchPeer(openid);
  enqueue({ convKey, ...m, label: '单聊', send });
}

async function onGroupAt(d) {
  if (isDuplicate(d.id)) return;
  const group = d?.group_openid;
  if (!group) return;

  // 黑名单群
  if (cfg.blacklistGroups.includes(group)) return;

  // 群消息记群（不记具体成员，App 端选群用）
  contacts.touchContact({ id: group, name: d?.group_name || d?.group?.name || '', kind: 'group' });

  const m = await parseMessage(d);
  if (!m.text && !m.images.length) return;
  if (m.text && isDupText(`g:${group}:${d?.author?.member_openid || 'anon'}:${m.text}`)) return;

  enqueue({
    convKey: `group:${group}:${d?.author?.member_openid || 'anon'}`,
    ...m,
    label: '群聊',
    send: makeSender('groups', group, d.id, m.text),
  });
}

const handlers = {
  C2C_MESSAGE_CREATE: onC2C,
  GROUP_AT_MESSAGE_CREATE: onGroupAt,
};

// ---------------------------------------------------------------- 真实QQ（OneBot）
//
// 手机 App 只当客户端：连你自己电脑/服务器上的 NapCat / Lagrange。
// 收到的事件走和官方通道同一套编排（记忆/插件/主动消息都一样）。

async function heard(ev) {
  if (!ev.audio) return '';
  try {
    return (await llm.speechToText(ev.audio)) || '';
  } catch (e) {
    log('语音转文字失败', e.message);
    return '';
  }
}

const onebotHandlers = {
  async onPrivate(ev) {
    if (isDuplicate(`ob-${ev.messageId}`)) return;
    const openid = String(ev.userId);
    if (cfg.blacklistUsers.includes(openid)) return;

    contacts.touchContact({ id: openid, name: ev.name || '', kind: 'private' });

    const said = await heard(ev);
    let text = ev.text;
    if (said) text = text ? `${text}（语音：${said}）` : said;
    const images = cfg.visionOn ? ev.images : [];
    if (!text && !images.length) return;
    if (text && isDupText(`c2c:${openid}:${text}`)) return;

    const convKey = `c2c:${openid}`;
    const rawSend = (t) => qq.sendText('users', openid, t, null);
    if (!images.length && (await handleCommand({ convKey, openid, text, send: rawSend }))) return;

    if (mem.isNoProactive(text)) mem.pauseProactive(openid);
    mem.touchPeer(openid);
    enqueue({
      convKey,
      text,
      images,
      wantVoice: !!ev.audio || asksVoice(text),
      send: makeSender('users', openid, null, text),
      label: '真实QQ单聊',
    });
  },

  async onGroup(ev) {
    if (isDuplicate(`ob-${ev.messageId}`)) return;
    const group = String(ev.groupId);
    if (cfg.blacklistGroups.includes(group)) return;

    contacts.touchContact({ id: group, name: ev.groupName || '', kind: 'group' });

    const said = await heard(ev);
    let text = ev.text;
    if (said) text = text ? `${text}（语音：${said}）` : said;
    const images = cfg.visionOn ? ev.images : [];
    if (!text && !images.length) return;
    if (text && isDupText(`g:${group}:${ev.userId || 'anon'}:${text}`)) return;

    enqueue({
      convKey: `group:${group}:${ev.userId || 'anon'}`,
      text,
      images,
      wantVoice: asksVoice(text),
      send: makeSender('groups', group, null, text),
      label: '真实QQ群',
    });
  },
};

// ---------------------------------------------------------------- 网关

const OP = { DISPATCH: 0, HEARTBEAT: 1, IDENTIFY: 2, RESUME: 6, RECONNECT: 7, INVALID_SESSION: 9, HELLO: 10, ACK: 11 };

class Gateway {
  constructor() {
    this.ws = null;
    this.seq = null;
    this.sessionId = null;
    this.timer = null;
    this.retry = 0;
    this.running = true;
    this.acked = true;
    this.miss = 0;
  }

  async start() {
    while (this.running) {
      try {
        await this.connect();
      } catch (e) {
        log('连接异常', e.message);
      }
      if (!this.running) break;
      const delay = Math.min(60, 2 ** this.retry) * 1000;
      this.retry = Math.min(this.retry + 1, 6);
      log(`${delay / 1000}s 后重连`);
      await sleep(delay);
    }
  }

  async connect() {
    const { url } = await qq.api('GET', '/gateway');
    if (!url) throw new Error('获取网关地址失败');
    log(`连接网关 ${url}`);

    return new Promise((resolve) => {
      const ws = new WebSocket(url);
      this.ws = ws;
      let done = false;
      const finish = () => {
        if (!done) {
          done = true;
          resolve();
        }
      };

      ws.addEventListener('open', () => log('WebSocket 已建立'));
      ws.addEventListener('message', (ev) => {
        try {
          this.onPayload(JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString()));
        } catch {}
      });
      ws.addEventListener('close', (ev) => {
        log(`连接关闭 code=${ev.code}`);
        clearInterval(this.timer);
        if (ev.code === 4914 || ev.code === 4915) {
          log('机器人已下架/封禁，停止重连');
          this.running = false;
        }
        finish();
      });
      ws.addEventListener('error', () => log('WebSocket 错误'));
    });
  }

  onPayload({ op, t, s, d }) {
    if (typeof s === 'number') this.seq = s;

    switch (op) {
      case OP.HELLO:
        log(`Hello，心跳 ${d.heartbeat_interval}ms`);
        this.startHeartbeat(d.heartbeat_interval);
        if (this.sessionId && this.seq !== null) {
          this.send({ op: OP.RESUME, d: { token: `QQBot ${qq.getTokenSync()}`, session_id: this.sessionId, seq: this.seq } });
          log('发送 Resume');
        } else {
          this.identify();
        }
        break;

      case OP.ACK:
        this.acked = true;
        this.miss = 0;
        break;

      case OP.RECONNECT:
        log('服务端要求重连');
        this.ws?.close(4000, 'server');
        break;

      case OP.INVALID_SESSION:
        this.sessionId = null;
        this.seq = null;
        this.identify();
        break;

      case OP.DISPATCH:
        if (t === 'READY') {
          this.sessionId = d?.session_id || null;
          this.retry = 0;
          log(`就绪：${d?.user?.username || '机器人'} 已上线`);
        } else if (t === 'RESUMED') {
          log('会话已恢复');
        } else if (handlers[t]) {
          Promise.resolve(handlers[t](d)).catch((e) => log(`处理 ${t} 失败`, e.message));
        }
        break;
    }
  }

  identify() {
    this.send({
      op: OP.IDENTIFY,
      d: {
        token: `QQBot ${qq.getTokenSync()}`,
        intents: cfg.intents,
        shard: [0, 1],
        properties: { $os: 'android', $browser: 'qqbot-app', $device: 'phone' },
      },
    });
    log(`发送 Identify（intents=${cfg.intents}）`);
  }

  startHeartbeat(interval) {
    clearInterval(this.timer);
    this.acked = true;
    this.miss = 0;
    this.timer = setInterval(() => {
      if (!this.acked) {
        this.miss += 1;
        log(`心跳无响应 ${this.miss}/2`);
        if (this.miss >= 2) {
          this.ws?.close(4008, 'heartbeat');
          return;
        }
      } else {
        this.miss = 0;
      }
      this.acked = false;
      this.send({ op: OP.HEARTBEAT, d: this.seq });
    }, interval);
  }

  send(obj) {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(obj));
  }
}

// ---------------------------------------------------------------- 启动

async function main() {
  if (!acquireInstanceLock()) process.exit(3);
  const onebot = cfg.channel === 'onebot';
  const missing = [];
  if (onebot) {
    if (!cfg.onebotUrl) missing.push('ONEBOT_WS_URL');
  } else {
    if (!cfg.appId) missing.push('QQ_APPID');
    if (!cfg.appSecret) missing.push('QQ_APP_SECRET');
  }
  if (!cfg.llmKey) missing.push('LLM_API_KEY');
  if (missing.length) {
    const msg = `缺少配置：${missing.join(', ')}（去 App 设置页填好再启动）`;
    log(msg);
    console.error(msg);
    process.exit(1);
  }

  rotateLog();
  log('==================== 启动 ====================');
  log(`接入方式：${onebot ? '真实QQ（OneBot）' : 'QQ 官方机器人'}`);
  // 启动现场：出问题时这几行能定位到底卡在哪一步
  log(`node ${process.version} · ${process.platform}/${process.arch}`);
  log(`目录 ${import.meta.dirname}`);
  log(`模型 ${cfg.model} | 接口 ${cfg.llmBase} | 历史 ${cfg.maxHistory} | intents ${cfg.intents}`);
  // 新功能状态：方便看设置有没有生效
  if (cfg.linkOn) log(`链接理解：开（正文最多 ${cfg.linkMaxChars} 字）`);
  if (cfg.mediaSmart) log('AI 选素材：开（emoji/ + image/）');
  log(
    `素材库：表情包 ${emoji.emojiCount()} 张 / 图片 ${emoji.imageCount()} 张` +
      (cfg.mediaSmart ? '（AI 按语境挑）' : '（用户要图时自动发一张）')
  );
  if (cfg.searchProvider) log(`联网搜索：${cfg.searchProvider}${cfg.searchKey ? '' : '（缺 SEARCH_KEY，实际关闭）'}`);
  if (cfg.linkChance > 0) log(`附带链接概率：回复 ${cfg.linkChance} / 主动 ${cfg.linkSendChanceProactive}`);
  // 配置自检：只提醒，不阻止启动
  if (cfg.ttsClone && !cfg.ttsVoice) log('⚠ TTS_CLONE=1 但 TTS_VOICE 为空，语音会失败');
  if (cfg.proactive && cfg.proactiveGapMinH > cfg.proactiveGapMaxH) log('⚠ 主动消息间隔上下限写反了');
  if (cfg.maxHistory < 10) log('⚠ MAX_HISTORY 偏小，可能记不住上下文');

  mem.loadAll();
  llm.loadUsage();
  mem.pruneOrphans();
  setInterval(mem.pruneOrphans, 30 * 60_000); // 每半小时清一次孤儿记忆

  if (cfg.skillsOn) {
    await loadSkills();
    log(`技能已加载`);
  }

  if (cfg.proactive) {
    log(
      `主动消息：${cfg.proactiveGapMinH}~${cfg.proactiveGapMaxH}h 随机，每天 0~${cfg.proactiveMaxDay} 条，${cfg.proactiveHourStart}~${cfg.proactiveHourEnd} 点`
    );
    setInterval(mem.proactiveTick, 60_000);
  }

  let gw = null;
  const stop = (sig) => {
    log(`收到 ${sig}，退出`);
    if (onebot) qq.stopOneBot();
    else if (gw) {
      gw.running = false;
      clearInterval(gw.timer);
      gw.ws?.close(1000, 'shutdown');
    }
    mem.saveAll(); // 先落盘再退
    releaseInstanceLock();
    setTimeout(() => process.exit(0), 400);
  };
  process.on('SIGINT', () => stop('SIGINT'));
  process.on('SIGTERM', () => stop('SIGTERM'));

  // 真实QQ：直接连自己的 OneBot，不走官方网关，也不需要 AppID
  if (onebot) {
    const masked = String(cfg.onebotUrl).replace(/access_token=[^&]+/g, 'access_token=***');
    log(`OneBot 地址 ${masked}（群里${cfg.onebotGroupAll ? '不 @ 也回' : '只回 @'}）`);
    await qq.startOneBot(onebotHandlers);
    return;
  }

  try {
    await qq.getToken();
  } catch (e) {
    const m = String((e && e.message) || e);
    let hint = '';
    if (/10004|不存在/.test(m)) hint = ' → QQ_APPID 填错了，或这个机器人还没在开放平台创建';
    else if (/100016|secret|401|403/i.test(m)) hint = ' → QQ_APP_SECRET 填错了';
    else if (/fetch failed|ENOTFOUND|ETIMEDOUT|timeout|aborted/i.test(m))
      hint = ' → 连不上 api.bot.qq.com，检查手机网络/流量';
    throw new Error(`${m}${hint}`);
  }

  gw = new Gateway();
  await gw.start();
}

// 只有直接运行 bot.mjs 才启动；被 import（测试/工具）时不动
const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  main().catch((e) => {
    const msg = (e && (e.stack || e.message)) || String(e);
    log('启动失败', msg);
    // 同时打到 stderr（App 把 stdout+stderr 合流写进 logs/bot.log）
    console.error('启动失败：' + ((e && e.message) || msg));
    process.exit(1);
  });
}


// 供测试脚本使用
export { onC2C, onGroupAt, handleCommand, askLLM, makeSender, parseMessage };
