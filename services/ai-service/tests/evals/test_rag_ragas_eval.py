"""
RAGAS Evaluation Suite for NexusEnterprise Hub Hybrid RAG Pipeline.

Evaluates `HybridRetriever` (Qdrant dense BGE embeddings + BM25Okapi + Cross-Encoder reranker)
against `golden_dataset.json` computing:
  - ContextPrecision
  - ContextRecall
  - Faithfulness
  - AnswerRelevancy

Supports both:
  1. Online LLM-as-a-judge mode when `OPENAI_API_KEY` is configured.
  2. Fully offline CPU mode using local HuggingFace embeddings (`BAAI/bge-small-en-v1.5`)
     and Cross-Encoder (`cross-encoder/ms-marco-MiniLM-L-6-v2`) scoring so CI runs
     deterministically at zero API cost.
"""

import json
import math
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List

import numpy as np
import pytest
from ragas import EvaluationDataset, SingleTurnSample

from app.rag.embeddings import get_embedding_service
from app.rag.reranker import get_reranker
from app.rag.retriever import HybridRetriever

REPORTS_DIR = Path(__file__).resolve().parent / "reports"
RAGAS_REPORT_PATH = REPORTS_DIR / "ragas_report.json"

THRESHOLDS = {
    "context_precision": 0.75,
    "context_recall": 0.80,
    "faithfulness": 0.80,
    "answer_relevancy": 0.75,
}


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


def _synthesize_grounded_response(question: str, retrieved_chunks: list) -> str:
    """Produces a concise grounded response directly from top retrieved chunks."""
    if not retrieved_chunks:
        return f"No relevant indexed enterprise documentation was found for: {question}"
    top_chunk = retrieved_chunks[0]
    return f"{top_chunk.content.strip()} [{top_chunk.citation}]"


def _compute_local_ragas_scores(
    samples: List[SingleTurnSample],
    cases: List[Dict[str, Any]],
    retrieved_citations_per_case: List[List[str]],
) -> List[Dict[str, float]]:
    """
    Computes deterministic local CPU approximations of the 4 core RAGAS metrics
    using our HuggingFace SentenceTransformer and Cross-Encoder models.
    """
    embedder = get_embedding_service()
    reranker = get_reranker()
    per_case_scores: List[Dict[str, float]] = []

    for sample, case, retrieved_citations in zip(samples, cases, retrieved_citations_per_case):
        question = sample.user_input or ""
        contexts = sample.retrieved_contexts or []
        response = sample.response or ""
        reference = sample.reference or ""
        expected_citations = set(case.get("expected_citations", []))
        expected_keywords = case.get("expected_keywords", [])

        # 1. Context Precision (Rank-weighted Average Precision with Cross-Encoder relevance)
        if not contexts:
            context_precision = 0.0
        else:
            ce_pairs = [[question, ctx] for ctx in contexts]
            ce_probs = [_sigmoid(float(s)) for s in reranker.model.predict(ce_pairs, show_progress_bar=False)]
            relevant_flags = [
                (cit in expected_citations) or (prob >= 0.65)
                for cit, prob in zip(retrieved_citations, ce_probs)
            ]
            prec_sum = 0.0
            hits_so_far = 0
            for rank_idx, is_rel in enumerate(relevant_flags, start=1):
                if is_rel:
                    hits_so_far += 1
                    prec_sum += hits_so_far / rank_idx
            ap = prec_sum / hits_so_far if hits_so_far > 0 else 0.0
            # Ensure top-1 rerank confidence is reflected cleanly
            context_precision = round(0.75 * ap + 0.25 * ce_probs[0], 4)

        # 2. Context Recall (Coverage of ground-truth statements & key technical facts)
        if not contexts:
            context_recall = 0.0
        else:
            combined_context_lower = " ".join(contexts).lower()
            kw_hits = sum(1 for kw in expected_keywords if kw.lower() in combined_context_lower)
            kw_recall = kw_hits / len(expected_keywords) if expected_keywords else 1.0

            ref_vec = embedder.embed_query(reference)
            ctx_vecs = embedder.embed_documents(contexts)
            max_semantic_recall = max(_cosine_similarity(ref_vec, cv) for cv in ctx_vecs)
            context_recall = round(0.55 * kw_recall + 0.45 * max_semantic_recall, 4)

        # 3. Faithfulness (Sentence-level attribution of response claims to retrieved contexts)
        sentences = [
            s.strip()
            for s in re.split(r"(?<=[.!?])\s+", response)
            if len(s.strip()) >= 12
        ]
        if not sentences or not contexts:
            faithfulness = 0.0
        else:
            ctx_vecs = embedder.embed_documents(contexts)
            sent_vecs = embedder.embed_documents(sentences)
            supported_scores: List[float] = []
            for sent, s_vec in zip(sentences, sent_vecs):
                best_sim = max(_cosine_similarity(s_vec, cv) for cv in ctx_vecs)
                best_ce = max(
                    _sigmoid(float(s))
                    for s in reranker.model.predict([[sent, ctx] for ctx in contexts], show_progress_bar=False)
                )
                # Claim is faithful if semantically entailed by at least one context chunk
                supported_scores.append(1.0 if (best_sim >= 0.72 or best_ce >= 0.70) else max(best_sim, best_ce))
            faithfulness = round(float(sum(supported_scores) / len(supported_scores)), 4)

        # 4. Answer Relevancy (Semantic alignment of response to question + ground truth)
        q_vec = embedder.embed_query(question)
        resp_vec = embedder.embed_query(response)
        ref_vec = embedder.embed_query(reference)
        q_resp_sim = _cosine_similarity(q_vec, resp_vec)
        ref_resp_sim = _cosine_similarity(ref_vec, resp_vec)
        ce_q_resp = _sigmoid(float(reranker.model.predict([[question, response]], show_progress_bar=False)[0]))
        answer_relevancy = round(0.40 * q_resp_sim + 0.35 * ref_resp_sim + 0.25 * ce_q_resp, 4)

        per_case_scores.append({
            "context_precision": context_precision,
            "context_recall": context_recall,
            "faithfulness": faithfulness,
            "answer_relevancy": answer_relevancy,
        })

    return per_case_scores


def _run_openai_ragas_eval(eval_dataset: EvaluationDataset) -> List[Dict[str, float]]:
    """Runs native RAGAS LLM-as-a-judge evaluation when OPENAI_API_KEY is available."""
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings
    from ragas import evaluate
    from ragas.embeddings import LangchainEmbeddingsWrapper
    from ragas.llms import LangchainLLMWrapper

    try:
        from ragas.metrics import (
            LLMContextPrecisionWithReference,
            LLMContextRecall,
            Faithfulness,
            ResponseRelevancy,
        )
        metrics = [
            LLMContextPrecisionWithReference(),
            LLMContextRecall(),
            Faithfulness(),
            ResponseRelevancy(),
        ]
    except ImportError:
        from ragas.metrics import (
            ContextPrecision,
            ContextRecall,
            Faithfulness,
            AnswerRelevancy,
        )
        metrics = [
            ContextPrecision(),
            ContextRecall(),
            Faithfulness(),
            AnswerRelevancy(),
        ]

    judge_model = os.getenv("EVAL_JUDGE_MODEL", "gpt-4o-mini")
    llm = LangchainLLMWrapper(ChatOpenAI(model=judge_model, temperature=0))
    embeddings = LangchainEmbeddingsWrapper(OpenAIEmbeddings())

    result = evaluate(
        dataset=eval_dataset,
        metrics=metrics,
        llm=llm,
        embeddings=embeddings,
        raise_exceptions=False,
    )
    df = result.to_pandas()

    def _pick(row: Any, keys: List[str]) -> float:
        for k in keys:
            if k in row and row[k] is not None and not math.isnan(float(row[k])):
                return round(float(row[k]), 4)
        return 0.0

    rows: List[Dict[str, float]] = []
    for _, row in df.iterrows():
        rows.append({
            "context_precision": _pick(row, ["llm_context_precision_with_reference", "context_precision"]),
            "context_recall": _pick(row, ["context_recall", "llm_context_recall"]),
            "faithfulness": _pick(row, ["faithfulness"]),
            "answer_relevancy": _pick(row, ["answer_relevancy", "response_relevancy"]),
        })
    return rows


def test_rag_hybrid_search_ragas_evaluation(
    golden_dataset: Dict[str, Any],
    eval_retriever: HybridRetriever,
):
    """
    Executes the full Hybrid Search + Cross-Encoder Reranking pipeline against all 5
    golden enterprise test cases, computes RAGAS metrics, asserts quality thresholds,
    and exports `tests/evals/reports/ragas_report.json`.
    """
    test_cases: List[Dict[str, Any]] = golden_dataset["test_cases"]
    assert len(test_cases) == 5, "Golden dataset must contain 5 curated enterprise test cases."

    samples: List[SingleTurnSample] = []
    retrieved_citations_per_case: List[List[str]] = []
    case_details: List[Dict[str, Any]] = []

    for case in test_cases:
        chunks = eval_retriever.search(
            tenant_id=case["tenant_id"],
            query=case["question"],
            top_k=3,
            alpha=0.7,
            rerank=True,
        )
        assert len(chunks) >= 1, f"HybridRetriever returned no chunks for {case['id']}"

        retrieved_contexts = [c.content for c in chunks]
        retrieved_citations = [c.citation for c in chunks]
        response = _synthesize_grounded_response(case["question"], chunks)

        # Verify expected authoritative source is ranked #1 after Cross-Encoder reranking
        assert retrieved_citations[0] in case["expected_citations"], (
            f"[{case['id']}] Expected top-1 citation in {case['expected_citations']}, "
            f"but got '{retrieved_citations[0]}'"
        )

        samples.append(
            SingleTurnSample(
                user_input=case["question"],
                retrieved_contexts=retrieved_contexts,
                response=response,
                reference=case["ground_truth"],
            )
        )
        retrieved_citations_per_case.append(retrieved_citations)
        case_details.append({
            "id": case["id"],
            "tenant_id": case["tenant_id"],
            "question": case["question"],
            "expected_citations": case["expected_citations"],
            "retrieved_citations": retrieved_citations,
            "top_rerank_score": round(float(chunks[0].rerank_score), 4),
            "top_hybrid_score": round(float(chunks[0].score), 4),
        })

    eval_dataset = EvaluationDataset(samples=samples)
    assert len(eval_dataset) == len(test_cases)

    use_openai = bool(os.getenv("OPENAI_API_KEY", "").strip())
    mode = "openai_judge" if use_openai else "local_cpu_neural"

    if use_openai:
        per_case_scores = _run_openai_ragas_eval(eval_dataset)
    else:
        per_case_scores = _compute_local_ragas_scores(
            samples=samples,
            cases=test_cases,
            retrieved_citations_per_case=retrieved_citations_per_case,
        )

    for detail, score_dict in zip(case_details, per_case_scores):
        detail["metrics"] = score_dict

    aggregate = {
        metric: round(
            sum(item["metrics"][metric] for item in case_details) / len(case_details),
            4,
        )
        for metric in THRESHOLDS
    }

    passed = all(aggregate[m] >= threshold for m, threshold in THRESHOLDS.items())

    report = {
        "framework": "RAGAS",
        "mode": mode,
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "dataset": golden_dataset["metadata"]["dataset_name"],
        "num_cases": len(case_details),
        "thresholds": THRESHOLDS,
        "aggregate_metrics": aggregate,
        "passed": passed,
        "cases": case_details,
    }

    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    with RAGAS_REPORT_PATH.open("w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    assert RAGAS_REPORT_PATH.exists(), "RAGAS report JSON must be exported."
    assert aggregate["context_precision"] >= THRESHOLDS["context_precision"], (
        f"RAGAS ContextPrecision {aggregate['context_precision']} < {THRESHOLDS['context_precision']}"
    )
    assert aggregate["context_recall"] >= THRESHOLDS["context_recall"], (
        f"RAGAS ContextRecall {aggregate['context_recall']} < {THRESHOLDS['context_recall']}"
    )
    assert aggregate["faithfulness"] >= THRESHOLDS["faithfulness"], (
        f"RAGAS Faithfulness {aggregate['faithfulness']} < {THRESHOLDS['faithfulness']}"
    )
    assert aggregate["answer_relevancy"] >= THRESHOLDS["answer_relevancy"], (
        f"RAGAS AnswerRelevancy {aggregate['answer_relevancy']} < {THRESHOLDS['answer_relevancy']}"
    )
