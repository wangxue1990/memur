# 碎碎念 Memur

语音优先的个人 AI 记忆维基 App。随心口述，AI 帮你管好所有碎想法。

## v2.0 架构：Agent Harness

整个后台是一个 **harness 智能体**：大模型根据用户对话自主选择、调用工具，多轮循环直到完成任务。
没有固定流水线，意图理解、执行路径全部由模型决策。

```
用户输入（语音转写 / 打字）
        │
        ▼
┌─────────────────────────────────────────────────────┐
│  AgentLoop（harness 核心）                            │
│                                                     │
│   ┌───────────────────────────────────────────┐     │
│   │ 循环：LLM 思考 → 发起工具调用 → 回填结果 → 续跑 │     │
│   └───────────────────────────────────────────┘     │
│        │ 工具执行                │ ask_user          │
│        ▼                        ▼                   │
│   工具实际干活            挂起等待用户回答              │
│   （Room/MD/闹钟/grep）   （回答后无缝续跑）           │
└─────────────────────────────────────────────────────┘
        │ 聊天流（Room 持久化）
        ▼
   对话式 UI（状态内联展示，不弹窗不抽屉）
```

### 工具集（14 个）

| 类别 | 工具 | 说明 |
|------|------|------|
| 知识检索 | `search_memory` | 对全部笔记 Markdown 做关键词 grep，返回命中片段 |
| | `read_note` / `list_notes` / `list_topics` | 读全文 / 最近笔记 / 主题清单 |
| 知识写入 | `write_note` | 保存清洗后的笔记（自动绑定本回合原始转写与音频） |
| | `update_note` / `delete_note` | 部分字段修改 / 删除（模型须先 ask_user 确认） |
| 提醒 | `create_reminder` | 一次性 / 每天 / 每周 / 每月，AlarmManager 精确调度 |
| | `list_reminders` / `delete_reminder` / `set_reminder_enabled` | 查询 / 删除 / 暂停恢复 |
| 交互 | `show_info_to_user` | 聊天流中插入结构化信息卡片 |
| | `ask_user` | 提问并挂起等待回答（确认兜底），支持快捷选项 |
| 系统 | `get_time` | 获取精确时间（推算「明天下午三点」等相对时间） |

### 扩展新能力

实现 `Tool` 接口 → 在 `agent/AgentSetup.kt` 注册一行，即完成接入。
提示词、循环、UI 无需改动。

## 功能总览

| 模块 | 实现 |
|------|------|
| 录音录入 | 首页录音键、后台/锁屏录音（前台服务）、桌面小组件一键录音 |
| 语音识别 | **完全本地**：sherpa-onnx + SenseVoice-Small int8（模型打包进 APK，不联网、不上传音频） |
| 智能体后台 | LLM 自主选择工具完成记录/提醒/检索/修改/删除；重要操作前 ask_user 确认 |
| 类Wiki知识库 | 相关碎念自动归并主题页（如「养猫」），支持重命名/合并/删除主题，手动编辑 |
| 检索记忆 | 无向量库：模型自主多轮 grep Markdown → 综合回答 |
| 提醒系统 | 语音建提醒（一次性/每天/每周/每月），到点推送，点击跳转原始碎念，开机自动恢复，可写系统日历 |
| 对话界面 | 聊天流 UI；处理状态（识别中/检索中/等待回答）内联显示，可随时取消 |
| 设置 | LLM 配置、音频保存策略、导出全部笔记（Markdown zip）、导入音频、夜间模式、隐私说明 |

## 工程结构

```
app/src/main/java/com/memur/app/
├── MemurApp.kt                    # Application + 依赖容器
├── MainActivity.kt                # 单 Activity + 深链
├── agent/                         # ★ Agent Harness
│   ├── Tool.kt                    #   Tool 接口 + 参数 Schema DSL
│   ├── ToolRegistry.kt            #   注册表（生成 OpenAI tools 声明）
│   ├── AgentLoop.kt               #   核心循环：思考→调工具→回填→续跑；ask_user 挂起；取消/排队
│   ├── AgentPrompts.kt            #   系统提示词
│   ├── AgentSetup.kt              #   依赖组装 + 工具注册
│   └── tools/                     #   KnowledgeTools / ReminderTools / InteractTools
├── data/                          # 数据层
│   ├── LlmClient.kt               #   OpenAI 兼容客户端（function calling）
│   ├── VoiceIntake.kt             #   录音 → 本地 ASR → 智能体
│   ├── AsrEngine.kt               #   SenseVoice 离线识别
│   ├── Repository / Entities / Daos / AppDatabase (Room v2)
│   ├── MarkdownStore.kt           #   笔记 MD 落盘（检索与导出数据源）
│   ├── RecordingService.kt        #   前台录音服务
│   ├── TtsController.kt           #   本地 TTS
│   └── reminder/                  #   AlarmManager 调度 + 通知 + 开机恢复
├── ui/                            # Compose UI
│   ├── screens/ChatScreen.kt      #   对话式首页（消息流 + 内联状态 + 输入/录音）
│   ├── screens/WikiScreen.kt 等   #   知识库 / 笔记详情 / 提醒 / 设置
│   └── Nav.kt / Theme.kt / NavBus.kt
└── widget/                        # 桌面录音小组件
```

## 构建

```bash
# 环境要求：JDK 17+、Android SDK（platforms;android-36、build-tools 36）
./gradlew assembleRelease   # 或用本地 gradle：tools/gradle/gradle-8.13/bin/gradle.bat assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk（约 300MB，含离线语音模型）
```

Gradle 已配置阿里云镜像仓库（国内网络友好）。

## 使用说明

1. **安装**：APK 拷贝到手机安装（允许未知来源）。
2. **首次使用**：点录音键 → 授予麦克风、通知权限 → 直接开说。
3. **普通碎念**：随口说，智能体自动清洗、起标题、打标签、归主题并保存。
4. **语音建提醒**：说「每周五晚上七点提醒我运动」。智能体会保存来源笔记并创建提醒，时间有歧义时会向你确认。
5. **检索记忆**：直接问「我上次说的买猫粮有什么要求？」，智能体多轮检索后综合回答。
6. **改内容**：直接说「把昨天的笔记标题改成XX」「删掉每周运动的提醒」，智能体调对应工具完成。
7. **隐私**：识别、播报均在本地；仅「智能体思考」会把清洗后的文字发往你配置的大模型 API；音频默认保留本机（可改为转写后即删）。

## 关键依赖

- [sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx) — 本地语音识别
- [SenseVoice-Small int8](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17) — 中英日韩粤离线识别模型
- Kotlin 2.1.20 · Jetpack Compose (BOM 2025.05) · Room 2.7 · AGP 8.13 · Gradle 8.13
