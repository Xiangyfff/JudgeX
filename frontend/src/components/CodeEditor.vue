<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { highlight } from '../lib/highlight'

const props = defineProps<{
  modelValue: string
  language: string
  readonly?: boolean
}>()

const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const ta = ref<HTMLTextAreaElement | null>(null)
const pre = ref<HTMLElement | null>(null)
const gutter = ref<HTMLElement | null>(null)

const html = computed(() => highlight(props.modelValue ?? '', props.language) + '\n')
const lineCount = computed(() => Math.max(1, (props.modelValue ?? '').split('\n').length))

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLTextAreaElement).value)
}

function syncScroll() {
  const el = ta.value
  if (!el) return
  if (pre.value) {
    pre.value.scrollTop = el.scrollTop
    pre.value.scrollLeft = el.scrollLeft
  }
  if (gutter.value) gutter.value.scrollTop = el.scrollTop
}

function insertText(text: string) {
  const el = ta.value
  if (!el || props.readonly) return
  const start = el.selectionStart
  const end = el.selectionEnd
  const value = el.value
  emit('update:modelValue', value.slice(0, start) + text + value.slice(end))
  nextTick(() => {
    el.selectionStart = el.selectionEnd = start + text.length
    syncScroll()
  })
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Tab') {
    e.preventDefault()
    insertText('  ')
  }
}

defineExpose({ focus: () => ta.value?.focus() })
</script>

<template>
  <div class="editor">
    <div ref="gutter" class="gutter" aria-hidden="true">
      <div v-for="n in lineCount" :key="n">{{ n }}</div>
    </div>
    <div class="cwrap">
      <pre ref="pre" class="layer hl" aria-hidden="true"><code v-html="html"></code></pre>
      <textarea
        ref="ta"
        class="layer input-area"
        :value="modelValue"
        :readonly="readonly"
        spellcheck="false"
        autocomplete="off"
        autocapitalize="off"
        wrap="off"
        @input="onInput"
        @scroll="syncScroll"
        @keydown="onKeydown"
      ></textarea>
    </div>
  </div>
</template>

<style scoped>
.editor {
  display: flex;
  height: 100%;
  min-height: 0;
  background: #04070d;
  font-family: var(--mono);
  font-size: 12.5px;
  line-height: 1.65;
}

.gutter {
  flex: none;
  width: 46px;
  padding: 12px 9px 12px 0;
  text-align: right;
  color: #3b475c;
  overflow: hidden;
  user-select: none;
  border-right: 1px solid rgba(148, 163, 184, 0.08);
  background: rgba(8, 12, 20, 0.6);
}

.cwrap {
  position: relative;
  flex: 1;
  min-width: 0;
}

.layer {
  position: absolute;
  inset: 0;
  margin: 0;
  padding: 12px 14px;
  font-family: var(--mono);
  font-size: 12.5px;
  line-height: 1.65;
  white-space: pre;
  overflow: auto;
  tab-size: 2;
  border: 0;
}

.hl {
  pointer-events: none;
  color: #c6d2e6;
}

.hl :deep(.tk-kw) {
  color: #c084fc;
}
.hl :deep(.tk-str) {
  color: #6ee7b7;
}
.hl :deep(.tk-com) {
  color: #4b5b74;
  font-style: italic;
}
.hl :deep(.tk-num) {
  color: #fbbf24;
}

.input-area {
  background: transparent;
  color: transparent;
  caret-color: #22d3ee;
  resize: none;
  outline: none;
  white-space: pre;
}

.input-area::selection {
  background: rgba(34, 211, 238, 0.28);
}
</style>
