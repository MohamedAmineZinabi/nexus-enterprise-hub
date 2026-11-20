import re
import logging
from dataclasses import dataclass
from typing import List, Optional, Tuple

logger = logging.getLogger("nexus.security.guardrails")


@dataclass(frozen=True)
class GuardrailAssessment:
    """Result of evaluating a user prompt against enterprise AI security guardrails."""
    is_blocked: bool
    category: Optional[str] = None
    matched_pattern: Optional[str] = None
    safe_refusal: str = ""


_SECURITY_RULES: List[Tuple[str, re.Pattern]] = [
    (
        "PROMPT_INJECTION",
        re.compile(
            r"(?i)\b("
            r"ignore\s+(all\s+)?(previous|prior|above|system)\s+(instructions|prompts|directives|rules)"
            r"|disregard\s+(all\s+)?(previous|prior|above|system)\s+(instructions|prompts|directives|rules)"
            r"|forget\s+(all\s+)?(your|previous|prior)\s+(instructions|prompts|directives|rules)"
            r"|system\s+override"
            r"|override\s+(all\s+)?(security|safety|system|tenant)\s+(policies|rules|filters|instructions)"
            r")\b"
        ),
    ),
    (
        "JAILBREAK",
        re.compile(
            r"(?i)\b("
            r"do\s+anything\s+now"
            r"|enter\s+dan\s+mode"
            r"|developer\s+mode\s+(override|enabled|unlocked)"
            r"|act\s+as\s+an?\s+unrestricted"
            r"|jailbreak"
            r")\b"
        ),
    ),
    (
        "DATA_EXFILTRATION",
        re.compile(
            r"(?i)\b("
            r"dump\s+(all\s+)?(tenant_ids|tenants|users|secrets|credentials|passwords|database|vectors)"
            r"|reveal\s+(your\s+)?(system\s+prompt|jwt\s+secret|redis\s+password|credentials|api\s+keys?|all\s+tenants)"
            r"|bypass\s+tenant(_id)?\s+(filter|isolation|boundary|guard)"
            r"|cross[- ]tenant\s+(dump|leak|export|access)"
            r"|select\s+\*\s+from\s+"
            r")\b"
        ),
    ),
]


def inspect_prompt(prompt: str) -> GuardrailAssessment:
    """
    Inspects an incoming prompt for prompt injection, jailbreak attempts,
    or cross-tenant data exfiltration payloads.
    """
    if not prompt or not prompt.strip():
        return GuardrailAssessment(is_blocked=False)

    normalized = " ".join(prompt.split())
    for category, pattern in _SECURITY_RULES:
        match = pattern.search(normalized)
        if match:
            logger.warning(
                "[SecurityGuardrail] Blocked adversarial prompt (category=%s, match='%s')",
                category,
                match.group(0),
            )
            refusal = (
                "# Security Policy Enforcement\n\n"
                f"Request declined: adversarial input detected under policy `{category}`. "
                "NexusEnterprise Hub strictly enforces instruction integrity and multi-tenant "
                "data isolation. No cross-tenant records, internal credentials, or system prompts "
                "can be disclosed."
            )
            return GuardrailAssessment(
                is_blocked=True,
                category=category,
                matched_pattern=match.group(0),
                safe_refusal=refusal,
            )

    return GuardrailAssessment(is_blocked=False)
