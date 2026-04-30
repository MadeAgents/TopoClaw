# 版本维护说明

## 概述

版本维护功能用于在客户端启动任务时自动检测是否有新版本可用，如果有新版本则弹窗提醒用户更新。

## 版本维护文件

版本信息由接口读取：**优先** `outputs/version/version_info.json`（运行期副本）；若不存在则回退到包根目录的 `version_info.json`。首次启动会从根目录复制到 `outputs/version/`。更新版本时请**同步修改**实际生效的文件（通常改 `outputs/version/version_info.json`，或改根目录后删除运行副本以触发重新复制）。

### 文件结构

```json
{
  "latest_version": "1.0",           // 最新版本号（格式：x.y.z）
  "min_supported_version": "1.0",     // 最低支持版本号（低于此版本需要强制更新）
  "update_url": "",  // 更新链接（建议通过 CUSTOMER_SERVICE_UPDATE_URL 环境变量注入）
  "versions": [                        // 版本历史记录（可选）
    {
      "version": "1.0",
      "release_date": "2026-01-XX",
      "update_message": "初始版本",
      "changelog": ["功能1", "功能2"]
    }
  ],
  "update_info": {                     // 更新信息
    "has_update": false,               // 是否有更新（通常由系统自动判断）
    "force_update": false,              // 是否强制更新（通常由系统自动判断）
    "update_message": "当前为最新版本"  // 更新提示消息
  },
  "last_updated": "2026-01-XX"        // 最后更新时间
}
```

## 如何更新版本信息

### 1. 发布新版本时

1. 打开 `version_info.json` 文件
2. 更新 `latest_version` 字段为新版本号（如 "1.1"）
3. 如需修改下载链接，优先更新 `CUSTOMER_SERVICE_UPDATE_URL` 环境变量
4. 更新 `update_info.update_message` 字段为更新说明
5. 在 `versions` 数组中添加新版本记录（可选）
6. 更新 `last_updated` 字段为当前日期
7. **更新 `CHANGELOG.md` 文件**，在文件顶部添加新版本的更新记录

### 2. 设置强制更新

如果需要强制用户更新（例如修复了严重bug）：
1. 将 `min_supported_version` 设置为最低支持的版本号
2. 客户端版本低于此版本时，会显示强制更新弹窗（无法取消）

### 示例：发布 1.1 版本

```json
{
  "latest_version": "1.1",
  "min_supported_version": "1.0",
  "update_url": "",
  "update_info": {
    "has_update": true,
    "force_update": false,
    "update_message": "新版本 1.1 已发布，包含以下更新：\n- 修复了已知问题\n- 优化了性能\n- 新增了功能"
  },
  "last_updated": "2026-01-15"
}
```

## API 端点

### GET /api/version/check

检查版本更新

**请求参数：**
- `current_version` (可选): 客户端当前版本号

**响应示例：**
```json
{
  "success": true,
  "current_version": "1.0",
  "latest_version": "1.1",
  "min_supported_version": "1.0",
  "update_url": "",
  "has_update": true,
  "force_update": false,
  "update_message": "发现新版本，建议更新",
  "last_updated": "2026-01-15"
}
```

## 客户端行为

1. **任务启动时检测**：每次启动新任务时，客户端会自动调用版本检查API
2. **发现新版本**：如果发现新版本，会显示更新提示弹窗
3. **用户选择**：
   - 点击"立即更新"：打开更新链接
   - 点击"稍后提醒"：关闭弹窗，继续执行任务
4. **强制更新**：如果是强制更新，弹窗不可取消，必须更新

## 更新记录文件

### CHANGELOG.md

`CHANGELOG.md` 文件用于维护详细的版本更新历史记录，包括：
- 版本号
- 发布日期
- 更新内容列表
- 更新说明

**维护方式：**
- 每次发布新版本时，在文件顶部添加新版本记录
- 格式参考文件中的示例
- 便于开发团队和用户查看历史更新记录

## 注意事项

1. 版本号格式建议使用 `x.y.z` 格式（如 1.0.0, 1.1.0, 1.1.1）
2. 版本比较逻辑会自动处理不同长度的版本号（如 1.0 和 1.0.0）
3. 更新链接应该是可以直接在浏览器中打开的URL
4. 版本检查失败不会影响任务执行，只记录日志
5. **发布新版本时，记得同时更新 `version_info.json` 和 `CHANGELOG.md` 两个文件**

