"""
Root pytest configuration for the AI service.

``INTERNAL_API_KEY`` is a required setting with no default; provide a deterministic test value
before any ``app`` module is imported. LLM calls are disabled unless a test opts in explicitly.
"""
import os

os.environ.setdefault("INTERNAL_API_KEY", "test-internal-api-key-0123456789")
if os.environ.get("RUN_LLM_TESTS") != "1":
    os.environ["LLM_API_KEY"] = ""
