import { useState } from 'react'
import {
  Button,
  Card,
  Drawer,
  Empty,
  Space,
  Tag,
  Typography,
} from 'antd'
import {
  PlayCircleOutlined,
  CheckCircleOutlined,
  FileTextOutlined,
} from '@ant-design/icons'
import { WorkNode, Artifact, NODE_TYPE_LABEL, nodeStatusView, parseList } from './types'

export function NodeDrawer({ node, nodes, artifacts, onClose, onRun, onComplete }: {
  node: WorkNode | null
  nodes: WorkNode[]
  artifacts: Artifact[]
  onClose: () => void
  onRun: (n: WorkNode) => void
  onComplete: (n: WorkNode) => void
}) {
  const [busy, setBusy] = useState(false)
  if (!node) return null
  const sv = nodeStatusView(node.status)
  const deps = parseList(node.depends_on)
  const pres = parseList(node.preconditions)
  const outputs = parseList(node.output_refs)
  const nodeArtifacts = artifacts.filter((a) => a.node_id === node.id)
  // 节点 id → 可读名称（ws_xxx_n3 → 详设（4）），依赖/前置条件不再显示原始 id
  const nodeName = (id: string) => {
    const n = nodes.find((x) => x.id === id)
    return n ? `${NODE_TYPE_LABEL[n.type] || n.type}（${n.seq}）` : id
  }

  const run = async () => {
    setBusy(true)
    try {
      await onRun(node)
    } finally {
      setBusy(false)
    }
  }
  const complete = async () => {
    setBusy(true)
    try {
      await onComplete(node)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Drawer
      title={`${NODE_TYPE_LABEL[node.type] || node.type} · 节点 ${node.seq}`}
      open={!!node}
      onClose={onClose}
      width={420}
      extra={
        <Space>
          <Button
            size="small"
            icon={<PlayCircleOutlined />}
            type="primary"
            loading={busy}
            onClick={() => void run()}
            disabled={node.status === 'DONE' || node.status === 'RUNNING'}
          >
            执行
          </Button>
          <Button
            size="small"
            icon={<CheckCircleOutlined />}
            loading={busy}
            onClick={() => void complete()}
            disabled={node.status === 'DONE'}
          >
            标记完成
          </Button>
        </Space>
      }
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <div>
          <Typography.Text type="secondary">状态：</Typography.Text>
          <Tag color={sv.color}>{sv.text}</Tag>
          <Typography.Text type="secondary">职能：</Typography.Text>
          <Tag>{node.owner_role || '—'}</Tag>
        </div>

        <div>
          <Typography.Text strong>依赖节点</Typography.Text>
          <div style={{ marginTop: 4 }}>
            {deps.length === 0
              ? <Typography.Text type="secondary">无（起始节点）</Typography.Text>
              : deps.map((d) => <Tag key={d} color="blue">{nodeName(d)}</Tag>)}
          </div>
        </div>

        <div>
          <Typography.Text strong>前置条件</Typography.Text>
          <div style={{ marginTop: 4 }}>
            {pres.length === 0
              ? <Typography.Text type="secondary">无</Typography.Text>
              : pres.map((p) => <Tag key={p} color="orange">{p}</Tag>)}
          </div>
        </div>

        <div>
          <Typography.Text strong>产出留档</Typography.Text>
          <div style={{ marginTop: 4 }}>
            {outputs.length === 0
              ? <Typography.Text type="secondary">暂无产出</Typography.Text>
              : outputs.map((o) => <Tag key={o} color="green">{o}</Tag>)}
          </div>
        </div>

        <div>
          <Typography.Text strong>节点留档（{nodeArtifacts.length}）</Typography.Text>
          <div style={{ marginTop: 4 }}>
            {nodeArtifacts.length === 0
              ? <Empty description="暂无留档" imageStyle={{ height: 40 }} />
              : nodeArtifacts.map((a) => (
                <Card key={a.id} size="small" style={{ marginBottom: 8 }}>
                  <Space direction="vertical" size={2} style={{ width: '100%' }}>
                    <Space>
                      <FileTextOutlined />
                      <Typography.Text strong>{a.name}</Typography.Text>
                      <Tag color={a.kind === 'DELIVERABLE' ? 'green' : 'default'}>
                        {a.kind === 'DELIVERABLE' ? '交付' : '过程'}
                      </Tag>
                      {a.version != null && <Tag>v{a.version}</Tag>}
                    </Space>
                    {a.content_md && (
                      <Typography.Paragraph
                        type="secondary"
                        style={{ fontSize: 12, marginBottom: 0 }}
                        ellipsis={{ rows: 2 }}
                      >
                        {a.content_md}
                      </Typography.Paragraph>
                    )}
                  </Space>
                </Card>
              ))}
          </div>
        </div>
      </Space>
    </Drawer>
  )
}

/** 项目详情：阶段链 + 留档 + 节点操作 */
/** 节点产出展示：output_json 是 jsonb 文本——JSON 包装则提取常见文本字段后按 markdown 渲染，
 *  整体就是 JSON（如 start 节点的规划数据）则格式化展示；滚动由外层容器管（scroll=false）。 */

export function NodeChain({ nodes, onNodeClick }: {
  nodes: WorkNode[]
  onNodeClick: (n: WorkNode) => void
}) {
  if (nodes.length === 0) {
    return <Empty description="暂无工作节点" style={{ padding: 24 }} />
  }
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, overflowX: 'auto', padding: '8px 4px' }}>
      {nodes.map((n, i) => {
        const sv = nodeStatusView(n.status)
        return (
          <div key={n.id} style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
            {i > 0 && <span style={{ color: '#bbb' }}>→</span>}
            <div
              onClick={() => onNodeClick(n)}
              style={{
                border: `1px solid ${sv.color === 'default' ? '#d9d9d9' : sv.color}`,
                borderRadius: 8,
                padding: '8px 12px',
                minWidth: 96,
                textAlign: 'center',
                cursor: 'pointer',
                background: sv.color === 'default' ? '#fafafa' : '#fff',
                transition: 'box-shadow 0.2s',
              }}
              onMouseEnter={(e) => { e.currentTarget.style.boxShadow = '0 2px 8px rgba(0,0,0,0.12)' }}
              onMouseLeave={(e) => { e.currentTarget.style.boxShadow = 'none' }}
            >
              <div style={{ fontWeight: 600, fontSize: 13 }}>
                {NODE_TYPE_LABEL[n.type] || n.type}
              </div>
              <div style={{ fontSize: 11, color: '#999' }}>{n.seq}</div>
              <Tag color={sv.color} style={{ marginTop: 4 }}>{sv.text}</Tag>
              {n.owner_role && (
                <div style={{ fontSize: 11, color: '#999', marginTop: 2 }}>{n.owner_role}</div>
              )}
            </div>
          </div>
        )
      })}
    </div>
  )
}


/**
 * 生命周期总览（V27 口径 + AI 自动流转理念）。
 *
 * <p>设计理念：AI 推动整个流程，用户只是介入——页面展示「AI 推进到哪了」，
 * 需要用户介入的点（批准规划 / 决策门 / 澄清对话）突出出来。阶段内容不再读
 * project_work_node 台账（V27 起规划不落台账，新项目 0 节点，读它必然与阶段执行
 * 对不上）：当前阶段读 exec（规划子任务 + run 进度），历史阶段读交付物留档计数。</p>
 */
