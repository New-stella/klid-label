"""콜백 URL 검증 — SSRF(CWE-918) 방어.

목 서버는 인증이 없고, VLM verify/describe 는 요청자가 지정한 ``callback_url`` 로
**서버측 outbound POST** 를 발사한다. 호스트를 제한하지 않으면 9400 에 도달한 누구나
목 서버를 발판으로 내부망(컨테이너 네트워크) 포트 스캔·요청 위조를 할 수 있다.

정책: 허용 호스트 목록(설정 ``MOCK_CALLBACK_ALLOWED_HOSTS``)에 정확히 일치하는 호스트만
수락하고, 그 외(호스트 파싱 불가 포함)는 거부한다 — allowlist 이므로 fail-closed 다.
포트/경로는 제한하지 않는다(콜백 수신 포트가 환경마다 다름).
"""

from __future__ import annotations

from urllib.parse import urlsplit


def callback_host(url: str) -> str | None:
    """URL 에서 호스트만 추출한다(소문자). 파싱 불가/호스트 부재면 None."""
    try:
        host = urlsplit(str(url)).hostname
    except ValueError:
        return None
    if not host:
        return None
    return host.lower()


def is_allowed_callback(url: str, allowed_hosts: list[str]) -> bool:
    """callback_url 이 허용 호스트 목록에 속하는지 판정한다(allowlist, fail-closed)."""
    host = callback_host(url)
    if host is None:
        return False
    return host in {h.lower() for h in allowed_hosts}
