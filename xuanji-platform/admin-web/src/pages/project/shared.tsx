import type { CSSProperties, ReactNode } from 'react'
import { Form, Typography } from 'antd'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

export function OutputBlock({ raw }: { raw?: string | null }) {
  if (!raw) return <Typography.Text type="secondary">（无产出内容）</Typography.Text>
  let text = raw
  let isJson = false
  try {
    const o = JSON.parse(raw) as Record<string, unknown>
    let found = false
    for (const k of ['result', 'content', 'output', 'text', 'summary']) {
      const v = o[k]
      if (typeof v === 'string' && v.trim()) {
        text = v
        found = true
        break
      }
    }
    if (!found) isJson = true
  } catch {
    /* 非 JSON，按 markdown 展示 */
  }
  return (
    <div style={{ maxHeight: 420, overflow: 'auto' }}>
      {isJson ? <MarkdownView json scroll={false} text={raw} /> : <MarkdownView scroll={false} text={text} />}
    </div>
  )
}

export function MarkdownView({ text, json = false, scroll = true }: { text?: string; json?: boolean; scroll?: boolean }) {
  const raw = (text || '').trim()
  if (!raw) return <Typography.Text type="secondary">（无内容）</Typography.Text>
  const boxStyle: CSSProperties = scroll
    ? { maxHeight: '60vh', overflow: 'auto' }
    : {}
  if (json && (raw.startsWith('{') || raw.startsWith('['))) {
    try {
      const pretty = JSON.stringify(JSON.parse(raw), null, 2)
      return (
        <pre style={{ margin: 0, padding: 10, background: '#f6f8fa', borderRadius: 6, fontSize: 12, lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word', ...boxStyle }}>
          {pretty}
        </pre>
      )
    } catch {
      /* 不是合法 JSON，按 markdown 渲染 */
    }
  }
  return (
    <div style={{ fontSize: 13, lineHeight: 1.7, wordBreak: 'break-word', overflowX: 'auto', ...boxStyle }}>
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{raw}</ReactMarkdown>
    </div>
  )
}



export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <Form.Item label={label} style={{ marginBottom: 12 }}>
      {children}
      {hint && (
        <Typography.Text type='secondary' style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
          {hint}
        </Typography.Text>
      )}
    </Form.Item>
  )
}

