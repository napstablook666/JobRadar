'use client'

import { useState, useEffect } from 'react'
import { Save, Brain, Info, SearchCheck } from 'lucide-react'
import { Check, Copy, Loader2, Sparkles } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import PageHeader from '@/app/components/PageHeader'

const defaultSayHi = '您好，我是应用物理学应届毕业生，希望应聘该岗位，期待与您进一步沟通。'

export default function AiConfigPage() {
  const [aiConfig, setAiConfig] = useState({
    introduce: '',
    prompt: '',
    screenPrompt: '',
    jdAnalysisPrompt: '',
    messagePrompt: '',
  })

  const [loading, setLoading] = useState(false)
  // 是否启用AI（映射 boss_config.enable_ai）
  const [enableAi, setEnableAi] = useState<number>(0)
  const [previewForm, setPreviewForm] = useState({
    keyword: '放疗设备',
    jobName: '放疗设备现场技术支持工程师',
    jd: '负责放疗设备的现场技术支持、设备应用培训、问题反馈与客户沟通；医学物理、应用物理或医疗器械相关专业，应届生可培养，能接受出差和驻场。',
    sayHi: defaultSayHi,
  })
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewResult, setPreviewResult] = useState('')
  const [previewError, setPreviewError] = useState('')
  const [previewCopied, setPreviewCopied] = useState(false)
  const [jdAnalysisLoading, setJdAnalysisLoading] = useState(false)
  const [jdAnalysisResult, setJdAnalysisResult] = useState('')
  const [jdAnalysisError, setJdAnalysisError] = useState('')
  const [jdAnalysisForm, setJdAnalysisForm] = useState({
    keyword: '放疗应用',
    jobName: '放疗产品应用专员',
    jd: '负责放疗设备的临床应用培训、产品演示、装机应用配合及客户问题答疑；本科，医学物理或应用物理专业，应届生可投；工作地点杭州，接受省内短期出差；月薪8000元。',
    minScore: '70',
  })
  // 加载AI配置
  useEffect(() => {
    fetchAiConfig()
    fetchEnableAi()
  }, [])

  const fetchAiConfig = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/ai/config', {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
        },
      })

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`)
      }

      const result = await response.json()
      if (result.success && result.data) {
        setAiConfig({
          introduce: result.data.introduce || '',
          prompt: result.data.prompt || '',
          screenPrompt: result.data.screenPrompt || '',
          jdAnalysisPrompt: result.data.jdAnalysisPrompt || '',
          messagePrompt: result.data.messagePrompt || '',
        })
      }
    } catch (error) {
      console.error('加载AI配置失败:', error)
      // 如果加载失败，使用默认值，不影响用户使用
      console.log('使用默认配置')
    }
  }

  // 加载 boss_config 的 enable_ai 字段
  const fetchEnableAi = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/boss/config', {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
        },
      })

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`)
      }

      const result = await response.json()
      const raw = result?.config?.enableAi
      const val = String(raw ?? '').trim().toLowerCase()
      setEnableAi(val === '1' || val === 'true' || val === 'on' ? 1 : Number(raw) === 1 ? 1 : 0)
      const sayHi = result?.config?.sayHi
      if (typeof sayHi === 'string' && sayHi.trim()) {
        setPreviewForm((prev) => ({ ...prev, sayHi }))
      }
    } catch (e) {
      console.error('加载enable_ai失败:', e)
    }
  }

  // 切换 AI 开关并保存到 boss_config
  const toggleEnableAi = async () => {
    try {
      const next = enableAi ? 0 : 1
      setEnableAi(next)
      const response = await fetch('http://localhost:8888/api/boss/config', {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ enableAi: next }),
      })
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`)
      }
      // 可选：校验返回体
      // const updated = await response.json()
    } catch (e) {
      console.error('更新enable_ai失败:', e)
      // 回滚
      setEnableAi((prev) => (prev ? 0 : 1))
      alert('切换失败，请检查后端服务连接')
    }
  }

  const handleSave = async () => {
    setLoading(true)
    try {
      // 保存AI配置
      const response = await fetch('http://localhost:8888/api/ai/config', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(aiConfig),
      })

      const result = await response.json()

      if (result.success) {
        alert('AI配置已保存！')
      } else {
        alert('保存失败: ' + result.message)
      }
    } catch (error) {
      console.error('保存AI配置失败:', error)
      alert('保存失败，请检查服务器连接！')
    } finally {
      setLoading(false)
    }
  }

  const updatePreviewField = (field: keyof typeof previewForm, value: string) => {
    setPreviewForm((prev) => ({ ...prev, [field]: value }))
  }

  const handlePreview = async () => {
    setPreviewError('')
    setPreviewResult('')
    setPreviewCopied(false)

    if (!aiConfig.introduce.trim() || !aiConfig.prompt.trim()) {
      setPreviewError('请先填写技能介绍和AI提示词')
      return
    }
    if (!previewForm.jobName.trim() || !previewForm.jd.trim()) {
      setPreviewError('请填写测试岗位名称和岗位要求')
      return
    }

    setPreviewLoading(true)
    try {
      const response = await fetch('http://localhost:8888/api/ai/preview', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          introduce: aiConfig.introduce,
          prompt: aiConfig.prompt,
          ...previewForm,
        }),
      })

      const result = await response.json()
      if (!response.ok || !result.success) {
        throw new Error(result.message || `HTTP error! status: ${response.status}`)
      }

      setPreviewResult(String(result.data ?? ''))
    } catch (error) {
      console.error('AI预览生成失败:', error)
      setPreviewError(error instanceof Error ? error.message : 'AI预览生成失败，请检查服务器连接')
    } finally {
      setPreviewLoading(false)
    }
  }

  const handleCopyPreview = async () => {
    if (!previewResult) return

    try {
      await navigator.clipboard.writeText(previewResult)
      setPreviewCopied(true)
      window.setTimeout(() => setPreviewCopied(false), 1600)
    } catch (error) {
      console.error('复制AI预览失败:', error)
      setPreviewError('复制失败，请手动选择结果文本')
    }
  }
  const updateJdAnalysisField = (field: keyof typeof jdAnalysisForm, value: string) => {
    setJdAnalysisForm((prev) => ({ ...prev, [field]: value }))
  }

  const handleJdAnalysis = async () => {
    setJdAnalysisError('')
    setJdAnalysisResult('')
    if (!aiConfig.introduce.trim()) {
      setJdAnalysisError('请先填写候选人技能介绍')
      return
    }
    if (!jdAnalysisForm.jobName.trim() || !jdAnalysisForm.jd.trim()) {
      setJdAnalysisError('请填写测试岗位名称和岗位要求')
      return
    }

    setJdAnalysisLoading(true)
    try {
      const response = await fetch('http://localhost:8888/api/ai/jd-analysis-preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          candidate: aiConfig.introduce,
          jdAnalysisPrompt: aiConfig.jdAnalysisPrompt,
          screenPrompt: aiConfig.screenPrompt,
          ...jdAnalysisForm,
        }),
      })
      const result = await response.json()
      if (!response.ok || !result.success) {
        throw new Error(result.message || `HTTP error! status: ${response.status}`)
      }
      setJdAnalysisResult(String(result.data ?? ''))
    } catch (error) {
      console.error('AI JD 分析失败:', error)
      setJdAnalysisError(error instanceof Error ? error.message : 'AI JD 分析失败，请检查服务器连接')
    } finally {
      setJdAnalysisLoading(false)
    }
  }

  const handleCopyJdAnalysis = async () => {
    if (!jdAnalysisResult) return
    try {
      await navigator.clipboard.writeText(jdAnalysisResult)
    } catch (error) {
      console.error('复制 AI JD 分析失败:', error)
      setJdAnalysisError('复制失败，请手动选择结果文本')
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<Brain className="text-2xl" />}
        title="AI配置"
        subtitle="配置AI相关的技能介绍和提示词"
        iconClass="text-white"
        accentBgClass="bg-purple-500"
        actions={
          <Button
            onClick={handleSave}
            size="sm"
            className="rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105"
            type="button"
            disabled={loading}
          >
            <Save className="mr-1" /> 保存配置
          </Button>
        }
      />

      <div className="space-y-6">
        {/* AI配置 */}
        <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
          <CardHeader className="flex items-start gap-4">
            <div className="min-w-0 space-y-2">
              <CardTitle className="flex items-center gap-2">
                <Brain className="text-primary" />
                AI配置
              </CardTitle>
              <CardDescription>配置AI相关的技能介绍和提示词，用于生成个性化求职内容</CardDescription>
            </div>
            <div>
              <button
                type="button"
                aria-label="AI启用开关"
                onClick={toggleEnableAi}
                className={`relative inline-flex h-7 w-14 rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-emerald-400/40 border border-white/30 shadow-[inset_0_1px_0_rgba(255,255,255,.25)] ${enableAi ? 'bg-emerald-500/80 hover:bg-emerald-500' : 'bg-white/10 hover:bg-white/15'}`}
              >
                <span
                  className={`absolute top-1 left-1 h-5 w-5 rounded-full bg-white shadow transition-transform ${enableAi ? 'translate-x-7' : 'translate-x-0'}`}
                />
              </button>
            </div>
          </CardHeader>
          <CardContent>
            <div className="space-y-6">
              <div className="space-y-2">
                <Label htmlFor="introduce">技能介绍</Label>
                <Textarea
                  id="introduce"
                  value={aiConfig.introduce}
                  onChange={(e) => setAiConfig({ ...aiConfig, introduce: e.target.value })}
                  placeholder="请输入您的技能介绍，例如：我熟练使用Java、Python等语言进行开发..."
                  className="min-h-[150px] resize-y"
                />
                <p className="text-xs text-muted-foreground">
                  详细描述您的技能、经验和专业背景，AI将使用这些信息生成个性化的求职文本
                </p>
              </div>

              <div className="space-y-2">
                <Label htmlFor="prompt">AI提示词</Label>
                <Textarea
                  id="prompt"
                  value={aiConfig.prompt}
                  onChange={(e) => setAiConfig({ ...aiConfig, prompt: e.target.value })}
                  placeholder="请输入AI提示词模板，例如：我目前在找工作，%s，我期望的岗位方向是【%s】..."
                  className="min-h-[150px] resize-y"
                />
                <p className="text-xs text-muted-foreground">
                  AI使用的提示词模板，支持使用 %s 作为占位符，用于动态插入内容
                </p>
              </div>

              <div className="space-y-2">
                <Label htmlFor="screenPrompt">批量评分模板</Label>
                <Textarea
                  id="screenPrompt"
                  value={aiConfig.screenPrompt}
                  onChange={(e) => setAiConfig({ ...aiConfig, screenPrompt: e.target.value })}
                  placeholder="使用 {{candidate}}、{{keyword}}、{{min_score}}、{{rules}}、{{jobs}}"
                  className="min-h-[190px] resize-y font-mono text-xs"
                />
                <p className="text-xs text-muted-foreground">
                  输出唯一 JSON：items[].jobId、score、decision、reasonCodes、reason。支持命名变量，不使用 %s。
                </p>
              </div>

              <div className="space-y-2">
                <Label htmlFor="messagePrompt">通过岗位话术模板</Label>
                <Textarea
                  id="messagePrompt"
                  value={aiConfig.messagePrompt}
                  onChange={(e) => setAiConfig({ ...aiConfig, messagePrompt: e.target.value })}
                  placeholder="使用 {{candidate}}、{{style_rules}}、{{accepted_jobs}}"
                  className="min-h-[170px] resize-y font-mono text-xs"
                />
                <p className="text-xs text-muted-foreground">
                  输出唯一 JSON：items[].jobId、message。话术由后端校验单行和长度后才进入投递流程。
                </p>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* AI JD 分析 */}
        <Card className="border-cyan-500/25 animate-in fade-in slide-in-from-bottom-5 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <SearchCheck className="h-5 w-5 text-cyan-400" />
              AI JD 分析
            </CardTitle>
            <CardDescription>
              筛选标准 · Boss / 猎聘 / 51job
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-6">
            <div className="grid gap-5 xl:grid-cols-[minmax(0,1.35fr)_minmax(320px,0.65fr)]">
              <div className="space-y-2">
                <Label htmlFor="jdAnalysisPrompt">AI JD 分析提示词</Label>
                <Textarea
                  id="jdAnalysisPrompt"
                  value={aiConfig.jdAnalysisPrompt}
                  onChange={(e) => setAiConfig({ ...aiConfig, jdAnalysisPrompt: e.target.value })}
                  placeholder="请输入岗位筛选标准"
                  className="min-h-[430px] resize-y font-mono text-xs leading-5"
                />
                <p className="text-xs text-muted-foreground">业务规则；JSON 协议由批量评分模板控制。</p>
              </div>

              <div className="space-y-4">
                <div className="rounded-lg border border-emerald-500/25 bg-emerald-500/5 p-4">
                  <p className="mb-3 text-sm font-semibold text-emerald-300">优先保留</p>
                  <ul className="space-y-2 text-xs leading-5 text-muted-foreground">
                    <li>放疗、直线加速器、CT 模拟定位、影像引导、后装治疗</li>
                    <li>临床培训、产品演示、科室带教、装机应用配合、客户答疑</li>
                    <li>应届 / 经验不限 / 1 年以内，本科相关专业可投</li>
                    <li>杭州或江西，省内及周边短期出差，月薪 6000 元以上</li>
                  </ul>
                </div>
                <div className="rounded-lg border border-red-500/25 bg-red-500/5 p-4">
                  <p className="mb-3 text-sm font-semibold text-red-300">直接淘汰</p>
                  <ul className="space-y-2 text-xs leading-5 text-muted-foreground">
                    <li>拆机维修、电路或机械排故、备件更换及维修工具硬要求</li>
                    <li>承担销售额、回款或陌生客户开发指标</li>
                    <li>长期全国出差 / 驻外，或 3 年以上放疗应用经验且拒绝应届</li>
                    <li>激光、内镜、超声及普通机电设备岗位</li>
                  </ul>
                </div>
                <div className="rounded-lg border border-amber-500/25 bg-amber-500/5 p-4 text-xs leading-5 text-muted-foreground">
                  命中淘汰条件必须输出 SKIP；关键条件缺失时输出 REVIEW；达到阈值且无硬冲突才输出 PASS。
                </div>
              </div>
            </div>

            <div className="border-t border-border/70 pt-5">
              <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                <div>
                  <p className="text-sm font-semibold text-foreground">测试一个岗位</p>
                  <p className="mt-1 text-xs text-muted-foreground">当前草稿 · 不保存 · 不投递</p>
                </div>
                <Button type="button" onClick={handleJdAnalysis} disabled={jdAnalysisLoading}>
                  {jdAnalysisLoading ? (
                    <><Loader2 className="mr-2 h-4 w-4 animate-spin" />分析中...</>
                  ) : (
                    <><SearchCheck className="mr-2 h-4 w-4" />分析 JD</>
                  )}
                </Button>
              </div>

              <div className="grid gap-4 md:grid-cols-[1fr_1fr_140px]">
                <div className="space-y-2">
                  <Label htmlFor="jd-analysis-keyword">搜索关键词</Label>
                  <Input
                    id="jd-analysis-keyword"
                    value={jdAnalysisForm.keyword}
                    onChange={(e) => updateJdAnalysisField('keyword', e.target.value)}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="jd-analysis-job-name">岗位名称</Label>
                  <Input
                    id="jd-analysis-job-name"
                    value={jdAnalysisForm.jobName}
                    onChange={(e) => updateJdAnalysisField('jobName', e.target.value)}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="jd-analysis-score">通过分数</Label>
                  <Input
                    id="jd-analysis-score"
                    type="number"
                    min="0"
                    max="100"
                    value={jdAnalysisForm.minScore}
                    onChange={(e) => updateJdAnalysisField('minScore', e.target.value)}
                  />
                </div>
                <div className="space-y-2 md:col-span-3">
                  <Label htmlFor="jd-analysis-jd">岗位要求 / JD</Label>
                  <Textarea
                    id="jd-analysis-jd"
                    value={jdAnalysisForm.jd}
                    onChange={(e) => updateJdAnalysisField('jd', e.target.value)}
                    className="min-h-[135px] resize-y"
                  />
                </div>
              </div>

              {(jdAnalysisLoading || jdAnalysisResult || jdAnalysisError) && (
                <div className="mt-4 rounded-lg border border-cyan-500/20 bg-cyan-500/5 p-4">
                  {jdAnalysisLoading && <p className="text-sm text-muted-foreground">正在分析岗位职责和硬性条件...</p>}
                  {!jdAnalysisLoading && jdAnalysisError && <p className="text-sm text-red-400">{jdAnalysisError}</p>}
                  {!jdAnalysisLoading && !jdAnalysisError && jdAnalysisResult && (
                    <div className="space-y-3">
                      <div className="flex items-center justify-between gap-3">
                        <p className="text-sm font-semibold">分析结果</p>
                        <Button type="button" variant="outline" size="sm" onClick={handleCopyJdAnalysis}>
                          <Copy className="mr-1.5 h-4 w-4" />复制结果
                        </Button>
                      </div>
                      <pre className="overflow-x-auto whitespace-pre-wrap break-words rounded-md border bg-background p-4 text-xs leading-5 text-foreground">{jdAnalysisResult}</pre>
                    </div>
                  )}
                </div>
              )}
            </div>
          </CardContent>
        </Card>

        {/* AI生成求职文本测试 */}
        <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Sparkles className="h-5 w-5 text-primary" />
              效果预览测试
            </CardTitle>
            <CardDescription>
              使用当前正在编辑的技能介绍和AI提示词，生成一条HR打招呼语；不会保存配置，也不会启动投递
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid gap-4 md:grid-cols-2">
              <div className="space-y-2">
                <Label htmlFor="preview-keyword">测试关键词</Label>
                <Input
                  id="preview-keyword"
                  value={previewForm.keyword}
                  onChange={(e) => updatePreviewField('keyword', e.target.value)}
                  placeholder="例如：放疗设备"
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="preview-job-name">测试岗位名称</Label>
                <Input
                  id="preview-job-name"
                  value={previewForm.jobName}
                  onChange={(e) => updatePreviewField('jobName', e.target.value)}
                  placeholder="例如：放疗设备现场技术支持工程师"
                />
              </div>

              <div className="space-y-2 md:col-span-2">
                <Label htmlFor="preview-jd">岗位要求 / JD</Label>
                <Textarea
                  id="preview-jd"
                  value={previewForm.jd}
                  onChange={(e) => updatePreviewField('jd', e.target.value)}
                  placeholder="粘贴测试岗位的职责和任职要求"
                  className="min-h-[120px] resize-y"
                />
              </div>

              <div className="space-y-2 md:col-span-2">
                <Label htmlFor="preview-say-hi">参考打招呼语</Label>
                <Textarea
                  id="preview-say-hi"
                  value={previewForm.sayHi}
                  onChange={(e) => updatePreviewField('sayHi', e.target.value)}
                  placeholder="可选，用于提示词中的第5个 %s"
                  className="min-h-[88px] resize-y"
                />
              </div>
            </div>

            <div className="mt-5 flex flex-wrap items-center justify-between gap-3">
              <p className="text-xs text-muted-foreground">
                每次点击会调用一次当前 AI 模型，测试内容不会写入配置。
              </p>
              <Button
                onClick={handlePreview}
                type="button"
                disabled={previewLoading}
                className="bg-emerald-600 text-white hover:bg-emerald-700"
              >
                {previewLoading ? (
                  <>
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    生成中...
                  </>
                ) : (
                  <>
                    <Sparkles className="mr-2 h-4 w-4" />
                    生成求职文本
                  </>
                )}
              </Button>
            </div>

            {(previewLoading || previewResult || previewError) && (
              <div className="mt-5 rounded-lg border border-primary/20 bg-primary/5 p-4">
                {previewLoading && (
                  <div className="flex items-center gap-2 text-sm text-muted-foreground">
                    <Loader2 className="h-4 w-4 animate-spin" />
                    正在请求 AI，请稍候...
                  </div>
                )}

                {!previewLoading && previewError && (
                  <p className="text-sm text-red-600 dark:text-red-400">{previewError}</p>
                )}

                {!previewLoading && !previewError && previewResult && (
                  <div className="space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <p className="text-sm font-semibold text-foreground">生成结果</p>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={handleCopyPreview}
                        title="复制生成结果"
                      >
                        {previewCopied ? (
                          <Check className="mr-1.5 h-4 w-4" />
                        ) : (
                          <Copy className="mr-1.5 h-4 w-4" />
                        )}
                        {previewCopied ? '已复制' : '复制结果'}
                      </Button>
                    </div>
                    <div className="whitespace-pre-wrap break-words rounded-md border bg-background p-4 text-sm leading-6 text-foreground">
                      {previewResult}
                    </div>
                  </div>
                )}
              </div>
            )}
          </CardContent>
        </Card>

        {/* ��用说明 */}
        <Card className="border-primary/20 bg-primary/5 animate-in fade-in slide-in-from-bottom-6 duration-700">
          <CardContent className="pt-6">
            <div className="flex gap-3">
              <Info className="h-5 w-5 text-primary flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-sm text-foreground mb-2">
                  <strong className="font-semibold">使用说明：</strong>
                </p>
                <ul className="text-sm text-muted-foreground space-y-2">
                  <li className="flex items-start gap-2">
                    <span className="text-primary mt-0.5">•</span>
                    <span><strong>技能介绍：</strong>用于AI了解您的专业技能、工作经验和技术背景，是生成个性化内容的基础</span>
                  </li>
                  <li className="flex items-start gap-2">
                    <span className="text-primary mt-0.5">•</span>
                    <span><strong>AI提示词：</strong>定义AI生成内容的模板和风格，支持使用 <code className="bg-muted px-1 py-0.5 rounded text-xs">%s</code> 作为占位符</span>
                  </li>
                  <li className="flex items-start gap-2">
                    <span className="text-primary mt-0.5">•</span>
                    <span><strong>效果：</strong>配置保存后，AI将在自动投递时使用这些信息生成匹配度高的求职沟通内容</span>
                  </li>
                  <li className="flex items-start gap-2">
                    <span className="text-primary mt-0.5">•</span>
                    <span><strong>提示：</strong>建议定期更新技能介绍以反映最新的技能和经验，提高匹配成功率</span>
                  </li>
                </ul>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* 操作按钮（已迁移到右上角 PageHeader.actions，保持与环境配置一致） */}
      </div>
    </div>
  )
}
