import { getOnlineUsers } from './api'

const CHECK_INTERVAL_MS = 30_000
const REQUEST_TIMEOUT_MS = 8_000

type OnlineStatusListener = (onlineFriends: Set<string>) => void

function setEquals(a: Set<string>, b: Set<string>): boolean {
  if (a.size !== b.size) return false
  for (const item of a) {
    if (!b.has(item)) return false
  }
  return true
}

class OnlineStatusManagerImpl {
  private readonly listeners = new Set<OnlineStatusListener>()
  private onlineUsers = new Set<string>()
  private intervalId: ReturnType<typeof setInterval> | null = null
  private activeConsumers = 0
  private inFlight = false

  addListener(listener: OnlineStatusListener): () => void {
    this.listeners.add(listener)
    listener(new Set(this.onlineUsers))
    return () => {
      this.listeners.delete(listener)
    }
  }

  startChecking(): void {
    this.activeConsumers += 1
    if (this.activeConsumers > 1) return
    void this.checkOnlineStatus()
    this.intervalId = setInterval(() => {
      void this.checkOnlineStatus()
    }, CHECK_INTERVAL_MS)
  }

  stopChecking(): void {
    this.activeConsumers = Math.max(0, this.activeConsumers - 1)
    if (this.activeConsumers > 0) return
    if (this.intervalId) {
      clearInterval(this.intervalId)
      this.intervalId = null
    }
  }

  isFriendOnline(friendImei: string): boolean {
    return this.onlineUsers.has(friendImei)
  }

  getOnlineUsersSnapshot(): Set<string> {
    return new Set(this.onlineUsers)
  }

  private async checkOnlineStatus(): Promise<void> {
    if (this.inFlight) return
    this.inFlight = true
    try {
      const users = await getOnlineUsers({ timeoutMs: REQUEST_TIMEOUT_MS })
      const nextOnlineUsers = new Set(users)
      if (setEquals(this.onlineUsers, nextOnlineUsers)) return
      this.onlineUsers = nextOnlineUsers
      this.emit()
    } finally {
      this.inFlight = false
    }
  }

  private emit(): void {
    const snapshot = new Set(this.onlineUsers)
    this.listeners.forEach((listener) => {
      try {
        listener(snapshot)
      } catch {
        // ignore listener errors to avoid interrupting others
      }
    })
  }
}

export const OnlineStatusManager = new OnlineStatusManagerImpl()
