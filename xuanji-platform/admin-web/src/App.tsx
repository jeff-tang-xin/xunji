import { HashRouter, Routes, Route, Navigate } from 'react-router-dom'
import { ConfigProvider, App as AntdApp } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import MainLayout from './layout/MainLayout'
import Login from './pages/Login'
import CanvasEditor from './pages/CanvasEditor'
import AgentAdmin from './pages/AgentAdmin'
import AppAdmin from './pages/AppAdmin'
import Assets from './pages/Assets'
import Permissions from './pages/Permissions'
import Workspace from './pages/Workspace'
import RunMonitor from './pages/RunMonitor'
import PublishCenter from './pages/PublishCenter'
import ApprovalCenter from './pages/ApprovalCenter'
import ReportCenter from './pages/ReportCenter'
import DataSourceAdmin from './pages/DataSourceAdmin'

/** 全局设计 Token（antdesign-philosophy：主色/圆角/字体统一声明，改一处全局生效） */
const themeConfig = {
  token: {
    colorPrimary: '#1677ff',
    colorInfo: '#1677ff',
    borderRadius: 6,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
  },
}

export default function App() {
  return (
    <ConfigProvider locale={zhCN} theme={themeConfig}>
      <AntdApp>
        <HashRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/" element={<MainLayout />}>
              <Route index element={<Navigate to="/workspace" replace />} />
              <Route path="/workspace" element={<Workspace />} />
              <Route path="/canvas" element={<CanvasEditor />} />
              <Route path="/run" element={<RunMonitor />} />
              <Route path="/approval" element={<ApprovalCenter />} />
              <Route path="/publish" element={<PublishCenter />} />
              <Route path="/agent" element={<AgentAdmin />} />
              <Route path="/app" element={<AppAdmin />} />
              <Route path="/report" element={<ReportCenter />} />
              <Route path="/perm" element={<Permissions />} />
              <Route path="/assets" element={<Assets />} />
              <Route path="/datasource" element={<DataSourceAdmin />} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </HashRouter>
      </AntdApp>
    </ConfigProvider>
  )
}
