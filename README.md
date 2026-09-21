<h1 align="center">JobRadar</h1>

<p align="center">多平台自动化求职助手 —— 从职位匹配到简历投递，一气呵成。</p>

<p align="center">
  <a href="./README.en.md">English</a> | <a href="./README.md">简体中文</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-3776AB?style=flat-square" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5.7-22C55E?style=flat-square" alt="Spring Boot 3.5.7">
  <img src="https://img.shields.io/badge/Playwright-1.51.0-0891B2?style=flat-square" alt="Playwright 1.51.0">
  <img src="https://img.shields.io/badge/Next.js-16-4F46E5?style=flat-square" alt="Next.js 16">
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-PolyForm_Noncommercial_1.0.0-22C55E?style=flat-square" alt="License"></a>
</p>

JobRadar 是一个面向国内主流招聘平台（Boss 直聘、猎聘、51job、智联招聘）的自动化求职助手。你只需在一个 Web 面板上配置好关键词、城市和薪资范围，剩下的职位搜索、打招呼、简历投递都可以自动完成。

## 亮点

| 亮点 | 为什么重要 |
|------|-----------|
| 多平台一站式投递 | 在一个面板上配置，自动向 Boss 直聘、猎聘、51job、智联招聘投递简历 |
| AI 智能打招呼 | 分析职位描述，自动生成个性化的打招呼消息（Boss 直聘） |
| 智能过滤 | 自动跳过不活跃 HR、猎头岗位和超出薪资范围的职位 |
| 图片简历直达 | 打招呼后立即发送图片版简历，无需等待 HR 索要 |
| 实时通知 | 投递结果推送到企业微信 webhook |
| 持久登录 | Cookie 长期有效，大部分平台每周只需扫码登录一次 |
| Web 管理面板 | 通过 Next.js 浏览器界面完成所有配置和监控 |

## 架构

```text
┌──────────────────────────────────────────────────────┐
│                     JobRadar                          │
│  ┌──────────────┐       ┌────────────────────────┐   │
│  │  Next.js UI   │──────▶│   Spring Boot API     │   │
│  │  (Frontend)   │ HTTP  │      (Backend)        │   │
│  └──────────────┘       └───────────┬────────────┘   │
│                                      │                │
│        ┌─────────────────────────────┼────────────┐   │
│        │         Playwright          │            │   │
│        │    (Browser Automation)     │            │   │
│        └──────┬─────────┬────────┬───┘            │   │
│               │         │        │                 │   │
│         ┌─────▼──┐ ┌───▼────┐ ┌──▼─────┐          │   │
│         │  Boss  │ │ Liepin │ │ 51job  │ Zhaopin  │   │
│         │ Zhipin │ │        │ │        │          │   │
│         └────────┘ └────────┘ └────────┘          │   │
│                                      │                │
│                      ┌───────────────▼────────────┐   │
│                      │       SQLite Database      │   │
│                      │     (MyBatis-Plus ORM)     │   │
│                      └────────────────────────────┘   │
└──────────────────────────────────────────────────────┘
```

## 使用示例

> 截图占位 —— 后续补入 Web 管理面板的配置界面和运行日志截图。

### Web 管理面板

通过浏览器面板配置所有平台、管理 Cookie、设置 AI 参数、实时监控投递状态。

- 运行日志：`[待补充]`
- Boss 直聘配置：`[待补充]`
- 岗位分析：`[待补充]`
- AI 参数配置：`[待补充]`
- 猎聘 / 51job / 智联配置：`[待补充]`

### 快速上手流程

```bash
# 1. 克隆仓库
git clone https://github.com/napstablook666/JobRadar.git
cd JobRadar

# 2. 构建后端（需要 JDK 21）
./gradlew bootRun

# 3. 打开 Web 面板
#    浏览器访问 http://localhost:8888
#    配置目标城市、职位关键词、薪资范围和投递平台

# 4. 系统会自动：
#    - 为每个平台启动浏览器会话
#    - 根据条件扫描匹配的岗位
#    - 发送 AI 生成的个性化打招呼消息
#    - 自动投递图片简历（可选）
#    - 跳过不活跃 HR 和不合适的职位
#    - 通过企业微信 webhook 推送投递结果
```

## 快速安装

### 环境要求

- **JDK 21** 或更高版本
- **Gradle**（项目内含 wrapper）

```bash
git clone https://github.com/napstablook666/JobRadar.git
cd JobRadar
./gradlew build
```

## 快速开始

1. 编辑 `src/main/resources/application.yaml` 或设置环境变量，配置目标城市、关键词和 AI API key。
2. 启动应用：

```bash
./gradlew bootRun
```

3. 浏览器访问 `http://localhost:8888`。
4. 在 Web 面板中配置各平台账号并完成 Cookie 登录。
5. 在面板中启动自动投递流程。

> 注意：使用国内招聘平台时请关闭 VPN / 代理，服务器 IP 可能被拦截导致无数据返回。

## 配置

### 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `JOBRADAR_BROWSER_MODE` | 浏览器可见性：`background` 或 `visible-login` | `background` |
| `JOBRADAR_BROWSER_TRANSPORT` | 传输模式：`browser` 或 `hybrid` | `hybrid` |
| `JOBRADAR_BROWSER_ENGINE` | 浏览器引擎：`auto` 或自定义路径 | `auto` |
| `JOBRADAR_DELIVERY_RUNTIME` | 平台运行模式：`isolated`（每个平台独立）或 `shared` | `isolated` |
| `JOBRADAR_HEADLESS_SHELL` | 自定义 Chromium headless-shell 路径 | （空） |

### AI 集成

在项目根目录创建 `.env` 文件：

```env
HOOK_URL=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=your_key_here
BASE_URL=https://api.openai.com
API_KEY=sk-xxx
MODEL=gpt-5-nano
```

| 变量 | 说明 |
|------|------|
| `HOOK_URL` | 企业微信机器人 webhook 地址（投递通知） |
| `BASE_URL` | OpenAI 兼容 API 端点（直连或代理） |
| `API_KEY` | LLM 服务的 API key |
| `MODEL` | 用于生成打招呼消息的模型名 |

## 文档

| 主题 | 内容 | 链接 |
|------|------|------|
| 环境搭建 | JDK、Gradle 及浏览器驱动配置 | [doc/doc.md](doc/doc.md) |
| 详细指南 | 各平台具体配置与排错 | [doc/Detail.md](doc/Detail.md) |
| 更新日志 | 版本发布历史 | [doc/更新日志.md](doc/更新日志.md) |


## 许可证

本项目基于 [PolyForm Noncommercial License 1.0.0](./LICENSE)。未经版权所有者明确许可，禁止商业用途。