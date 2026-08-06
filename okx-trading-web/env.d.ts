/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

declare module 'qrcode' {
  const QRCode: {
    toDataURL: (text: string, opts?: Record<string, unknown>) => Promise<string>
  }
  export default QRCode
}
