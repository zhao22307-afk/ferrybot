// 掷骰子：发「掷骰子 / 骰子 / roll」
export default {
  name: '掷骰子',
  description: '发「掷骰子」随机回 1~6',
  priority: 10,
  match: ({ text }) => /^(掷骰子|骰子|roll|d6|摇骰子)$/i.test(text.trim()),
  handle: () => `🎲 ${1 + Math.floor(Math.random() * 6)}`,
};