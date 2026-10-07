/// <reference types="vitest/config" />
import { readFileSync } from 'node:fs'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// During development the app talks to the real monitor through this proxy.
// Set DEVICE_URL in web/.env.local (git-ignored), e.g. DEVICE_URL=http://solar.local
export default defineConfig(({ mode }) => {
  const version = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).version
  const env = loadEnv(mode, import.meta.dirname, '')
  const device = env.DEVICE_URL || 'http://solar.local'
  const proxy = { target: device, changeOrigin: true }
  return {
    define: { __APP_VERSION__: JSON.stringify(version) },
    plugins: [react(), tailwindcss()],
    base: '/',
    build: {
      target: 'es2022',
      assetsInlineLimit: 0,
      cssCodeSplit: false,
      // Short, flat file names: the ESP32's flash file system has a 64-character path limit.
      rollupOptions: {
        output: {
          entryFileNames: 'assets/[name]-[hash:8].js',
          chunkFileNames: 'assets/[name]-[hash:8].js',
          assetFileNames: 'assets/[name]-[hash:8][extname]',
        },
      },
    },
    server: {
      port: 5173,
      proxy: { '/api': proxy, '/events': proxy, '/raw': proxy, '/update': proxy, '/classic': proxy },
    },
    test: { environment: 'node' },
  }
})
