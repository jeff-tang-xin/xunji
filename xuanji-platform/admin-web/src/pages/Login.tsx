import { useState } from 'react'
import { Form, Input, Button, message, Alert } from 'antd'
import { useNavigate } from 'react-router-dom'
import { setToken } from '../api/client'

/** 登录 + 首次改密双视图（等价旧 login.html） */
export default function Login() {
  const [mode, setMode] = useState<'login' | 'change'>('login')
  const [pending, setPending] = useState<{ token: string; username: string } | null>(null)
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  const onLogin = async ({ username, password }: { username: string; password: string }) => {
    setLoading(true)
    try {
      const r = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      })
      const data = (await r.json()) as {
        token?: string
        user?: { mustChangePassword?: boolean }
        error?: string
      }
      if (!r.ok) {
        message.error(data.error || '登录失败')
        return
      }
      if (data.user?.mustChangePassword && data.token) {
        setPending({ token: data.token, username })
        setMode('change')
        message.info('首次登录，请先修改密码')
        return
      }
      if (data.token) {
        setToken(data.token)
        navigate('/', { replace: true })
      }
    } catch (e) {
      message.error('网络错误: ' + (e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  const onChangePassword = async (v: { oldPassword: string; newPassword: string; confirm: string }) => {
    if (v.newPassword !== v.confirm) {
      message.error('两次输入的新密码不一致')
      return
    }
    if (!pending) return
    setLoading(true)
    try {
      const r = await fetch('/api/auth/change-password', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer ' + pending.token,
        },
        body: JSON.stringify({ oldPassword: v.oldPassword, newPassword: v.newPassword }),
      })
      const data = (await r.json()) as { error?: string }
      if (!r.ok) {
        message.error(data.error || '修改失败')
        return
      }
      message.success('密码修改成功，请使用新密码登录')
      setMode('login')
      setPending(null)
    } catch (e) {
      message.error('网络错误: ' + (e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg, #1677ff 0%, #0958d9 100%)',
      }}
    >
      <div
        style={{
          background: '#fff',
          borderRadius: 12,
          padding: '40px 44px',
          width: 380,
          boxShadow: '0 12px 40px rgba(0,0,0,.18)',
        }}
      >
        <div style={{ fontSize: 24, fontWeight: 700, textAlign: 'center', marginBottom: 4 }}>
          玄机万象 <em style={{ color: '#1677ff', fontStyle: 'normal' }}>· XuanJi</em>
        </div>
        <div style={{ color: '#888', fontSize: 13, textAlign: 'center', marginBottom: 28 }}>
          DevOps 全链路编排平台
        </div>
        {mode === 'login' ? (
          <Form layout="vertical" onFinish={onLogin}>
            <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input placeholder="用户名" size="large" autoFocus />
            </Form.Item>
            <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password placeholder="密码" size="large" />
            </Form.Item>
            <Button type="primary" htmlType="submit" size="large" block loading={loading}>
              登 录
            </Button>
          </Form>
        ) : (
          <Form layout="vertical" onFinish={onChangePassword}>
            <Alert
              type="warning"
              showIcon
              message="首次登录（或密码被重置），必须修改密码后才能进入平台"
              style={{ marginBottom: 16 }}
            />
            <Form.Item name="oldPassword" rules={[{ required: true, message: '请输入当前密码' }]}>
              <Input.Password placeholder="当前密码" size="large" />
            </Form.Item>
            <Form.Item
              name="newPassword"
              rules={[
                { required: true, message: '请输入新密码' },
                { min: 8, message: '至少 8 位' },
              ]}
            >
              <Input.Password placeholder="新密码（至少 8 位）" size="large" />
            </Form.Item>
            <Form.Item name="confirm" rules={[{ required: true, message: '请再次输入新密码' }]}>
              <Input.Password placeholder="确认新密码" size="large" />
            </Form.Item>
            <Button type="primary" htmlType="submit" size="large" block loading={loading}>
              修改密码
            </Button>
          </Form>
        )}
        <div style={{ marginTop: 18, fontSize: 12, color: '#aaa', textAlign: 'center' }}>
          用户由平台管理员创建 · 初始密码一次性发放
        </div>
      </div>
    </div>
  )
}
