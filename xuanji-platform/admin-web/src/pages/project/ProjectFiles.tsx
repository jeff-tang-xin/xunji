import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { Button, Empty, Input, Modal, Segmented, Spin, Tag, Tooltip, Tree, Typography, message } from 'antd'
import type { DataNode } from 'antd/es/tree'
import {
  CopyOutlined,
  FileMarkdownOutlined,
  FileOutlined,
  FolderFilled,
  FolderOpenFilled,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import { apiJson } from '../../api/client'
import { MarkdownView } from './shared'

/**
 * 项目文件（产出可见）：项目目录（root_path）文件树 + 文件内容在线查看。
 * 产出不再只是对话碎片——代码/文档落盘即在这里可见；点文件拉内容（≤200KB 文本）。
 * 形态对齐 AI-Claw 工作区：标准树级——同层内目录在前、文件在后按名混排，点目录进下一层；
 * 交互对齐 AntD 规范：antd Tree（hover/选中态/展开动效）、类型彩色图标（替代满屏 Tag）、
 * 搜索过滤；点文件弹 Modal 看内容（右栏 tab 窄，内嵌内容区每行只挤得下五六个字——
 * 弹窗 780px 宽 + 65vh 滚动才是读文档的形态），文件头带路径面包屑/大小/渲染切换/复制。
 */

interface FileEntry {
  path: string
  type: 'dir' | 'file'
  size?: number
}

function fmtSize(n?: number): string {
  if (n == null) return ''
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

/** 文件类型 → 语义色图标：颜色即类型（替代每行一个彩色 Tag 的花哨做法） */
function fileIcon(path: string): ReactNode {
  const ext = path.slice(path.lastIndexOf('.') + 1).toLowerCase()
  const c = (color: string) => <FileOutlined style={{ color, fontSize: 14 }} />
  switch (ext) {
    case 'md':
    case 'markdown':
      return <FileMarkdownOutlined style={{ color: '#1677ff', fontSize: 14 }} />
    case 'json':
      return c('#08979c')
    case 'java':
      return c('#d46b08')
    case 'ts':
    case 'tsx':
      return c('#2f54eb')
    case 'js':
    case 'jsx':
      return c('#faad14')
    case 'sql':
      return c('#722ed1')
    case 'sh':
    case 'bat':
    case 'ps1':
      return c('#389e0d')
    case 'yml':
    case 'yaml':
      return c('#c41d7f')
    case 'xml':
    case 'html':
      return c('#ad6800')
    case 'py':
      return c('#1677ff')
    case 'css':
    case 'less':
    case 'scss':
      return c('#0958d9')
    case 'png':
    case 'jpg':
    case 'jpeg':
    case 'gif':
    case 'svg':
      return c('#52c41a')
    default:
      return c('rgba(0,0,0,0.45)')
  }
}

export default function ProjectFiles({ projectId, refreshKey = 0 }: { projectId: string; refreshKey?: number }) {
  const [entries, setEntries] = useState<FileEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [content, setContent] = useState<string>('')
  const [fileLoading, setFileLoading] = useState(false)
  const [search, setSearch] = useState('')
  /** md 文件默认渲染预览，可切原文 */
  const [rawMode, setRawMode] = useState(false)
  /** 受控展开：默认展开第一层（与 AI-Claw 一致，根层目录+文件一目了然）；搜索时全展开 */
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([])

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

  // refreshKey（后端状态事件计数）驱动重拉：agent 写文件/任务产出落盘后文件树自动更新，
  // 不再「跑完任务文件 tab 还是旧的，要手动点刷新」（审计修复 2026-10-19）
  useEffect(() => {
    void load()
  }, [load, refreshKey])

  const openFile = async (path: string) => {
    setSelected(path)
    setRawMode(false)
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

  const copyContent = async () => {
    try {
      await navigator.clipboard.writeText(content)
      message.success('已复制全文')
    } catch {
      message.error('复制失败（浏览器不支持剪贴板）')
    }
  }

  /** 搜索过滤：保留匹配文件与其祖先目录链 */
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return entries
    return entries.filter((e) => e.path.toLowerCase().includes(q))
  }, [entries, search])

  /** 平铺 entries → antd Tree 数据：同层内目录在前、文件在后（树级形态，与 AI-Claw 一致） */
  const { treeData, allDirKeys } = useMemo(() => {
    const root: DataNode[] = []
    const dirIndex = new Map<string, DataNode[]>()
    const dirKeys: string[] = []
    const ensureDir = (segments: string[]): DataNode[] => {
      if (segments.length === 0) return root
      const parent = ensureDir(segments.slice(0, -1))
      const full = segments.join('/')
      let list = dirIndex.get(full)
      if (!list) {
        dirKeys.push(full)
        const node: DataNode = {
          key: full,
          title: segments[segments.length - 1],
          children: [],
        }
        list = node.children as DataNode[]
        dirIndex.set(full, list)
        parent.push(node)
      }
      return list
    }
    // 目录先建（在前），文件后挂（在后）——同层内目录排前面
    for (const e of filtered.filter((x) => x.type === 'dir')) ensureDir(e.path.split('/'))
    for (const e of filtered.filter((x) => x.type !== 'dir')) {
      const segs = e.path.split('/')
      const parent = ensureDir(segs.slice(0, -1))
      parent.push({
        key: e.path,
        isLeaf: true,
        title: segs[segs.length - 1],
      })
    }
    return {
      treeData: root,
      allDirKeys: dirKeys,
    }
  }, [filtered])

  // 默认全展开（用户反馈「文件要列出所有内容」）：文件藏在 3-4 层深处（docs/{stage}/iter{n}/），
  // 默认全展开（用户反馈「文件要列出所有内容」）：文件藏在 3-4 层深处（docs/{stage}/iter{n}/），
  // 只展开顶层的话用户要点穿层层目录才见文件；项目条目量级（<100）全展开无压力
  useEffect(() => {
    setExpandedKeys(allDirKeys)
  }, [allDirKeys])

  const selectedEntry = selected ? entries.find((e) => e.path === selected) : undefined

  return (
    <div>
      {/* 工具行：搜索 + 刷新同排（亲密性），不各自占行 */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
        <Input
          size="small"
          allowClear
          prefix={<SearchOutlined style={{ color: 'rgba(0,0,0,0.25)' }} />}
          placeholder="搜索文件名"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ flex: 1 }}
        />
        <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </div>

      {loading
        ? <div style={{ padding: 40, textAlign: 'center' }}><Spin /></div>
        : entries.length === 0
          ? <Empty description="项目目录为空——项目产出（代码/文档）会自动落盘到这里" />
          : (
            <div style={{
              border: '1px solid #f0f0f0', borderRadius: 8, padding: '8px 4px', background: '#fff',
            }}>
              {treeData.length === 0
                ? <Empty description={`没有匹配「${search.trim()}」的文件`} image={Empty.PRESENTED_IMAGE_SIMPLE} />
                : (
                  <Tree
                    blockNode
                    treeData={treeData}
                    expandedKeys={expandedKeys}
                    onExpand={(keys) => setExpandedKeys(keys)}
                    selectedKeys={selected ? [selected] : []}
                    onSelect={(keys, info) => {
                      const k = String(keys[0] ?? '')
                      if (!k) return
                      if (info.node.isLeaf) void openFile(k)
                      else setExpandedKeys((prev) => (prev.includes(k) ? prev.filter((x) => x !== k) : [...prev, k]))
                    }}
                    titleRender={(n) => {
                      const isDir = !n.isLeaf
                      const expanded = expandedKeys.includes(n.key)
                      const size = !isDir
                        ? fmtSize(entries.find((e) => e.path === n.key)?.size)
                        : ''
                      return (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, minWidth: 0, maxWidth: '100%' }}>
                          {isDir
                            ? (expanded
                                ? <FolderOpenFilled style={{ color: '#faad14', fontSize: 14 }} />
                                : <FolderFilled style={{ color: '#faad14', fontSize: 14 }} />)
                            : fileIcon(String(n.key))}
                          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {String(n.title)}
                          </span>
                          {size && <span style={{ color: 'rgba(0,0,0,0.45)', fontSize: 12, flexShrink: 0 }}>{size}</span>}
                        </span>
                      )
                    }}
                  />
                )}
            </div>
          )}

      {/* 文件内容弹窗：右栏 tab 太窄，读文档（CHATLOG 回放/PRD）开 Modal 才有阅读宽度 */}
      <Modal
        open={selected != null}
        onCancel={() => setSelected(null)}
        footer={null}
        width={780}
        title={
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
            {selected && fileIcon(selected)}
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {selected?.split('/').pop() || ''}
            </span>
          </span>
        }
      >
        <div style={{ marginTop: -8 }}>
          {/* 文件头：路径面包屑 + 大小 + 渲染切换 + 复制 */}
          <div style={{
            display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap',
            padding: '6px 0 10px', borderBottom: '1px solid #f0f0f0', marginBottom: 10,
          }}>
            {selected?.split('/').map((seg, i2, arr) => (
              <span key={i2} style={{ display: 'inline-flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                <Typography.Text
                  strong={i2 === arr.length - 1}
                  type={i2 === arr.length - 1 ? undefined : 'secondary'}
                  style={{ fontSize: 12 }}
                >
                  {seg}
                </Typography.Text>
                {i2 < arr.length - 1 && <span style={{ color: 'rgba(0,0,0,0.25)' }}>/</span>}
              </span>
            ))}
            {selectedEntry?.size != null && (
              <Tooltip title="文件大小">
                <Tag style={{ marginInlineEnd: 0 }}>{fmtSize(selectedEntry.size)}</Tag>
              </Tooltip>
            )}
            <span style={{ flex: 1 }} />
            {selected?.toLowerCase().endsWith('.md') && (
              <Segmented
                size="small"
                options={['渲染', '原文']}
                value={rawMode ? '原文' : '渲染'}
                onChange={(v) => setRawMode(v === '原文')}
              />
            )}
            <Tooltip title="复制全文">
              <Button size="small" type="text" icon={<CopyOutlined />} onClick={() => void copyContent()} />
            </Tooltip>
          </div>
          <div style={{ maxHeight: '65vh', overflowY: 'auto' }}>
            {fileLoading
              ? <div style={{ padding: 40, textAlign: 'center' }}><Spin /></div>
              : selected?.toLowerCase().endsWith('.md') && !rawMode
                ? <MarkdownView scroll={false} text={content} />
                : (
                  <pre style={{
                    margin: 0, fontSize: 12, lineHeight: 1.6,
                    background: '#fff', padding: 12,
                    whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                  }}>
                    {content || '（空文件）'}
                  </pre>
                )}
          </div>
        </div>
      </Modal>
    </div>
  )
}
