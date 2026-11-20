"""
DeepEval Multi-Agent Evaluation Suite for NexusEnterprise Hub.

Evaluates the full LangGraph StateGraph (`Planner -> Researcher -> Synthesizer -> Critic`)
using DeepEval `LLMTestCase` and metrics:
  - Online mode (`OPENAI_API_KEY` set): DeepEval `HallucinationMetric` & `AnswerRelevancyMetric`.
  - Offline CPU mode: Custom DeepEval `BaseMetric` implementations backed by local
    SentenceTransformer embeddings (`BAAI/bge-small-en-v1.5`) and Cross-Encoder
    (`cross-encoder/ms-marco-MiniLM-L-6-v2`) grounding verification.

Verifies synthesized executive reports remain strictly grounded in retrieved Qdrant/MCP
context with zero hallucinations.
"""

import json
import math
import os
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

import numpy as np
import pytest
from deepeval.metrics import BaseMetric
from deepeval.test_case import LLMTestCase

from app.agents.state import AgentWorkflowState
from app.rag.embeddings import get_embedding_service
from app.rag.reranker import get_reranker

REPORTS_DIR = Path(__file__).resolve().parent / "reports"
DEEPEVAL_REPORT_PATH = REPORTS_DIR / "deepeval_report.json"

HALLUCINATION_MAX_THRESHOLD = 0.20
RELEVANCY_MIN_THRESHOLD = 0.75


def _cosine_similarity(vec_a: List[float], vec_b: List[float]) -> float:
    a = np.asarray(vec_a, dtype=np.float32)
    b = np.asarray(vec_b, dtype=np.float32)
    denom = float(np.linalg.norm(a) * np.linalg.norm(b))
    if denom == 0.0:
        return 0.0
    return float(max(0.0, min(1.0, float(np.dot(a, b)) / denom)))


def _sigmoid(raw_score: float) -> float:
    if raw_score <= -700:
        return 0.0
    if raw_score >= 700:
        return 1.0
    return 1.0 / (1.0 + math.exp(-raw_score))


def _extract_factual_findings(markdown_report: str) -> List[str]:
    """Extracts cited finding bullets from the Synthesizer's Key Findings section."""
    findings: List[str] = []
    for line in markdown_report.splitlines():
        stripped = line.strip()
        if stripped.startswith("- "):
            # Strip bullet prefix and footnote tag [^n]
            cleaned = re.sub(r"\s*\[\^\d+\]\s*$", "", stripped[2:]).strip()
            if cleaned:
                findings.append(cleaned)
    return findings


class LocalHallucinationMetric(BaseMetric):
    """
    Offline DeepEval BaseMetric measuring hallucination ratio (0.0 = zero hallucinations,
    1.0 = completely ungrounded) against `test_case.context` using local BGE embeddings
    and Cross-Encoder verification.
    """

    def __init__(self, threshold: float = HALLUCINATION_MAX_THRESHOLD):
        self.threshold = threshold
        self.score: float = 1.0
        self.reason: Optional[str] = None
        self.success: bool = False
        self.include_reason = True
        self.strict_mode = False
        self.async_mode = False

    def measure(self, test_case: LLMTestCase) -> float:
        contexts = test_case.context or test_case.retrieval_context or []
        actual_output = test_case.actual_output or ""
        findings = _extract_factual_findings(actual_output)
        footnotes = re.findall(r"\[\^\d+\]", actual_output)

        if not contexts or not findings or not footnotes:
            self.score = 1.0
            self.reason = "Missing retrieved grounding context, findings, or footnote citations."
            self.success = False
            return self.score

        embedder = get_embedding_service()
        reranker = get_reranker()
        ctx_vecs = embedder.embed_documents(contexts)
        finding_vecs = embedder.embed_documents(findings)

        hallucinated = 0
        for finding, f_vec in zip(findings, finding_vecs):
            best_sim = max(_cosine_similarity(f_vec, cv) for cv in ctx_vecs)
            best_ce = max(
                _sigmoid(float(s))
                for s in reranker.model.predict([[finding, ctx] for ctx in contexts], show_progress_bar=False)
            )
            if best_sim < 0.72 and best_ce < 0.70:
                hallucinated += 1

        self.score = round(hallucinated / len(findings), 4)
        self.success = self.score <= self.threshold
        self.reason = (
            f"{len(findings) - hallucinated}/{len(findings)} synthesized findings strictly grounded "
            f"in retrieved context with {len(set(footnotes))} unique citations (hallucination_rate={self.score})."
        )
        return self.score

    async def a_measure(self, test_case: LLMTestCase) -> float:
        return self.measure(test_case)

    def is_successful(self) -> bool:
        return self.success

    @property
    def __name__(self) -> str:
        return "LocalHallucinationMetric"


class LocalAnswerRelevancyMetric(BaseMetric):
    """
    Offline DeepEval BaseMetric measuring answer relevancy (0.0 to 1.0) of the synthesized
    multi-agent report with respect to the prompt and reference ground truth.
    """

    def __init__(self, threshold: float = RELEVANCY_MIN_THRESHOLD):
        self.threshold = threshold
        self.score: float = 0.0
        self.reason: Optional[str] = None
        self.success: bool = False
        self.include_reason = True
        self.strict_mode = False
        self.async_mode = False

    def measure(self, test_case: LLMTestCase) -> float:
        embedder = get_embedding_service()
        reranker = get_reranker()

        findings = _extract_factual_findings(test_case.actual_output or "")
        focused_answer = " ".join(findings[:2]) if findings else (test_case.actual_output or "")

        q_vec = embedder.embed_query(test_case.input)
        ans_vec = embedder.embed_query(focused_answer)
        q_sim = _cosine_similarity(q_vec, ans_vec)
        ce_prob = _sigmoid(float(reranker.model.predict([[test_case.input, focused_answer]], show_progress_bar=False)[0]))

        if test_case.expected_output:
            exp_vec = embedder.embed_query(test_case.expected_output)
            exp_sim = _cosine_similarity(exp_vec, ans_vec)
            score = 0.35 * q_sim + 0.40 * exp_sim + 0.25 * ce_prob
        else:
            score = 0.55 * q_sim + 0.45 * ce_prob

        self.score = round(float(score), 4)
        self.success = self.score >= self.threshold
        self.reason = (
            f"Synthesized report relevancy={self.score} "
            f"(query_cosine={q_sim:.3f}, cross_encoder={ce_prob:.3f})."
        )
        return self.score

    async def a_measure(self, test_case: LLMTestCase) -> float:
        return self.measure(test_case)

    def is_successful(self) -> bool:
        return self.success

    @property
    def __name__(self) -> str:
        return "LocalAnswerRelevancyMetric"


@pytest.mark.asyncio
async def test_multi_agent_langgraph_deepeval(
    golden_dataset: Dict[str, Any],
    eval_agent_graph: Any,
):
    """
    Runs the LangGraph Multi-Agent pipeline (`Planner -> Researcher -> Synthesizer -> Critic`)
    across all 5 golden test cases and validates zero hallucinations + high answer relevancy
    via DeepEval metrics.
    """
    use_openai = bool(os.getenv("OPENAI_API_KEY", "").strip())
    mode = "openai_judge" if use_openai else "local_cpu_deepeval"

    if use_openai:
        from deepeval.metrics import AnswerRelevancyMetric, HallucinationMetric

        judge_model = os.getenv("EVAL_JUDGE_MODEL", "gpt-4o-mini")
        hallucination_metric: BaseMetric = HallucinationMetric(
            threshold=HALLUCINATION_MAX_THRESHOLD,
            model=judge_model,
        )
        relevancy_metric: BaseMetric = AnswerRelevancyMetric(
            threshold=RELEVANCY_MIN_THRESHOLD,
            model=judge_model,
        )
    else:
        hallucination_metric = LocalHallucinationMetric(threshold=HALLUCINATION_MAX_THRESHOLD)
        relevancy_metric = LocalAnswerRelevancyMetric(threshold=RELEVANCY_MIN_THRESHOLD)

    case_results: List[Dict[str, Any]] = []

    for case in golden_dataset["test_cases"]:
        task_id = f"eval-deepeval-{case['id']}-{uuid.uuid4().hex[:8]}"
        initial_state: AgentWorkflowState = {
            "task_id": task_id,
            "tenant_id": case["tenant_id"],
            "user_prompt": case["question"],
            "trace_id": f"trace-{task_id}",
            "document_ids": [],
            "metadata": {"eval_suite": "deepeval"},
            "plan": [],
            "research_data": [],
            "draft_report": "",
            "critique": {},
            "iteration_count": 0,
            "current_node": "START",
            "status": "PROCESSING",
        }

        final_state = await eval_agent_graph.ainvoke(
            initial_state,
            config={"configurable": {"thread_id": task_id}},
        )

        # Verify LangGraph state machine reached validated CRITIQUE_COMPLETED state
        assert final_state["status"] == "CRITIQUE_COMPLETED"
        assert final_state["critique"]["is_valid"] is True
        assert final_state["critique"]["hallucination_score"] == 0.0
        assert len(final_state["research_data"]) >= 1

        retrieved_contexts = [item["content"] for item in final_state["research_data"]]
        draft_report = final_state["draft_report"]

        test_case = LLMTestCase(
            input=case["question"],
            actual_output=draft_report,
            expected_output=case["ground_truth"],
            context=retrieved_contexts,
            retrieval_context=retrieved_contexts,
        )

        h_score = hallucination_metric.measure(test_case)
        h_ok = hallucination_metric.is_successful()
        h_reason = hallucination_metric.reason

        r_score = relevancy_metric.measure(test_case)
        r_ok = relevancy_metric.is_successful()
        r_reason = relevancy_metric.reason

        case_results.append({
            "id": case["id"],
            "question": case["question"],
            "evidence_items": len(retrieved_contexts),
            "critic_valid": final_state["critique"]["is_valid"],
            "critic_hallucination_score": final_state["critique"]["hallucination_score"],
            "hallucination_score": round(float(h_score), 4),
            "hallucination_passed": bool(h_ok),
            "hallucination_reason": h_reason,
            "answer_relevancy_score": round(float(r_score), 4),
            "answer_relevancy_passed": bool(r_ok),
            "answer_relevancy_reason": r_reason,
        })

        assert h_ok, f"[{case['id']}] DeepEval Hallucination check failed: {h_score} ({h_reason})"
        assert r_ok, f"[{case['id']}] DeepEval AnswerRelevancy check failed: {r_score} ({r_reason})"

    avg_hallucination = round(
        sum(c["hallucination_score"] for c in case_results) / len(case_results), 4
    )
    avg_relevancy = round(
        sum(c["answer_relevancy_score"] for c in case_results) / len(case_results), 4
    )

    report = {
        "framework": "DeepEval",
        "mode": mode,
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "num_cases": len(case_results),
        "thresholds": {
            "hallucination_max": HALLUCINATION_MAX_THRESHOLD,
            "answer_relevancy_min": RELEVANCY_MIN_THRESHOLD,
        },
        "aggregate_metrics": {
            "avg_hallucination_score": avg_hallucination,
            "avg_answer_relevancy": avg_relevancy,
        },
        "passed": all(c["hallucination_passed"] and c["answer_relevancy_passed"] for c in case_results),
        "cases": case_results,
    }

    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    with DEEPEVAL_REPORT_PATH.open("w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    assert avg_hallucination <= HALLUCINATION_MAX_THRESHOLD
    assert avg_relevancy >= RELEVANCY_MIN_THRESHOLD
