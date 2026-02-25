import secrets
from urllib.parse import urlencode
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse
from authlib.integrations.httpx_client import AsyncOAuth2Client
from config import settings
from middleware.auth import create_session_token, verify_session_token

router = APIRouter(prefix="/auth", tags=["auth"])

GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
REDIRECT_URI = "https://distillpod.duckdns.org/auth/google/callback"


@router.get("/google")
async def google_login():
    """Redirect user to Google OAuth2 consent screen."""
    state = secrets.token_urlsafe(32)
    params = {
        "client_id": settings.google_client_id,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "scope": "openid email profile",
        "state": state,
        "access_type": "online",
    }
    url = f"{GOOGLE_AUTH_URL}?{urlencode(params)}"
    response = RedirectResponse(url=url)
    # Store state in short-lived cookie for CSRF validation
    response.set_cookie(
        "oauth_state", state,
        max_age=600,  # 10 minutes
        httponly=True, secure=True, samesite="lax",
    )
    return response


@router.get("/google/callback")
async def google_callback(request: Request, code: str, state: str):
    """Handle Google OAuth2 callback, validate, set session cookie."""
    # CSRF: validate state matches what we stored
    stored_state = request.cookies.get("oauth_state")
    if not stored_state or stored_state != state:
        return JSONResponse({"detail": "Invalid OAuth state"}, status_code=400)

    # Exchange code for access token
    async with AsyncOAuth2Client(
        client_id=settings.google_client_id,
        client_secret=settings.google_client_secret,
        redirect_uri=REDIRECT_URI,
    ) as client:
        await client.fetch_token(GOOGLE_TOKEN_URL, code=code)
        resp = await client.get(GOOGLE_USERINFO_URL)
        userinfo = resp.json()

    email = userinfo.get("email", "")
    allowed = [e.strip() for e in settings.allowed_emails.split(",") if e.strip()]
    if email not in allowed:
        return JSONResponse({"detail": "Access denied"}, status_code=403)

    user = {
        "email": email,
        "name": userinfo.get("name", ""),
        "picture": userinfo.get("picture", ""),
    }

    session_token = create_session_token(user)
    response = RedirectResponse(url="/", status_code=302)
    response.set_cookie(
        "distillpod_session", session_token,
        max_age=settings.session_max_age,
        httponly=True, secure=True, samesite="lax", path="/",
    )
    response.delete_cookie("oauth_state")
    return response


@router.get("/me")
async def get_me(request: Request):
    """Return current logged-in user from session cookie, or 401."""
    token = request.cookies.get("distillpod_session")
    if not token:
        return JSONResponse({"detail": "Unauthorized"}, status_code=401)
    user = verify_session_token(token)
    if not user:
        return JSONResponse({"detail": "Unauthorized"}, status_code=401)
    return {
        "email": user.get("email"),
        "name": user.get("name"),
        "picture": user.get("picture"),
    }


@router.post("/logout")
async def logout():
    """Clear the session cookie."""
    response = JSONResponse({"ok": True})
    response.delete_cookie("distillpod_session", path="/")
    return response


@router.post("/test-session")
async def test_session():
    """
    TEST_MODE ONLY — set a valid session cookie without going through Google OAuth.
    Used by Playwright E2E global setup. Returns 404 in production (test_mode=False).
    """
    if not settings.test_mode:
        return JSONResponse({"detail": "Not found"}, status_code=404)

    user = {"email": "test@example.com", "name": "Test User", "picture": ""}
    token = create_session_token(user)
    response = JSONResponse({"ok": True})
    response.set_cookie(
        "distillpod_session", token,
        max_age=settings.session_max_age,
        httponly=True,
        secure=False,   # False for http://localhost in E2E
        samesite="lax",
        path="/",
    )
    return response
