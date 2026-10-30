/**
 * 接入层：根据 CHANNEL 决定用「QQ 官方机器人」还是「真实QQ（OneBot）」
 *
 * 其它模块只从这里拿发送能力，不用关心底层是哪一种。
 *   CHANNEL=official（默认）→ lib/qq.mjs，走 api.bot.qq.com
 *   CHANNEL=onebot          → lib/onebot.mjs，走你自己的 NapCat / Lagrange
 */
import { cfg } from './config.mjs';

const impl = cfg.channel === 'onebot' ? await import('./onebot.mjs') : await import('./qq.mjs');

export const channel = cfg.channel;

export const getToken = (...a) => impl.getToken(...a);
export const getTokenSync = (...a) => impl.getTokenSync(...a);
export const api = (...a) => impl.api(...a);
export const sendList = (...a) => impl.sendList(...a);
export const sendText = (...a) => impl.sendText(...a);
export const sendMedia = (...a) => impl.sendMedia(...a);
export const uploadMedia = (...a) => impl.uploadMedia(...a);
export const uploadLocalFile = (...a) => impl.uploadLocalFile(...a);
export const recallMessage = (...a) => impl.recallMessage(...a);
export const proactiveText = (...a) => impl.proactiveText(...a);
export const sendTextRaw = (...a) => impl.sendTextRaw(...a);
export const splitForSend = impl.splitForSend;

/** 只有 onebot 通道才有的连接控制 */
export const startOneBot = (handlers) => impl.start(handlers);
export const stopOneBot = () => impl.stop?.();