/**
 * 表情包 / 图片：把图片放进 emoji/ 或 image/ 目录就能用
 * 发送时随机挑一张，走分片上传（本地图没有公网 URL）
 *
 *   emoji/ —— 表情包（小图，随机配在回复后面）
 *   image/ —— 图片（大图/照片，随机发）
 */
import fs from 'node:fs';
import path from 'node:path';
import { EMOJI_DIR, IMAGE_DIR } from './config.mjs';
import { log } from './log.mjs';
import { uploadLocalFile, sendMedia } from './transport.mjs';

const EXTS = /\.(png|jpe?g|gif|webp)$/i;
const MAX_BYTES = 8 * 1024 * 1024; // QQ 富媒体上限

function listDir(dir) {
  try {
    return fs
      .readdirSync(dir)
      .filter((f) => EXTS.test(f))
      .map((f) => path.join(dir, f));
  } catch {
    return [];
  }
}

export const listEmojis = () => listDir(EMOJI_DIR);
export const listImages = () => listDir(IMAGE_DIR);
export const emojiCount = () => listEmojis().length;
export const imageCount = () => listImages().length;

/** 按文件名找文件（只认 basename，防止 `../` 之类跑出目录） */
function findByName(name, files) {
  const want = path.basename(String(name || '').trim().replace(/\\/g, '/'));
  if (!want) return '';
  const lower = want.toLowerCase();
  return files.find((f) => path.basename(f) === want) || files.find((f) => path.basename(f).toLowerCase() === lower) || '';
}

export const findEmoji = (name) => findByName(name, listEmojis());
export const findImage = (name) => findByName(name, listImages());

/** 发指定的一张图（表情包/图片共用），成功返回 true */
export async function sendOneFile(file, kind, id, msgId, seq = 1, label = '图片') {
  if (!file) return false;
  try {
    const buf = fs.readFileSync(file);
    if (!buf.length || buf.length > MAX_BYTES) {
      log(`${label}太大或为空，跳过：${path.basename(file)}`);
      return false;
    }

    const fileInfo = await uploadLocalFile(kind, id, buf, path.basename(file), 1);
    if (!fileInfo) return false;

    await sendMedia(kind, id, fileInfo, msgId, seq);
    log(`${label}已发：${path.basename(file)}`);
    return true;
  } catch (e) {
    log(`${label}发送失败`, e.message);
    return false;
  }
}

/** 随机发一张图（表情包或图片共用），成功返回 true */
async function sendRandomFrom(files, kind, id, msgId, seq, label) {
  if (!files.length) return false;
  return sendOneFile(files[Math.floor(Math.random() * files.length)], kind, id, msgId, seq, label);
}

export const sendRandomEmoji = (kind, id, msgId, seq = 1) =>
  sendRandomFrom(listEmojis(), kind, id, msgId, seq, '表情包');

export const sendRandomImage = (kind, id, msgId, seq = 1) =>
  sendRandomFrom(listImages(), kind, id, msgId, seq, '图片');

/** 按文件名发表情包（AI 用 [EMOJI:文件名] 指定时走这里） */
export const sendEmojiFile = (name, kind, id, msgId, seq = 1) =>
  sendOneFile(findEmoji(name), kind, id, msgId, seq, '表情包');

/** 按文件名发图片（AI 用 [PIC:文件名] 指定时走这里） */
export const sendImageFile = (name, kind, id, msgId, seq = 1) =>
  sendOneFile(findImage(name), kind, id, msgId, seq, '图片');