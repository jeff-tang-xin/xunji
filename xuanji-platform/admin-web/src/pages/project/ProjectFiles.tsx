import { useCallback, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { Button, Empty, Spin, Tag, Typography, message } from 'antd'
import { FileTextOutlined, FolderOutlined, ReloadOutlined } from '@ant-design/icons'
import { apiJson } from '../../api/client'

/**
 * 项目文件（产出可见）：项目目录（root_path）文件树 + 文件内容在线查看。
 * 产出不再只是对话碎片——代码/文档落盘即在这里可见；点文件拉内容（≤200KB 文本）。
 */

interface FileEntry {
  path: string
  type: 'dir' | 'file'
  size?: number
}

interface FileNode {
  title: ReactNode
  key: string
  isLeaf?: boolean
  children?: FileNode[]
}

function fmtSize(n?: number): string {
  if (n == null) return ''
  if (n < 1024) return `${n}B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}KB`
  return `${(n / 1024 / 1024).toFixed(1)}MB`
}

function extTag(path: string): { label: string; color: string } | null {
  const ext = path.slice(path.lastIndexOf('.') + 1).toLowerCase()
  const map: Record<string, { label: string; color: string }> = {
    md: { label: '文档', color: 'blue' },
    java: { label: 'Java', color: 'orange' },
    ts: { label: 'TS', color: 'geekblue' },
    tsx: { label: 'TSX', color: 'geekblue' },
    sql: { label: 'SQL', color: 'purple' },
    sh: { label: 'Shell', color: 'green' },
    json: { label: 'JSON', color: 'cyan' },
    xml: { label: 'XML', color: 'gold' },
    yml: { label: 'YML', color: 'volcano' },
    yaml: { label: 'YAML', color: 'volcano' },
    py: { label: 'Python', color: 'blue' },
  }
  return map[ext] ?? null
}

export default function ProjectFiles({ projectId }: { projectId: string }) {
  const [entries, setEntries] = useState<FileEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [content, setContent] = useState<string>('')
  const [fileLoading, setFileLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await apiJson<FileEntry[]>(`/api/projects/${projectId}/files`, 'GET')
      setEntries(Array.isArray(data) ? data : [])
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setLoading(false)
    }
  }, [projectId])

  useEffect(() => {
    void load()
  }, [load])

  const openFile = async (path: string) => {
    setSelected(path)
    setFileLoading(true)
    try {
      const data = await apiJson<{ content: string }>(
        `/api/projects/${projectId}/files/content?path=${encodeURIComponent(path)}`, 'GET',
      )
      setContent(data?.content ?? '')
    } catch (e) {
      setContent('')
      if (e instanceof Error) message.error(e.message)
    } finally {
      setFileLoading(false)
    }
  }

  /** 平铺 entries → 树：按路径逐级建目录节点，文件挂到所属目录下 */
  const tree = (): FileNode[] => {
    const root: FileNode[] = []
    const dirIndex = new Map<string, FileNode[]>()
    const ensureDir = (segments: string[]): FileNode[] => {
      if (segments.length === 0) return root
      const parent = ensureDir(segments.slice(0, -1))
      const full = segments.join('/')
      let list = dirIndex.get(full)
      if (!list) {
        const node: FileNode = {
          key: `dir:${full}`,
          title: (
            <span><FolderOutlined style={{ color: '#faad14', marginRight: 4 }} />{segments[segments.length - 1]}</span>
          ),
          children: [],
        }
        list = node.children as FileNode[]
        dirIndex.set(full, list)
        parent.push(node)
      }
      return list
    }
    for (const e of entries) {
      const segs = e.path.split('/')
      if (e.type === 'dir') {
        ensureDir(segs)
        continue
      }
      const parent = ensureDir(segs.slice(0, -1))
      const tag = extTag(e.path)
      parent.push({
        key: e.path,
        isLeaf: true,
        title: (
          <span>
            <FileTextOutlined style={{ marginRight: 4 }} />
            {segs[segs.length - 1]}
            {tag && <Tag style={{ marginLeft: 6 }} color={tag.color}>{tag.label}</Tag>}
            <Typography.Text type="secondary" style={{ marginLeft: 6, fontSize: 12 }}>{fmtSize(e.size)}</Typography.Text>
          </span>
        ),
      })
    }
    return root
  }

  const nodes = tree()

  return (
    <div>
      <div style={{ marginBottom: 8, textAlign: 'right' }}>
        <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </div>
      {loading
        ? <div style={{ padding: 40, textAlign: 'center' }}><Spin /></div>
        : entries.length === 0
          ? <Empty description="项目目录为空" />
          : (
            <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
              <div style={{ width: 260, flexShrink: 0, maxHeight: 480, overflow: 'auto', border: '1px solid #f0f0f0', borderRadius: 6, padding: 8 }}>
                {nodes.map((n) => (
                  <div key={n.key}>
                    {n.title}
                    {n.children && n.children.map((c) => (
                      <div key={c.key} style={{ paddingLeft: 18, cursor: c.isLeaf ? 'pointer' : 'default' }}
                        onClick={() => c.isLeaf && void openFile(c.key)}>
                        {c.title}
                      </div>
                    ))}
                  </div>
                ))}
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                {selected == null
                  ? <Empty description="点左侧文件查看内容" />
                  : (
                    <>
                      <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
                        <FileTextOutlined /> {selected}
                      </Typography.Text>
                      {fileLoading
                        ? <Spin />
                        : (
                          <pre style={{
                            margin: 0, maxHeight: 440, overflow: 'auto', fontSize: 12,
                            background: '#fafafa', border: '1px solid #f0f0f0', borderRadius: 6, padding: 12,
                            whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                          }}>
                            {content}
                          </pre>
                        )}
                    </>
                  )}
              </div>
            </div>
          )}
    </div>
  )
}
