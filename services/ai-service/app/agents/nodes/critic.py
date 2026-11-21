import re
import json
import logging
from typing import Dict, Any, List

from app.agents.state import AgentWorkflowState
from app.agents.llm import get_chat_model, invoke_llm, parse_json_response
from app.agents.text_utils import keywords

logger = logging.getLogger("nexus.agents.critic")

_FOOTNOTE_MARKER = re.compile(r"\[\^(\d+)\](?!:)")
_FOOTNOTE_DEFINITION = re.compile(r"^\[\^(\d+)\]:", re.MULTILINE)

CRITIC_SYSTEM_PROMPT = (
    "You are a strict fact-checking reviewer. Compare the draft answer with the evidence. "
    "Flag any claim that is not supported by the evidence and any part of the question left unanswered. "
    'Respond ONLY with JSON: {"is_valid": true|false, "hallucination_score": <0.0-1.0 share of unsupported claims>, '
    '"feedback": "<one or two sentences>", "suggested_queries": ["<search query to close a gap>", ...]}'
)


def _structural_audit(draft_report: str, research_data: List[Dict[str, Any]], user_prompt: str) -> Dict[str, Any]:
    """Deterministic checks that hold in every mode: citations present, no dangling footnotes, topical coverage."""
    used = {int(n) for n in _FOOTNOTE_MARKER.findall(draft_report)}
    defined = {int(n) for n in _FOOTNOTE_DEFINITION.findall(draft_report)}
    dangling = sorted(used - defined)

    prompt_terms = keywords(user_prompt)
    evidence_text = " ".join(str(item.get("content", "")) for item in research_data).lower()
    covered = [t for t in prompt_terms if t in evidence_text]
    missing = [t for t in prompt_terms if t not in evidence_text]
    coverage = (len(covered) / len(prompt_terms)) if prompt_terms else 1.0

    if not research_data:
        return {
            "is_valid": False,
            "feedback": "No evidence was retrieved; the report correctly declines to answer. Retrying with narrower queries.",
            "hallucination_score": 0.0,
            "suggested_queries": prompt_terms[:3],
            "citation_count": 0,
            "coverage": 0.0,
        }
    if dangling:
        return {
            "is_valid": False,
            "feedback": f"Draft cites footnotes {dangling} that do not exist in the references.",
            "hallucination_score": 1.0,
            "suggested_queries": [],
            "citation_count": len(used),
            "coverage": coverage,
        }
    if not used:
        return {
            "is_valid": False,
            "feedback": "Draft contains no citations to the retrieved evidence.",
            "hallucination_score": 1.0,
            "suggested_queries": [],
            "citation_count": 0,
            "coverage": coverage,
        }
    if coverage == 0.0:
        return {
            "is_valid": False,
            "feedback": f"Retrieved evidence does not mention any key term of the question ({', '.join(prompt_terms[:5])}).",
            "hallucination_score": 0.0,
            "suggested_queries": [" ".join(prompt_terms[:3])] if prompt_terms else [],
            "citation_count": len(used),
            "coverage": coverage,
        }

    feedback = f"{len(used)} citation(s) verified against retrieved evidence; question term coverage {coverage:.0%}."
    if missing:
        feedback += f" Terms not found in evidence: {', '.join(missing[:5])}."
    return {
        "is_valid": True,
        "feedback": feedback,
        "hallucination_score": 0.0,
        "suggested_queries": missing[:3],
        "citation_count": len(used),
        "coverage": coverage,
    }


async def critic_node(state: AgentWorkflowState) -> Dict[str, Any]:
    """
    Critic Node: audits the draft against the evidence. Structural checks always run; when an LLM is
    configured it additionally judges factual support. Loop termination is enforced by the graph
    using ``settings.max_agent_iterations`` (the critic never fakes a pass to stop the loop).
    """
    task_id = state.get("task_id", "")
    user_prompt = state.get("user_prompt", "")
    draft_report = state.get("draft_report", "")
    research_data = state.get("research_data", []) or []
    current_iteration = int(state.get("iteration_count", 0) or 0)
    errors: List[str] = list(state.get("errors", []))
    token_usage = int(state.get("token_usage", 0) or 0)

    if state.get("status") == "SECURITY_BLOCKED" or state.get("metadata", {}).get("security_blocked"):
        return {
            "critique": {
                "is_valid": True,
                "feedback": "Security guardrail blocked the prompt; no retrieval was performed.",
                "hallucination_score": 0.0,
                "suggested_queries": [],
            },
            "iteration_count": current_iteration + 1,
            "current_node": "critic",
            "status": "SECURITY_BLOCKED",
        }

    critique = _structural_audit(draft_report, research_data, user_prompt)

    if critique["is_valid"] and get_chat_model() is not None:
        evidence = "\n\n".join(
            f"- ({item.get('citation') or 'Unknown Source'}) {' '.join(str(item.get('content', '')).split())[:600]}"
            for item in research_data[:8]
        )
        try:
            text, tokens = await invoke_llm(
                CRITIC_SYSTEM_PROMPT,
                f"Question: {user_prompt}\n\nDraft answer:\n{draft_report}\n\nEvidence:\n{evidence}",
            )
            token_usage += tokens
            verdict = parse_json_response(text)
            if not isinstance(verdict, dict) or "is_valid" not in verdict:
                raise ValueError("verdict JSON missing 'is_valid'")
            critique.update({
                "is_valid": bool(verdict.get("is_valid")),
                "hallucination_score": max(0.0, min(1.0, float(verdict.get("hallucination_score", 0.0) or 0.0))),
                "feedback": str(verdict.get("feedback", critique["feedback"])),
                "suggested_queries": [str(q) for q in (verdict.get("suggested_queries") or [])][:3],
            })
        except Exception as exc:
            errors.append(f"critic: LLM review failed, used structural audit only ({type(exc).__name__}: {exc})")
            logger.warning(f"[Critic] LLM review failed for task '{task_id}': {exc}")

    logger.info(
        f"[Critic] task_id='{task_id}', iteration={current_iteration + 1}, valid={critique['is_valid']}, "
        f"score={critique['hallucination_score']:.2f}, feedback={json.dumps(critique['feedback'])}"
    )

    return {
        "critique": critique,
        "errors": errors,
        "token_usage": token_usage,
        "iteration_count": current_iteration + 1,
        "current_node": "critic",
        "status": "CRITIQUE_COMPLETED",
    }
