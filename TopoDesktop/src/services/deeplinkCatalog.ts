import deeplinkCatalog from '../data/deeplink_catalog.json'

type DeeplinkItem = {
  id: number
  app_name: string
  feature_name: string
  deeplink: string
  call_method: string
  description: string
  parameters: string[]
  notes: string
}

type DeeplinkCatalogDocument = {
  schema_version: string
  source: string
  catalog_version: string
  total: number
  items: DeeplinkItem[]
}

type ColorClawBridge = {
  get?: (path: string) => Promise<{ success: boolean; data?: unknown; error?: string }>
  post?: (path: string, body: unknown) => Promise<{ success: boolean; data?: unknown; error?: string }>
}

const catalogDoc = deeplinkCatalog as DeeplinkCatalogDocument

function getColorClawService(): ColorClawBridge | undefined {
  return (window as unknown as { colorClawService?: ColorClawBridge }).colorClawService
}

function hashCatalogVersion(payload: string): string {
  // FNV-1a 32-bit: stable + very fast for version reporting.
  let hash = 0x811c9dc5
  for (let i = 0; i < payload.length; i += 1) {
    hash ^= payload.charCodeAt(i)
    hash = (hash * 0x01000193) >>> 0
  }
  return hash.toString(16).padStart(8, '0')
}

export function getLocalDeeplinkCatalogVersion(): string {
  const payload = JSON.stringify(catalogDoc)
  return `desktop-${catalogDoc.catalog_version}-${hashCatalogVersion(payload)}`
}

export async function syncDeeplinkCatalogToService(): Promise<{ ok: boolean; reason?: string }> {
  const bridge = getColorClawService()
  if (!bridge?.post || !bridge?.get) {
    return { ok: false, reason: 'colorClawService unavailable' }
  }

  const sourceVersion = getLocalDeeplinkCatalogVersion()
  const syncResult = await bridge.post('/skills/deeplink-catalog/sync', {
    schema_version: catalogDoc.schema_version,
    catalog_version: catalogDoc.catalog_version,
    source: catalogDoc.source || 'TopoDesktop',
    source_version: sourceVersion,
    items: catalogDoc.items,
  })
  if (!syncResult.success) {
    return { ok: false, reason: syncResult.error || 'sync failed' }
  }

  const statusResult = await bridge.get('/skills/deeplink-catalog/status')
  if (!statusResult.success) {
    return { ok: false, reason: statusResult.error || 'status check failed' }
  }
  return { ok: true }
}

