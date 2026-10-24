package com.openminis.app.agent

/**
 * [Mines-38.9.6 i17] 技能优先 (user 2026-10-18): the agent looks for a matching installed skill before any multi-step task
 * and follows it; after a new multi-step task with no skill it saves one (no question, one line to the user). Never for
 * bulk / batch account registration. Toggle: Settings › Agent Tools › 技能优先 (AgentToolSwitch.SKILL_FIRST, default on).
 * Fixed text so the system-prompt prefix stays cache-stable.
 */
object SkillFirst {
    const val PROMPT = """Skill-first (技能优先, ON):
- Before starting any multi-step task, look for an installed skill that matches it: check the skills listed above, and if none is listed by name, search /var/minis/skills/*/SKILL.md (e.g. `grep -ril <keyword> /var/minis/skills --include=SKILL.md`). If one matches, file_read its SKILL.md first and follow it.
- If no skill matches, the task was new, and it took several steps, then AFTER it succeeds write a new skill at /var/minis/skills/<short-kebab-id>/SKILL.md: YAML front matter with `name` and `description` (one line: when to use it), then the steps that actually worked, the commands/paths, and pitfalls you hit. Do not ask first; just save it and tell the user in one line: 「已存成技能：<name>」.
- Never put passwords, API keys, tokens, cookies, verification codes or personal data into a skill. Do not write skills for bulk / batch account registration or anything that mass-creates accounts.
- A single-step request (one question, one command) needs no skill lookup."""
}
