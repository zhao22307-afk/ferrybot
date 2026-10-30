/**
 * OneBot v11 接入：连接你自己电脑/服务器上跑的协议端（NapCat / Lagrange）
 *
 * 为什么不在手机里跑协议端：QQ 的协议端需要持续对抗风控，且基本都是 PC 端
 * 注入/签名方案，手机 App 里跑不了。所以「真实 QQ」= 手机 App 只当客户端，
 * 连你那台机器上的 OneBot WebSocket。
 *
 * 只依赖 Node 内置的 WebSocket（Node 22+ 自带），不需要额外装包。
 */
import { cfg } from './config.mjs';
import { log } from './log.mjs';
import { sleep } from './store.mjs';
import { splitForSend } from './qq.mjs';

let sock = null;
let running = false;
let selfId = null;
let echoSeq = 0;
const waiting = new Map(); // echo -> { res, rej, timer }

export const getTokenSync = () => '';
export async function getToken() {
  if (!cfg.onebotUrl) throw new Error('没有填 OneBot 地址（设置 → 接入方式 → 真实QQ）');
  return '';
}
export async function api() {
  throw new Error('「真实QQ」接入方式下不能用官方接口');
}

/** 拼 WebSocket 地址，token 走 query（NapCat 的 access_token 支持这种写法） */
function wsUrl() {
  const raw = String(cfg.onebotUrl || '').trim();
  if (!raw) return '';
  const withScheme = /^wss?:\/\//i.test(raw) ? raw : `ws://${raw}`;
  try {
    const u = new URL(withScheme);
    if (cfg.onebotToken && !u.searchParams.has('access_token')) u.searchParams.set('access_token', cfg.onebotToken);
    return u.toString();
  } catch {
    return '';
  }
}

const maskUrl = (u) => u.replace(/access_token=[^&]+/g, 'access_token=***');

/**
 * Node 的 WebSocket 实现有几个版本：undici（标准 addEventListener）、
 * 以及某些精简构建（只有 EventEmitter 风格的 on / onmessage）。
 * 真机上出现过 `ws.addEventListener is not a function`，所以这里全兼容。
 */
function attach(ws, type, fn) {
  if (typeof ws.addEventListener === 'function') ws.addEventListener(type, fn);
  else if (typeof ws.on === 'function') ws.on(type, fn);
  else ws['on' + type] = fn;
}

/** EventEmitter 风格的事件处理函数直接收到数据本身，不是 event 对象 */
const dataOf = (ev) => (ev && typeof ev === 'object' && 'data' in ev ? ev.data : ev);

/** 调一个 OneBot 接口，等它的返回（带 echo 匹配 + 超时） */
function call(action, params = {}, timeout = 20_000) {
  if (!sock || sock.readyState !== 1) return Promise.reject(new Error('OneBot 还没连上'));
  const echo = `e${++echoSeq}`;
  return new Promise((res, rej) => {
    const timer = setTimeout(() => {
      waiting.delete(echo);
      rej(new Error(`OneBot ${action} 超时`));
    }, timeout);
    waiting.set(echo, { res, rej, timer });
    try {
      sock.send(JSON.stringify({ action, params, echo }));
    } catch (e) {
      clearTimeout(timer);
      waiting.delete(echo);
      rej(e);
    }
  });
}

// ---------------------------------------------------------------- 发消息

const target = (kind, id) =>
  kind === 'groups' ? { message_type: 'group', group_id: Number(id) } : { message_type: 'private', user_id: Number(id) };

async function sendSegments(kind, id, segs) {
  const data = await call('send_msg', { ...target(kind, id), message: segs });
  const mid = data?.message_id;
  maybeRecall(mid);
  return data;
}

function maybeRecall(messageId) {
  const sec = Number(cfg.recallSeconds) || 0;
  if (sec <= 0 || !messageId) return;
  const t = setTimeout(() => {
    recallMessage('', '', messageId).catch(() => {});
  }, sec * 1000);
  if (typeof t.unref === 'function') t.unref();
}

export async function recallMessage(_kind, _id, messageId) {
  try {
    await call('delete_msg', { message_id: Number(messageId) }, 8000);
    log(`已撤回消息 ${messageId}`);
    return true;
  } catch (e) {
    log('撤回失败', e.message);
    return false;
  }
}

export async function sendText(kind, id, text, _msgId, _seqStart = 1, maxChunks = 4) {
  const chunks = splitForSend(text, maxChunks);
  for (let i = 0; i < chunks.length; i++) {
    await sendSegments(kind, id, [{ type: 'text', data: { text: chunks[i] } }]);
    if (i < chunks.length - 1) await sleep(500 + Math.random() * 800);
  }
  return chunks;
}

/** 原样发一整条（不拆句）：给「标题 + 换行 + URL」这类必须一条发的内容用 */
export async function sendTextRaw(kind, id, text, _msgId, _seq = 1) {
  await sendSegments(kind, id, [{ type: 'text', data: { text: String(text ?? '') } }]);
}

export async function sendList(kind, id, list, _msgId, reserve = 0) {
  const limit = Math.max(1, 4 - reserve);
  const items = list.slice(0, limit);
  for (let i = 0; i < items.length; i++) {
    await sendSegments(kind, id, [{ type: 'text', data: { text: items[i] } }]);
    if (i < items.length - 1) await sleep(500 + Math.random() * 800);
  }
  return items.length;
}

/** 公网 URL 直接交给协议端去拉，不用上传 */
export async function uploadMedia(_kind, _id, url, fileType = 3) {
  if (!url) return null;
  return { onebot: true, fileType, file: url };
}

/** 本地文件转 base64://（协议端在你电脑上，读不到手机里的文件） */
export async function uploadLocalFile(_kind, _id, buf, fileName, fileType = 1) {
  if (!buf?.length) return null;
  return { onebot: true, fileType, file: `base64://${Buffer.from(buf).toString('base64')}`, name: fileName };
}

export async function sendMedia(kind, id, fileInfo, _msgId, _seq) {
  if (!fileInfo?.file) return;
  const type = Number(fileInfo.fileType) === 3 ? 'record' : Number(fileInfo.fileType) === 1 ? 'image' : 'file';
  const data = type === 'file' ? { file: fileInfo.file, name: fileInfo.name || 'file' } : { file: fileInfo.file };
  await sendSegments(kind, id, [{ type, data }]);
}

/** 主动消息：单聊直接发 */
export async function proactiveText(openid, text) {
  await sendText('users', openid, text, null);
}

// ---------------------------------------------------------------- 收消息

/** 把 OneBot 的 message 段数组拆成「文本 / 图片 / 语音 / 是否@我」 */
function parseMessage(msg, self) {
  const segs = Array.isArray(msg) ? msg : [];
  const texts = [];
  const images = [];
  let audio = null;
  let atSelf = false;
  for (const s of segs) {
    const t = s?.type;
    const d = s?.data || {};
    if (t === 'text') texts.push(String(d.text || ''));
    else if (t === 'image') {
      const u = d.url || (typeof d.file === 'string' && /^https?:/i.test(d.file) ? d.file : '');
      if (u) images.push(u);
    } else if (t === 'record') audio = d.url || d.file || null;
    else if (t === 'at') {
      if (String(d.qq) === String(self)) atSelf = true;
    }
  }
  let text = texts.join('').trim();
  if (!text && audio) text = '';
  return { text, images: images.slice(0, 3), audio, atSelf };
}

function onEvent(data, handlers) {
  // 接口返回
  if (data.echo && waiting.has(data.echo)) {
    const w = waiting.get(data.echo);
    clearTimeout(w.timer);
    waiting.delete(data.echo);
    if (data.status === 'failed' || (typeof data.retcode === 'number' && data.retcode !== 0 && data.retcode !== 1)) {
      w.rej(new Error(data.wording || data.message || `OneBot 返回 ${data.retcode}`));
    } else {
      w.res(data.data || {});
    }
    return;
  }

  if (data.self_id) selfId = data.self_id;
  if (data.post_type !== 'message') return;

  const p = parseMessage(data.message, data.self_id || selfId);
  // 昵称：私聊/群名片优先，拿不到就空串（联系人导出用）
  const senderName = String(data.sender?.card || data.sender?.nickname || '').trim();
  const base = {
    userId: data.user_id,
    messageId: data.message_id,
    text: p.text,
    images: p.images,
    audio: p.audio,
    name: senderName,
  };
  const msgId = String(data.message_id ?? '');

  if (data.message_type === 'private') {
    handlers.onPrivate?.({ ...base, msgId });
  } else if (data.message_type === 'group') {
    if (!p.atSelf && !cfg.onebotGroupAll) return; // 群里默认只回 @
    handlers.onGroup?.({
      ...base,
      msgId,
      groupId: data.group_id,
      atSelf: p.atSelf,
      groupName: String(data.group_name || '').trim(),
    });
  }
}

function once(handlers) {
  const url = wsUrl();
  if (!url) throw new Error('没有填 OneBot 地址（设置 → 接入方式 → 真实QQ）');
  log(`连接 OneBot ${maskUrl(url)}`);

  return new Promise((resolve) => {
    let done = false;
    let opened = false;
    const finish = () => {
      if (!done) {
        done = true;
        resolve();
      }
    };

    if (typeof WebSocket !== 'function') throw new Error('当前 Node 没有内置 WebSocket（需要 Node 22 及以上）');
    const ws = new WebSocket(url);
    sock = ws;

    attach(ws, 'open', () => {
      opened = true;
      log('OneBot 已连接');
    });
    attach(ws, 'message', (ev) => {
      try {
        const data = dataOf(ev);
        const raw = typeof data === 'string' ? data : data.toString();
        onEvent(JSON.parse(raw), handlers);
      } catch (e) {
        log('OneBot 消息解析失败', e.message);
      }
    });
    attach(ws, 'close', (ev) => {
      if (opened) log(`OneBot 连接关闭 code=${ev?.code ?? 1006}`);
      sock = null;
      for (const [k, w] of waiting) {
        clearTimeout(w.timer);
        w.rej(new Error('连接已断开'));
        waiting.delete(k);
      }
      finish();
    });
    attach(ws, 'error', () => log('OneBot 连接错误'));
  });
}

/** 主循环：断了就重连。刚启动时加快重试（协议端可能还在起），稳定后逐步退避到 30s */
export async function start(handlers = {}) {
  running = true;
  let retry = 0;
  const waits = [2, 3, 5, 8, 12, 20, 30];
  while (running) {
    let err = null;
    try {
      await once(handlers);
    } catch (e) {
      err = e;
    }
    if (!running) break;
    if (!err) {
      // 正常断开（比如协议端重启）：重连计数归零，等下按最短间隔重连
      retry = 0;
      await sleep(1000);
      continue;
    }
    const msg = err.message || String(err);
    const notReady = /ECONNREFUSED|EHOSTUNREACH|ENETUNREACH|connect|连接被拒绝/i.test(msg);
    log(notReady ? `OneBot 连接失败：协议端还没就绪（${msg}）` : `OneBot 连接失败：${msg}`);
    const sec = waits[Math.min(retry, waits.length - 1)];
    retry = Math.min(retry + 1, waits.length - 1);
    log(`OneBot ${sec}s 后重连（第 ${retry} 次）`);
    await sleep(sec * 1000);
  }
}

export function stop() {
  running = false;
  try {
    sock?.close(1000, 'shutdown');
  } catch {}
  sock = null;
}