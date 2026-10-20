"""Bearer-token auth for REST and WebSocket."""

from __future__ import annotations

import hmac

from fastapi import Header, HTTPException, status


def token_matches(expected: str, provided: str | None) -> bool:
    if not provided or not expected or expected == "CHANGE_ME":
        return False
    return hmac.compare_digest(expected.encode(), provided.encode())


def make_dependency(expected_token: str):
    def require_token(authorization: str | None = Header(default=None)) -> None:
        provided = None
        if authorization and authorization.lower().startswith("bearer "):
            provided = authorization[7:].strip()
        if not token_matches(expected_token, provided):
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid or missing token")

    return require_token
