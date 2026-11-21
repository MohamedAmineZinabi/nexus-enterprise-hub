"""
Optional OpenAI-compatible chat model used by the planner, synthesizer and critic nodes.

When ``LLM_API_KEY`` is unset the nodes run an honest deterministic fallback instead.
``LLM_BASE_URL`` makes this work with any OpenAI-compatible endpoint (OpenAI, Groq, Ollama, vLLM).
"""
import json
import logging
import re
from typing import Any, Optional, Tuple

from app.core.config import settings

logger = logging.getLogger("nexus.agents.llm")

_chat_model: Optional[Any] = None


def get_chat_model() -> Optional[Any]:
    """Returns a cached ChatOpenAI client, or None when no LLM is configured."""
    global _chat_model
    if not settings.llm_enabled:
        return None
    if _chat_model is None:
        from langchain_openai import ChatOpenAI

        kwargs = dict(
            model=settings.llm_model,
            api_key=settings.llm_api_key,
            temperature=settings.llm_temperature,
            timeout=settings.llm_timeout_seconds,
            max_retries=2,
        )
        if settings.llm_base_url:
            kwargs["base_url"] = settings.llm_base_url
        _chat_model = ChatOpenAI(**kwargs)
        logger.info("Initialized LLM client (model=%s, base_url=%s)", settings.llm_model, settings.llm_base_url or "default")
    return _chat_model


def reset_chat_model() -> None:
    """Clears the cached client (used by tests)."""
    global _chat_model
    _chat_model = None


async def invoke_llm(system_prompt: str, user_prompt: str) -> Tuple[str, int]:
    """
    Calls the configured LLM asynchronously and returns (text, total_tokens).
    Raises RuntimeError when no LLM is configured; provider errors propagate to the caller.
    """
    model = get_chat_model()
    if model is None:
        raise RuntimeError("LLM is not configured")

    from langchain_core.messages import HumanMessage, SystemMessage

    message = await model.ainvoke([SystemMessage(content=system_prompt), HumanMessage(content=user_prompt)])
    text = message.content if isinstance(message.content, str) else str(message.content)
    usage = getattr(message, "usage_metadata", None) or {}
    total_tokens = int(usage.get("total_tokens", 0) or 0)
    return text.strip(), total_tokens


_JSON_BLOCK = re.compile(r"```(?:json)?\s*(.*?)```", re.DOTALL)


def parse_json_response(text: str) -> Any:
    """Parses JSON from an LLM response, tolerating ```json fenced blocks and surrounding prose."""
    candidate = text.strip()
    fenced = _JSON_BLOCK.search(candidate)
    if fenced:
        candidate = fenced.group(1).strip()
    try:
        return json.loads(candidate)
    except json.JSONDecodeError:
        pass
    for open_ch, close_ch in (("{", "}"), ("[", "]")):
        start, end = candidate.find(open_ch), candidate.rfind(close_ch)
        if start != -1 and end > start:
            try:
                return json.loads(candidate[start:end + 1])
            except json.JSONDecodeError:
                continue
    raise ValueError("LLM response did not contain valid JSON")
