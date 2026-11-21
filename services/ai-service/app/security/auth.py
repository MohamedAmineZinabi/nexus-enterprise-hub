"""
Internal service-to-service authentication and tenant validation for the AI service.

Every gRPC call and every HTTP request outside the public health probes must present the
shared secret configured in ``INTERNAL_API_KEY``. The ai-service is never meant to be reached
by end users directly: core-service authenticates users, resolves their tenant, and forwards
calls with the internal key plus a trusted ``TenantContext``.
"""
import hmac
import json
import logging
import re
from typing import Any, Awaitable, Callable, Optional

import grpc

from app.core.config import settings

logger = logging.getLogger("nexus.security.auth")

INTERNAL_API_KEY_HEADER = "x-internal-api-key"

# Only liveness/readiness probes are reachable without the internal key.
PUBLIC_HTTP_PATHS = frozenset({"/healthz", "/readyz"})

# Standard gRPC health / reflection services stay open for orchestrator probes.
EXEMPT_GRPC_PREFIXES = ("/grpc.health.v1.Health/",)

_TENANT_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")


class TenantValidationError(ValueError):
    """Raised when a tenant_id is missing or malformed."""


def is_valid_internal_key(candidate: Optional[str]) -> bool:
    """Constant-time comparison of a presented key with the configured internal key."""
    if not candidate:
        return False
    return hmac.compare_digest(candidate.encode("utf-8"), settings.internal_api_key.encode("utf-8"))


def require_tenant_id(tenant_id: Optional[str]) -> str:
    """Returns a normalized tenant_id or raises if it is missing/malformed (no 'default' fallback)."""
    value = (tenant_id or "").strip()
    if not value:
        raise TenantValidationError("tenant_id is required")
    if not _TENANT_ID_PATTERN.match(value):
        raise TenantValidationError("tenant_id is malformed")
    return value


# -----------------------------------------------------------------------------
# HTTP (ASGI) middleware
# -----------------------------------------------------------------------------
class InternalApiKeyMiddleware:
    """
    Pure ASGI middleware (streaming-safe for the MCP SSE transport) that rejects every HTTP
    request lacking a valid ``X-Internal-Api-Key`` header, except the public health probes.
    """

    def __init__(self, app: Callable[..., Awaitable[Any]]):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope.get("type") != "http":
            return await self.app(scope, receive, send)

        path = scope.get("path", "")
        if path in PUBLIC_HTTP_PATHS:
            return await self.app(scope, receive, send)

        presented = None
        for raw_name, raw_value in scope.get("headers", []):
            if raw_name.decode("latin-1").lower() == INTERNAL_API_KEY_HEADER:
                presented = raw_value.decode("latin-1")
                break

        if is_valid_internal_key(presented):
            return await self.app(scope, receive, send)

        logger.warning("Rejected unauthenticated HTTP request to %s %s", scope.get("method"), path)
        body = json.dumps({"detail": "Missing or invalid internal API key"}).encode("utf-8")
        await send({
            "type": "http.response.start",
            "status": 401,
            "headers": [
                (b"content-type", b"application/json"),
                (b"content-length", str(len(body)).encode("ascii")),
                (b"www-authenticate", b"X-Internal-Api-Key"),
            ],
        })
        await send({"type": "http.response.body", "body": body})


# -----------------------------------------------------------------------------
# gRPC server interceptor
# -----------------------------------------------------------------------------
_UNAUTHENTICATED_DETAILS = "Missing or invalid x-internal-api-key metadata"


def _build_rejecting_handler(handler: grpc.RpcMethodHandler) -> grpc.RpcMethodHandler:
    """Builds a handler of the same streaming shape that aborts with UNAUTHENTICATED."""

    async def _abort_unary(request_or_iterator, context):
        await context.abort(grpc.StatusCode.UNAUTHENTICATED, _UNAUTHENTICATED_DETAILS)

    async def _abort_stream(request_or_iterator, context):
        await context.abort(grpc.StatusCode.UNAUTHENTICATED, _UNAUTHENTICATED_DETAILS)
        yield  # pragma: no cover - makes this an async generator

    kwargs = dict(
        request_deserializer=handler.request_deserializer,
        response_serializer=handler.response_serializer,
    )
    if handler.request_streaming and handler.response_streaming:
        return grpc.stream_stream_rpc_method_handler(_abort_stream, **kwargs)
    if handler.request_streaming:
        return grpc.stream_unary_rpc_method_handler(_abort_unary, **kwargs)
    if handler.response_streaming:
        return grpc.unary_stream_rpc_method_handler(_abort_stream, **kwargs)
    return grpc.unary_unary_rpc_method_handler(_abort_unary, **kwargs)


class InternalApiKeyServerInterceptor(grpc.aio.ServerInterceptor):
    """Rejects any gRPC call that does not carry a valid ``x-internal-api-key`` metadata entry."""

    async def intercept_service(self, continuation, handler_call_details):
        method = handler_call_details.method or ""
        if method.startswith(EXEMPT_GRPC_PREFIXES):
            return await continuation(handler_call_details)

        presented = None
        for key, value in handler_call_details.invocation_metadata or ():
            if key.lower() == INTERNAL_API_KEY_HEADER:
                presented = value if isinstance(value, str) else value.decode("utf-8", "replace")
                break

        handler = await continuation(handler_call_details)
        if is_valid_internal_key(presented):
            return handler

        logger.warning("Rejected unauthenticated gRPC call to %s", method)
        if handler is None:
            return None
        return _build_rejecting_handler(handler)
