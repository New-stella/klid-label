"""
관제 계정 창구 목의 토큰 재발급 — HMAC(HS256/384/512) JWT 서명·검증 (표준 라이브러리만 사용).

관제지원 계정 서비스의 갱신 창구(POST /api/account/auth/refresh)를 흉내 내려면, 로컬 저작도구가
새 access 토큰을 **인증에 그대로 쓰므로** 저작도구가 검증하는 JWT 여야 한다. 그래서 목은 저작도구와
<같은 비밀키>(MOCK_CONTROL_JWT_SECRET ← compose 의 JWT_SECRET)로 서명한다. 비밀키를 스스로 만들지 않는다.

관제 실측 토큰 형태(2026-09-10 · INT-015):
- 헤더 ``{"alg": "HS512"}`` · payload 에 sub(로그인 아이디) · userId · userNm · authority · sessionId ·
  type(access|refresh) · sessionExpAlarm(분) · sessionTime(분) · iat · exp
- access 수명 30분 · refresh 수명 7일 · 갱신 시 sessionId 유지 · refresh 토큰도 교체

재발급은 **들어온 refresh 토큰의 클레임을 복사**해 만든다(iat·exp·type 만 새로 쓴다). 서명 알고리즘도
들어온 토큰의 것을 따른다 — 그 토큰이 이 비밀키로 검증됐다는 것은 저작도구도 같은 알고리즘을 검증할 수
있다는 뜻이다(HS512 는 64바이트 이상 키가 필요해, 짧은 로컬 키로 HS512 를 강제하면 저작도구 검증이 깨진다).

보안: 토큰 원문·클레임 값은 로그에 싣지 않는다(CWE-532). 서명 비교는 상수시간(hmac.compare_digest).
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
from typing import Any

ALGORITHMS = {
    "HS256": hashlib.sha256,
    "HS384": hashlib.sha384,
    "HS512": hashlib.sha512,
}

ACCESS_TTL_SECONDS = 30 * 60
REFRESH_TTL_SECONDS = 7 * 24 * 60 * 60

# 관제 실측 기본값 — 들어온 토큰에 없을 때만 채운다(있으면 그 값을 유지한다).
DEFAULT_SESSION_EXP_ALARM_MINUTES = 5
DEFAULT_SESSION_TIME_MINUTES = 30

# 재발급 때 새로 쓰는 클레임 — 나머지는 들어온 토큰에서 그대로 복사한다.
_REISSUED_CLAIMS = frozenset({"iat", "exp", "type"})


class InvalidTokenError(Exception):
    """갱신에 쓸 수 없는 토큰 — ``reason`` 은 로그용 분류 문자열이며 토큰 내용을 담지 않는다."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def _b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _b64url_decode(text: str) -> bytes:
    padding = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + padding)


def _json_segment(value: dict[str, Any]) -> str:
    return _b64url_encode(json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))


def sign(header: dict[str, Any], payload: dict[str, Any], secret: str) -> str:
    """헤더·페이로드를 HMAC 으로 서명한 compact JWT 를 돌려준다."""
    alg = header.get("alg")
    digest = ALGORITHMS.get(alg) if isinstance(alg, str) else None
    if digest is None:
        raise InvalidTokenError("alg")
    signing_input = f"{_json_segment(header)}.{_json_segment(payload)}"
    signature = hmac.new(secret.encode("utf-8"), signing_input.encode("ascii"), digest).digest()
    return f"{signing_input}.{_b64url_encode(signature)}"


def verify(token: str, secret: str, now: int) -> tuple[dict[str, Any], dict[str, Any]]:
    """서명·만료를 검증하고 (헤더, 페이로드)를 돌려준다. 실패하면 :class:`InvalidTokenError`."""
    parts = token.split(".")
    if len(parts) != 3:
        raise InvalidTokenError("format")
    try:
        header = json.loads(_b64url_decode(parts[0]))
        payload = json.loads(_b64url_decode(parts[1]))
        signature = _b64url_decode(parts[2])
    except (ValueError, UnicodeDecodeError) as exc:
        raise InvalidTokenError("format") from exc
    if not isinstance(header, dict) or not isinstance(payload, dict):
        raise InvalidTokenError("format")
    alg = header.get("alg")
    digest = ALGORITHMS.get(alg) if isinstance(alg, str) else None
    if digest is None:
        raise InvalidTokenError("alg")
    expected = hmac.new(
        secret.encode("utf-8"), f"{parts[0]}.{parts[1]}".encode("ascii"), digest
    ).digest()
    if not hmac.compare_digest(expected, signature):
        raise InvalidTokenError("signature")
    exp = payload.get("exp")
    if isinstance(exp, bool) or not isinstance(exp, (int, float)) or exp <= now:
        raise InvalidTokenError("expired")
    return header, payload


def reissue(refresh_token: str, secret: str, now: int) -> tuple[str, str]:
    """refresh 토큰을 검증하고 새 (access, refresh) 토큰 쌍을 돌려준다."""
    header, payload = verify(refresh_token, secret, now)
    if payload.get("type") != "refresh":
        # access 토큰으로 갱신을 부르면 관제도 갱신하지 않는다(INT-015 — 담는 토큰이 창구마다 반대다).
        raise InvalidTokenError("type")
    base = {k: v for k, v in payload.items() if k not in _REISSUED_CLAIMS}
    base.setdefault("sessionExpAlarm", DEFAULT_SESSION_EXP_ALARM_MINUTES)
    base.setdefault("sessionTime", DEFAULT_SESSION_TIME_MINUTES)
    new_header = {"alg": header["alg"]}
    access = sign(
        new_header,
        {**base, "type": "access", "iat": now, "exp": now + ACCESS_TTL_SECONDS},
        secret,
    )
    refresh = sign(
        new_header,
        {**base, "type": "refresh", "iat": now, "exp": now + REFRESH_TTL_SECONDS},
        secret,
    )
    return access, refresh
