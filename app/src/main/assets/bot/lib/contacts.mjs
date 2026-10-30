/**
 * 联系人：把最近私聊/群聊过的人记下来，供 App 端「从最近聊过的人里选」。
 *
 * 文件：data/contacts.json —— 对象数组，按 last 倒序，最多 500 条：
 *   [{ "id": "openid 或 QQ 群号", "name": "昵称（拿不到就是空串）", "kind": "private" | "group", "last": 毫秒时间戳 }]
 * 原子写：先写 .tmp 再 rename，App 读的时候不会读到半截 JSON。
 */
import fs from 'node:fs';
import path from 'node:path';
import { DATA_DIR } from './config.mjs';
import { log } from './log.mjs';

export const CONTACTS_FILE = path.join(DATA_DIR, 'contacts.json');
const MAX_CONTACTS = 500;

let list = null;
let timer = null;

function normalize(raw) {
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((x) => x && x.id !== undefined && x.id !== null && String(x.id).trim())
    .map((x) => ({
      id: String(x.id).trim(),
      name: typeof x.name === 'string' ? x.name : '',
      kind: x.kind === 'group' ? 'group' : 'private',
      last: Number(x.last) || 0,
    }))
    .sort((a, b) => b.last - a.last)
    .slice(0, MAX_CONTACTS);
}

/** 读盘（只读一次，之后走内存） */
export function loadContacts() {
  if (list) return list;
  try {
    list = normalize(JSON.parse(fs.readFileSync(CONTACTS_FILE, 'utf8')));
  } catch {
    list = [];
  }
  return list;
}

/** 给外部用的快照（按 last 倒序） */
export function listContacts() {
  return loadContacts()
    .slice()
    .sort((a, b) => b.last - a.last);
}

function scheduleSave() {
  if (timer) return;
  timer = setTimeout(() => {
    timer = null;
    saveContactsNow();
  }, 800);
  if (typeof timer.unref === 'function') timer.unref();
}

/** 立即原子落盘 */
export function saveContactsNow() {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  try {
    const arr = listContacts();
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const tmp = `${CONTACTS_FILE}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(arr, null, 2));
    fs.renameSync(tmp, CONTACTS_FILE);
  } catch (e) {
    log('保存联系人失败', (e && e.message) || String(e));
  }
}

/**
 * 记一个联系人（同一个 id+kind 只留一条，更新 last；昵称拿不到时保留旧的）
 * @param {{id:string|number, name?:string, kind?:'private'|'group', at?:number}} o
 */
export function touchContact({ id, name = '', kind = 'private', at = Date.now() } = {}) {
  const key = String(id ?? '').trim();
  if (!key) return false;
  const k = kind === 'group' ? 'group' : 'private';
  const arr = loadContacts();
  const idx = arr.findIndex((x) => x.id === key && x.kind === k);
  let item = { id: key, name: String(name || '').trim(), kind: k, last: Number(at) || Date.now() };
  if (idx >= 0) {
    const old = arr[idx];
    item.name = item.name || old.name || '';
    item.last = Math.max(item.last, Number(old.last) || 0);
    arr.splice(idx, 1);
  }
  arr.push(item);
  arr.sort((a, b) => b.last - a.last);
  if (arr.length > MAX_CONTACTS) arr.length = MAX_CONTACTS;
  scheduleSave();
  return true;
}

export { MAX_CONTACTS };