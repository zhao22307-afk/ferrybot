/**
 * 配置：从 .env 读取，集中在这里
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

// ---- 读取 .env ----
function loadEnv() {
  const file = path.join(ROOT, '.env');
  if (!fs.existsSync(file)) return;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq < 1) continue;
    const key = line.slice(0, eq).trim();
    let val = line.slice(eq + 1).trim();
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
      val = val.slice(1, -1);
    }
    if (process.env[key] === undefined) process.env[key] = val;
  }
}
loadEnv();

const env = (k, d = '') => process.env[k] ?? d;
const num = (k, d) => {
  const v = Number(env(k, String(d)));
  return Number.isFinite(v) ? v : d; // 注意 0 是合法值
};
const flag = (k, d = true) => {
  const v = env(k, d ? '1' : '0').toLowerCase();
  return ['1', 'true', 'yes', 'on'].includes(v);
};

// ---- 目录：先定义，cfg 里拼默认路径要用 ----
export const DATA_DIR = path.join(ROOT, 'data');
export const EMOJI_DIR = path.join(ROOT, 'emoji');
export const IMAGE_DIR = path.join(ROOT, 'image');

// MEDIA_TAGS_FILE 可以写相对名（放 data 目录）或绝对路径
const dataFile = (name) => (path.isAbsolute(name) ? name : path.join(DATA_DIR, name));

// 语音/识别的服务商默认值：选了哪家就用哪家的地址/模型/音色默认，
// 和「模型」里填的聊天网关互不影响（可以聊天走中转、语音走官方）。
// TTS_PROVIDER / ASR_PROVIDER 为空时按 OpenAI 兼容处理，方便接任意同类服务。
const TTS_PROVIDER = env('TTS_PROVIDER', 'dashscope') || 'dashscope';
const TTS_DEFAULTS = {
  dashscope: { base: 'https://dashscope.aliyuncs.com/api/v1', model: 'qwen3-tts-flash', voice: 'Cherry' },
  openai: { base: 'https://api.openai.com/v1', model: 'tts-1', voice: 'alloy' },
  local: { base: '', model: '', voice: '' },
};
const ttsDef = TTS_DEFAULTS[TTS_PROVIDER] || TTS_DEFAULTS.openai;

const ASR_PROVIDER = env('ASR_PROVIDER', 'dashscope') || 'dashscope';
const ASR_DEFAULTS = {
  dashscope: { base: 'https://dashscope.aliyuncs.com/api/v1', model: 'qwen-audio-3.1-asr-flash' },
  openai: { base: 'https://api.openai.com/v1', model: 'whisper-1' },
};
const asrDef = ASR_DEFAULTS[ASR_PROVIDER] || ASR_DEFAULTS.openai;

export const cfg = {
  // 接入方式：official=QQ 官方机器人（默认） | onebot=真实QQ（连自建协议端 NapCat/Lagrange）
  channel: (env('CHANNEL', 'official') || 'official').toLowerCase() === 'onebot' ? 'onebot' : 'official',
  onebotUrl: env('ONEBOT_WS_URL', ''), // 例：ws://192.168.1.10:3001
  onebotToken: env('ONEBOT_TOKEN', ''),
  onebotGroupAll: flag('ONEBOT_GROUP_ALL', false), // 群里不 @ 也回（默认只回 @）

  // QQ 官方机器人
  appId: env('QQ_APPID'),
  appSecret: env('QQ_APP_SECRET'),
  intents: num('QQ_INTENTS', 1 << 25), // 单聊 + 群@（不含频道）

  // 模型
  llmKey: env('LLM_API_KEY'),
  llmBase: env('LLM_BASE_URL', 'https://dashscope.aliyuncs.com/compatible-mode/v1').replace(/\/+$/, ''),
  model: env('LLM_MODEL', 'qwen3.8-max'),
  temperature: num('LLM_TEMPERATURE', 1.05),
  thinking: flag('ENABLE_THINKING', false),

  // 人设
  promptFile: env('SYSTEM_PROMPT_FILE', 'prompt.txt'),
  promptInline: env('SYSTEM_PROMPT'),

  // 机器人自称（改名）。空 = 用人设里写的名字
  botName: env('BOT_NAME', ''),
  humanize: flag('HUMANIZE'),

  // 记忆
  memoryOn: flag('MEMORY'),
  factsOn: flag('FACTS', true), // 长期记忆：从聊天里提炼事实存下来
  maxHistory: num('MAX_HISTORY', 80),
  maxChars: num('MAX_HISTORY_CHARS', 16000),
  maxSessions: num('MAX_SESSIONS', 50),
  learnOn: flag('LEARN'),
  learnMinMsgs: num('LEARN_MIN_MSGS', 30),
  learnStep: num('LEARN_STEP', 30),
  recentKeep: num('RECENT_KEEP', 30),

  // 主动消息
  proactive: flag('PROACTIVE', false),
  proactiveMaxDay: num('PROACTIVE_MAX_DAY', 3),
  proactiveGapMinH: num('PROACTIVE_GAP_MIN_H', 5),
  proactiveGapMaxH: num('PROACTIVE_GAP_MAX_H', 10),
  proactiveHourStart: num('PROACTIVE_HOUR_START', 9),
  proactiveHourEnd: num('PROACTIVE_HOUR_END', 22),
  proactiveDry: flag('PROACTIVE_DRY', false),
  proactiveRandomCap: flag('PROACTIVE_RANDOM_CAP'),
  proactiveVoiceChance: num('PROACTIVE_VOICE_CHANCE', 0.15), // 主动消息带语音的概率
  proactiveAfterReplyMax: num('PROACTIVE_AFTER_REPLY_MAX', 6), // 对方今天回过消息时，当天上限
  proactiveBusyMax: num('PROACTIVE_BUSY_MAX', 4), // 24h 内聊很频繁时，当天上限
  proactiveTo: env('PROACTIVE_TO', 'admin'), // admin=只找管理员 | all=所有聊过的人
  proactivePauseDays: num('PROACTIVE_PAUSE_DAYS', 7), // 对方说"别主动找我"后停多久

  // 语音
  ttsOn: flag('TTS'),
  visionOn: flag('VISION'), // 看图开关
  ttsClone: env('TTS_MODE', flag('TTS_CLONE', false) ? 'clone' : 'preset') === 'clone',
  // 音色方案：preset=现成音色 / clone=云端复刻 / local=本地小模型（连自建 TTS 服务）
  ttsMode: env('TTS_MODE', flag('TTS_CLONE', false) ? 'clone' : 'preset'),
  localTtsUrl: env('LOCAL_TTS_URL', ''),
  localTtsKey: env('LOCAL_TTS_KEY', ''),
  voiceChance: num('VOICE_CHANCE', 0.15), // 没被要求时，随机发语音的概率
  voiceOnlyChance: num('VOICE_ONLY_CHANCE', 0.15), // 发语音时只发语音不发文字的概率
  emojiChance: num('EMOJI_CHANCE', 0.08), // 随机附带一张表情包的概率
  // 回复条数分布：第 1/2/3/4 条的权重，默认 29/38/20/13
  // 回复形态：句数:条数:权重（直接指定组合，不再分别随机）
  // 默认 1句/1条 75%，2句/2条 18%，2句/1条 5%，3句/1条 2%
  plan: env('MSG_PLAN', '1:1:75,2:2:18,2:1:5,3:1:2')
    .split(',')
    .map((x) => {
      const [s, m, w] = x.split(':').map(Number);
      return { s, m, w };
    })
    .filter((p) => p.s > 0 && p.m > 0 && p.w > 0),

  // 用户可填的回复形态概率：
  //   MSG_COUNT_W = "60,30,10" → 一次回 1 / 2 / 3 条消息的权重
  //   SENT_W      = "65,25,10" → 每条消息写 1 / 2 / 3 句的权重
  // 任意一项填了就在发送时按这两条分布随机，不再用上面的 MSG_PLAN
  countWeights: env('MSG_COUNT_W', '').split(',').map((x) => Math.max(0, Number(x) || 0)),
  sentWeights: env('SENT_W', '').split(',').map((x) => Math.max(0, Number(x) || 0)),
  ttsModel: env('TTS_MODEL', ttsDef.model),
  ttsVoice: env('TTS_VOICE', ttsDef.voice),
  ttsBase: (env('TTS_BASE', '') || ttsDef.base).replace(/\/+$/, ''),
  asrOn: flag('ASR'),
  asrModel: env('ASR_MODEL', asrDef.model),
  asrBase: (env('ASR_BASE', '') || asrDef.base).replace(/\/+$/, ''),
  // 语音服务商：dashscope（千问/CosyVoice）| openai（任何 OpenAI 兼容 TTS/ASR）| local（自建服务）
  ttsProvider: TTS_PROVIDER,
  ttsKey: env('TTS_KEY') || env('LLM_API_KEY'),
  asrProvider: ASR_PROVIDER,
  asrKey: env('ASR_KEY') || env('LLM_API_KEY'),

  // 消息类型控制：开关 + 权重
  msgText: flag('MSG_TEXT', true),
  msgImage: flag('MSG_IMAGE', true),
  msgVoice: flag('MSG_VOICE', true),
  msgEmoji: flag('MSG_EMOJI', true),
  msgTextW: num('MSG_TEXT_W', 70),
  msgImageW: num('MSG_IMAGE_W', 1),
  msgVoiceW: num('MSG_VOICE_W', 8),
  msgEmojiW: num('MSG_EMOJI_W', 8),
  imageChance: num('IMAGE_CHANCE', 0.05), // 随机附带一张「图片」（image/ 目录）的概率

  // 指令权限：#指令 / /usage / /reset 默认只给管理员（号主）用
  adminOnly: flag('ADMIN_ONLY', true),
  adminIds: (env('ADMIN_IDS', '') || '')
    .split(/[,\s]+/)
    .map((s) => s.trim())
    .filter(Boolean),

  // 链接理解：收到带链接的消息时，抓网页正文一起交给模型看
  linkOn: flag('LINK_ON', true),
  linkMaxChars: num('LINK_MAX_CHARS', 3000),
  linkChance: num('LINK_CHANCE', 0), // 0~1：回复后再单独发一条收藏链接的概率
  linkSendChanceProactive: num('LINK_CHANCE_PROACTIVE', 0), // 0~1：主动消息后附带链接的概率

  // AI 自己挑表情包 / 图片（发 [EMOJI:文件名] / [PIC:文件名]）
  mediaSmart: flag('MEDIA_SMART', true),
  mediaTagsFile: dataFile(env('MEDIA_TAGS_FILE', 'media-tags.json')),

  // 联网搜索：''=关 | tavily / serper / bing / bocha
  searchProvider: env('SEARCH_PROVIDER', '').toLowerCase(),
  searchBase: env('SEARCH_BASE', '').replace(/\/+$/, ''), // 留空用各 provider 默认地址
  searchKey: env('SEARCH_KEY', ''),
  searchMax: num('SEARCH_MAX', 5),

  // 主动消息内容模板
  proactiveStyle: env('PROACTIVE_STYLE', 'mix'), // mix / caring / flirty / roast / custom
  proactiveTemplate: env('PROACTIVE_TEMPLATE', ''), // 自定义模板：{nickname} 占位

  // 主动消息目标（逗号分隔的 openid 列表，空=全部已交互者）
  proactiveTargets: env('PROACTIVE_TARGETS', '').split(',').map((s) => s.trim()).filter(Boolean),

  // 内容过滤
  filterWords: env('FILTER_WORDS', '').split(',').map((s) => s.trim()).filter(Boolean),
  blacklistUsers: env('BLACKLIST_USERS', '').split(',').map((s) => s.trim()).filter(Boolean),
  blacklistGroups: env('BLACKLIST_GROUPS', '').split(',').map((s) => s.trim()).filter(Boolean),

  // 细节调参
  typingDelayMinMs: num('TYPING_DELAY_MIN_MS', 400),
  typingDelayMaxMs: num('TYPING_DELAY_MAX_MS', 2200),
  recallSeconds: num('RECALL_SECONDS', 0), // 0=不撤回
  msgPrefix: env('MSG_PREFIX', ''), // 每条消息头部固定前缀，如 "[bot] "

  // 插件系统
  skillsOn: flag('SKILLS', true),

};

// ---- 人设 ----
export const systemPrompt = (() => {
  const f = path.isAbsolute(cfg.promptFile) ? cfg.promptFile : path.join(ROOT, cfg.promptFile);
  const raw = fs.existsSync(f) ? fs.readFileSync(f, 'utf8').trim() : (cfg.promptInline || '你是一个友好、简洁的助手。');
  // 用户改了名字：把人设里「你叫 XXX」替换成新名字；人设里没写名字就补一句
  const nm = String(cfg.botName || '').trim();
  if (!nm) return raw;
  if (/你叫[^，,\n]{1,16}/.test(raw)) return raw.replace(/你叫[^，,\n]{1,16}/, `你叫${nm}`);
  return `你叫${nm}。\n${raw}`;
})();

fs.mkdirSync(EMOJI_DIR, { recursive: true });
fs.mkdirSync(IMAGE_DIR, { recursive: true });
fs.mkdirSync(DATA_DIR, { recursive: true }); // 启动即确保数据目录存在
export const LOG_FILE = path.join(ROOT, 'logs', 'qqbot.log');
