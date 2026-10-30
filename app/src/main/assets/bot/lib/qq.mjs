import crypto from 'node:crypto';
/**
 * QQ 机器人 API：令牌、发消息、富媒体上传
 */
import { cfg } from './config.mjs';
import { log } from './log.mjs';
import { sleep } from './store.mjs';

const BASE = 'https://api.bot.qq.com';
const MAX_CHUNKS = 4; // QQ 被动回复上限

let token = { value: '', expiresAt: 0 };

export async function getToken(force = false) {
  if (!force && token.value && Date.now() < token.expiresAt - 60_000) return token.value;
  const res = await fetch(`${BASE}/app/getAppAccessToken`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ appId: cfg.appId, clientSecret: cfg.appSecret }),
    signal: AbortSignal.timeout(15_000),
  });
  const data = await res.json().catch(() => ({}));
  if (!data.access_token) throw new Error(`换取 access_token 失败: ${data.code} ${data.message || ''}`);
  token = { value: data.access_token, expiresAt: Date.now() + Number(data.expires_in || 7200) * 1000 };
  log(`access_token 已获取（${data.expires_in}s）`);
  return token.value;
}

export const getTokenSync = () => token.value;

export async function api(method, path, body, retried = false) {
  const t = await getToken();
  const res = await fetch(BASE + path, {
    method,
    headers: { Authorization: `QQBot ${t}`, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(20_000),
  });
  if (res.status === 401 && !retried) {
    await getToken(true);
    return api(method, path, body, true);
  }
  const text = await res.text();
  let data = null;
  try {
    data = JSON.parse(text);
  } catch {}
  if (!res.ok) throw new Error(`${path} → HTTP ${res.status} ${text.slice(0, 200)}`);
  return data;
}

// ---- 文本切分：先按行，再按长度，最多 4 条 ----
function cutByLength(text, size = 800) {
  if (text.length <= size) return [text];
  const out = [];
  let rest = text;
  while (rest.length > size) {
    let cut = rest.lastIndexOf('\n', size);
    if (cut < size * 0.5) cut = rest.lastIndexOf('。', size);
    if (cut < size * 0.5) cut = size;
    out.push(rest.slice(0, cut + 1).trim());
    rest = rest.slice(cut + 1).trim();
  }
  if (rest) out.push(rest);
  return out;
}

export function splitForSend(text, max = MAX_CHUNKS) {
  const paras = String(text)
    .split(/\n+/)
    .map((s) => s.trim())
    .filter(Boolean);
  const out = [];
  for (const p of paras.length ? paras : [text]) out.push(...cutByLength(p));
  if (!out.length) return [String(text)];
  if (out.length > max) {
    const head = out.slice(0, max - 1);
    head.push(out.slice(max - 1).join(' ')); // 合并时用空格，不换行
    return head;
  }
  return out;
}

/** 按给定数组逐条发送（不做任何切分），返回实际发送条数 */
export async function sendList(kind, id, list, msgId, reserve = 0, seqStart = 1) {
  const limit = Math.max(1, 4 - reserve); // QQ 被动回复上限 4，reserve 给语音留位
  const items = list.slice(0, limit);
  let seq = Math.max(0, Number(seqStart) || 1) - 1;
  for (const c of items) {
    seq += 1;
    const body = { content: c, msg_type: 0, msg_id: msgId };
    body.msg_seq = seq;
    const data = await api('POST', `/v2/${kind}/${id}/messages`, body);
    maybeRecall(kind, id, data);
    if (seq < items.length) await sleep(500 + Math.random() * 800);
  }
  return items.length;
}

/** 发文本（自动分片、自动递增 msg_seq） */
export async function sendText(kind, id, text, msgId, seqStart = 1, maxChunks = MAX_CHUNKS) {
  const chunks = splitForSend(text, maxChunks);
  let seq = seqStart - 1;
  for (const c of chunks) {
    seq += 1;
    const body = { content: c, msg_type: 0, msg_id: msgId };
    body.msg_seq = seq;
    const data = await api('POST', `/v2/${kind}/${id}/messages`, body);
    maybeRecall(kind, id, data);
    if (seq < chunks.length) await sleep(500 + Math.random() * 800);
  }
  return chunks;
}

/** 原样发一整条（不拆句）：给「标题 + 换行 + URL」这类必须一条发的内容用 */
export async function sendTextRaw(kind, id, text, msgId, seq = 1) {
  const body = { content: String(text ?? ''), msg_type: 0, msg_id: msgId };
  body.msg_seq = Math.max(1, Number(seq) || 1);
  const data = await api('POST', `/v2/${kind}/${id}/messages`, body);
  maybeRecall(kind, id, data);
}

/** 主动消息：单聊直接发（官方接口不带 msg_id） */
export async function proactiveText(openid, text) {
  const data = await api('POST', `/v2/users/${openid}/messages`, { content: text, msg_type: 0 });
  maybeRecall('users', openid, data);
}

/** 撤回一条消息（自己发的。QQ 限制：一般 2 分钟内可撤） */
export async function recallMessage(kind, id, messageId) {
  try {
    await api('DELETE', `/v2/${kind}/${id}/messages/${messageId}`);
    log(`已撤回消息 ${messageId}`);
    return true;
  } catch (e) {
    log('撤回失败', e.message);
    return false;
  }
}

/** RECALL_SECONDS > 0 时，发完定时撤回 */
function maybeRecall(kind, id, data) {
  const sec = Number(cfg.recallSeconds) || 0;
  if (sec <= 0 || !data?.id) return;
  const t = setTimeout(() => {
    recallMessage(kind, id, data.id).catch(() => {});
  }, sec * 1000);
  if (typeof t.unref === 'function') t.unref();
}

/** 上传公网音频，拿到 file_info */
export async function uploadMedia(kind, id, url, fileType = 3) {
  const up = await api('POST', `/v2/${kind}/${id}/files`, { file_type: fileType, url });
  return up?.file_info || null;
}

/** 本地文件分片上传，返回 file_info（图片/语音都可用） */
export async function uploadLocalFile(kind, id, buf, fileName, fileType = 1) {
  try {
    const md5 = crypto.createHash('md5').update(buf).digest('hex');
    const sha1 = crypto.createHash('sha1').update(buf).digest('hex');
    const md5_10m = crypto.createHash('md5').update(buf.subarray(0, 10002432)).digest('hex');

    const prep = await api('POST', `/v2/${kind}/${id}/upload_prepare`, {
      file_type: fileType,
      file_size: String(buf.length),
      file_name: fileName,
      md5,
      sha1,
      md5_10m,
    });
    const uploadId = prep?.upload_id;
    const parts = prep?.parts || [];
    if (!uploadId || !parts.length) {
      log('上传预检失败', JSON.stringify(prep).slice(0, 150));
      return null;
    }

    let offset = 0;
    for (const part of parts) {
      const size = Number(part.block_size || buf.length);
      const chunk = buf.subarray(offset, offset + size);
      const put = await fetch(part.presigned_url, { method: 'PUT', body: chunk });
      if (!put.ok) {
        log('分片上传失败', put.status);
        return null;
      }
      await api('POST', `/v2/${kind}/${id}/upload_part_finish`, { upload_id: uploadId, part_index: part.index });
      offset += size;
    }

    const done = await api('POST', `/v2/${kind}/${id}/files`, { upload_id: uploadId });
    return done?.file_info || null;
  } catch (e) {
    log('本地文件上传失败', e.message);
    return null;
  }
}

/** 发富媒体（语音/图片） */
export async function sendMedia(kind, id, fileInfo, msgId, seq) {
  const body = { msg_type: 7, media: { file_info: fileInfo }, msg_id: msgId };
  body.msg_seq = seq;
  const data = await api('POST', `/v2/${kind}/${id}/messages`, body);
  maybeRecall(kind, id, data);
}
