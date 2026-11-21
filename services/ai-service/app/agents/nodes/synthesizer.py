import logging
from typing import Dict, Any, List, Tuple

from app.agents.state import AgentWorkflowState
from app.agents.llm import get_chat_model, invoke_llm

logger = logging.getLogger("nexus.agents.synthesizer")

MAX_EVIDENCE_ITEMS = 8
MAX_SNIPPET_CHARS = 600

SYNTHESIZER_SYSTEM_PROMPT = (
    "You write grounded research answers for enterprise users. Use ONLY the numbered evidence provided. "
    "Every factual sentence must end with one or more footnote markers like [^1] that point to the evidence "
    "numbers you used. Do not invent facts, numbers, policies or sources. If the evidence does not answer part "
    "of the question, say so explicitly. Write concise Markdown with a short '## Answer' section followed by "
    "'## Key Findings' as bullet points. Do not write a references section; it is appended automatically."
)


def _rank_evidence(research_data: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Orders evidence by rerank score (then fused score), keeping MCP/tool evidence after search hits."""
    def _key(item: Dict[str, Any]) -> Tuple[int, float, float]:
        is_search = 0 if item.get("source") == "qdrant_hybrid_search" else 1
        return (is_search, -float(item.get("rerank_score") or 0.0), -float(item.get("score") or 0.0))
    return sorted(research_data, key=_key)[:MAX_EVIDENCE_ITEMS]


def _footnotes(evidence: List[Dict[str, Any]]) -> Tuple[List[int], List[str]]:
    """Assigns one footnote number per distinct citation; returns per-item indexes and reference lines."""
    citation_map: Dict[str, int] = {}
    item_indexes: List[int] = []
    references: List[str] = []
    for item in evidence:
        cit = item.get("citation") or "Unknown Source"
        if cit not in citation_map:
            citation_map[cit] = len(citation_map) + 1
            relevance = item.get("rerank_score") or item.get("score")
            rel_str = f" (relevance {float(relevance):.3f})" if relevance else ""
            references.append(f"[^{citation_map[cit]}]: **{cit}** [{item.get('source', 'evidence')}]{rel_str}")
        item_indexes.append(citation_map[cit])
    return item_indexes, references


def _snippet(item: Dict[str, Any]) -> str:
    text = " ".join(str(item.get("content", "")).split())
    return text if len(text) <= MAX_SNIPPET_CHARS else text[:MAX_SNIPPET_CHARS].rstrip() + "…"


def _issues_section(errors: List[str]) -> List[str]:
    if not errors:
        return []
    return ["", "## Retrieval Issues", *[f"- {e}" for e in errors[-5:]]]


async def synthesizer_node(state: AgentWorkflowState) -> Dict[str, Any]:
    """
    Synthesizer Node: produces a Markdown report grounded strictly in the retrieved evidence.
    LLM mode writes an answer with footnote citations; deterministic mode returns ranked verbatim
    excerpts and clearly says that no LLM synthesis took place.
    """
    user_prompt = state.get("user_prompt", "").strip()
    task_id = state.get("task_id", "")
    research_data = state.get("research_data", []) or []
    metadata = state.get("metadata", {}) or {}
    errors: List[str] = list(state.get("errors", []))
    token_usage = int(state.get("token_usage", 0) or 0)

    if state.get("status") == "SECURITY_BLOCKED" or metadata.get("security_blocked"):
        refusal = metadata.get(
            "safe_refusal",
            "# Security Policy Enforcement\n\nRequest declined due to security policy violation.",
        )
        logger.warning(f"[Synthesizer] Returning security refusal for task_id='{task_id}'.")
        return {"draft_report": refusal, "current_node": "synthesizer", "status": "SECURITY_BLOCKED"}

    logger.info(f"[Synthesizer] task_id='{task_id}', evidence_count={len(research_data)}")

    if not research_data:
        report_lines = [
            f"# Research Report: {user_prompt}",
            "",
            "## Answer",
            "No relevant content was found in this workspace's indexed documents, so no answer can be given "
            "without speculating. Upload documents that cover this topic and run the task again.",
            *_issues_section(errors),
        ]
        return {
            "draft_report": "\n".join(report_lines),
            "current_node": "synthesizer",
            "status": "SYNTHESIS_COMPLETED",
        }

    evidence = _rank_evidence(research_data)
    item_indexes, references = _footnotes(evidence)

    body: List[str] = []
    if get_chat_model() is not None:
        numbered = "\n\n".join(
            f"[{idx}] (source: {item.get('citation') or 'Unknown Source'})\n{_snippet(item)}"
            for idx, item in zip(item_indexes, evidence)
        )
        try:
            text, tokens = await invoke_llm(
                SYNTHESIZER_SYSTEM_PROMPT,
                f"Question: {user_prompt}\n\nEvidence:\n{numbered}",
            )
            token_usage += tokens
            if text:
                body = [text]
        except Exception as exc:
            errors.append(f"synthesizer: LLM synthesis failed, used extractive report ({type(exc).__name__}: {exc})")
            logger.warning(f"[Synthesizer] LLM synthesis failed for task '{task_id}': {exc}")

    if not body:
        body = [
            "_Deterministic mode: no language model was used. The findings below are verbatim excerpts of the "
            "highest-ranked evidence retrieved for this question, not a synthesized answer._",
            "",
            "## Key Findings",
            *[f"- {_snippet(item)} [^{idx}]" for idx, item in zip(item_indexes, evidence)],
        ]

    report_lines = [
        f"# Research Report: {user_prompt}",
        "",
        *body,
        *_issues_section(errors),
        "",
        "## References",
        *references,
    ]
    draft_report = "\n".join(report_lines)
    logger.info(f"[Synthesizer] Generated report ({len(draft_report)} chars, {len(references)} references).")

    return {
        "draft_report": draft_report,
        "errors": errors,
        "token_usage": token_usage,
        "current_node": "synthesizer",
        "status": "SYNTHESIS_COMPLETED",
    }
