/**
 * JSON 持久化：一个 Map + 防抖落盘
 */
import fs from 'node:fs';
import path from 'node:path';

export class JsonMap extends Map {
  /**
   * @param {string} file 存储路径
   * @param {{debounce?: number, maxSize?: number, pick?: (v:any)=>any}} opts
   */
  constructor(file, opts = {}) {
    super();
    this.file = file;
    this.debounce = opts.debounce ?? 500;
    this.maxSize = opts.maxSize ?? 0;
    this._timer = null;
  }

  load() {
    try {
      const raw = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      for (const [k, v] of Object.entries(raw)) if (v) this.set(k, v);
    } catch {}
    return this;
  }

  save() {
    clearTimeout(this._timer);
    this._timer = setTimeout(() => this.saveNow(), this.debounce);
  }

  saveNow() {
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      // 超出上限时淘汰最早的（Map 保持插入顺序）
      if (this.maxSize > 0) {
        while (this.size > this.maxSize) this.delete(this.keys().next().value);
      }
      const tmp = this.file + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(Object.fromEntries(this)));
      fs.renameSync(tmp, this.file);
    } catch {}
  }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
