import json
import logging
from typing import Dict, Any, List

from app.agents.state import AgentWorkflowState
from app.agents.llm import get_chat_model, invoke_llm, parse_json_response
from app.agents.text_utils import keywords, dedupe_queries
from app.security.guardrails import inspect_prompt

logger = logging.getLogger("nexus.agents.planner")

MAX_PLAN_QUERIES = 4

PLANNER_SYSTEM_PROMPT = (
    "You are the planning step of a retrieval-augmented research agent. "
    "Decompose the user's question into 1 to 4 short, self-contained search queries that will be run "
    "against a semantic + keyword search engine over the user's own documents. "
    "If reviewer feedback is provided, write queries that close the gaps it describes. "
    'Respond ONLY with JSON of the form {"queries": ["query 1", "query 2"]}.'
)


def _deterministic_plan(user_prompt: str, critique: Dict[str, Any]) -> List[str]:
    """Honest rule-based plan: the original question, a keyword-only variant, and critic-suggested queries."""
    plan: List[str] = [user_prompt]
    if critique and not critique.get("is_valid", True):
        plan.extend(critique.get("suggested_queries") or [])
    kw = " ".join(keywords(user_prompt))
    if kw:
        plan.append(kw)
    return dedupe_queries(plan, limit=MAX_PLAN_QUERIES)


async def planner_node(state: AgentWorkflowState) -> Dict[str, Any]:
    """
    Planner Node: turns the user question into concrete search queries.
    Uses the configured LLM when available; otherwise a transparent deterministic plan.
    Re-plans with the critic's feedback when the critic rejected the previous draft.
    """
    user_prompt = state.get("user_prompt", "").strip()
    task_id = state.get("task_id", "")
    critique = state.get("critique", {}) or {}
    iteration = state.get("iteration_count", 0)
    errors: List[str] = list(state.get("errors", []))
    token_usage = int(state.get("token_usage", 0) or 0)

    guardrail = inspect_prompt(user_prompt)
    if guardrail.is_blocked:
        updated_meta = dict(state.get("metadata", {}))
        updated_meta.update({
            "security_blocked": True,
            "security_category": guardrail.category,
            "safe_refusal": guardrail.safe_refusal,
        })
        return {
            "plan": [],
            "metadata": updated_meta,
            "current_node": "planner",
            "status": "SECURITY_BLOCKED",
        }

    replanning = bool(critique) and not critique.get("is_valid", True)
    logger.info(f"[Planner] task_id='{task_id}', iteration={iteration}, replanning={replanning}")

    plan: List[str] = []
    if get_chat_model() is not None:
        user_msg = f"Question: {user_prompt}"
        if replanning:
            user_msg += (
                f"\n\nPrevious queries: {json.dumps(state.get('plan', []))}"
                f"\nReviewer feedback: {critique.get('feedback', '')}"
                f"\nSuggested follow-up queries: {json.dumps(critique.get('suggested_queries', []))}"
            )
        try:
            text, tokens = await invoke_llm(PLANNER_SYSTEM_PROMPT, user_msg)
            token_usage += tokens
            parsed = parse_json_response(text)
            raw_queries = parsed.get("queries", []) if isinstance(parsed, dict) else parsed
            if not isinstance(raw_queries, list):
                raise ValueError("'queries' is not a list")
            plan = dedupe_queries([user_prompt] + [str(q) for q in raw_queries], limit=MAX_PLAN_QUERIES)
        except Exception as exc:
            errors.append(f"planner: LLM planning failed, used deterministic plan ({type(exc).__name__}: {exc})")
            logger.warning(f"[Planner] LLM planning failed for task '{task_id}': {exc}")

    if not plan:
        plan = _deterministic_plan(user_prompt, critique)

    logger.info(f"[Planner] Generated {len(plan)} search queries.")
    return {
        "plan": plan,
        "errors": errors,
        "token_usage": token_usage,
        "current_node": "planner",
        "status": "PLANNING_COMPLETED",
    }
