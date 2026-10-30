// 自检：发「ping」确认机器人在线
export default {
  name: 'ping',
  description: '发「ping」回 pong + 已运行时长',
  priority: 5,
  match: ({ text }) => /^(ping|在吗|pong)$/i.test(text.trim()),
  handle: () => {
    const sec = Math.floor(process.uptime());
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    return `pong（已运行 ${h ? `${h} 小时 ` : ''}${m} 分钟）`;
  },
};