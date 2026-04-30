// Copyright 2025 OPPO

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

//     http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/**
 * 为应用图标添加圆角（似圆非方）
 * 采用 squircle 风格：介于圆形与方形之间，圆角更饱满
 * 输出:
 * - Image_PC_rounded.png (用于窗口/任务栏图标)
 * - Image_PC_tray.png (用于托盘图标，避免运行时缩放损失圆角)
 * - icon.ico (用于 exe/NSIS 安装包)
 */
const path = require('path');
const fs = require('fs');
const sharp = require('sharp');

const rootDir = path.join(__dirname, '..');
const inputPath = path.join(rootDir, 'TopoClaw7.png');
const outputPngPath = path.join(rootDir, 'Image_PC_rounded.png');
const outputTrayPath = path.join(rootDir, 'Image_PC_tray.png');
const outputIcoPath = path.join(rootDir, 'icon.ico');

async function roundIcon() {
  try {
    const trimmed = sharp(inputPath).trim();
    const metadata = await trimmed.metadata();
    const { width, height } = metadata;
    const size = Math.max(width || 0, height || 0);
    const visualFill = 1.0;
    const innerSize = Math.max(1, Math.round(size * visualFill));
    //圆角半径约 26%，形成「似圆非方」的 squircle 效果
    const radius = Math.round(size * 0.26);

    const roundedRectSvg = `
      <svg width="${size}" height="${size}">
        <rect width="${size}" height="${size}" rx="${radius}" ry="${radius}" fill="white"/>
      </svg>
    `;

    // png-to-ico 要求正方形 PNG；先去掉源图透明留白，再居中放入正方形透明画布并做圆角蒙版。
    const normalized = await trimmed
      .resize(innerSize, innerSize, {
        fit: 'contain',
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      })
      .extend({
        top: Math.floor((size - innerSize) / 2),
        bottom: Math.ceil((size - innerSize) / 2),
        left: Math.floor((size - innerSize) / 2),
        right: Math.ceil((size - innerSize) / 2),
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      })
      .png()
      .toBuffer();

    await sharp(normalized)
      .composite([{
        input: Buffer.from(roundedRectSvg),
        blend: 'dest-in',
      }])
      .png()
      .toFile(outputPngPath);

    console.log(`✓ 风格圆角图标: Image_PC_rounded.png (圆角半径: ${radius}px, 视觉占比: ${Math.round(visualFill * 100)}%)`);

    // 生成托盘专用小图标，避免 Electron 运行时缩放导致圆角被“磨平”
    await sharp(outputPngPath)
      .resize(20, 20, {
        fit: 'cover',
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      })
      .png()
      .toFile(outputTrayPath);
    console.log(`✓ 托盘专用图标: Image_PC_tray.png (20x20)`); 

    // NSIS 与 rcedit 需要 .ico 格式，生成多尺寸 icon.ico（png-to-ico 为 ES 模块，需动态 import）
    const { default: pngToIco } = await import('png-to-ico');
    const icoBuf = await pngToIco(outputPngPath);
    fs.writeFileSync(outputIcoPath, icoBuf);
    console.log(`✓ 已生成 icon.ico (供 exe/安装包使用)`);
  } catch (err) {
    console.error('生成圆角图标失败:', err.message);
    process.exit(1);
  }
}

roundIcon();
