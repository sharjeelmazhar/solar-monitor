// Uploads the built web app (web/dist) to the monitor's flash over Wi-Fi.
// Usage: npm run deploy    (reads DEVICE_URL and ADMIN_PASS from web/.env.local, which is git-ignored)
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join, relative } from 'node:path'
import { gzipSync, constants } from 'node:zlib'

const root = join(import.meta.dirname, '..')
const env = Object.fromEntries(
  (existsSync(join(root, '.env.local')) ? readFileSync(join(root, '.env.local'), 'utf8') : '')
    .split(/\r?\n/).map((l) => l.match(/^\s*([A-Z_]+)\s*=\s*(.*)\s*$/)).filter(Boolean).map((m) => [m[1], m[2]]),
)
const device = (process.env.DEVICE_URL || env.DEVICE_URL || 'http://solar.local').replace(/\/$/, '')
const pass = process.env.ADMIN_PASS || env.ADMIN_PASS
if (!pass) {
  console.error('Set ADMIN_PASS (the password from firmware secrets.h) in web/.env.local')
  process.exit(1)
}
const auth = 'Basic ' + Buffer.from('admin:' + pass).toString('base64')
const dist = join(root, 'dist')

// collect files: compressible ones are stored gzipped (the ESP serves *.gz transparently)
const files = []
const walk = (dir) => {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name)
    if (e.isDirectory()) walk(p)
    else {
      const rel = relative(dist, p).replaceAll('\\', '/')
      const raw = readFileSync(p)
      const zip = /\.(js|css|html|json|map)$/.test(rel)
      if (rel.endsWith('.map')) continue
      files.push({ path: zip ? rel + '.gz' : rel, body: zip ? gzipSync(raw, { level: constants.Z_BEST_COMPRESSION }) : raw })
    }
  }
}
walk(dist)
for (const f of files) {
  if (f.path.length > 48 || (f.path.includes('/') && !f.path.startsWith('assets/'))) throw new Error('path not allowed on device: ' + f.path)
}
const index = files.find((f) => f.path === 'index.html.gz')
if (!index) throw new Error('dist/index.html missing - run the build first')

const total = files.reduce((a, f) => a + f.body.length, 0)
console.log(`Deploying ${files.length} files (${(total / 1024).toFixed(0)} KB) to ${device}`)

const existing = await (await fetch(device + '/api/www')).json()
async function upload(f) {
  const fd = new FormData()
  fd.append('f', new Blob([f.body]), f.path.split('/').pop())
  const r = await fetch(`${device}/api/www?path=${encodeURIComponent(f.path)}`, { method: 'POST', headers: { Authorization: auth }, body: fd })
  const j = r.ok ? await r.json() : null
  if (!j?.ok) throw new Error(`upload failed for ${f.path}: HTTP ${r.status}`)
  console.log(`  ✓ ${f.path} (${(f.body.length / 1024).toFixed(1)} KB)`)
}
// assets first, index last: old pages keep working until the new index points at the new files
for (const f of files.filter((f) => f !== index)) {
  if (f.path.startsWith('assets/') && existing.includes(f.path)) { console.log(`  = ${f.path} (unchanged)`); continue }
  await upload(f)
}
await upload(index)
for (const old of existing) {
  if (!files.some((f) => f.path === old)) {
    await fetch(`${device}/api/www/delete?path=${encodeURIComponent(old)}`, { method: 'POST', headers: { Authorization: auth } })
    console.log(`  − removed ${old}`)
  }
}
console.log('Done. Open ' + device + '/')
