#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# uninstall.sh — [대상 서버] klid-label 제거(롤백)
#
#   서비스 중지·비활성화 → systemd 유닛 제거 → 앱/런타임 제거.
#   ★ 기본은 데이터(${KLID_DATA})·환경설정(${KLID_ETC})·로그는 보존한다.
#     완전 제거는 PURGE=1 로 실행한다(데이터까지 삭제 — 복구 불가).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"
require_root

KLID_PREFIX="${KLID_PREFIX:-/opt/klid}"
KLID_ETC="${KLID_ETC:-/etc/klid}"
KLID_DATA="${KLID_DATA:-/var/lib/klid}"
KLID_LOG="${KLID_LOG:-/var/log/klid}"
KLID_USER="${KLID_USER:-klid}"
SYSTEMD_DIR="${SYSTEMD_DIR:-/etc/systemd/system}"

warn "klid-label 을 제거합니다."
[[ "${PURGE:-0}" == "1" ]] && warn "PURGE=1 — 데이터/환경설정/로그까지 모두 삭제됩니다(복구 불가)."
confirm "계속하시겠습니까?" || die "취소했습니다."

# 서비스 중지/비활성화
for svc in klid-frontend klid-backend klid-ai-server; do
  if systemctl list-unit-files 2>/dev/null | grep -q "^${svc}.service"; then
    systemctl disable --now "${svc}" 2>/dev/null || warn "중지 실패(무시): ${svc}"
    rm -f "${SYSTEMD_DIR}/${svc}.service"
    ok "서비스 제거: ${svc}"
  fi
done
systemctl daemon-reload 2>/dev/null || true

# 앱/런타임 제거
rm -rf "${KLID_PREFIX}"
ok "제거: ${KLID_PREFIX}"

if [[ "${PURGE:-0}" == "1" ]]; then
  rm -rf "${KLID_ETC}" "${KLID_DATA}" "${KLID_LOG}"
  ok "삭제: ${KLID_ETC} ${KLID_DATA} ${KLID_LOG}"
  if id "${KLID_USER}" >/dev/null 2>&1; then
    userdel "${KLID_USER}" 2>/dev/null || warn "사용자 삭제 실패(무시): ${KLID_USER}"
  fi
else
  info "보존됨(삭제하려면 PURGE=1): ${KLID_ETC} ${KLID_DATA} ${KLID_LOG}"
fi

ok "제거 완료."
