// 示例技能：把它复制到 bot 的 skills/ 目录就能用。
// 一个技能就是一个 .mjs 文件，默认导出下面这个对象即可。
//
// 可用能力（都是普通 Node + 项目内已有模块）：
//   - fetch 外部 HTTP/HTTPS（Node 内置，带内置 CA）
//   - import { DATA_DIR } from './lib/config.mjs' 读写自己的数据文件
//   - 返回值可以是字符串，也可以是字符串数组（会按条分开发）
//   - 不支持 npm 包、Python、exe；需要依赖就自己把代码一起打进 zip

export default {
  name: '示例技能',
  desc: '发「ping」回一声「在呢」，发「时间」报当前时间',

  // match 返回 false/undefined 表示这条消息不归它管；
  // 返回 true 或一个对象（会作为 args 传给 handle）。
  match(text) {
    const t = String(text || '').trim()
    if (/^(ping|在吗)$/i.test(t)) return { cmd: 'ping' }
    if (/^(时间|几点了?)$/.test(t)) return { cmd: 'time' }
    return false
  },

  async handle({ text, convKey, args }) {
    if (args?.cmd === 'ping') return '在呢'
    if (args?.cmd === 'time') {
      const d = new Date()
      const hh = String(d.getHours()).padStart(2, '0')
      const mm = String(d.getMinutes()).padStart(2, '0')
      return `现在是 ${hh}:${mm}`
    }
    return ''
  },
}