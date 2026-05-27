# 项目说明：Multi-Agent（ADD）酒店定价系统架构设计

本项目实现了一个基于多 Agent 协作的“ADD 3.0（Attribute-Driven Design）式”架构设计流程。程序在命令行中按迭代执行多个 Agent（Orchestrator/Architect/Critic/ContextCompactor + 人类检查点），并将每轮的设计产物写入 Markdown 报告与对话日志。

---

## 1. 项目结构与文件职责

### 1.1 顶层目录

- [pom.xml](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/pom.xml)：Maven 配置（JDK 17）。依赖包含 Spring AI、Spring AI Alibaba Graph（用于 StateGraph 工作流）、Jackson、SLF4J 等。
- [architecture_report.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/architecture_report.md)：一次运行生成的“架构报告”示例（实际运行时也会在输出目录生成同名文件）。
- [2026SoftArch-assignment2_extracted.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/2026SoftArch-assignment2_extracted.md)：作业材料/题目提取文档（与引导代码阅读相关，但不参与运行）。

### 1.2 Java 代码结构（src/main/java）

入口与装配

- [App.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/App.java)：程序入口。
  - 从环境变量/配置加载运行参数（迭代次数、输出目录、模型配置）。
  - 选择 ChatModel（OpenAI 兼容 / DashScope / Mock）。
  - 创建 MultiAgentEngine 并启动 `run()`。
- [AppConfig.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/config/AppConfig.java)：配置解析。
  - `ma.iterations` / `MA_ITERATIONS`：迭代轮数，默认 4。
  - `ma.max-revisions` / `MA_MAX_REVISIONS`：每轮最大“批改”次数（Architect 返工上限），默认 2。
  - `ma.output-dir` / `MA_OUTPUT_DIR`：输出目录（生成日志与报告）；为空则使用当前目录绝对路径。
  - `spring.ai.openai.*` / `APP_OPENAI_*`：OpenAI 兼容 API BaseUrl、ApiKey、Model（默认模型名 `qwen3-32b`）。

多 Agent 工作流引擎

- [MultiAgentEngine.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/engine/MultiAgentEngine.java)：核心编排。
  - 使用 Alibaba Cloud AI Graph 的 `StateGraph` 定义节点（node）与条件边（edge）。
  - 负责加载 prompts、向 LLM 发起 JSON 输出调用、维护状态键（iteration、goal、critic_issues…）、写入 markdown 日志/报告。

日志与交互

- [ConsoleIO.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/io/ConsoleIO.java)：人类检查点的命令行输入（只接受 `approve/retry`）。
- [MarkdownLogWriter.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/log/MarkdownLogWriter.java)：输出写入器。
  - `conversation_log.md`：记录每个节点的输入（prompt）与输出（LLM JSON）。
  - `architecture_report.md`：按 iteration 追加 Design、Mermaid 图、Critic Issues、Decision Log 等。

模型/数据结构

- [Turn.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/state/Turn.java)：一次节点执行的记录（ts/node/input/outputJson）。
- [IterationResult.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/state/IterationResult.java)：一轮迭代的产物汇总。
- [ArchitectOutput.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/engine/model/ArchitectOutput.java)、[CriticOutput.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/engine/model/CriticOutput.java)、[OverallState.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/state/OverallState.java)：早期/备用数据结构（当前主流程主要用 Graph 的 OverAllState + Map 读写状态）。

LLM 适配与 Mock

- [MockChatModel.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/ai/MockChatModel.java)：无真实模型配置时的兜底 ChatModel，实现“能跑通流程”的固定 JSON 输出。
- [llm/*](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/llm)：早期 OpenAI 兼容 HTTP 客户端接口（当前主流程已改用 Spring AI `ChatClient`）。
  - [LlmClient.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/llm/LlmClient.java)：接口。
  - [OpenAiCompatibleChatClient.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/llm/OpenAiCompatibleChatClient.java)：HTTP 调用 `/v1/chat/completions` 并尝试解析 JSON。
  - [MockLlmClient.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/llm/MockLlmClient.java)：对应接口的 mock。

### 1.3 Prompts（src/main/resources/prompts）

项目将每个 Agent 的 system/user prompt 都外置为资源文件，运行时由 [MultiAgentEngine.PromptTemplates.load()](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/engine/MultiAgentEngine.java#L601-L625) 加载，并使用简单的 `{{var}}` 替换渲染。

基础上下文

- [prior_knowledge.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/resources/prompts/prior_knowledge.md)：唯一允许使用的领域知识/ADD 方法与 HPS（Hotel Pricing System）案例输入（用例、质量属性、约束等）。主流程会把它写入 `conversation_log.md` 的开头。

Orchestrator（确定每轮目标）

- [orchestrator_system.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/resources/prompts/orchestrator_system.md)：规定 Orchestrator 行为、固定 4 轮 iteration goal、输出 JSON 字段。
- [orchestrator_user.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/resources/prompts/orchestrator_user.md)：把 `prior_knowledge`、`compacted_history`、`iteration` 传入，要求产出本轮 `iteration_goal`。

Architect（产出设计）

- [architect_system.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/resources/prompts/architect_system.md)：规定 Architect 只能做 ADD Step 2-5 风格设计，并输出 JSON：`design/diagram_code/decision_log`。
- [architect_user.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/resources/prompts/architect_user.md)：注入本轮 `goal`、历史压缩 `compacted_history`、以及可选 `critic_block`（用于返工时携带批评点）。
- [architect_critic_block.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/resources/prompts/architect_critic_block.md)：当 Critic 不通过时，把 `issues` 作为“必须修复项”拼到 Architect 的 user prompt 尾部。

Critic（质量检查与返工触发）

- [critic_system.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/resources/prompts/critic_system.md)：规定 Critic 做 QA/约束覆盖检查，输出 JSON：`pass/issues/decision_log`。
- [critic_user.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/resources/prompts/critic_user.md)：传入本轮 `goal`、Architect 的 `design/diagram_code`，并列出检查清单与返工规则。

ContextCompactor（压缩历史上下文）

- [context_compactor_system.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/resources/prompts/context_compactor_system.md)：规定只“压缩”不新增决策，输出 JSON：`compacted_history`。
- [context_compactor_user.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/resources/prompts/context_compactor_user.md)：把过去的 iteration results 列表（文本化）传入，要求压缩到 1200 字符以内。

Human Checkpoints（人工闸门）

- [human_checkpoint_before.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/resources/prompts/human_checkpoint_before.md)：每轮开始前，展示 goal，要求输入 `approve/retry`。
- [human_checkpoint_after.md](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/resources/prompts/human_checkpoint_after.md)：每轮结束后，展示 goal，要求输入 `approve/retry`。

---

## 2. 整体协作流程（多 Agent + 每轮执行细节）

### 2.1 Agent 角色（按节点）

所有 Agent 都通过 [MultiAgentEngine.callJson()](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/engine/MultiAgentEngine.java#L374-L389) 调用 LLM，并约定输出为 JSON（若解析失败则会把原始文本包进 `raw_text` 字段）。

- Orchestrator：根据 iteration（1~N）生成本轮 `iteration_goal`，并把对话写入日志。
- HumanCheckpointBefore：在进入本轮设计前，人工决定是否继续（approve）或重来（retry，回到 Orchestrator）。
- Architect：根据 `prior_knowledge + compacted_history + iteration_goal` 产出设计（design）、结构图（Mermaid）、决策日志（decision_log）。
- Critic：对 Architect 产物做约束/质量属性/一致性检查；若不通过则提出 issues 并触发 Architect 返工。
- Scribe：把本轮最终定稿写入 `architecture_report.md`（并记录一个“Finalize”turn 到 `conversation_log.md`）。
- ContextCompactor：将全部历史迭代结果压缩成 `compacted_history`，用于下一轮 prompt，避免上下文无限增长。
- HumanCheckpointAfter：在本轮结束后做人工确认；`retry` 会停留在该检查点反复询问（并写一条 note）。
- NextIteration：清理本轮状态（issues/decision_log/human flags 等），iteration++，进入下一轮或结束。

### 2.2 一轮 iteration 的状态机流程

主流程在 [MultiAgentEngine.buildGraph()](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/engine/MultiAgentEngine.java#L65-L372) 中定义，整体可以概括为：

1. Orchestrator：计算/生成 `iteration_goal`
2. HumanCheckpointBefore：
   - approve：进入 Architect
   - retry：回到 Orchestrator（重新生成 goal/重开本轮）
3. Architect：输出 `architect_design/architect_mermaid/architect_decision_log`
4. Critic：输出 `critic_pass/critic_issues/critic_decision_log` 并决定：
   - pass：进入 Scribe
   - revise：若 `revision_count < maxRevisions`，回到 Architect，且把 `critic_issues` 注入 `architect_critic_block.md`
   - maxed：达到返工上限也会进入 Scribe（带着未解决 issues 定稿）
5. Scribe：把 IterationResult 写入 `architecture_report.md`
6. ContextCompactor：把所有历史结果压缩为 `compacted_history`
7. HumanCheckpointAfter：
   - approve：进入 NextIteration
   - retry：停留在该检查点重复询问（不回滚内容）
8. NextIteration：
   - iteration <= cfg.iterations：回到 Orchestrator 开启下一轮
   - 否则：结束（END）

### 2.3 输出物（便于组内复盘）

运行后输出目录会包含：

- `conversation_log.md`：逐 turn 记录“每个节点的输入 prompt + LLM 输出 JSON”（最适合排查 prompt/解析/路由问题）。
- `architecture_report.md`：逐 iteration 汇总“Goal/Design/Mermaid/Critic Issues/Decision Log”（最适合组内评审与写作）。

---

## 3. 代码阅读建议（从哪里开始）

- 从入口看整体：先读 [App.java](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/App.java) 的模型选择与 Engine 启动。
- 再看流程图：重点读 [MultiAgentEngine.buildGraph()](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/engine/MultiAgentEngine.java#L65-L372) 的节点与条件边。
- 最后对照 prompts：把每个节点拼的 system/user prompt 与输出 JSON 结构对应到 `prompts/*.md`。

---

## 4. 已知实现细节与注意点（便于理解行为）

- iteration goal 的来源：代码里有一个 “建议目标”默认值（[defaultIterationGoal](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/engine/MultiAgentEngine.java#L406-L414)），但实际会以 Orchestrator 的 JSON 输出 `iteration_goal` 为准（输出缺失才回退到默认值）。
- Critic prompt 的上下文注入：`critic_user.md` 文本里写了 `prior_knowledge/compacted_history`，但当前实现渲染 Critic user prompt 时只注入了 `iteration/goal/design/diagram_code`（[critic 节点](file:///d:/code/java/Software_Architecture2026/Multi-Agent-Hotel-System-Design/src/main/java/com/hotel/system/engine/MultiAgentEngine.java#L181-L216)）。如果你们希望 Critic 也严格受“只用 prior_knowledge + compacted_history”约束，需要把这两个变量也传入渲染参数。
- HumanCheckpointAfter 的 retry：只会重复询问，不会回滚本轮内容（并写 note），适合做“确认输出已经写入/是否继续下一轮”的闸门。

