#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# install-ai.sh — [AI 서버] ai-server 를 설치·기동한다  (klid-ai-gpu-01 ~ 02)
#
#   이 서버에 올라가는 것 : 파이썬 3.11 런타임 · 오프라인 휠 · YOLOX/SAM2 모델 ·
#                           systemd 유닛 klid-ai-server (9300)
#   이 서버에 없는 것     : WAS · httpd · DB
#
#   사용법
#     sudo ./scripts/install-ai.sh
#
#   ★ 설치 뒤 <반드시> — 이 서버 주소를 WAS 쪽 관리자 화면의 「연동 서버 주소」에 등록한다.
#     등록하지 않으면 WAS 가 이 서버로 요청을 보내지 않는다. 2대면 <둘 다> 등록해야 하며,
#     한 대만 등록하면 나머지는 놀고 있는데 아무 오류도 나지 않는다.
#
#   ★ 이번 반입은 CPU 전용이다. GPU 를 쓰려면 CPU 판 설치가 끝난 <뒤에> GPU 델타를 얹는다
#     (별도 매체 · 델타 안 README 참조). 델타 없이 GPU 로 돌지 않는다.
#
#   확인만 하려면:  sudo ./scripts/install-ai.sh --check
# ============================================================================
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for a in "$@"; do
  [[ "$a" == --role=* ]] && { echo "✗ 이 스크립트는 AI 서버 전용입니다 — --role 을 주지 마세요." >&2; exit 1; }
done
case "${1:-}" in --help|-h) sed -n '3,22p' "${BASH_SOURCE[0]}"; exit 0 ;; esac
exec "${SELF_DIR}/site-install.sh" --role=ai "$@"
