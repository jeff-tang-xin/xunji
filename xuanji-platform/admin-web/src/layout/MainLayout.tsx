import { useEffect, useState } from 'react'
import { Layout, Menu, Dropdown, Spin, Typography, Result } from 'antd'
import {
  FolderOpenOutlined, ApartmentOutlined, MonitorOutlined, AuditOutlined,
  RocketOutlined, RobotOutlined, AppstoreOutlined, BarChartOutlined,
  SafetyOutlined, DatabaseOutlined, CloudServerOutlined, LogoutOutlined, UserOutlined,
  ProjectOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { me, logout, clearToken, type MeInfo } from '../api/client'

/** 菜单 code → hash 路由（后端 menu.path 仍存旧 HTML 路径，前端按 code 映射） */
const CODE_ROUTE: Record<string, string> = {
  workspace: '/workspace',
  project: '/project',
  canvas: '/canvas',
  run: '/run',
  approval: '/approval',
  publish: '/publish',
  agent: '/agent',
  app: '/app',
  report: '/report',
  perm: '/perm',
  assets: '/assets',
  datasource: '/datasource',
}

const PAGE_TITLE: Record<string, string> = {
  workspace: '工作区',
  project: '项目看板',
  canvas: '画布编辑器',
  run: '运行监控',
  approval: '审批中心',
  publish: '发布中心',
  agent: 'Agent 管理',
  app: '应用管理',
  report: '报表中心',
  perm: '权限管理',
  assets: '资产管理',
  datasource: '数据源管理',
}

/** Header 副标题：页面一句话说明（产品语言，不暴露实现术语） */
const PAGE_SUB: Record<string, string> = {
  workspace: '创建工作区并绑定 Agent 或工作流，输入内容即可触发执行',
  project: '以项目为容器管理软件全生命周期：阶段链推进、留档沉淀、跨阶段衔接',
  canvas: '拖拽节点编排流程，配置每个节点的引用与执行策略',
  run: '查看每次执行的节点序列与输入输出',
  approval: '处理等待人工确认的流程',
  publish: '将流程、Agent 等资产发布为固定版本，供调用方使用',
  agent: '配置 Agent 的模型、工具与技能',
  app: '把已发布的流程或 Agent 发布为应用，供外部系统调用',
  report: '定时执行流程并生成看板快照',
  perm: '管理用户、角色与数据权限',
  assets: '管理工具、技能、MCP 与模型接入',
  datasource: '配置外部业务库连接，供工作流节点查询真实数据',
}

/** 菜单 code → 图标（标准中后台视觉锚点） */
const CODE_ICON: Record<string, React.ReactNode> = {
  workspace: <FolderOpenOutlined />,
  project: <ProjectOutlined />,
  canvas: <ApartmentOutlined />,
  run: <MonitorOutlined />,
  approval: <AuditOutlined />,
  publish: <RocketOutlined />,
  agent: <RobotOutlined />,
  app: <AppstoreOutlined />,
  report: <BarChartOutlined />,
  perm: <SafetyOutlined />,
  assets: <DatabaseOutlined />,
  datasource: <CloudServerOutlined />,
  // DIR 分组图标
  grp_project: <ProjectOutlined />,
  grp_flow: <ApartmentOutlined />,
  grp_agent: <RobotOutlined />,
  grp_asset: <DatabaseOutlined />,
  grp_integration: <AppstoreOutlined />,
  grp_system: <SafetyOutlined />,
}

export default function MainLayout() {
  const [user, setUser] = useState<MeInfo | null>(null)
  const [error, setError] = useState<string | null>(null)
  const location = useLocation()
  const navigate = useNavigate()

  useEffect(() => {
    if (!getToken0()) {
      navigate('/login', { replace: true })
      return
    }
    me()
      .then(setUser)
      .catch((e: Error) => setError(e.message))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (error) {
    return (
      <div style={{ padding: 60 }}>
        <Result status="error" title="加载失败" subTitle={error} />
      </div>
    )
  }
  if (!user) {
    return (
      <div style={{ padding: 80, textAlign: 'center' }}>
        <Spin />
      </div>
    )
  }

  const menuItems = user.menus.map((m) => {
    const children = (m as { children?: Array<{ code: string; name: string }> }).children
    if (children && children.length > 0) {
      // DIR 分组：子菜单
      return {
        key: m.code,
        label: m.name,
        icon: CODE_ICON[m.code],
        children: children.map((c) => ({
          key: c.code,
          label: c.name,
          icon: CODE_ICON[c.code],
        })),
      }
    }
    return { key: m.code, label: m.name, icon: CODE_ICON[m.code] }
  })

  const currentCode =
    Object.keys(CODE_ROUTE).find((c) => CODE_ROUTE[c] === location.pathname) || ''

  return (
    <Layout style={{ height: '100vh' }}>
      <Layout.Sider className="xj-sider" width={200} theme="dark">
        <div className="xj-logo">玄机万象 · XuanJi</div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[currentCode]}
          items={menuItems}
          onClick={(info) => {
            const route = CODE_ROUTE[info.key]
            if (route) navigate(route)
          }}
        />
      </Layout.Sider>
      <Layout>
        <Layout.Header className="xj-header">
          <span style={{ fontWeight: 600 }}>{PAGE_TITLE[currentCode] || ''}</span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 16, minWidth: 0 }}>
            {PAGE_SUB[currentCode] && (
              <Typography.Text
                type="secondary"
                style={{
                  fontSize: 12,
                  maxWidth: '48vw',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
                title={PAGE_SUB[currentCode]}
              >
                {PAGE_SUB[currentCode]}
              </Typography.Text>
            )}
            <Dropdown
              menu={{
                items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }],
                onClick: (info) => {
                  if (info.key === 'logout') {
                    clearToken()
                    void logout()
                  }
                },
              }}
            >
              <span style={{ cursor: 'pointer', whiteSpace: 'nowrap', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                <UserOutlined style={{ fontSize: 14 }} />
                {user.displayName || user.username}
                {user.isPlatformAdmin ? ' · 平台管理员' : ''}
              </span>
            </Dropdown>
          </span>
        </Layout.Header>
        <Layout.Content className="xj-content">
          <Outlet context={user} />
        </Layout.Content>
      </Layout>
    </Layout>
  )
}

function getToken0(): string | null {
  return localStorage.getItem('xj_token')
}

export function PageSub({ text }: { text: string }) {
  return (
    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
      {text}
    </Typography.Text>
  )
}
