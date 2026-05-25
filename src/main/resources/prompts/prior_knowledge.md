Project: Multi-Agent Hotel System Design (ADD-style)
Agents:
- Orchestrator: plan iteration goals, route nodes
- Architect: produce design + Mermaid
- Critic: QA/constraints check, request revision (max 2)
- Scribe: consolidate final, log decisions and rationale
- Context Compactor: summarize history to avoid context overflow

Rules:
- Human checkpoint input only: approve / retry
- Structured JSON output from agents: design, diagram_code, issues, decision_log fields (as applicable)
- Run 4 iterations, sequential, with critic-architect revision loop up to 2
