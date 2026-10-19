import { Button, Input, Typography } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'

/** 服务器连接条目（地址+凭证+说明；凭证脱敏回显为 ***，提交 *** 表示沿用旧值） */
export type OpsServer = { address: string; password?: string; note?: string }

/** 数据库连接条目（地址+账号+凭证+说明） */
export type OpsDatabase = { address: string; username?: string; password?: string; note?: string }

const rowStyle = { display: 'flex', gap: 6, marginBottom: 6, alignItems: 'center' } as const

/**
 * 运维连接编辑列表（服务器连接 / 数据库连接）——创建项目、编辑项目、阶段设置三处共用。
 *
 * 凭证托管：地址与说明 AI 可见（boardDigest 注入引用清单），密码只进平台加密存储，
 * 回显一律脱敏（***）；提交 *** 或空表示沿用旧密码，避免脱敏值被二次加密覆盖真值。
 */
export function OpsConnLists({ servers, databases, onServers, onDatabases }: {
  servers: OpsServer[]
  databases: OpsDatabase[]
  onServers: (v: OpsServer[]) => void
  onDatabases: (v: OpsDatabase[]) => void
}) {
  return (
    <div>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        服务器连接（部署 / 巡检 / 变更的目标机器；AI 经 remote_shell 操作，密码平台托管不进 AI 上下文）
      </Typography.Text>
      <div style={{ marginTop: 6 }}>
        {servers.map((s, i) => (
          <div key={i} style={rowStyle}>
            <Input
              style={{ flex: '1.4 1 0', minWidth: 0 }}
              placeholder="root@1.2.3.4:22"
              value={s.address}
              onChange={(e) => onServers(servers.map((x, j) => j === i ? { ...x, address: e.target.value } : x))}
            />
            <Input.Password
              style={{ flex: '1 1 0', minWidth: 0 }}
              placeholder="密码（***=沿用）"
              autoComplete="new-password"
              value={s.password || ''}
              onChange={(e) => onServers(servers.map((x, j) => j === i ? { ...x, password: e.target.value } : x))}
            />
            <Input
              style={{ flex: '1.2 1 0', minWidth: 0 }}
              placeholder="用途说明（如：应用服务器）"
              value={s.note || ''}
              onChange={(e) => onServers(servers.map((x, j) => j === i ? { ...x, note: e.target.value } : x))}
            />
            <Button
              type="text"
              danger
              icon={<DeleteOutlined />}
              onClick={() => onServers(servers.filter((_, j) => j !== i))}
            />
          </div>
        ))}
        {servers.length === 0 && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>未配置服务器连接</Typography.Text>
        )}
        <Button
          size="small"
          icon={<PlusOutlined />}
          onClick={() => onServers([...servers, { address: '', password: '', note: '' }])}
        >
          添加服务器连接
        </Button>
      </div>

      <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 12 }}>
        数据库连接（部署建表 / 初始化 / 排障查数；AI 经 db_query / db_dml 操作，账号密码平台托管）
      </Typography.Text>
      <div style={{ marginTop: 6 }}>
        {databases.map((d, i) => (
          <div key={i} style={rowStyle}>
            <Input
              style={{ flex: '1.3 1 0', minWidth: 0 }}
              placeholder="192.168.1.10:5432/库名"
              value={d.address}
              onChange={(e) => onDatabases(databases.map((x, j) => j === i ? { ...x, address: e.target.value } : x))}
            />
            <Input
              style={{ flex: '0.8 1 0', minWidth: 0 }}
              placeholder="账号"
              value={d.username || ''}
              onChange={(e) => onDatabases(databases.map((x, j) => j === i ? { ...x, username: e.target.value } : x))}
            />
            <Input.Password
              style={{ flex: '0.9 1 0', minWidth: 0 }}
              placeholder="密码（***=沿用）"
              autoComplete="new-password"
              value={d.password || ''}
              onChange={(e) => onDatabases(databases.map((x, j) => j === i ? { ...x, password: e.target.value } : x))}
            />
            <Input
              style={{ flex: '1.1 1 0', minWidth: 0 }}
              placeholder="用途说明（如：应用库）"
              value={d.note || ''}
              onChange={(e) => onDatabases(databases.map((x, j) => j === i ? { ...x, note: e.target.value } : x))}
            />
            <Button
              type="text"
              danger
              icon={<DeleteOutlined />}
              onClick={() => onDatabases(databases.filter((_, j) => j !== i))}
            />
          </div>
        ))}
        {databases.length === 0 && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>未配置数据库连接</Typography.Text>
        )}
        <Button
          size="small"
          icon={<PlusOutlined />}
          onClick={() => onDatabases([...databases, { address: '', username: '', password: '', note: '' }])}
        >
          添加数据库连接
        </Button>
      </div>
    </div>
  )
}

/** 提交前清洗：去掉地址为空的行；密码 *** 原样传（后端识别为沿用旧值）。 */
export function cleanServers(list: OpsServer[]): OpsServer[] {
  return list
    .map((s) => ({ address: s.address.trim(), password: (s.password || '').trim(), note: (s.note || '').trim() }))
    .filter((s) => s.address !== '')
}

export function cleanDatabases(list: OpsDatabase[]): OpsDatabase[] {
  return list
    .map((d) => ({
      address: d.address.trim(),
      username: (d.username || '').trim(),
      password: (d.password || '').trim(),
      note: (d.note || '').trim(),
    }))
    .filter((d) => d.address !== '')
}
