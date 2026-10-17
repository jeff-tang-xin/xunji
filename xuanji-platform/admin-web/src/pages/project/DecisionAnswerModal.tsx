/**
 * 决策答复 Modal（共享组件）——新内核 DECISION 步的项目区域答复通道。
 *
 * 从 StageExecutionPanel 提取（2026-10-16 右栏收敛「都归于追溯」）：有未处理决策时
 * 左侧对话输入被锁定，这里是唯一解锁通道。指令是 LLM 自由文本，按行防御性解析：
 * 决策标题行加粗、选项行变可点击（点击填编号）、推荐高亮——千字指令一坨纯文本
 * + 只给一个 textarea 的形态没有交互感（实测反馈）。
 */
import { useMemo, useState } from 'react'
import { Button, Input, Modal, Tag, Tooltip, Typography, message } from 'antd'
import { apiJson } from '../../api/client'
import type { StageStepRow } from './types'

/** 决策指令解析后的行：决策标题 / 可点击选项 / 普通文本 */
type DecLine =
  | { kind: 'decision'; label: string; text: string }
  | { kind: 'option'; code: string; text: string; recommended: boolean }
  | { kind: 'plain'; text: string }

export function parseDecisionLines(instruction: string): DecLine[] {
  return (instruction || '').split('\n').map((line): DecLine => {
    const opt = line.match(/^\s*[-•]?\s*([A-Z]\d{1,2})\s*[.、．:：]\s*(.*)$/)
    if (opt) return { kind: 'option', code: opt[1], text: opt[2].replace(/【推荐】|（推荐）|\(推荐\)/g, '').trim(), recommended: line.includes('推荐') }
    const dec = line.match(/^\s*([A-Z])\s*[.、．:：]\s*(.+)$/)
    if (dec) return { kind: 'decision', label: dec[1], text: dec[2] }
    const dec2 = line.match(/^\s*决策\s*(\d+)\s*[.、．:：]?\s*(.*)$/)
    if (dec2) return { kind: 'decision', label: `决策 ${dec2[1]}`, text: dec2[2] }
    return { kind: 'plain', text: line }
  })
}

export function DecisionAnswerModal({ projectId, step, onClose, onAnswered }: {
  projectId: string
  step: StageStepRow | null
  onClose: () => void
  onAnswered: () => void
}) {
  const [answer, setAnswer] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const decisionLines = useMemo(() => parseDecisionLines(step?.instruction || ''), [step])
  const recommendedCodes = useMemo(
    () => decisionLines.filter((l) => l.kind === 'option' && l.recommended).map((l) => (l as { code: string }).code),
    [decisionLines],
  )

  const submit = async () => {
    if (!step || !answer.trim()) return
    setSubmitting(true)
    try {
      await apiJson(`/api/projects/${projectId}/stage/decision/answer`, 'POST', {
        seq: step.seq,
        answer: answer.trim(),
      })
      message.success('决策已答复，主控正在继续推进')
      onClose()
      // 续跑由后端 answer 端点直接唤醒主控（答复内容进唤醒消息），前端经事件总线实时回显
      onAnswered()
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      open={!!step}
      title={`答复决策 #${step?.seq ?? ''}`}
      okText="提交答复"
      okButtonProps={{ loading: submitting, disabled: !answer.trim() }}
      onOk={() => void submit()}
      onCancel={onClose}
      width={640}
    >
      <Tooltip title={step?.name}>
        <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8, fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {step?.name}
        </Typography.Text>
      </Tooltip>
      {recommendedCodes.length > 0 && (
        <Button
          size="small"
          type="link"
          style={{ padding: 0, marginBottom: 4 }}
          onClick={() => setAnswer(recommendedCodes.join(' '))}
        >
          采纳全部推荐（{recommendedCodes.join(' ')}）
        </Button>
      )}
      <div style={{ maxHeight: 320, overflowY: 'auto', border: '1px solid #f0f0f0', borderRadius: 6, padding: '8px 10px', marginBottom: 12 }}>
        {decisionLines.map((ln, i) => {
          if (ln.kind === 'decision') {
            return (
              <div key={i} style={{ fontWeight: 600, fontSize: 13, marginTop: i === 0 ? 0 : 10 }}>
                {ln.label}. {ln.text}
              </div>
            )
          }
          if (ln.kind === 'option') {
            return (
              <Button
                key={i}
                block
                type="text"
                size="small"
                style={{ textAlign: 'left', padding: '3px 8px', height: 'auto', whiteSpace: 'normal' }}
                title={`点击填入 ${ln.code}`}
                onClick={() => setAnswer((prev) => (prev.trim() ? `${prev.trim()} ${ln.code}` : ln.code))}
              >
                <Tag style={{ marginInlineEnd: 6 }}>{ln.code}</Tag>
                <span style={{ fontSize: 12 }}>{ln.text}</span>
                {ln.recommended && <Tag color="orange" style={{ marginInlineStart: 6 }}>推荐</Tag>}
              </Button>
            )
          }
          if (!ln.text.trim()) return null
          return (
            <div key={i} style={{ fontSize: 13, lineHeight: 1.7 }}>{ln.text}</div>
          )
        })}
      </div>
      <Input.TextArea
        rows={3}
        value={answer}
        onChange={(e) => setAnswer(e.target.value)}
        placeholder="点上方选项快速填入编号（如 A1 B1 C1 D1），也可直接输入文字；全部采纳推荐可点「采纳全部推荐」。主控会按答复继续推进，答复留档可追溯"
      />
    </Modal>
  )
}
