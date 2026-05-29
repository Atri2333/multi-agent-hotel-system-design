Context:
- prior_knowledge: {{prior_knowledge}}
- compacted_history: {{compacted_history}}
- iteration: {{iteration}}
- goal: {{goal}}
Review target:
- design: {{design}}
- diagram_code: {{diagram_code}}
Checks:
- If iteration = 1: overall system structure, CRN-1, CON-1, CON-2, CON-6
- If iteration = 2: HPS-1, HPS-2, HPS-3, HPS-4, HPS-5, HPS-6, CON-5
- If iteration = 3: QA-2, QA-3, QA-8
- If iteration = 4: QA-5, QA-6, QA-7, QA-9, CON-3, CON-4
- Do not fail the current iteration for missing drivers reserved for later iterations.
- ADD consistency and traceability to provided prior knowledge only
Required evidence policy:
- For each required driver, check explicit evidence in design and/or diagram, not only keyword mention.
- Accept concise evidence; do not require implementation-level detail.
- If evidence is missing, add one actionable issue with a concrete fix target.
Issue writing policy:
- Keep issues specific and non-redundant.
- Cap issues to at most 5 items.
Revision control:
- if issues exist, set pass=false and list actionable issues
