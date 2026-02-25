import time
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from fastapi.responses import JSONResponse
from jose import jwt, JWTError
from config import settings

EXEMPT_PREFIXES = ["/auth/", "/health", "/assets/"]


def create_session_token(user: dict) -> str:
    payload = {
        "email": user["email"],
        "name": user.get("name", ""),
        "picture": user.get("picture", ""),
        "exp": int(time.time()) + settings.session_max_age,
    }
    return jwt.encode(payload, settings.session_secret, algorithm="HS256")


def verify_session_token(token: str) -> dict | None:
    try:
        payload = jwt.decode(token, settings.session_secret, algorithms=["HS256"])
        return payload
    except JWTError:
        return None


class AuthMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        # TEST_MODE: bypass auth entirely — E2E only, never in prod
        if settings.test_mode:
            request.state.user = {
                "email": "test@example.com",
                "name": "Test User",
                "picture": "",
            }
            return await call_next(request)

        path = request.url.path

        # Exempt auth routes, health, and static assets
        if any(path.startswith(p) for p in EXEMPT_PREFIXES):
            return await call_next(request)

        # Static files served by FastAPI (icons, manifest, etc.)
        if path.startswith("/assets/") or "." in path.split("/")[-1]:
            return await call_next(request)

        # Validate session cookie
        token = request.cookies.get("distillpod_session")
        if not token:
            return JSONResponse({"detail": "Unauthorized"}, status_code=401)

        user = verify_session_token(token)
        if not user:
            return JSONResponse({"detail": "Unauthorized"}, status_code=401)

        request.state.user = user
        return await call_next(request)
