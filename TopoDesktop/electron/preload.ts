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

import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('electronAPI', {
  getAppVersion: () => ipcRenderer.invoke('app:get-version') as Promise<string>,
  minimize: () => ipcRenderer.send('window:minimize'),
  maximize: () => ipcRenderer.send('window:maximize'),
  close: () => ipcRenderer.send('window:close'),
  openExternal: (url: string) => ipcRenderer.invoke('shell:openExternal', url),
  openPath: (filePath: string) => ipcRenderer.invoke('shell:openPath', filePath),
  showItemInFolder: (filePath: string) => ipcRenderer.invoke('shell:showItemInFolder', filePath),
  resolveGeneratedFile: (fileToken: string) => ipcRenderer.invoke('shell:resolve-generated-file', fileToken),
  revealGeneratedFile: (fileToken: string) => ipcRenderer.invoke('shell:reveal-generated-file', fileToken),
  focus: () => ipcRenderer.send('window:focus'),
  isMaximized: () => ipcRenderer.invoke('window:isMaximized'),
  notifyNewMessage: (totalUnread: number) => ipcRenderer.invoke('app:notify-new-message', totalUnread),
  saveImageAs: (dataUrl: string, defaultFileName?: string) =>
    ipcRenderer.invoke('app:save-image-data-url', { dataUrl, defaultFileName }),
  saveChatImageToWorkspace: (dataUrl: string, originalFileName?: string) =>
    ipcRenderer.invoke('app:save-chat-image-to-workspace', { dataUrl, originalFileName }),
  saveChatFileToWorkspace: (dataUrl: string, originalFileName?: string, batchDir?: string) =>
    ipcRenderer.invoke('app:save-chat-file-to-workspace', { dataUrl, originalFileName, batchDir }),
  readWorkspaceProfileFile: (kind: 'soul' | 'memory') =>
    ipcRenderer.invoke('app:read-workspace-profile-file', { kind }) as Promise<{
      ok: boolean
      content?: string
      path?: string
      error?: string
    }>,
  readWorkspaceProfileDefaultFile: (kind: 'soul' | 'memory') =>
    ipcRenderer.invoke('app:read-workspace-profile-default-file', { kind }) as Promise<{
      ok: boolean
      content?: string
      path?: string
      error?: string
    }>,
  readConversationSummaryFile: () =>
    ipcRenderer.invoke('app:read-conversation-summary-file') as Promise<{
      ok: boolean
      content?: string
      path?: string
      error?: string
    }>,
  writeConversationSummaryFile: (content: string) =>
    ipcRenderer.invoke('app:write-conversation-summary-file', { content }) as Promise<{
      ok: boolean
      path?: string
      error?: string
    }>,
  writeWorkspaceProfileFile: (kind: 'soul' | 'memory', content: string) =>
    ipcRenderer.invoke('app:write-workspace-profile-file', { kind, content }) as Promise<{
      ok: boolean
      path?: string
      error?: string
    }>,
  listWorkspaceFiles: (opts?: { maxFiles?: number; maxBytes?: number }) =>
    ipcRenderer.invoke('app:list-workspace-files', opts ?? {}) as Promise<{
      ok: boolean
      workspaceDir?: string
      files?: Array<{ relativePath: string; content: string }>
      error?: string
    }>,
  pickFolderFiles: (opts?: { maxFiles?: number; maxBytes?: number }) =>
    ipcRenderer.invoke('app:pick-folder-files', opts ?? {}) as Promise<{
      ok: boolean
      canceled?: boolean
      folderPath?: string
      files?: Array<{ relativePath: string; content: string }>
      error?: string
    }>,
  listFolderFiles: (opts?: { folderPath?: string; maxFiles?: number; maxBytes?: number }) =>
    ipcRenderer.invoke('app:list-folder-files', opts ?? {}) as Promise<{
      ok: boolean
      folderPath?: string
      files?: Array<{ relativePath: string; content: string }>
      error?: string
    }>,
  saveCsvAs: (text: string, defaultFileName?: string) =>
    ipcRenderer.invoke('app:save-csv', { text, defaultFileName }),
  saveTextAs: (text: string, defaultFileName?: string) =>
    ipcRenderer.invoke('app:save-text-as', { text, defaultFileName }),
  writeTextFile: (filePath: string, text: string) =>
    ipcRenderer.invoke('app:write-text-file', { filePath, text }) as Promise<{
      ok: boolean
      path?: string
      error?: string
    }>,
  readTextFile: (filePath: string) =>
    ipcRenderer.invoke('app:read-text-file', { filePath }) as Promise<{
      ok: boolean
      path?: string
      content?: string
      error?: string
    }>,
  readBinaryFile: (filePath: string) =>
    ipcRenderer.invoke('app:read-binary-file', { filePath }) as Promise<{
      ok: boolean
      path?: string
      base64?: string
      error?: string
    }>,
  copyFileToClipboard: (filePath: string) =>
    ipcRenderer.invoke('app:copy-file-to-clipboard', filePath) as Promise<{
      success: boolean
      error?: string
    }>,
  copyImage: (opts: { dataUrl?: string; url?: string; fileUrl?: string }) =>
    ipcRenderer.invoke('app:copy-image', opts),
  readClipboardImageBase64Sync: (): string | null => {
    try {
      return ipcRenderer.sendSync('app:read-clipboard-image-sync') as string | null
    } catch {
      return null
    }
  },
  onMaximizeChange: (callback: (maximized: boolean) => void) => {
    const handler = (_: unknown, maximized: boolean) => callback(maximized)
    ipcRenderer.on('window:maximized-change', handler)
    return () => ipcRenderer.removeListener('window:maximized-change', handler)
  },
  onDesktopScreenshotPrefill: (
    callback: (payload: {
      text: string
      autoSend?: boolean
      imageBase64?: string
      imageMime?: string
      imageName?: string
      forceTopoClaw?: boolean
      skipComposerImage?: boolean
      saveToQuickNote?: boolean
    }) => void
  ) => {
    const handler = (
      _: unknown,
      payload: {
        text?: string
        autoSend?: boolean
        imageBase64?: string
        imageMime?: string
        imageName?: string
        forceTopoClaw?: boolean
        skipComposerImage?: boolean
        saveToQuickNote?: boolean
      }
    ) =>
      callback({
        text: String(payload?.text || ''),
        autoSend: payload?.autoSend === true,
        imageBase64: typeof payload?.imageBase64 === 'string' ? payload.imageBase64 : undefined,
        imageMime: typeof payload?.imageMime === 'string' ? payload.imageMime : undefined,
        imageName: typeof payload?.imageName === 'string' ? payload.imageName : undefined,
        forceTopoClaw: payload?.forceTopoClaw === true,
        skipComposerImage: payload?.skipComposerImage === true,
        saveToQuickNote: payload?.saveToQuickNote === true,
      })
    ipcRenderer.on('desktop-screenshot:prefill', handler)
    return () => ipcRenderer.removeListener('desktop-screenshot:prefill', handler)
  },
  openXiaoTuo: () => ipcRenderer.invoke('xiaotuo:open') as Promise<{ ok: boolean; enabled: boolean; error?: string }>,
  closeXiaoTuo: () => ipcRenderer.invoke('xiaotuo:close') as Promise<{ ok: boolean; enabled: boolean; error?: string }>,
  getXiaoTuoStatus: () => ipcRenderer.invoke('xiaotuo:status') as Promise<{ ok: boolean; enabled: boolean; error?: string }>,
  askTopoClawFromXiaoTuo: (payload?: { prompt?: string; fileType?: string; appName?: string }) =>
    ipcRenderer.invoke('xiaotuo:ask-topoclaw', payload ?? {}) as Promise<{ ok: boolean; error?: string }>,
  onXiaoTuoDetected: (
    callback: (payload: {
      fileType: string
      processName: string
      title: string
      avatar: 'ppt' | 'pdf' | 'doc' | 'file'
    }) => void
  ) => {
    const handler = (
      _: unknown,
      payload: { fileType?: string; processName?: string; title?: string; avatar?: 'ppt' | 'pdf' | 'doc' | 'file' }
    ) =>
      callback({
        fileType: String(payload?.fileType || '').trim().toLowerCase(),
        processName: String(payload?.processName || '').trim(),
        title: String(payload?.title || '').trim(),
        avatar: payload?.avatar === 'ppt' || payload?.avatar === 'pdf' || payload?.avatar === 'doc' ? payload.avatar : 'file',
      })
    ipcRenderer.on('xiaotuo:detected', handler)
    return () => ipcRenderer.removeListener('xiaotuo:detected', handler)
  },
})

contextBridge.exposeInMainWorld('pcRecord', {
  start: () => ipcRenderer.send('pc-record:start'),
  stop: () => ipcRenderer.send('pc-record:stop'),
  onAction: (callback: (data: { action: string; x: number; y: number }) => void) => {
    const handler = (_: unknown, data: { action: string; x: number; y: number }) => callback(data)
    ipcRenderer.on('pc-record:action', handler)
    return () => ipcRenderer.removeListener('pc-record:action', handler)
  },
})

contextBridge.exposeInMainWorld('trajectory', {
  start: (mode?: 'a' | 'b') => ipcRenderer.send('trajectory:start', mode),
  stop: () => ipcRenderer.send('trajectory:stop'),
  clear: () => ipcRenderer.send('trajectory:clear'),
  setMode: (mode: 'a' | 'b') => ipcRenderer.send('trajectory:set-mode', mode),
  onAction: (callback: (data: { action: string; x: number; y: number }) => void) => {
    const handler = (_: unknown, data: { action: string; x: number; y: number }) => callback(data)
    ipcRenderer.on('pc-record:action', handler)
    return () => ipcRenderer.removeListener('pc-record:action', handler)
  },
})

contextBridge.exposeInMainWorld('computerUse', {
  showDesktop: () => ipcRenderer.invoke('computer-use:show-desktop'),
  screenshot: () => ipcRenderer.invoke('computer-use:screenshot'),
  uploadScreenshotAndGetAction: (
    uploadUrl: string,
    requestId: string,
    query: string,
    chatSummary?: string | null
  ) =>
    ipcRenderer.invoke('computer-use:upload-screenshot', {
      uploadUrl,
      requestId,
      query,
      chatSummary: chatSummary || undefined,
    }),
  click: (x: number, y: number) =>
    ipcRenderer.invoke('computer-use:click', { x, y }),
  doubleClick: (x: number, y: number) =>
    ipcRenderer.invoke('computer-use:double-click', { x, y }),
  rightClick: (x: number, y: number) =>
    ipcRenderer.invoke('computer-use:right-click', { x, y }),
  type: (text: string) => ipcRenderer.invoke('computer-use:type', { text }),
  clickAndType: (x: number, y: number, text: string) =>
    ipcRenderer.invoke('computer-use:click-and-type', { x, y, text }),
  getCursorPosition: () =>
    ipcRenderer.invoke('computer-use:cursor-position'),
  scroll: (delta: number, x?: number, y?: number) =>
    ipcRenderer.invoke('computer-use:scroll', { delta, x, y }),
  pressKey: (keyname: string) =>
    ipcRenderer.invoke('computer-use:key', { keyname }),
  move: (x: number, y: number) =>
    ipcRenderer.invoke('computer-use:move', { x, y }),
  keyDown: (keyname: string) =>
    ipcRenderer.invoke('computer-use:key-down', { keyname }),
  keyUp: (keyname: string) =>
    ipcRenderer.invoke('computer-use:key-up', { keyname }),
  drag: (x1: number, y1: number, x2: number, y2: number) =>
    ipcRenderer.invoke('computer-use:drag', { x1, y1, x2, y2 }),
})

contextBridge.exposeInMainWorld('codeExec', {
  run: (code: string) =>
    ipcRenderer.invoke('code-exec:run', { code }),
  installPackage: (packageName: string) =>
    ipcRenderer.invoke('code-exec:install-package', { packageName }),
})

contextBridge.exposeInMainWorld('publicHubFetch', {
  get: (url: string) => ipcRenderer.invoke('publichub:fetch', url),
})

contextBridge.exposeInMainWorld('colorClawService', {
  get: (path: string) => ipcRenderer.invoke('colorclaw:get', { path }),
  post: (path: string, body: unknown) => ipcRenderer.invoke('colorclaw:post', { path, body }),
  delete: (path: string) => ipcRenderer.invoke('colorclaw:delete', { path }),
})

contextBridge.exposeInMainWorld('builtinAssistant', {
  getDefaults: () => ipcRenderer.invoke('builtin-assistant:get-defaults'),
  getDefaultUrl: (slot?: 'topoclaw' | 'groupmanager') => ipcRenderer.invoke('builtin-assistant:get-default-url', slot ?? 'topoclaw'),
  getDefaultUrls: () => ipcRenderer.invoke('builtin-assistant:get-default-urls') as Promise<{ topoclaw: string; groupmanager: string }>,
  getGlobalEnabled: () =>
    ipcRenderer.invoke('builtin-assistant:get-global-enabled') as Promise<{ ok: boolean; enabled: boolean; error?: string }>,
  setGlobalEnabled: (enabled: boolean) =>
    ipcRenderer.invoke('builtin-assistant:set-global-enabled', enabled) as Promise<{ ok: boolean; enabled: boolean; error?: string }>,
  ensureStarted: () => ipcRenderer.invoke('builtin-assistant:ensure-started'),
  setTopomobileNodeId: (nodeId: string) => ipcRenderer.invoke('builtin-assistant:set-topomobile-node-id', nodeId),
  setActiveCustomerServiceUrl: (customerServiceUrl: string) =>
    ipcRenderer.invoke('builtin-assistant:set-active-customer-service-url', customerServiceUrl),
  syncTopomobileWsUrlFromCustomerServiceUrl: (customerServiceUrl: string) =>
    ipcRenderer.invoke('builtin-assistant:sync-topomobile-ws-url', customerServiceUrl),
  startCustomerService: (params?: { restart?: boolean }) =>
    ipcRenderer.invoke('builtin-assistant:customer-service-start', params ?? {}),
  customerServiceLogPipeActive: () =>
    ipcRenderer.invoke('builtin-assistant:customer-service-log-pipe-active') as Promise<boolean>,
  customerServiceGetLogBuffer: () =>
    ipcRenderer.invoke('builtin-assistant:customer-service-get-log-buffer') as Promise<string>,
  restart: (slot?: 'topoclaw' | 'groupmanager') => ipcRenderer.invoke('builtin-assistant:restart', slot),
  hasLogStream: (slot?: 'topoclaw' | 'groupmanager') => ipcRenderer.invoke('builtin-assistant:has-log-stream', slot ?? 'topoclaw'),
  /** 是否为本应用 spawn 的子进程（为 true 时 stderr/stdout 才会持续进入日志弹窗） */
  logPipeActive: (slot?: 'topoclaw' | 'groupmanager') => ipcRenderer.invoke('builtin-assistant:log-pipe-active', slot ?? 'topoclaw'),
  getLogBuffer: (slot?: 'topoclaw' | 'groupmanager') => ipcRenderer.invoke('builtin-assistant:get-log-buffer', slot ?? 'topoclaw'),
  exportLog: (text: string) => ipcRenderer.invoke('builtin-assistant:export-log', text),
  weixinGetQr: (input?: { baseUrl?: string; botType?: string; skRouteTag?: string }) =>
    ipcRenderer.invoke('builtin-assistant:weixin-get-qr', input),
  weixinPollQrStatus: (input: { baseUrl?: string; qrcodeTicket?: string; skRouteTag?: string }) =>
    ipcRenderer.invoke('builtin-assistant:weixin-poll-qr-status', input),
  getImLocalHistory: (input: { channel: 'qq' | 'weixin'; limit?: number }) =>
    ipcRenderer.invoke('builtin-assistant:get-im-local-history', input),
  onLog: (callback: (payload: { slot: string; chunk: string }) => void) => {
    const handler = (_: unknown, payload: { slot: string; chunk: string }) => callback(payload)
    ipcRenderer.on('builtin-assistant:log', handler)
    return () => ipcRenderer.removeListener('builtin-assistant:log', handler)
  },
  saveConfig: (config: {
    model?: string
    apiBase?: string
    apiKey?: string
    guiModel?: string
    guiApiBase?: string
    guiApiKey?: string
    qqEnabled?: boolean
    qqAppId?: string
    qqAppSecret?: string
    qqAllowFrom?: string | string[]
    weixinEnabled?: boolean
    weixinBotToken?: string
    weixinBaseUrl?: string
    weixinAllowFrom?: string | string[]
  }) => ipcRenderer.invoke('builtin-assistant:save-config', config),
  getModelProfiles: () => ipcRenderer.invoke('builtin-assistant:get-model-profiles'),
  saveModelProfiles: (payload: {
    nonGuiProfiles?: Array<{ model: string; apiBase: string; apiKey: string }>
    guiProfiles?: Array<{ model: string; apiBase: string; apiKey: string }>
    activeNonGuiModel?: string
    activeImageModel?: string
    activeGuiModel?: string
    activeGroupManagerModel?: string
  }) => ipcRenderer.invoke('builtin-assistant:save-model-profiles', payload),
  applyModelSelection: (params: {
    slot: 'topoclaw' | 'groupmanager'
    nonGuiModel: string
    guiModel?: string
  }) => ipcRenderer.invoke('builtin-assistant:apply-model-selection', params),
  readLocalConfigTxt: () =>
    ipcRenderer.invoke('builtin-assistant:read-local-config-txt') as Promise<
      | { ok: true; nonGuiProfiles: Array<{ model: string; apiBase: string; apiKey: string }>; guiProfiles: Array<{ model: string; apiBase: string; apiKey: string }> }
      | { ok: false; error: string }
    >,
  saveLocalConfigTxt: (payload: {
    nonGuiProfiles?: Array<{ model: string; apiBase: string; apiKey: string }>
    guiProfiles?: Array<{ model: string; apiBase: string; apiKey: string }>
  }) =>
    ipcRenderer.invoke('builtin-assistant:save-local-config-txt', payload) as Promise<
      | { ok: true; path?: string }
      | { ok: false; error: string }
    >,
})

contextBridge.exposeInMainWorld('terminalAPI', {
  openWindow: () => ipcRenderer.invoke('terminal:open-window'),
  create: (payload?: { cwd?: string }) => ipcRenderer.invoke('terminal:create', payload ?? {}),
  write: (data: string) => ipcRenderer.send('terminal:write', data),
  resize: (cols: number, rows: number) =>
    ipcRenderer.send('terminal:resize', { cols, rows }),
  onData: (callback: (data: string) => void) => {
    const handler = (_: unknown, data: string) => callback(data)
    ipcRenderer.on('terminal:data', handler)
    return () => ipcRenderer.removeListener('terminal:data', handler)
  },
})
