// Moving the monitor to another Wi-Fi from inside the app (same logic as Android's data/WifiSetup.kt).
export interface WifiNet { ssid: string; rssi: number; open: boolean }

export const signalOf = (rssi: number) => (rssi > -60 ? 'strong' : rssi > -72 ? 'good' : 'weak')

/** Result of GET /api/scan: null while the monitor is still scanning; strongest first, one row per name, hidden names dropped. */
export function parseScan(j: { scanning?: boolean; nets?: WifiNet[] }): WifiNet[] | null {
  if (j.scanning) return null
  const seen = new Set<string>()
  return [...(j.nets ?? [])].sort((a, b) => b.rssi - a.rssi).filter((n) => n.ssid && !seen.has(n.ssid) && !!seen.add(n.ssid))
}

const bytes = (s: string) => new TextEncoder().encode(s).length

/** What is wrong with the form, or null when it can be sent. A network not in the list may still be open, so an empty password is allowed for it. */
export function formError(ssid: string, pass: string, open: boolean, admin: string): string | null {
  if (!ssid) return 'Pick a Wi-Fi network'
  if (bytes(ssid) > 32) return 'That Wi-Fi name is too long'
  if (bytes(pass) > 63) return 'That Wi-Fi password is too long'
  if (!open && pass && pass.length < 8) return 'Wi-Fi passwords have at least 8 characters'
  if (!admin) return "Enter the monitor's admin password"
  return null
}
