/**
 * 构建配置：与 package.json 的 build 一致；若仓库根存在 config.txt 则一并打入安装包的 resources/config.txt
 */
const fs = require('fs')
const path = require('path')
const pkg = require('./package.json')

const build = { ...pkg.build }
build.extraResources = [...(build.extraResources || [])]
if (fs.existsSync(path.join(__dirname, 'config.txt'))) {
  build.extraResources.push({ from: 'config.txt', to: 'config.txt' })
}
build.extraResources = build.extraResources.filter((item) => {
  const from = typeof item?.from === 'string' ? item.from : ''
  if (!from) return true
  const resolved = path.isAbsolute(from) ? from : path.join(__dirname, from)
  return fs.existsSync(resolved)
})

module.exports = build
