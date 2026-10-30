/**
 * 模型能力：对话、视觉、语音识别、语音合成
 */
import fs from 'node:fs';
import path from 'node:path';
import { cfg, systemPrompt, DATA_DIR } from './config.mjs';
import { log } from './log.mjs';
import { decode as decodeSilk } from 'silk-wasm';

const strip = (t) => String(t || '').replace(/[\s\p{P}]/gu, '').length;

// 用量统计：本次运行 + 累计 + 按天（落盘到 data/usage.json）
const USAGE_FILE = path.join(DATA_DIR, 'usage.json');
const blank = () => ({ calls: 0, prompt: 0, completion: 0 });

export const usage = { ...blank(), since: Date.now() }; // 本次运行
const total = blank(); // 累计（跨重启）
const days = {}; // 按天
const byUser = {}; // 按会话/人
let saveTimer = null;

export function loadUsage() {
  try {
    const d = JSON.parse(fs.readFileSync(USAGE_FILE, 'utf8'));
    Object.assign(total, d.total || {});
    Object.assign(days, d.days || {});
    Object.assign(byUser, d.byUser || {});
  } catch {}
}

function saveUsageSoon() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      fs.writeFileSync(USAGE_FILE, JSON.stringify({ total, days, byUser }, null, 2));
    } catch {}
  }, 2000);
}

const todayKey = () => {
  const d = new Date(); // 本地时区，避免凌晨 0~8 点记到前一天
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

function record(p, c, who = '系统') {
  usage.calls += 1;
  usage.prompt += p;
  usage.completion += c;

  total.calls += 1;
  total.prompt += p;
  total.completion += c;

  const k = todayKey();
  days[k] = days[k] || blank();
  days[k].calls += 1;
  days[k].prompt += p;
  days[k].completion += c;

  byUser[who] = byUser[who] || blank();
  byUser[who].calls += 1;
  byUser[who].prompt += p;
  byUser[who].completion += c;

  saveUsageSoon();
}

/** 价格：qwen3.7-flash 输入 0.2 元/百万，输出 0.8 元/百万 */
const priceOf = (u) => (u.prompt / 1e6) * 0.2 + (u.completion / 1e6) * 0.8;

/** 给 /usage 用的完整报告 */
export function usageReport() {
  const today = days[todayKey()] || blank();
  return {
    run: { ...usage, cost: priceOf(usage) },
    today: { ...today, cost: priceOf(today) },
    total: { ...total, cost: priceOf(total) },
    byUser: Object.fromEntries(
      Object.entries(byUser).map(([k, v]) => [k, { ...v, cost: priceOf(v) }])
    ),
    days,
  };
}

/** 基础对话调用 */
export async function chat(messages, { temperature, maxTokens = 2048, who = '系统' } = {}) {
  const res = await fetch(`${cfg.llmBase}/chat/completions`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${cfg.llmKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: cfg.model,
      messages,
      max_tokens: maxTokens,
      temperature: temperature ?? cfg.temperature,
      stream: false,
      enable_thinking: cfg.thinking,
    }),
    signal: AbortSignal.timeout(90_000),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`LLM HTTP ${res.status}: ${text.slice(0, 200)}`);
  const data = JSON.parse(text);
  const u = data?.usage || {};
  record(u.prompt_tokens || 0, u.completion_tokens || 0, who);

  const reply = data?.choices?.[0]?.message?.content?.trim();
  if (!reply) throw new Error(`模型返回为空: ${text.slice(0, 200)}`);
  return reply;
}

/** 下载图片并转 data URL（限制大小） */
export async function imageToDataUrl(url, maxBytes = 8 * 1024 * 1024) {
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(20_000) });
    if (!res.ok) return null;
    const buf = Buffer.from(await res.arrayBuffer());
    if (!buf.length || buf.length > maxBytes) return null;
    const ct = res.headers.get('content-type') || 'image/jpeg';
    if (!ct.startsWith('image/')) return null;
    return `data:${ct};base64,${buf.toString('base64')}`;
  } catch {
    return null;
  }
}

/** SILK → WAV（QQ 语音真实格式） */
async function silkToWav(buf, sampleRate = 24000) {
  const { data } = await decodeSilk(buf, sampleRate);
  const pcm = Buffer.from(data);
  const n = pcm.length / 2;
  const out = Buffer.alloc(44 + pcm.length);
  out.write('RIFF', 0);
  out.writeUInt32LE(36 + pcm.length, 4);
  out.write('WAVE', 8);
  out.write('fmt ', 12);
  out.writeUInt32LE(16, 16);
  out.writeUInt16LE(1, 20);
  out.writeUInt16LE(1, 22);
  out.writeUInt32LE(sampleRate, 24);
  out.writeUInt32LE(sampleRate * 2, 28);
  out.writeUInt16LE(2, 32);
  out.writeUInt16LE(16, 34);
  out.write('data', 36);
  out.writeUInt32LE(pcm.length, 40);
  pcm.copy(out, 44);
  return { wav: out, seconds: n / sampleRate };
}

/** 下载语音并转文字；失败返回 null */
export async function speechToText(url) {
  if (!cfg.asrOn || !url) return null;
  try {
    const dl = await fetch(url, { signal: AbortSignal.timeout(20_000) });
    if (!dl.ok) {
      log(`语音识别：下载失败 HTTP ${dl.status}`);
      return null;
    }
    let buf = Buffer.from(await dl.arrayBuffer());
    if (!buf.length || buf.length > 8 * 1024 * 1024) {
      log(`语音识别：大小异常 ${buf.length}`);
      return null;
    }

    // 判断真实格式：SILK 头前有 0x02，用 includes
    const head = buf.subarray(0, 24).toString('latin1');
    let fmt = 'silk';
    if (!head.includes('#!SILK')) {
      if (head.includes('#!AMR')) fmt = 'amr';
      else if (head.includes('RIFF')) fmt = 'wav';
      else if (head.includes('OggS')) fmt = 'ogg';
      else if (head.includes('ID3')) fmt = 'mp3';
    }

    if (fmt === 'silk') {
      const { wav, seconds } = await silkToWav(buf);
      buf = wav;
      fmt = 'wav';
      log(`语音识别：silk → wav（${seconds.toFixed(1)}s）`);
    }

    const mime = { wav: 'audio/wav', amr: 'audio/amr', ogg: 'audio/ogg', mp3: 'audio/mpeg' }[fmt];

    // OpenAI 兼容的语音识别：POST {base}/audio/transcriptions（multipart）
    if (cfg.asrProvider === 'openai') {
      const form = new FormData();
      form.append('model', cfg.asrModel);
      form.append('file', new Blob([buf], { type: mime }), `audio.${fmt}`);
      const r = await fetch(`${cfg.asrBase}/audio/transcriptions`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${cfg.asrKey}` },
        body: form,
        signal: AbortSignal.timeout(60_000),
      });
      if (!r.ok) {
        log(`语音识别失败：HTTP ${r.status} ${(await r.text()).slice(0, 120)}`);
        return null;
      }
      const d = await r.json();
      const out = String(d?.text || '').trim().replace(/[。！？，、；：]+$/g, '');
      if (!out) {
        log('语音识别：没有结果');
        return null;
      }
      log(`语音识别：${out}`);
      return out;
    }

    const dataUrl = `data:${mime};base64,${buf.toString('base64')}`;

    const res = await fetch(`${cfg.asrBase}/services/aigc/multimodal-generation/generation`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${cfg.asrKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: cfg.asrModel,
        input: { messages: [{ role: 'user', content: [{ type: 'input_audio', input_audio: { data: dataUrl } }] }] },
        parameters: { format: fmt, sample_rate: '24000' },
      }),
      signal: AbortSignal.timeout(60_000),
    });
    const d = await res.json();
    const text = d?.output?.sentence?.text || d?.sentence?.text || null;
    if (!text) {
      log('语音识别：没有结果', JSON.stringify(d).slice(0, 160));
      return null;
    }
    const clean = String(text).trim().replace(/[。！？，、；：]+$/g, '');
    log(`语音识别：${clean}`);
    return clean || null;
  } catch (e) {
    log('语音识别失败', e.message);
    return null;
  }
}

/** 合成语音，返回音频 URL */
/** 本地 TTS（Piper / 自建服务）：失败只记日志并返回 null，不抛错 */
async function tryLocalTts(text) {
  if (!cfg.localTtsUrl) {
    log('本地语音没配置地址（LOCAL_TTS_URL 为空）');
    return null;
  }
  try {
    const t0 = Date.now();
    const r = await fetch(cfg.localTtsUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(cfg.localTtsKey ? { Authorization: `Bearer ${cfg.localTtsKey}` } : {}),
      },
      body: JSON.stringify({
        text: String(text).slice(0, 400),
        voice: cfg.ttsVoice,
        format: 'wav',
      }),
      signal: AbortSignal.timeout(120_000),
    });
    if (!r.ok) {
      const body = (await r.text().catch(() => '')).slice(0, 300).replace(/\s+/g, ' ');
      log(`本地语音失败：HTTP ${r.status}（${cfg.localTtsUrl}）${body ? '：' + body : ''}`);
      return null;
    }
    const buf = Buffer.from(await r.arrayBuffer());
    if (!buf.length) {
      log('本地语音失败：返回空音频');
      return null;
    }
    const f = path.join(DATA_DIR, `tts-${Date.now()}.wav`);
    fs.writeFileSync(f, buf);
    log(`语音：本地合成 ${(buf.length / 1024).toFixed(0)}KB（${Date.now() - t0}ms）`);
    return `file://${f}`;
  } catch (e) {
    log(`本地语音失败：${e.message}（${cfg.localTtsUrl}）——本机语音服务没启动，或 Piper 没装`);
    return null;
  }
}

export async function textToSpeech(text) {
  if (!cfg.ttsOn) return null;
  // 本地小模型：连自建 TTS 服务（Piper / GPT-SoVITS / CosyVoice / XTTS…）
  if (cfg.ttsProvider === 'local' || cfg.ttsMode === 'local') {
    const local = await tryLocalTts(text);
    if (local) return local;
    log('本地语音不可用，改用云端音色');
  }
  const hasKey = Boolean(cfg.ttsKey) || Boolean(cfg.apiKey);
  try {
    // 任何 OpenAI 兼容的 TTS：POST {base}/audio/speech，返回音频字节
    if (cfg.ttsProvider === 'openai') {
      if (!cfg.ttsBase) {
        log('语音合成失败：「OpenAI 兼容」没填 TTS 接口地址（去「对话 → 语音」里填，如 https://api.openai.com/v1）');
        return null;
      }
      if (!cfg.ttsKey) {
        log('语音合成失败：没填语音 Key（「对话 → 语音 → TTS Key」，留空则用「模型」里的 Key）');
        return null;
      }
      const r = await fetch(`${cfg.ttsBase}/audio/speech`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${cfg.ttsKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: cfg.ttsModel,
          input: String(text).slice(0, 400),
          voice: cfg.ttsVoice,
          response_format: 'wav',
        }),
        signal: AbortSignal.timeout(60_000),
      });
      if (!r.ok) {
        log(`TTS 失败：HTTP ${r.status} ${(await r.text()).slice(0, 120)}`);
        return null;
      }
      const buf = Buffer.from(await r.arrayBuffer());
      if (!buf.length) {
        log('TTS 失败：返回空音频');
        return null;
      }
      const f = path.join(DATA_DIR, `tts-${Date.now()}.wav`);
      fs.writeFileSync(f, buf);
      log(`语音：合成 ${(buf.length / 1024).toFixed(0)}KB`);
      return `file://${f}`;
    }
    // 复刻出来的音色也用同一个「多模态语音合成」接口：model=复刻模型，voice=voice_id。
    // 旧版走 /services/audio/tts/SpeechSynthesizer（另一套 API），复刻音色用它反而不通。
    if (cfg.ttsMode === 'clone') {
      log(`语音：复刻音色 ${cfg.ttsVoice || '(空)'}（模型 ${cfg.ttsModel}）`);
    }
    if (!hasKey) {
      log('语音合成失败：没填语音服务的 Key（云端 TTS 要用自己的 Key）');
      return null;
    }
    if (!/tts|speech|audio|cosy/i.test(cfg.ttsModel)) {
      log(`⚠ 语音模型「${cfg.ttsModel}」不像 TTS 专用模型（应该是 qwen3-tts-flash 这类），可能失败或超时`);
    }
    const DEFAULT_DASHSCOPE = 'https://dashscope.aliyuncs.com/api/v1';
    const tryDash = async (base) => {
      const t0 = Date.now();
      log(`语音：请求 ${base} · 模型 ${cfg.ttsModel} · 音色 ${cfg.ttsVoice}`);
      const r = await fetch(`${base}/services/aigc/multimodal-generation/generation`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${cfg.ttsKey || cfg.apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: cfg.ttsModel,
          input: { text: String(text).slice(0, 400), voice: cfg.ttsVoice, language_type: 'Chinese' },
        }),
        // 30 秒：TTS 首句可能慢，但再久只会把整条回复拖死
        signal: AbortSignal.timeout(30_000),
      });
      if (!r.ok) {
        const body = (await r.text()).slice(0, 160);
        log(`语音合成失败：HTTP ${r.status}（${base}）${body}`);
        return { url: null, retry: r.status >= 500 || r.status === 404 || r.status === 403 };
      }
      const d = await r.json();
      const url = d?.output?.audio?.url || null;
      if (!url) {
        log(`语音合成失败：返回里没有音频地址（${base}）`);
        return { url: null, retry: false };
      }
      log(`语音：合成成功（${((Date.now() - t0) / 1000).toFixed(1)}s）`);
      return { url, retry: false };
    };
    // 语音地址填成聊天地址（…/compatible-mode/v1、…/chat…）时直接用官方语音地址，
    // 否则会一直卡到超时，日志里只会看到一句“超时”
    const looksChat = /compatible-mode|\/chat\/|chat\/completions/i.test(cfg.ttsBase || '');
    if (looksChat) log(`语音：地址「${cfg.ttsBase}」像是聊天接口，自动改用 ${DEFAULT_DASHSCOPE}`);
    const bases =
      cfg.ttsBase === DEFAULT_DASHSCOPE || !cfg.ttsBase || looksChat
        ? [DEFAULT_DASHSCOPE]
        : [cfg.ttsBase, DEFAULT_DASHSCOPE];
    for (let i = 0; i < bases.length; i++) {
      const base = bases[i];
      try {
        const r = await tryDash(base);
        if (r.url) return r.url;
        if (!r.retry) return null;
      } catch (e) {
        const msg = String(e?.message || e);
        if (/aborted|timeout/i.test(msg)) {
          log(`语音合成失败：${base} 30 秒没响应（模型 ${cfg.ttsModel}）——地址可能不是语音接口，或者网络不通`);
        } else {
          log(`语音合成失败：${msg}（地址 ${base} · 模型 ${cfg.ttsModel}）`);
        }
        if (base === DEFAULT_DASHSCOPE) {
          log('提示：大概率是语音地址/模型不对。语音不是聊天模型，模型填 qwen3-tts-flash 这类，地址用 https://dashscope.aliyuncs.com/api/v1');
        }
      }
      if (i + 1 < bases.length) log(`语音：换默认地址再试一次（${DEFAULT_DASHSCOPE}）`);
    }
    return null;
  } catch (e) {
    const msg = String(e?.message || e);
    if (/aborted|timeout/i.test(msg)) {
      log(`语音合成失败：${cfg.ttsBase} 请求超时（60 秒）——地址不对或网络不通；语音地址要填 TTS 服务的地址`);
    } else {
      log(`语音合成失败：${msg}（地址 ${cfg.ttsBase}）——网络不通，或这个地址不支持 TTS`);
    }
    return null;
  }
}

/** 生成"用语音说什么"：接着说文字回复，不换话题 */
export async function voiceScript(reply) {
  try {
    const t = await chat(
      [
        {
          role: 'system',
          content:
            systemPrompt +
            '\n\n现在你要发一条语音。把【你刚打字回的那句】接着说下去：同一件事、同一个情绪，补一点细节。' +
            '不许换话题、不许编新的小事、不许说"好 你听"这种开场白。20~50 字，直接说内容。',
        },
        { role: 'user', content: `你刚打字回了：${reply}` },
      ],
      { maxTokens: 180 }
    );
    let out = String(t).replace(/\n+/g, ' ').trim();
    out = out.replace(/^(好[\s，,、]*你听|你听|嗯+|哦+|在呢)[\s，,。！!~～]*/, '').trim();
    if (strip(out) < 10) out = String(reply).replace(/\n+/g, '，').trim();
    return out;
  } catch {
    return String(reply).replace(/\n+/g, '，').trim();
  }
}

export { strip };
