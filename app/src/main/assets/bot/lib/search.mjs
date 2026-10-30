/**
 * 联网搜索：模型在回复里单独一行写 [SEARCH:查询词]，这里负责真的去搜。
 *
 * 支持 provider（SEARCH_PROVIDER）：
 *   tavily  POST {base|https://api.tavily.com}/search
 *   serper  POST {base|https://google.serper.dev}/search
 *   bing    GET  {base|https://api.bing.microsoft.com/v7.0/search}?q=
 *   bocha   POST {base|https://api.bochaai.com}/v1/web-search
 * 没配 SEARCH_PROVIDER / SEARCH_KEY 时直接当没这功能：标记从文本里摘掉，正常回复。
 */
import { cfg } from './config.mjs';
import { log } from './log.mjs';

const DEFAULT_BASE = {
  tavily: 'https://api.tavily.com',
  serper: 'https://google.serper.dev',
  bing: 'https://api.bing.microsoft.com/v7.0/search',
  bocha: 'https://api.bochaai.com',
};

export const PROVIDERS = Object.keys(DEFAULT_BASE);

/** 开关：provider 合法且填了 key 才算开 */
export function searchEnabled() {
  return PROVIDERS.includes(String(cfg.searchProvider || '').toLowerCase()) && !!cfg.searchKey;
}

const MARKER_RE = /\[\s*SEARCH\s*[:：]\s*([^\]\n]{1,120}?)\s*\]/gi;

/**
 * 抽出 [SEARCH:查询词]（只取第一条），并把所有搜索标记从文本里剔除。
 * 模型偶尔会把标记写在行中间，这里一并处理。
 */
export function takeSearchMarker(text) {
  let query = '';
  const clean = String(text || '')
    .replace(MARKER_RE, (_m, q) => {
      if (!query) query = String(q).trim();
      return '';
    })
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return { query, text: clean };
}

const norm = (item) => {
  const url = String(item?.url || item?.link || '').trim();
  if (!/^https?:\/\//i.test(url)) return null;
  return {
    title: String(item?.title || item?.name || '').trim(),
    snippet: String(item?.snippet || item?.content || item?.description || item?.summary || '').replace(/\s+/g, ' ').trim(),
    url,
  };
};

const take = (arr) => (Array.isArray(arr) ? arr.map(norm).filter(Boolean).slice(0, Math.max(1, cfg.searchMax)) : []);

function baseOf(p) {
  return cfg.searchBase || DEFAULT_BASE[p];
}

async function tavily(query) {
  const r = await fetch(`${baseOf('tavily')}/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ api_key: cfg.searchKey, query, max_results: Math.max(1, cfg.searchMax) }),
    signal: AbortSignal.timeout(15_000),
  });
  if (!r.ok) throw new Error(`tavily HTTP ${r.status}`);
  const d = await r.json();
  return take(d?.results);
}

async function serper(query) {
  const r = await fetch(`${baseOf('serper')}/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-KEY': cfg.searchKey },
    body: JSON.stringify({ q: query }),
    signal: AbortSignal.timeout(15_000),
  });
  if (!r.ok) throw new Error(`serper HTTP ${r.status}`);
  const d = await r.json();
  return take(d?.organic);
}

async function bing(query) {
  const base = baseOf('bing');
  const q = encodeURIComponent(query);
  const r = await fetch(`${base}?q=${q}&count=${Math.max(1, cfg.searchMax)}&mkt=zh-CN`, {
    headers: { 'Ocp-Apim-Subscription-Key': cfg.searchKey },
    signal: AbortSignal.timeout(15_000),
  });
  if (!r.ok) throw new Error(`bing HTTP ${r.status}`);
  const d = await r.json();
  return take(d?.webPages?.value);
}

async function bocha(query) {
  const r = await fetch(`${baseOf('bocha')}/v1/web-search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${cfg.searchKey}` },
    body: JSON.stringify({ query, count: Math.max(1, cfg.searchMax) }),
    signal: AbortSignal.timeout(15_000),
  });
  if (!r.ok) throw new Error(`bocha HTTP ${r.status}`);
  const d = await r.json();
  return take(d?.data?.webPages?.value || d?.webPages?.value);
}

/** 搜一次；失败/没配置都返回 []，不抛错 */
export async function search(query) {
  const q = String(query || '').trim();
  if (!q || !searchEnabled()) return [];
  const p = String(cfg.searchProvider).toLowerCase();
  try {
    const items = await { tavily, serper, bing, bocha }[p](q);
    log(`联网搜索（${p}）：${q} → ${items.length} 条`);
    return items;
  } catch (e) {
    log(`联网搜索失败（${p}）`, String((e && e.message) || e).slice(0, 120));
    return [];
  }
}

/** 把结果整理成给模型看的文本 */
export function formatResults(items) {
  if (!Array.isArray(items) || !items.length) return '';
  return items
    .map((x, i) => `${i + 1}. ${x.title || '(无标题)'}\n${x.snippet || ''}\n${x.url}`.trim())
    .join('\n\n')
    .slice(0, 4000);
}