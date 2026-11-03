import { reactive } from 'vue'

export interface ToastItem {
  id: number
  text: string
  kind: 'ok' | 'err' | 'info'
}

let seq = 1
export const toasts = reactive<ToastItem[]>([])

export function toast(text: string, kind: 'ok' | 'err' | 'info' = 'info', ms = 3200) {
  const item: ToastItem = { id: seq++, text, kind }
  toasts.push(item)
  setTimeout(() => {
    const i = toasts.findIndex((t) => t.id === item.id)
    if (i >= 0) toasts.splice(i, 1)
  }, ms)
}
