#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# install-web.sh — [웹 서버] 프론트 정적자산만 배치한다  (klid-web-01 ~ 02)
#
#   이 서버에 올라가는 것 : 프론트 dist(정적 파일) + 런타임 설정 klid-config.js
#   이 서버에 없는 것     : WAS · DB · ai-server
#
#   ★ httpd 설정은 <건드리지 않는다>. 현장 httpd 에 프록시·로드밸런싱이 이미 잡혀 있고,
#     우리가 덮어쓰면 그 설정이 통째로 날아간다. 우리가 만들어야 할 때만 --with-httpd-conf.
#
#   사용법
#     sudo ./scripts/install-web.sh --web-root=<현장 DocumentRoot>
#     현장 확정값(2026-09-04):
#       sudo ./scripts/install-web.sh --web-root=/GCLOUD/WebApp/label-studio
#
#   ⚠ 그 경로에는 <1차 저작도구가 이미 올라가 있다>. 스크립트는 지우지 않고
#     같은 자리에 .bak.<시각> 으로 옮겨 둔 뒤 새 자산을 놓는다. 되돌리기는 한 줄이다.
#
#     httpd 설정까지 우리가 만들어야 하면:
#       sudo ./scripts/install-web.sh --web-root=... --with-httpd-conf \
#            --backend=<WAS1>:8080,<WAS2>:8080,<WAS3>:8080,<WAS4>:8080
#
#   설치 뒤 <사람이> 확인할 것 둘 — 스크립트가 알 수 없는 값이다
#     · 현장 httpd 의 DocumentRoot 가 방금 배치한 경로를 가리키는가
#     · /api 프록시 대상이 WAS 4대의 8080 을 향하는가
#
#   확인만 하려면:  sudo ./scripts/install-web.sh --check
# ============================================================================
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for a in "$@"; do
  [[ "$a" == --role=* ]] && { echo "✗ 이 스크립트는 웹 전용입니다 — --role 을 주지 마세요." >&2; exit 1; }
done
case "${1:-}" in --help|-h) sed -n '3,25p' "${BASH_SOURCE[0]}"; exit 0 ;; esac
exec "${SELF_DIR}/site-install.sh" --role=web "$@"
