<h1 align="center">JobRadar</h1>

<p align="center">Multi-platform automated job application assistant powered by AI and browser automation.</p>

<p align="center">
  <a href="./README.md">简体中文</a> | <a href="./README.en.md">English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-3776AB?style=flat-square" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5.7-22C55E?style=flat-square" alt="Spring Boot 3.5.7">
  <img src="https://img.shields.io/badge/Playwright-1.51.0-0891B2?style=flat-square" alt="Playwright 1.51.0">
  <img src="https://img.shields.io/badge/Next.js-16-4F46E5?style=flat-square" alt="Next.js 16">
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-PolyForm_Noncommercial_1.0.0-22C55E?style=flat-square" alt="License"></a>
</p>

JobRadar automates job applications on major Chinese recruitment platforms (Boss Zhipin, Liepin, 51job, Zhaopin) from a single web dashboard. Configure your search keywords, target city, and salary range once — the system handles scanning, greeting, and resume delivery automatically.

## Highlights

| Highlight | Why it matters |
|-----------|---------------|
| Multi-platform delivery | Submit applications on Boss Zhipin, Liepin, 51job, and Zhaopin from one dashboard |
| AI-powered matching | Analyzes job descriptions and generates personalized greeting messages (Boss Zhipin) |
| Intelligent filtering | Skips inactive HR, headhunter positions, and out-of-range salary offers automatically |
| Image resume delivery | Sends a picture-based resume immediately after greeting, no waiting for HR to ask |
| Real-time notifications | Pushes delivery results to WeCom (WeChat Work) webhook |
| Persistent login | Long-lived cookie session — scan a QR code once a week for most platforms |
| Web management UI | Configure everything through a browser-based dashboard powered by Next.js |

## Architecture

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

## Usage Example

> Screenshots placeholder — Web dashboard configuration pages and runtime logs to be added.

### Web Management Dashboard

Configure all platforms, manage cookies, set AI parameters, and monitor delivery status in real time through the Next.js dashboard.

- Runtime logs: `[TBD]`
- Boss Zhipin configuration: `[TBD]`
- Job analysis: `[TBD]`
- AI configuration: `[TBD]`
- Liepin / 51job / Zhaopin configuration: `[TBD]`

### Quick Start Flow

```bash
# 1. Clone the repository
git clone https://github.com/napstablook666/JobRadar.git
cd JobRadar

# 2. Build and run with Gradle (JDK 21 required)
./gradlew bootRun

# 3. Open the web dashboard
#    Visit http://localhost:8888 in your browser.
#    Configure your target city, job keywords, salary range, and platforms.

# 4. The application will automatically:
#    - Launch a browser session for each platform
#    - Scan matching job listings based on your criteria
#    - Send customized AI-generated greeting messages
#    - Deliver image resumes (optional)
#    - Skip inactive HR and unsuitable positions
#    - Notify you via WeCom webhook with delivery results
```

## Quick Install

### Prerequisites

- **JDK 21** or later
- **Gradle** (bundled wrapper is included)

```bash
git clone https://github.com/napstablook666/JobRadar.git
cd JobRadar
./gradlew build
```

## Quick Start

1. Configure `src/main/resources/application.yaml` or set environment variables for your target city, keywords, and AI API key.
2. Run the application:

```bash
./gradlew bootRun
```

3. Open `http://localhost:8888` in your browser.
4. Configure platform accounts and complete cookie-based login through the web UI.
5. Start the automated delivery process from the dashboard.

> **Note:** Close VPN or proxy services when using Chinese recruitment platforms, as server IP addresses may be blocked and return no data.

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `JOBRADAR_BROWSER_MODE` | Browser visibility: `background` or `visible-login` | `background` |
| `JOBRADAR_BROWSER_TRANSPORT` | Transport mode: `browser` or `hybrid` | `hybrid` |
| `JOBRADAR_BROWSER_ENGINE` | Browser engine selection: `auto` or custom path | `auto` |
| `JOBRADAR_DELIVERY_RUNTIME` | Platform runtime: `isolated` (per-platform) or `shared` | `isolated` |
| `JOBRADAR_HEADLESS_SHELL` | Custom Chromium headless-shell executable path | (empty) |

### AI Integration

Create a `.env` file in the project root:

```env
HOOK_URL=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=your_key_here
BASE_URL=https://api.openai.com
API_KEY=sk-xxx
MODEL=gpt-5-nano
```

| Variable | Description |
|----------|-------------|
| `HOOK_URL` | WeCom bot webhook URL for delivery notifications |
| `BASE_URL` | OpenAI-compatible API endpoint (direct or proxy) |
| `API_KEY` | Your API key for the LLM service |
| `MODEL` | Model name used for greeting message generation |

## Documentation

| Topic | What it covers | Link |
|-------|---------------|------|
| Environment setup | JDK, Gradle, and browser driver configuration | [doc/doc.md](doc/doc.md) |
| Detailed guide | Platform-specific configuration and troubleshooting | [doc/Detail.md](doc/Detail.md) |
| Changelog | Release history and version updates | [doc/更新日志.md](doc/更新日志.md) |


## License

This project is licensed under the [PolyForm Noncommercial License 1.0.0](./LICENSE). Commercial use is prohibited without explicit permission from the copyright holder.