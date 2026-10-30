/**
 * 技能（插件）系统：把 .mjs 放进 skills/ 目录，收到消息时优先匹配。
 *
 * 一个技能就是一个 ES 模块，默认导出：
 *   export default {
 *     name: '掷骰子',
 *     description: '发「掷骰子」时随机回 1~6',
 *     priority: 10,                    // 越大越先匹配，默认 0
 *     match({ text, convKey }) {},     // 返回 true / 有值对象 表示命中；对象会作为 args 传给 handle
 *     async handle({ text, convKey, args }) { return '要回复的文字' }
 *   }
 *
 * 也支持目录形式：skills/我的技能/index.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { log } from './log.mjs';
import { loadProcessSkills } from './procskill.mjs';

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
export const SKILLS_DIR = path.join(ROOT, 'skills');

const ENTRY_NAMES = ['index.mjs', 'index.js', 'main.mjs', 'main.js'];
const loaded = new Map(); // key -> { name, desc, match, handle, priority, mtime }
let lastScan = 0;

function discover() {
  const out = [];
  let names = [];
  try {
    names = fs.readdirSync(SKILLS_DIR);
  } catch {
    return out;
  }
  for (const n of names.sort()) {
    if (n.startsWith('.') || n.startsWith('_')) continue; // . 开头是临时文件，_ 开头表示停用
    const full = path.join(SKILLS_DIR, n);
    try {
      const st = fs.statSync(full);
      if (st.isDirectory()) {
        for (const c of ENTRY_NAMES) {
          const p = path.join(full, c);
          if (fs.existsSync(p)) {
            out.push({ key: n, entry: p });
            break;
          }
        }
      } else if (/\.m?js$/i.test(n)) {
        out.push({ key: n, entry: full });
      }
    } catch {}
  }
  return out;
}

/** 扫描并加载/重载技能，返回当前技能列表 */
export async function loadSkills(force = false) {
  try {
    fs.mkdirSync(SKILLS_DIR, { recursive: true });
  } catch {}

  const found = discover();
  const alive = new Set(found.map((f) => f.key));

  for (const it of found) {
    let mtime = 0;
    try {
      mtime = fs.statSync(it.entry).mtimeMs;
    } catch {}
    const prev = loaded.get(it.key);
    if (prev && !force && prev.mtime === mtime) continue;

    try {
      // 加时间戳参数，避免改名/改内容后命中 import 缓存
      const url = `${pathToFileURL(it.entry).href}?v=${Math.round(mtime)}`;
      const mod = await import(url);
      const def = mod?.default || mod?.skill;
      if (!def || typeof def.match !== 'function' || typeof def.handle !== 'function') {
        log(`技能 ${it.key}：缺少 match/handle，已跳过`);
        loaded.delete(it.key);
        continue;
      }
      loaded.set(it.key, {
        name: String(def.name || it.key.replace(/\.m?js$/i, '')),
        desc: String(def.description || def.desc || ''),
        match: def.match,
        handle: def.handle,
        priority: Number(def.priority) || 0,
        mtime,
      });
      log(`技能已加载：${def.name || it.key}`);
    } catch (e) {
      log(`技能 ${it.key} 加载失败：${e.message}`);
      loaded.delete(it.key);
    }
  }

  for (const k of [...loaded.keys()]) if (!alive.has(k)) loaded.delete(k);

  // 进程技能：目录里有 plugin.json 的（跑 Python / shell 等外部程序）
  const jsKeys = new Set(found.map((f) => f.key));
  for (const ps of loadProcessSkills()) {
    if (jsKeys.has(ps.key)) continue; // 同名 JS 技能优先
    loaded.set(ps.key, ps);
  }
  for (const k of [...loaded.keys()]) {
    const it = loaded.get(k);
    if (it?.kind === 'process' && !fs.existsSync(path.join(SKILLS_DIR, k))) loaded.delete(k);
  }

  return listSkills();
}

/** 当前已加载的技能（给外部看，比如 /skills 命令） */
export function listSkills() {
  return [...loaded.values()]
    .map((s) => ({ name: s.name, desc: s.desc, priority: s.priority, kind: s.kind || 'js' }))
    .sort((a, b) => b.priority - a.priority);
}

/**
 * 匹配并执行技能。
 * 每分钟最多重新扫一次目录（这样导入/删掉技能不用重启）。
 * 命中返回回复文字，未命中返回 null。
 */
export async function matchSkill(convKey, text) {
  const now = Date.now();
  if (now - lastScan > 60_000) {
    lastScan = now;
    loadSkills().catch(() => {});
  }

  const t = String(text || '').trim();
  if (!t || !loaded.size) return null;

  const arr = [...loaded.values()].sort((a, b) => b.priority - a.priority);
  for (const s of arr) {
    let hit = false;
    try {
      hit = !!s.match({ text: t, convKey });
    } catch (e) {
      log(`技能 ${s.name} 匹配出错`, e.message);
      continue;
    }
    if (!hit) continue;

    try {
      const args = typeof hit === 'object' ? hit : null;
      const r = await s.handle({ text: t, convKey, args });
      const out = Array.isArray(r) ? r.join('\n') : typeof r === 'string' ? r : r?.text;
      if (out && String(out).trim()) {
        log(`技能命中：${s.name}`);
        return String(out).trim();
      }
    } catch (e) {
      log(`技能 ${s.name} 执行失败`, e.message);
      return `技能「${s.name}」出错了：${String(e.message).slice(0, 80)}`;
    }
  }
  return null;
}