/**
 * 进程技能：把「要跑外部程序」的技能接进技能系统（Python / shell / 任何 Linux 命令）。
 *
 * 技能目录里放 plugin.json：
 *   {
 *     "name": "示例",
 *     "description": "发「测试」时回一句",
 *     "priority": 0,
 *     "match": ["测试"],            // 关键词数组；或正则字符串（可写 "/^天气/" ）
 *     "cmd": ["python3", "/skills/示例/main.py"],
 *     "timeoutMs": 20000
 *   }
 *
 * 运行在 App 里装的 Linux 环境（proot + Ubuntu rootfs）里：
 *   /skills → 机器人技能目录（只读用它就好，脚本都在里面）
 *   /host   → 运行目录   /dl → 下载目录
 * 输入：往进程 stdin 写一行 JSON  {"text":"对方说的","convKey":"c2c:123","args":{...}}
 * 输出：进程 stdout 最后的结果文本就是回复；stderr 会打到机器人日志里。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { log } from './log.mjs';

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const FILES_DIR = path.dirname(ROOT);
const LINUX = path.join(FILES_DIR, 'linux');
const SKILLS_DIR = path.join(ROOT, 'skills');

const PROOT = path.join(LINUX, 'native', 'proot');
const ROOTFS = path.join(LINUX, 'rootfs');
const NATIVE = path.join(LINUX, 'native');
const RUN = path.join(LINUX, 'run');
const DL = path.join(LINUX, 'downloads');
const L2S = path.join(LINUX, 'l2s');

const MAX_OUT = 64 * 1024;

/** Linux 环境是否装好了（proot + rootfs 都有） */
export function envReady() {
  try {
    return fs.existsSync(PROOT) && fs.existsSync(path.join(ROOTFS, 'usr/bin/env'));
  } catch {
    return false;
  }
}

function prootArgs(cmd) {
  const shm = path.join(ROOTFS, 'tmp');
  try {
    fs.mkdirSync(shm, { recursive: true });
  } catch {}
  return [
    '--kill-on-exit',
    '--link2symlink',
    '-0',
    '-r', ROOTFS,
    '-b', '/dev',
    '-b', '/proc',
    '-b', '/sys',
    '-b', `${shm}:/dev/shm`,
    '-b', `${DL}:/dl`,
    '-b', `${RUN}:/host`,
    '-b', `${SKILLS_DIR}:/skills`,
    '-w', '/root',
    '/usr/bin/env', '-i',
    'HOME=/root',
    'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
    'TERM=dumb',
    'LANG=C.UTF-8',
    ...cmd,
  ];
}

/** 跑一次外部进程技能，返回 stdout 文本 */
export function runProcessSkill(skill, payload) {
  return new Promise((resolve) => {
    if (!envReady()) {
      resolve('这个技能要跑外部程序（Python 等），得先装 Linux 环境：接入 → 运行环境。');
      return;
    }
    const timeout = Math.max(2000, Number(skill.timeoutMs) || 20000);
    let out = '';
    let err = '';
    let done = false;
    let child;
    try {
      child = spawn(PROOT, prootArgs(skill.cmd), {
        cwd: skill.dir,
        env: {
          ...process.env,
          PROOT_TMP_DIR: path.join(ROOTFS, 'tmp'),
          PROOT_LOADER: path.join(NATIVE, 'libexec/proot/loader'),
          PROOT_L2S_DIR: L2S,
          LD_LIBRARY_PATH: path.join(NATIVE, 'lib'),
        },
        stdio: ['pipe', 'pipe', 'pipe'],
      });
    } catch (e) {
      resolve(`技能「${skill.name}」启动失败：${e.message}`);
      return;
    }

    const finish = (text) => {
      if (done) return;
      done = true;
      try {
        child.kill('SIGKILL');
      } catch {}
      if (err.trim()) log(`进程技能 ${skill.name} 输出：${err.trim().slice(-400)}`);
      resolve(text);
    };

    const timer = setTimeout(() => finish(`技能「${skill.name}」执行超时（${timeout}ms）`), timeout);

    child.stdout.on('data', (b) => {
      out += b.toString('utf8');
      if (out.length > MAX_OUT) out = out.slice(-MAX_OUT);
    });
    child.stderr.on('data', (b) => {
      err += b.toString('utf8');
      if (err.length > MAX_OUT) err = err.slice(-MAX_OUT);
    });
    child.on('error', (e) => {
      clearTimeout(timer);
      finish(`技能「${skill.name}」启动失败：${e.message}`);
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      const text = out.trim();
      if (text) return finish(text.slice(-4000));
      if (code === 127) return finish(`技能「${skill.name}」找不到命令：${(skill.cmd || []).join(' ')}`);
      finish(`技能「${skill.name}」没有输出（退出码 ${code}）`);
    });

    try {
      child.stdin.write(JSON.stringify(payload) + '\n');
      child.stdin.end();
    } catch {}
  });
}

function matchOf(manifest) {
  const m = manifest.match;
  if (Array.isArray(m)) {
    const keys = m.map((x) => String(x)).filter(Boolean);
    return ({ text }) => keys.some((k) => text.includes(k));
  }
  if (typeof m === 'string' && m.trim()) {
    const raw = m.trim();
    const body = raw.length > 2 && raw.startsWith('/') && raw.lastIndexOf('/') > 0
      ? raw.slice(1, raw.lastIndexOf('/'))
      : raw;
    let re;
    try {
      re = new RegExp(body, 'i');
    } catch {
      re = null;
    }
    if (re) return ({ text }) => re.test(text);
    return ({ text }) => text.includes(body);
  }
  return null;
}

/** 扫描 skills/ 下带 plugin.json 的技能，返回数组（给 skill.mjs 合并） */
export function loadProcessSkills() {
  const out = [];
  let names = [];
  try {
    names = fs.readdirSync(SKILLS_DIR);
  } catch {
    return out;
  }
  for (const n of names.sort()) {
    if (n.startsWith('.') || n.startsWith('_')) continue;
    const dir = path.join(SKILLS_DIR, n);
    const manifestFile = path.join(dir, 'plugin.json');
    try {
      if (!fs.statSync(dir).isDirectory()) continue;
      if (!fs.existsSync(manifestFile)) continue;
      const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
      const cmd = Array.isArray(manifest.cmd) ? manifest.cmd.map(String) : [];
      if (!cmd.length) {
        log(`进程技能 ${n}：plugin.json 缺 cmd，已跳过`);
        continue;
      }
      const mtime = fs.statSync(manifestFile).mtimeMs;
      const match = matchOf(manifest);
      if (!match) {
        log(`进程技能 ${n}：plugin.json 缺 match，已跳过`);
        continue;
      }
      const name = String(manifest.name || n);
      out.push({
        key: n,
        name,
        desc: String(manifest.description || manifest.desc || ''),
        kind: 'process',
        priority: Number(manifest.priority) || 0,
        mtime,
        cmd,
        timeoutMs: Number(manifest.timeoutMs) || 20000,
        dir,
        match,
        handle: async ({ text, convKey, args }) =>
          runProcessSkill(
            { name, cmd, timeoutMs: Number(manifest.timeoutMs) || 20000, dir },
            { text, convKey, args }
          ),
      });
    } catch (e) {
      log(`进程技能 ${n} 读取失败：${e.message}`);
    }
  }
  return out;
}