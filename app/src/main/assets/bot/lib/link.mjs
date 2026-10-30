/**
 * 链接相关：
 *   1) 链接理解 —— 收到 http(s) 链接时抓正文，交给模型一起看
 *   2) 收藏链接 —— data/links.json 里随机抽一条，偶尔在回复/主动消息后单独发出去
 *
 * 只用 Node 内置能力（fetch / AbortSignal.timeout），失败一律静默，不影响正常聊天。
 */
import fs from 'node:fs';
import path from 'node:path';
import { cfg, DATA_DIR } from './config.mjs';
import { log } from './log.mjs';
import { sendList, sendTextRaw } from './transport.mjs';

const UA =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';
const ACCEPT = 'text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5';
const MAX_BYTES = 600 * 1024; // 只读前 600KB，够取正文了

export const LINKS_FILE = path.join(DATA_DIR, 'links.json');

/** 从文本里取第一条 http(s) 链接（去掉结尾的标点/括号） */
export function firstUrl(text) {
  const m = String(text || '').match(/https?:\/\/[^\s<>"'）)\]】，。；、]+/i);
  if (!m) return '';
  return m[0].replace(/[.,;:!?、，。；：！？…]+$/, '');
}

const ENTITIES = {
  amp: '&',
  lt: '<',
  gt: '>',
  quot: '"',
  apos: "'",
  nbsp: ' ',
  ensp: ' ',
  emsp: ' ',
  copy: '©',
  reg: '®',
  hellip: '…',
  mdash: '—',
  ndash: '–',
  middot: '·',
  ldquo: '“',
  rdquo: '”',
  lsquo: '‘',
  rsquo: '’',
};

/** 解码 HTML 实体（命名 + 十进制/十六进制） */
export function decodeEntities(s) {
  return String(s || '').replace(/&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z][a-zA-Z0-9]*);/g, (m, g) => {
    if (g[0] === '#') {
      const hex = g[1] === 'x' || g[1] === 'X';
      const n = hex ? parseInt(g.slice(2), 16) : parseInt(g.slice(1), 10);
      if (Number.isFinite(n) && n >= 0 && n <= 0x10ffff) {
        try {
          return String.fromCodePoint(n);
        } catch {
          return m;
        }
      }
      return m;
    }
    const v = ENTITIES[g.toLowerCase()];
    return v === undefined ? m : v;
  });
}

/** HTML → 纯文本：去 script/style/注释/标签，解码实体，压缩空白，按行整理 */
export function htmlToText(html) {
  let s = String(html || '');
  s = s.replace(/<!--[\s\S]*?-->/g, ' ');
  s = s.replace(/<(script|style|noscript|template|svg|iframe|head)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, ' ');
  s = s.replace(/<\s*(br|hr)\s*\/?>/gi, '\n');
  s = s.replace(/<\/\s*(p|div|li|tr|h[1-6]|section|article|aside|header|footer|blockquote|pre|dd|dt|table|ul|ol)\s*>/gi, '\n');
  s = s.replace(/<[^>]*>/g, ' ');
  s = decodeEntities(s);
  s = s
    .replace(/[ \t\u00a0\u3000]+/g, ' ')
    .replace(/\s*\n\s*/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return s;
}

/** 按响应头/页面 meta 猜编码（国内站点还有不少 GBK），失败退回 UTF-8 */
function decodeBody(buf, contentType = '') {
  let enc = '';
  const m = String(contentType).match(/charset\s*=\s*["']?\s*([\w-]+)/i);
  if (m) enc = m[1].toLowerCase();
  if (!enc) {
    const head = buf.subarray(0, 4096).toString('latin1');
    const mm = head.match(/charset\s*=\s*["']?\s*([\w-]+)/i);
    if (mm) enc = mm[1].toLowerCase();
  }
  if (enc && !['utf-8', 'utf8'].includes(enc)) {
    try {
      return new TextDecoder(enc).decode(buf);
    } catch {}
  }
  return buf.toString('utf8');
}

/** 读响应体但不超过 maxBytes，避免被超大页面拖死 */
async function readLimited(res, maxBytes = MAX_BYTES) {
  try {
    if (!res.body?.getReader) {
      const t = await res.text();
      return t.slice(0, maxBytes);
    }
    const reader = res.body.getReader();
    const chunks = [];
    let size = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value?.length) {
        chunks.push(Buffer.from(value));
        size += value.length;
      }
      if (size >= maxBytes) {
        try {
          await reader.cancel();
        } catch {}
        break;
      }
    }
    return decodeBody(Buffer.concat(chunks).subarray(0, maxBytes));
  } catch {
    return '';
  }
}

/** 抓网页正文；超时 8s，失败返回 ''（静默） */
export async function fetchLinkText(url, maxChars = cfg.linkMaxChars) {
  if (!url || !/^https?:\/\//i.test(url)) return '';
  try {
    const res = await fetch(url, {
      redirect: 'follow',
      headers: { 'User-Agent': UA, Accept: ACCEPT, 'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.6' },
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) return '';
    const ct = String(res.headers.get('content-type') || '');
    if (ct && !/text\/|html|xml|json/i.test(ct)) return '';

    let raw;
    if (/text\/plain/i.test(ct)) {
      raw = decodeBody(Buffer.from(await res.arrayBuffer()).subarray(0, MAX_BYTES), ct);
    } else {
      raw = await readLimited(res, MAX_BYTES);
    }
    const text = htmlToText(raw);
    if (!text) return '';
    return text.slice(0, Math.max(0, Number(maxChars) || 0));
  } catch (e) {
    log('链接正文抓取失败', String((e && e.message) || e).slice(0, 100));
    return '';
  }
}

// ---------------------------------------------------------------- links.json

/** 读 data/links.json：[{"title":"...","url":"https://..."}]；文件坏了就当时没有 */
export function loadLinks() {
  try {
    const raw = JSON.parse(fs.readFileSync(LINKS_FILE, 'utf8'));
    const arr = Array.isArray(raw) ? raw : Array.isArray(raw?.links) ? raw.links : [];
    return arr
      .filter((x) => x && /^https?:\/\//i.test(String(x.url || '').trim()))
      .map((x) => ({ title: String(x.title || '').trim(), url: String(x.url).trim() }));
  } catch {
    return [];
  }
}

export function pickLink() {
  const links = loadLinks();
  if (!links.length) return null;
  return links[Math.floor(Math.random() * links.length)];
}

/**
 * 发一条链接消息（标题 + 换行 + URL），是单独一条消息、不走「按行拆句」
 * @param {string} kind 'users' | 'groups'
 * @param {string} id  openid / group_openid
 * @param {string|null} msgId 被动回复的 msg_id；主动消息传 null
 * @param {number} seqStart 官方接口 msg_seq 起点
 */
export async function sendLink(kind, id, msgId = null, seqStart = 1) {
  const l = pickLink();
  if (!l) return false;
  const text = l.title ? `${l.title}\n${l.url}` : l.url;
  try {
    if (!msgId) {
      // 主动消息：官方/OneBot 都走「原样发一整条」，不拆句
      await sendTextRaw('users', id, text, null, 1);
    } else {
      // sendList 不做切分，整条（含换行）一次发出去
      await sendList(kind, id, [text], msgId, 0, seqStart);
    }
    log(`附带链接：${l.title || l.url}`);
    return true;
  } catch (e) {
    log('附带链接失败', e.message);
    return false;
  }
}

/**
 * 按概率附一条链接
 * @param {{chance:number, kind:string, id:string, msgId?:string|null, sentCount?:number, maxMsgs?:number}} o
 */
export async function maybeSendLink({ chance, kind, id, msgId = null, sentCount = 0, maxMsgs = 4 } = {}) {
  const p = Number(chance) || 0;
  if (p <= 0 || !id || sentCount >= maxMsgs) return false;
  if (Math.random() >= p) return false;
  return sendLink(kind, id, msgId, sentCount + 1);
}