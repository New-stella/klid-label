#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# install-was.sh — [WAS 서버] 백엔드만 설치한다  (klid-ai-gen-was-01 ~ 04)
#
#   이 서버에 올라가는 것 : api.war → JBoss EAP 8.1 · DB 설정 · 저장소 배선
#   이 서버에 없는 것     : httpd · 프론트 정적자산 · PostgreSQL · ai-server
#
#   사용법
#     [1번 서버 — DB 스키마 적재를 여기서만 한다]
#       sudo ./scripts/install-was.sh --node=first \
#            --storage=/nas-storage1/klid \
#            --db-hosts=10.177.199.148:19999,10.177.199.149:19999 \
#            --db-name=klid_system_pg_prod --db-user=postgres \
#            --trusted-proxies=<웹01 IP>,<웹02 IP>
#
#     [2~4번 서버]  위와 같고 --node=more 만 바꾼다
#
#   ★ --node=more 를 빠뜨리면 이미 적재된 스키마 위에 다시 적재를 시도한다.
#   ★ DB 비밀번호는 명령줄로 받지 않는다 — 실행 중 화면에 안 찍히게 물어본다.
#   ★ 멱등하다. 다시 돌려도 안전하다.
#   ⚠ --restart 는 WAS 기동 주체가 systemd 로 일원화된 뒤에 쓴다.
#     (현장에서 WAS 가 systemd 밖에서 떠 있으면 스크립트가 감지해 중단한다)
#
#   확인만 하려면:  sudo ./scripts/install-was.sh --check
# ============================================================================
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for a in "$@"; do
  [[ "$a" == --role=* ]] && { echo "✗ 이 스크립트는 WAS 전용입니다 — --role 을 주지 마세요." >&2; exit 1; }
done
case "${1:-}" in --help|-h) sed -n '3,26p' "${BASH_SOURCE[0]}"; exit 0 ;; esac
exec "${SELF_DIR}/site-install.sh" --role=was "$@"
