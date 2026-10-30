/**
 * AI 自己挑素材（MEDIA_SMART=1 时生效）：
 *   - 把 emoji/ 与 image/ 的文件清单（可带标签）追加进 system prompt
 *   - 模型用 [EMOJI:文件名] / [PIC:文件名] 指定要发的图
 *   - 发送端把标记摘掉，再去对应目录找文件发出（找不到就只删标记）
 *
 * 标签文件：data/media-tags.json（路径见 cfg.mediaTagsFile），形如 {"猫猫.png":"撒娇"}
 */
import fs from 'node:fs';
import path from 'node:path';
import { cfg } from './config.mjs';
import { listEmojis, listImages, sendEmojiFile, sendImageFile } from './emoji.mjs';
import { log } from './log.mjs';

const MAX_LIST_EMOJI = 60; // 别把 prompt 撑太大
const MAX_LIST_IMAGE = 40;

/** 读标签文件：{"文件名.png":"撒娇"}，读不到就空对象 */
export function loadTags() {
  try {
    const raw = JSON.parse(fs.readFileSync(cfg.mediaTagsFile, 'utf8'));
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
    const out = {};
    for (const [k, v] of Object.entries(raw)) {
      const name = String(k || '').trim();
      if (name && typeof v === 'string' && v.trim()) out[name] = v.trim().slice(0, 20);
    }
    return out;
  } catch {
    return {};
  }
}

const toItem = (file, tags) => {
  const name = path.basename(file);
  return { name, file, tag: tags[name] || '' };
};

/** 当前可用素材清单 */
export function catalog() {
  const tags = loadTags();
  return {
    emojis: listEmojis().map((f) => toItem(f, tags)),
    images: listImages().map((f) => toItem(f, tags)),
  };
}

/** 纯函数：根据清单拼 system prompt 追加段（方便测试） */
export function buildPrompt(cat) {
  const { emojis = [], images = [] } = cat || {};
  if (!emojis.length && !images.length) return '';
  const fmt = (arr, max) => {
    const shown = arr.slice(0, max).map((x) => (x.tag ? `${x.name}=${x.tag}` : x.name)).join('、');
    return arr.length > max ? `${shown}…` : shown;
  };
  let s =
    '\n\n【可用素材】需要发表情包时，在回复里单独一行写 [EMOJI:文件名]；需要发图片时单独一行写 [PIC:文件名]。' +
    '文件名必须从下面的清单里原样挑，一次最多各一个，不需要就千万别写这些标记。' +
    '（不要说“手机坏了/相册空了/没存表情包”这类话。）';
  if (emojis.length) s += `\n表情包清单：${fmt(emojis, MAX_LIST_EMOJI)}`;
  if (images.length) s += `\n图片清单：${fmt(images, MAX_LIST_IMAGE)}`;
  return s;
}

/** 读真实目录 + 标签文件，拼出要追加到 system 的内容 */
export function buildMediaPrompt() {
  let cat;
  try {
    cat = catalog();
  } catch (e) {
    log('素材清单读取失败', e.message);
    return '';
  }
  const ec = cat.emojis.length;
  const ic = cat.images.length;

  // 没开「AI 按语境挑素材」时也别让模型一口咬定"相册是空的"：
  // 至少告诉它素材有多少张（实际发送由 bot.mjs 的「用户要图」兜底完成）
  if (!cfg.mediaSmart) {
    if (!ec && !ic) return '\n\n【素材】你没有任何表情包和图片。用户要图片/照片时，直说发不了，别编。';
    return (
      `\n\n【素材】你有表情包 ${ec} 张、图片 ${ic} 张。用户要图片/照片/表情时，正常回应就行（系统会自动附一张）；` +
      '不要说“我手机坏了”“相册是空的”“没存表情包”这类话，也不要解释发不出去。'
    );
  }
  return buildPrompt(cat);
}

const MARKER_RE = /[ \t]*\[\s*(EMOJI|PIC)\s*[:：]\s*([^\]\n]{1,120}?)\s*\][ \t]*/gi;

/**
 * 摘出 [EMOJI:文件名] / [PIC:文件名] 标记，返回去掉标记后的文本和待发文件
 * @returns {{text:string, emojis:string[], pics:string[]}}
 */
export function takeMediaMarkers(text) {
  const emojis = [];
  const pics = [];
  const clean = String(text || '')
    .replace(MARKER_RE, (_m, kind, name) => {
      const n = path.basename(String(name || '').trim().replace(/\\/g, '/'));
      if (!n) return '';
      (String(kind).toUpperCase() === 'EMOJI' ? emojis : pics).push(n);
      return '';
    })
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return { text: clean, emojis: emojis.slice(0, 2), pics: pics.slice(0, 2) };
}

/**
 * 发模型指定的素材（表情包按 image 类型上传，和随机表情逻辑一致）
 * @returns {Promise<number>} 实际发出去的条数
 */
export async function sendMarkedMedia(kind, id, msgId, marks, seqStart = 1, slots = 4) {
  const { emojis = [], pics = [] } = marks || {};
  let sent = 0;
  const left = Math.max(0, Number(slots) || 0);
  for (const [type, names] of [
    ['EMOJI', emojis],
    ['PIC', pics],
  ]) {
    for (const name of names) {
      if (sent >= left) return sent;
      const ok =
        type === 'EMOJI'
          ? await sendEmojiFile(name, kind, id, msgId, seqStart + sent)
          : await sendImageFile(name, kind, id, msgId, seqStart + sent);
      if (ok) sent += 1;
      else log(`AI 指定的${type === 'EMOJI' ? '表情包' : '图片'}没发出去：${name}`);
    }
  }
  return sent;
}