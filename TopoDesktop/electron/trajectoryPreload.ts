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

contextBridge.exposeInMainWorld('trajectoryOverlay', {
  onPoint: (callback: (data: { action: string; x: number; y: number }) => void) => {
    const handler = (_: unknown, data: { action: string; x: number; y: number }) => callback(data)
    ipcRenderer.on('trajectory:point', handler)
    return () => ipcRenderer.removeListener('trajectory:point', handler)
  },
  onClear: (callback: () => void) => {
    const handler = () => callback()
    ipcRenderer.on('trajectory:clear', handler)
    return () => ipcRenderer.removeListener('trajectory:clear', handler)
  },
  onInit: (
    callback: (data: {
      offsetX: number
      offsetY: number
      width: number
      height: number
      mode?: 'a' | 'b'
    }) => void
  ) => {
    const handler = (
      _: unknown,
      data: { offsetX: number; offsetY: number; width: number; height: number; mode?: 'a' | 'b' }
    ) => callback(data)
    ipcRenderer.on('trajectory:init', handler)
    return () => ipcRenderer.removeListener('trajectory:init', handler)
  },
  onSetMode: (callback: (data: { mode: 'a' | 'b' }) => void) => {
    const handler = (_: unknown, data: { mode: 'a' | 'b' }) => callback(data)
    ipcRenderer.on('trajectory:set-mode', handler)
    return () => ipcRenderer.removeListener('trajectory:set-mode', handler)
  },
  interceptedClick: (x: number, y: number, action: string) =>
    ipcRenderer.invoke('trajectory:intercepted-click', { x, y, action }),
})
