/**
 * 日志：写文件 + 前台打印，超过 5MB 自动截断
 */
import fs from 'node:fs';
import path from 'node:path';
import { LOG_FILE } from './config.mjs';

fs.mkdirSync(path.dirname(LOG_FILE), { recursive: true });

export function rotateLog(maxBytes = 5 * 1024 * 1024, keepLines = 500) {
  try {
    if (fs.statSync(LOG_FILE).size <= maxBytes) return;
    const tail = fs.readFileSync(LOG_FILE, 'utf8').split('\n').slice(-keepLines).join('\n');
    fs.writeFileSync(LOG_FILE, tail);
  } catch {}
}

export function log(...args) {
  const line = `[${new Date().toLocaleString('zh-CN', { hour12: false })}] ${args
    .map((a) => (typeof a === 'string' ? a : JSON.stringify(a)))
    .join(' ')}`;
  try {
    fs.appendFileSync(LOG_FILE, line + '\n');
  } catch {}
  // 一律打到 stdout：App 用管道接住写进 filesDir/logs/bot.log，界面才看得到（
  // 原来的 isTTY 判断在被 App 拉起时永远是 false，等于日志全被吞掉）
  try {
    console.log(line);
  } catch {}
}
