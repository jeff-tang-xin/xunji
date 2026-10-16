import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { Button, Empty, Input, Spin, Tag, Tooltip, Tree, Typography, message } from 'antd'
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

/**
 * 项目文件（产出可见）：项目目录（root_path）文件树 + 文件内容在线查看。
 * 产出不再只是对话碎片——代码/文档落盘即在这里可见；点文件拉内容（≤200KB 文本）。
 * 形态对齐 AI-Claw 工作区：标准树级——同层内目录在前、文件在后按名混排，点目录进下一层；
 * 交互对齐 AntD 规范：antd Tree（hover/选中态/展开动效）、类型彩色图标（替代满屏 Tag）、
 * 搜索过滤、内容区带文件头（路径面包屑/大小/复制）。
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

export default function ProjectFiles({ projectId }: { projectId: string }) {
  const [entries, setEntries] = useState<FileEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [content, setContent] = useState<string>('')
  const [fileLoading, setFileLoading] = useState(false)
  const [search, setSearch] = useState('')
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
  const { treeData, allDirKeys, topDirKeys } = useMemo(() => {
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
      topDirKeys: dirKeys.filter((k) => !k.includes('/')),
    }
  }, [filtered])

  // 搜索时全展开（过滤后的树直接看全部命中），清空搜索回到默认只展开第一层
  useEffect(() => {
    setExpandedKeys(search.trim() ? allDirKeys : topDirKeys)
  }, [search, allDirKeys, topDirKeys])

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
            <div style={{ display: 'flex', gap: 12, alignItems: 'stretch' }}>
              {/* 文件树：antd Tree 自带 hover/选中/展开动效；目录点击 = 切换展开 */}
              <div style={{
                width: 280, flexShrink: 0, maxHeight: 520, overflow: 'auto',
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
                          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, minWidth: 0 }}>
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

              {/* 内容区：文件头（路径面包屑 + 大小 + 复制）+ 内容 */}
              <div style={{
                flex: 1, minWidth: 0, border: '1px solid #f0f0f0', borderRadius: 8, background: '#fff',
                display: 'flex', flexDirection: 'column',
              }}>
                {selected == null
                  ? (
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: 480 }}>
                      <Empty description="点左侧文件查看内容" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    </div>
                  )
                  : (
                    <>
                      <div style={{
                        display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap',
                        padding: '8px 12px', borderBottom: '1px solid #f0f0f0', background: '#fafafa',
                        borderRadius: '8px 8px 0 0',
                      }}>
                        {fileIcon(selected)}
                        {selected.split('/').map((seg, i, arr) => (
                          <span key={i} style={{ display: 'inline-flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                            <Typography.Text
                              strong={i === arr.length - 1}
                              type={i === arr.length - 1 ? undefined : 'secondary'}
                              style={{ fontSize: 13 }}
                            >
                              {seg}
                            </Typography.Text>
                            {i < arr.length - 1 && <span style={{ color: 'rgba(0,0,0,0.25)' }}>/</span>}
                          </span>
                        ))}
                        {selectedEntry?.size != null && (
                          <Tooltip title="文件大小">
                            <Tag style={{ marginInlineEnd: 0 }}>{fmtSize(selectedEntry.size)}</Tag>
                          </Tooltip>
                        )}
                        <span style={{ flex: 1 }} />
                        <Tooltip title="复制全文">
                          <Button size="small" type="text" icon={<CopyOutlined />} onClick={() => void copyContent()} />
                        </Tooltip>
                      </div>
                      <div style={{ flex: 1, minHeight: 0, maxHeight: 480, overflow: 'auto' }}>
                        {fileLoading
                          ? <div style={{ padding: 40, textAlign: 'center' }}><Spin /></div>
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
                    </>
                  )}
              </div>
            </div>
          )}
    </div>
  )
}
