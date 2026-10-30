import fs from 'node:fs';
/**
 * 记忆：会话原文 / 老对话摘要 / 长期事实 / 联系人（主动消息）
 */
import path from 'node:path';
import { cfg, DATA_DIR, systemPrompt } from './config.mjs';
import { log } from './log.mjs';
import { JsonMap } from './store.mjs';
import { chat, strip } from './llm.mjs';
import { uploadMedia, sendMedia, proactiveText } from './transport.mjs';
import { textToSpeech } from './llm.mjs';
import { maybeSendLink } from './link.mjs';
import { saveContactsNow } from './contacts.mjs';

const file = (n) => path.join(DATA_DIR, n);

export const sessions = new JsonMap(file('sessions.json'), { maxSize: cfg.maxSessions });
export const summaries = new JsonMap(file('summaries.json'));
export const facts = new JsonMap(file('facts.json')); // bot 的 /reset all 要用
export const peers = new JsonMap(file('peers.json'));
// 累计收到多少条用户消息（不受会话长度限制，用于触发长期记忆）
export const counts = new JsonMap(file('counts.json'));

export function bumpCount(convKey) {
  const n = (counts.get(convKey) || 0) + 1;
  counts.set(convKey, n);
  counts.save();
  return n;
}

/** 退出前把内存里的东西全部落盘，避免丢最后几条 */
export function saveAll() {
  for (const m of [sessions, summaries, facts, peers]) {
    try {
      m.saveNow();
    } catch {}
  }
  // 联系人另存一份（数组格式、原子写），退出时立即刷盘
  try {
    saveContactsNow();
  } catch {}
}

/** 会话被淘汰后，它的摘要/画像也要跟着删，否则越积越多 */
export function pruneOrphans() {
  const alive = new Set(sessions.keys());
  let n = 0;
  for (const k of [...summaries.keys()]) if (!alive.has(k)) { summaries.delete(k); n += 1; }
  for (const k of [...facts.keys()]) if (!alive.has(k)) { facts.delete(k); n += 1; }
  for (const k of [...counts.keys()]) if (!alive.has(k)) { counts.delete(k); n += 1; }
  if (n) {
    summaries.save();
    facts.save();
    log(`清理孤立记忆：${n} 条`);
  }
}

export function loadAll() {
  if (cfg.memoryOn) sessions.load();
  summaries.load();
  facts.load();
  peers.load();
  counts.load();
  log(`记忆已加载：会话 ${sessions.size} · 摘要 ${summaries.size} · 人 ${facts.size} · 联系人 ${peers.size}`);
}

// ---------------- 滚动摘要 ----------------
const rolling = new Set();

export async function rollSummary(convKey) {
  const all = sessions.get(convKey) || [];
  if (all.length <= cfg.recentKeep + 4 || rolling.has(convKey)) return;
  rolling.add(convKey);
  try {
    const old = summaries.get(convKey)?.text || '';
    const chunk = all.slice(0, all.length - cfg.recentKeep);
    const recent = all.slice(-cfg.recentKeep);
    const dialog = chunk.map((m) => `${m.role === 'user' ? '对方' : '我'}: ${m.content}`).join('\n');

    const text = await chat(
      [
        {
          role: 'system',
          content:
            '把「已有摘要」和「新增对话」合并成一段新摘要，不超过 150 字。保留：你们的关系、重要的事、对方的情绪和状态、约定过的事。去掉寒暄。第三人称，不要标题。',
        },
        { role: 'user', content: `已有摘要：${old || '（无）'}\n\n新增对话：\n${dialog}` },
      ],
      { temperature: 0.3, maxTokens: 300 }
    );

    summaries.set(convKey, { text });
    sessions.set(convKey, recent);
    summaries.save();
    sessions.save();
    log(`压缩 ${chunk.length} 条旧对话 → 摘要`);
  } catch (e) {
    log('压缩摘要失败', e.message);
  } finally {
    rolling.delete(convKey);
  }
}

// ---------------- 长期事实 ----------------
const extracting = new Set();

export async function extractFacts(convKey) {
  if (!cfg.learnOn || !cfg.factsOn || extracting.has(convKey)) return;
  const msgs = (sessions.get(convKey) || []).filter((m) => m.role === 'user').map((m) => m.content);
  // 用累计条数判断——会话本身只有十几条，永远到不了阈值
  const total = counts.get(convKey) || 0;
  if (total < cfg.learnMinMsgs) return;
  const prev = facts.get(convKey);
  if (prev && total - (prev.count || 0) < cfg.learnStep) return;
  if (msgs.length < 3) return;

  extracting.add(convKey);
  try {
    const text = await chat(
      [
        {
          role: 'system',
          content:
            '从这些消息里提取两类信息，用两行小标题分开：\n' +
            '【对方】名字、身份、喜欢什么、讨厌什么、最近状况（2~4 行，每行不超 20 字）\n' +
            '【相处】你俩是什么关系、对方喜欢你怎么说话、有没有固定的称呼或梗（1~2 行，每行不超 20 字）\n' +
            '只写有把握的，没提到的不要编，不要序号。',
        },
        { role: 'user', content: msgs.slice(-40).join('\n') },
      ],
      { temperature: 0.3, maxTokens: 200 }
    );
    facts.set(convKey, { text, count: total, at: Date.now() });
    facts.save();
    log(`更新长期记忆：${text.replace(/\n/g, ' / ').slice(0, 50)}`);
  } catch (e) {
    log('提炼记忆失败', e.message);
  } finally {
    extracting.delete(convKey);
  }
}

// ---------------- 联系人 + 主动消息 ----------------
const LINES = ['在干嘛', '干嘛呢', '今天咋没声了', '我想你了', '人呢', '忙啥呢', '理理我', '干嘛去了', '饭吃了没'];

// 按风格的默认句库
const TEMPLATES = {
  caring: [
    '天冷多穿点呀', '吃饭了没', '今天累不累', '早点休息哦', '别太累了',
    '注意身体', '给你倒了杯热水（意念送达）', '今天开心不',
  ],
  flirty: [
    '想你了', '突然想到你', '今天干嘛呢，有没有想我', '刚才好像有人想我，是你吗',
    '你不在，有点无聊', '想你了，虽然才聊过没多久',
  ],
  roast: [
    '今天又摸鱼了吧', '你是不是又在发呆', '该不会又在躺着吧',
    '今天代码又崩了？', '我猜你又在刷视频',
  ],
};

/** 按风格生成主动消息 */
async function genProactiveText(openid, style) {
  // 自定义模板
  if (style === 'custom' && cfg.proactiveTemplate.trim()) {
    const tmpl = cfg.proactiveTemplate;
    // 尝试从 memory 提取对方名字/昵称
    let name = '';
    try {
      const f = facts.get(`c2c:${openid}`)?.text || '';
      const m = f.match(/名字[：:]([^\n]{1,10})/);
      if (m) name = m[1].trim();
    } catch {}
    return tmpl.replace(/\{name\}/g, name || '你');
  }

  // 具体风格：9 成用预制库，1 成用 LLM
  if (style !== 'mix') {
    if (Math.random() < 0.9) {
      const pool = TEMPLATES[style] || LINES;
      return pool[Math.floor(Math.random() * pool.length)];
    }
  }

  // mix 或小概率 LLM → 用模型生成
  const convKey = `c2c:${openid}`;
  const hist = sessions.get(convKey) || [];

  let mood = '';
  if (style === 'caring') mood = '语气关心、温柔的，可以关心天气/身体/心情。';
  else if (style === 'flirty') mood = '语气暧昧、亲昵的，可以表达想念/在意。';
  else if (style === 'roast') mood = '吐槽/调侃对方，但要亲昵不伤人，像损友。';

  const sys = buildSystem(convKey, hist) +
    '\n\n现在是你主动找对方说话（对方还没开口）。说一句话。' +
    mood +
    '不超过 20 字，别用"在干嘛"这种空话。';

  try {
    const generated = await chat(
      [
        { role: 'system', content: sys },
        ...hist.slice(-12),
        { role: 'user', content: '（你想主动说点什么）' },
      ],
      { maxTokens: 80 }
    );
    const clean = String(generated).replace(/\n+/g, ' ').trim();
    if (clean && clean.length <= 60) return clean;
  } catch (e) {
    log('主动消息生成失败，用默认句', e.message);
  }
  return LINES[Math.floor(Math.random() * LINES.length)];
}
const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
};
const randGap = () =>
  (cfg.proactiveGapMinH + Math.random() * (cfg.proactiveGapMaxH - cfg.proactiveGapMinH)) * 3600_000;

// 对方明确说别主动找他
const NO_PROACTIVE = /不要主动|别主动|别老发|别一直发|别总发|不要发消息给我|不想收到|别烦我|别来找我|少发点/;
export const isNoProactive = (t) => NO_PROACTIVE.test(String(t || ''));

/** 暂停对这个人的主动消息（默认 7 天） */
export function pauseProactive(openid, days = cfg.proactivePauseDays) {
  const p = peers.get(openid) || { lastSeen: 0, nextAt: 0, day: '', count: 0 };
  p.pausedUntil = Date.now() + days * 86400_000;
  peers.set(openid, p);
  peers.save();
  log(`主动消息：${openid.slice(0, 8)}… 暂停 ${days} 天（对方明确要求）`);
}

export function touchPeer(openid) {
  if (!cfg.proactive || !openid) return;
  const now = Date.now();
  const p = peers.get(openid) || { lastSeen: 0, nextAt: 0, day: '', count: 0 };
  p.lastSeen = now;
  p.repliedDay = today();
  p.unanswered = 0;
  if (p.heatAt && now - p.heatAt > 86400_000) p.heat = 0;
  p.heat = (p.heat || 0) + 1;
  p.heatAt = now;
  // 聊得越频繁，主动配额越低；但不再一刀切到 0
  p.cap = p.heat >= 12 ? cfg.proactiveBusyMax : p.heat >= 6 ? 6 : cfg.proactiveMaxDay;
  p.nextAt = now + randGap();
  peers.set(openid, p);
  peers.save();
}

export async function proactiveTick() {
  if (!cfg.proactive) return;
  const now = Date.now();
  const day = today();
  const hour = new Date().getHours();
  if (hour < cfg.proactiveHourStart || hour >= cfg.proactiveHourEnd) return;

  for (const [openid, p] of peers) {
    // 目标过滤：如果设了只对谁发，就只对列表里的发
    if (cfg.proactiveTargets.length && !cfg.proactiveTargets.includes(openid)) continue;

    // 对方要求过别主动找 → 到期前不发
    if (p.pausedUntil && now < p.pausedUntil) continue;
    if (!p.nextAt || now < p.nextAt) continue;
    if (p.day !== day) {
      p.day = day;
      p.count = 0;

      // 实时算热度（24 小时前的记录不算），不依赖磁盘里可能过时的 cap
      const heat = p.heatAt && now - p.heatAt > 86400_000 ? 0 : p.heat || 0;
      const base = heat >= 12 ? cfg.proactiveBusyMax : cfg.proactiveMaxDay;

      p.todayCap = cfg.proactiveRandomCap ? Math.floor(Math.random() * (base + 1)) : base;
      if (p.repliedDay === day) p.todayCap = Math.min(p.todayCap, cfg.proactiveAfterReplyMax);
    }
    if (p.count >= (p.todayCap || 0)) continue;

    // 用模板/模型生成内容
    const text = await genProactiveText(openid, cfg.proactiveStyle);

    try {
      if (cfg.proactiveDry) {
        log(`[DRY] 本应主动发给 ${openid.slice(0, 8)}…：${text}`);
      } else {
        await proactiveText(openid, text);
        log(`主动开口：${text}`);

        // 小概率再单独附一条收藏链接（data/links.json）
        await maybeSendLink({
          chance: cfg.linkSendChanceProactive,
          kind: 'users',
          id: openid,
          msgId: null,
          sentCount: 1,
          maxMsgs: 2,
        }).catch(() => false);

        // 小概率同时发一条语音
        if (Math.random() < cfg.proactiveVoiceChance) {
          try {
            const url = await textToSpeech(text);
            if (url) {
              const fi = await uploadMedia('users', openid, url, 3);
              if (fi) {
                await sendMedia('users', openid, fi, null, 1);
                log('主动语音：已发出');
              }
            }
          } catch (e) {
            log('主动语音失败', e.message);
          }
        }
      }
      p.count += 1;
      p.nextAt = now + randGap();
      peers.set(openid, p);
      peers.save();
    } catch (e) {
      log('主动发送失败', e.message);
      p.nextAt = now + 3600_000;
      peers.set(openid, p);
    }
  }
}

// ---------------- 构建对话上下文 ----------------
export function buildSystem(convKey, history) {
  // 注意：这里不放时间——system 前缀必须稳定，才能命中平台缓存
  let sys = systemPrompt;

  // 当下流行的梗（data/slang.json，可手动更新）
  try {
    const slang = JSON.parse(fs.readFileSync(path.join(DATA_DIR, 'slang.json'), 'utf8'));
    const items = (slang?.items || []).filter((x) => x?.word).slice(0, 6);
    if (items.length) {
      sys += '\n\n最近流行的说法（可以偶尔自然用一下，别硬塞、别解释）：\n' +
        items.map((x) => `- ${x.word}：${x.meaning || ''}`).join('\n');
    }
  } catch {}

  const sum = summaries.get(convKey);
  if (sum?.text) sys += `\n\n【之前聊过的，摘要】${sum.text}`;

  const f = facts.get(convKey);
  if (f?.text) sys += `\n\n关于对方，你记得这些（自然用上，别照念）：\n${f.text}`;

  // 关系深度：按聊过多少条决定熟络程度
  const userCount = history.filter((m) => m.role === 'user').length;
  const stage =
    userCount >= 60 ? '你们已经很熟了。可以怼、可以撒娇、可以懒得客气，直接说话。'
    : userCount >= 20 ? '你俩有点熟了。说话随意点，别像刚认识那样拘谨。'
    : userCount >= 5 ? '刚聊过几次，还不到随便开玩笑的程度，但也不用太客气。'
    : '这是刚认识，别太自来熟，也别端着。';
  sys += `\n\n和这个人的关系：${stage}（已聊 ${userCount} 条）`;

  // 长度自适应
  const lens = history.filter((m) => m.role === 'user').map((m) => String(m.content || '').length);
  if (lens.length >= 3) {
    const avg = lens.reduce((a, b) => a + b, 0) / lens.length;
    if (avg >= 15) sys += '\n\n对方说话偏长，你也多说几句。';
    else if (avg <= 6) sys += '\n\n对方说话很短，你也别啰嗦。';
  }

  // 对方在重复同一句话 → 别再当新问题答，调侃他
  const userMsgs = history.filter((m) => m.role === 'user').map((m) => String(m.content || '').trim());
  const tail = userMsgs.slice(-3);
  if (tail.length >= 2 && new Set(tail).size === 1) {
    sys += '\n\n对方已经连着发了同一句话好几遍。别再正常回答了，直接调侃他：你在干嘛、是不是卡了、复读机啊。';
  }

  // 防复读
  const recent = history.filter((m) => m.role === 'assistant').slice(-2);
  if (recent.length >= 2 && recent.every((m) => strip(m.content) <= 6)) {
    sys += '\n\n【重要】你刚才连着几次都回得很短，这次至少 15 个字，换个说法。';
  }

  return sys;
}
