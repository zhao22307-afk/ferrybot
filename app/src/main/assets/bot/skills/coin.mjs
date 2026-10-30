// 抛硬币
export default {
  name: '抛硬币',
  description: '发「抛硬币 / 猜正反」时随机回正面或反面',
  priority: 10,
  match: ({ text }) => /^(抛硬币|扔硬币|硬币|flip)$/i.test(text.trim()),
  handle: () => (Math.random() < 0.5 ? '正面 🪙' : '反面 🪙'),
};