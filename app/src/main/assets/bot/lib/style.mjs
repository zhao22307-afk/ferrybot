/**
 * 说话方式语料：用户在「对话 → 人设 → 说话方式（语料）」里导入的
 * 聊天记录 / 句子，存在 data/style.json。
 *
 * 这里只做一件事：把它压成一小段 system 提示，让模型学语气、用词、
 * 句子长短和标点；不让它照抄内容，也不让它提这份参考。
 *
 * 注意两点：
 *  - 每次请求都会带上这段，所以有硬预算（STYLE_BUDGET 字），越长约贵；
 *  - system 前缀要尽量稳定才能命中平台缓存，所以只在文件内容变化时才重读。
 */
import fs from 'node:fs';
import path from 'node:path';
import { DATA_DIR } from './config.mjs';

const STYLE_FILE = path.join(DATA_DIR, 'style.json');
/** 整段提示（含标题行）的字符上限；超了就少放几条 */
const STYLE_BUDGET = 1200;
const MAX_PAIRS = 8;
const MAX_LINES = 20;

// 文件没变就不重复解析：data/style.json 只在导入/清空时改一次
let cachedStamp = '';
let cachedData = null;

function loadStyle() {
  try {
    const st = fs.statSync(STYLE_FILE);
    const stamp = `${st.mtimeMs}:${st.size}`;
    if (stamp === cachedStamp) return cachedData;
    const data = JSON.parse(fs.readFileSync(STYLE_FILE, 'utf8'));
    cachedStamp = stamp;
    cachedData = data;
    return data;
  } catch {
    cachedStamp = '';
    cachedData = null;
    return null;
  }
}

/** 压成一行、去掉换行/多余空白，并按需截断 */
function clean(text, max = 90) {
  return String(text ?? '').replace(/\s+/g, ' ').trim().slice(0, max);
}

/**
 * 返回要拼进 system prompt 的片段（前面带空行），没有语料时返回空串。
 * 调用方直接 sys += buildStyleBlock() 即可。
 */
export function buildStyleBlock() {
  const data = loadStyle();
  if (!data || !Array.isArray(data.samples) || data.samples.length === 0) return '';

  const head =
    '【说话方式参考】下面是同类聊天里的真实说法。学它的语气、用词、句子长短和标点，' +
    '让回复听起来像同一个人；不要提这份参考，也不要照抄原话的内容。';
  const body = [];
  let budget = STYLE_BUDGET - head.length;

  if (data.mode === 'dialog') {
    for (const item of data.samples) {
      if (body.length >= MAX_PAIRS) break;
      const a = clean(item?.a);
      if (!a) continue;
      const u = clean(item?.u);
      const line = u ? `对方：${u}\n你：${a}` : `你：${a}`;
      if (line.length > budget) break;
      body.push(line);
      budget -= line.length + 1;
    }
  } else {
    for (const item of data.samples) {
      if (body.length >= MAX_LINES) break;
      const line = `- ${clean(typeof item === 'string' ? item : item?.a || item?.text)}`;
      if (line.length > budget) break;
      body.push(line);
      budget -= line.length + 1;
    }
  }

  if (body.length === 0) return '';
  return '\n\n' + head + '\n' + body.join('\n');
}