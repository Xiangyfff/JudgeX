<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppShell from '../components/AppShell.vue'
import { api, type ProblemDetail, type ProblemSummary, type TestCaseView } from '../lib/api'
import { toast } from '../lib/toast'

const problems = ref<ProblemSummary[]>([])
const loading = ref(true)
const saving = ref(false)
const selectedId = ref<number | null>(null)

interface FormState {
  slug: string
  title: string
  difficulty: string
  tags: string
  statement: string
  timeLimitMs: number
  memoryLimitMb: number
  published: boolean
}

const form = reactive<FormState>({
  slug: '',
  title: '',
  difficulty: 'EASY',
  tags: '',
  statement: '',
  timeLimitMs: 2000,
  memoryLimitMb: 256,
  published: true
})

const cases = ref<TestCaseView[]>([])
const isNew = computed(() => selectedId.value === null)

async function load() {
  loading.value = true
  try {
    problems.value = await api.problems()
    if (!selectedId.value && problems.value.length) {
      await select(problems.value[0].id)
    }
  } catch (e) {
    toast(e instanceof Error ? e.message : '加载失败', 'err')
  } finally {
    loading.value = false
  }
}

function applyDetail(detail: ProblemDetail) {
  selectedId.value = detail.summary.id
  form.slug = detail.summary.slug
  form.title = detail.summary.title
  form.difficulty = detail.summary.difficulty
  form.tags = detail.summary.tags ?? ''
  form.statement = detail.statement ?? ''
  form.timeLimitMs = detail.summary.timeLimitMs
  form.memoryLimitMb = detail.summary.memoryLimitMb
  form.published = detail.summary.published
  cases.value = detail.cases.map((c) => ({ ...c }))
}

async function select(id: number) {
  try {
    applyDetail(await api.problem(id))
  } catch (e) {
    toast(e instanceof Error ? e.message : '加载题目失败', 'err')
  }
}

function startNew() {
  selectedId.value = null
  form.slug = ''
  form.title = ''
  form.difficulty = 'EASY'
  form.tags = ''
  form.statement = '## 题目描述\n\n在这里写题面。\n\n## 输入格式\n\n\n## 输出格式\n\n'
  form.timeLimitMs = 2000
  form.memoryLimitMb = 256
  form.published = true
  cases.value = [
    { id: 0, ordinal: 1, input: '', expectedOutput: '', points: 10, hidden: false }
  ]
}

function addCase() {
  cases.value.push({
    id: 0,
    ordinal: cases.value.length + 1,
    input: '',
    expectedOutput: '',
    points: 10,
    hidden: true
  })
}

function removeCase(i: number) {
  cases.value.splice(i, 1)
}

async function save() {
  if (!form.title.trim() || !form.slug.trim()) {
    toast('标题和 slug 不能为空', 'err')
    return
  }
  saving.value = true
  try {
    const payload = {
      slug: form.slug.trim(),
      title: form.title.trim(),
      difficulty: form.difficulty,
      tags: form.tags,
      statement: form.statement,
      timeLimitMs: Number(form.timeLimitMs) || 2000,
      memoryLimitMb: Number(form.memoryLimitMb) || 256,
      published: form.published
    }
    const saved = await api.saveProblem(payload, selectedId.value ?? undefined)
    const id = saved.summary.id
    const normalized = cases.value.map((c, i) => ({
      ordinal: i + 1,
      input: c.input,
      expectedOutput: c.expectedOutput,
      points: Number(c.points) || 10,
      hidden: c.hidden
    }))
    const withCases = await api.replaceCases(id, normalized)
    applyDetail(withCases)
    toast('已保存', 'ok')
    problems.value = await api.problems()
  } catch (e) {
    toast(e instanceof Error ? e.message : '保存失败', 'err')
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (selectedId.value === null) return
  if (!window.confirm(`确认删除题目「${form.title}」？该操作不可撤销。`)) return
  try {
    await api.deleteProblem(selectedId.value)
    toast('已删除', 'ok')
    selectedId.value = null
    await load()
    if (problems.value.length) await select(problems.value[0].id)
  } catch (e) {
    toast(e instanceof Error ? e.message : '删除失败', 'err')
  }
}

const totalPoints = computed(() => cases.value.reduce((s, c) => s + (Number(c.points) || 0), 0))

onMounted(load)
</script>

<template>
  <AppShell title="题库管理" section="JudgeX">
    <div class="head rise">
      <div>
        <div class="eyebrow">admin</div>
        <h1>题库管理</h1>
        <p>维护题面与测试点。测试点直接决定判题结果，改动会立即生效。</p>
      </div>
      <button class="btn btn-primary" @click="startNew">+ 新建题目</button>
    </div>

    <div v-if="loading" class="empty"><i class="spinner" style="margin: 0 auto"></i></div>

    <div v-else class="layout">
      <aside class="list card">
        <div class="list-head mono">PROBLEMS · {{ problems.length }}</div>
        <div
          v-for="p in problems"
          :key="p.id"
          class="list-item"
          :class="{ on: selectedId === p.id }"
          @click="select(p.id)"
        >
          <span class="mono idx">{{ p.id }}</span>
          <div class="li-main">
            <b>{{ p.title }}</b>
            <span class="mono dim">/{{ p.slug }}</span>
          </div>
          <span class="pill" :class="p.published ? 'pill-ac' : 'pill-sys'">
            {{ p.published ? 'ON' : 'OFF' }}
          </span>
        </div>
        <div v-if="selectedId === null" class="list-item on">
          <span class="mono idx">+</span>
          <div class="li-main"><b>新题目</b><span class="mono dim">unsaved</span></div>
        </div>
      </aside>

      <section class="editor card">
        <div class="ed-head">
          <div class="mono dim">{{ isNew ? 'NEW PROBLEM' : `PROBLEM #${selectedId}` }}</div>
          <div class="ed-actions">
            <button class="btn btn-sm btn-ghost" :disabled="saving" @click="remove" v-if="!isNew">删除</button>
            <button class="btn btn-sm btn-primary" :disabled="saving" @click="save">
              <i v-if="saving" class="spinner" style="width: 12px; height: 12px"></i>
              {{ saving ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>

        <div class="ed-body">
          <div class="grid3">
            <label class="field span2">
              <span>标题</span>
              <input v-model="form.title" class="input" placeholder="A + B" />
            </label>
            <label class="field">
              <span>slug</span>
              <input v-model="form.slug" class="input mono" placeholder="a-plus-b" />
            </label>
          </div>

          <div class="grid4">
            <label class="field">
              <span>难度</span>
              <select v-model="form.difficulty" class="input">
                <option value="EASY">简单</option>
                <option value="MEDIUM">中等</option>
                <option value="HARD">困难</option>
              </select>
            </label>
            <label class="field">
              <span>时间限制 ms</span>
              <input v-model.number="form.timeLimitMs" class="input mono" type="number" />
            </label>
            <label class="field">
              <span>内存限制 MB</span>
              <input v-model.number="form.memoryLimitMb" class="input mono" type="number" />
            </label>
            <label class="field">
              <span>状态</span>
              <select v-model="form.published" class="input">
                <option :value="true">已发布</option>
                <option :value="false">草稿</option>
              </select>
            </label>
          </div>

          <label class="field">
            <span>标签（逗号分隔）</span>
            <input v-model="form.tags" class="input mono" placeholder="array,math" />
          </label>

          <label class="field">
            <span>题面（Markdown）</span>
            <textarea v-model="form.statement" class="input statement" rows="14"></textarea>
          </label>

          <div class="cases-head">
            <div class="card-title">测试点</div>
            <div class="mono dim">{{ cases.length }} 个 · 共 {{ totalPoints }} 分</div>
          </div>

          <div class="case-table">
            <div class="ct-row ct-head">
              <span>#</span>
              <span>输入</span>
              <span>期望输出</span>
              <span>分值</span>
              <span>隐藏</span>
              <span></span>
            </div>
            <div v-for="(c, i) in cases" :key="i" class="ct-row">
              <span class="mono dim">{{ i + 1 }}</span>
              <textarea v-model="c.input" class="input cell" rows="2" placeholder="1 2"></textarea>
              <textarea v-model="c.expectedOutput" class="input cell" rows="2" placeholder="3"></textarea>
              <input v-model.number="c.points" class="input cell mono" type="number" />
              <input v-model="c.hidden" type="checkbox" class="cb" />
              <button class="btn btn-sm btn-ghost del" @click="removeCase(i)">×</button>
            </div>
          </div>

          <button class="btn btn-sm" @click="addCase">+ 添加测试点</button>
        </div>
      </section>
    </div>
  </AppShell>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 20px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}
h1 {
  font-size: 26px;
  margin: 4px 0 6px;
  letter-spacing: -0.02em;
}
.head p {
  color: var(--mute);
  margin: 0;
  font-size: 13px;
}

.layout {
  display: grid;
  grid-template-columns: 268px 1fr;
  gap: 14px;
  align-items: start;
}

.list {
  overflow: hidden;
  position: sticky;
  top: 0;
}
.list-head {
  padding: 12px 15px;
  font-size: 10px;
  letter-spacing: 0.16em;
  color: var(--mute);
  border-bottom: 1px solid var(--border);
}
.list-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 15px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.06);
  cursor: pointer;
  transition: background 0.14s;
}
.list-item:hover {
  background: rgba(148, 163, 184, 0.05);
}
.list-item.on {
  background: rgba(34, 211, 238, 0.08);
  box-shadow: inset 2px 0 0 var(--cyan);
}
.idx {
  font-size: 10.5px;
  color: #4b5b74;
  width: 16px;
}
.li-main {
  flex: 1;
  min-width: 0;
}
.li-main b {
  display: block;
  font-size: 12.5px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.li-main span {
  font-size: 10px;
}
.dim {
  color: #4b5b74;
}

.ed-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 13px 18px;
  border-bottom: 1px solid var(--border);
  font-size: 10.5px;
  letter-spacing: 0.1em;
}
.ed-actions {
  display: flex;
  gap: 8px;
}
.ed-body {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.grid3 {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr;
  gap: 12px;
}
.span2 {
  grid-column: span 2;
}
.grid4 {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.statement {
  font-family: var(--mono);
  font-size: 12.5px;
}

.cases-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 6px;
  font-size: 11px;
}
.case-table {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
}
.ct-row {
  display: grid;
  grid-template-columns: 34px 1.4fr 1.2fr 78px 52px 40px;
  gap: 10px;
  padding: 9px 12px;
  align-items: center;
  border-bottom: 1px solid rgba(148, 163, 184, 0.07);
}
.ct-head {
  font-size: 10px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--mute);
  background: rgba(148, 163, 184, 0.04);
}
.cell {
  height: 34px;
  padding: 6px 9px;
  font-size: 12px;
  resize: vertical;
}
.cb {
  width: 15px;
  height: 15px;
  accent-color: #22d3ee;
  cursor: pointer;
}
.del {
  width: 30px;
  padding: 0;
  color: #fca5a5;
}

@media (max-width: 1100px) {
  .layout {
    grid-template-columns: 1fr;
  }
  .grid3,
  .grid4 {
    grid-template-columns: 1fr 1fr;
  }
  .span2 {
    grid-column: span 2;
  }
}
</style>
