#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# uninstall.sh — [대상 서버] klid-label 제거(롤백)
#
#   서비스 중지·비활성화 → systemd 유닛 제거 → 앱/런타임 제거.
#   ★ 기본은 데이터(${KLID_DATA})·환경설정(${KLID_ETC})·로그는 보존한다.
#     완전 제거는 PURGE=1 로 실행한다(데이터까지 삭제 — 복구 불가).
#
#   ★ /etc/klid/was.env 를 <별도 제거 대상으로 두지 않는다> (2026-08-30 판단, 근거 아래).
#     이 파일은 이미 ${KLID_ETC} 안에 있어 기본 실행에서는 보존되고 PURGE=1 에서는 함께 지워진다.
#     그 동작이 맞다:
#       ① 내용이 <현장값>이다 — WAS 유닛명·WAS_HOME·배포 디렉터리·로그 경로. 비밀값이 없어
#          일찍 지울 이유가 없고, 재설치 시 운영자가 다시 알아내야 하는 정보다.
#       ② 이 스크립트가 끝나면서 지시하는 <수동 WAS 언디플로이>가 바로 그 값을 필요로 한다.
#          기본 실행에서 지워 버리면 그다음 작업을 못 한다.
#       ③ PURGE 는 "우리 흔적을 전부 지운다"는 뜻이고 was.env 도 우리가 만든 파일이다.
#     ⚠ 다만 PURGE 는 <언디플로이에 필요한 값을 먼저 없앤다>는 순서 함정이 있다.
#       그래서 지우기 <전에> 그 값을 화면에 출력한다(아래).
#
#   ★★ backend 는 이 스크립트가 내리지 못한다 (배포 형상 = 외부 WAS 에 api.war 반입,
#     @design DEPLOY-001). WAS 에 올린 WAR 의 언디플로이와 WAS 중지는 <사람이> 한다.
#     아래 서비스 목록의 klid-backend/klid-frontend 는 베어메탈·구 형상 잔재라 대개 존재하지
#     않으며, 없으면 조용히 건너뛴다(있는 경우를 위해 목록에 남긴다).
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
# ★ 설치 루트는 역할마다 다르다 — AI 장비는 /GCLOUD/klid-at 이다(KLID_AI_PREFIX).
#   이 스크립트는 역할을 모르므로 <지울 곳을 스스로 넓히지 않는다>. 지정된 루트가 비어 있는데
#   다른 알려진 루트에 설치본이 있으면, 지우지 않고 <그 사실을 말하고 멈춘다>.
#   ⚠ 종전에는 없는 경로에 rm -rf 를 걸고 "제거: /opt/klid" 를 찍었다 — 아무것도 지우지
#     않았는데 지웠다고 보고하는 <거짓 성공>이었다. 삭제는 되돌릴 수 없으므로 자동으로
#     대상을 넓히지 않고, 사람이 경로를 명시하게 한다.
if [[ -d "${KLID_PREFIX}" ]]; then
  rm -rf "${KLID_PREFIX}"
  ok "제거: ${KLID_PREFIX}"
else
  _found=""
  for _cand in /opt/klid "${KLID_AI_PREFIX}" /data/klid; do
    [[ "${_cand}" != "${KLID_PREFIX}" ]] || continue
    [[ -d "${_cand}/ai" || -d "${_cand}/app" || -d "${_cand}/web" ]] && _found="${_cand}" && break
  done
  if [[ -n "${_found}" ]]; then
    die "설치 루트 ${KLID_PREFIX} 가 없습니다. 대신 ${_found} 에 설치본이 있습니다.
   지울 곳을 임의로 넓히지 않습니다. 지우려면 경로를 명시하세요:
     sudo KLID_PREFIX=${_found} ${BASH_SOURCE[0]}"
  fi
  warn "설치 루트가 없습니다(이미 제거됨): ${KLID_PREFIX}"
fi

if [[ "${PURGE:-0}" == "1" ]]; then
  # ★ 지우기 전에 WAS 현장값을 화면에 남긴다. 이 값이 없으면 아래에서 안내하는
  #   <수동 WAS 언디플로이>를 수행할 수 없다(어느 디렉터리의 무엇을 지울지 모른다).
  if [[ -f "${KLID_ETC}/was.env" ]]; then
    warn "PURGE 로 ${KLID_ETC}/was.env 가 삭제됩니다. 아래 현장값을 지금 적어 두세요:"
    grep -E '^[A-Z_]+=' "${KLID_ETC}/was.env" 2>/dev/null | sed 's/^/    /' || true
  fi
  rm -rf "${KLID_ETC}" "${KLID_DATA}" "${KLID_LOG}"
  ok "삭제: ${KLID_ETC} ${KLID_DATA} ${KLID_LOG}"
  if id "${KLID_USER}" >/dev/null 2>&1; then
    userdel "${KLID_USER}" 2>/dev/null || warn "사용자 삭제 실패(무시): ${KLID_USER}"
  fi
else
  info "보존됨(삭제하려면 PURGE=1): ${KLID_ETC} ${KLID_DATA} ${KLID_LOG}"
  info "  ★ ${KLID_ETC}/was.env(WAS 유닛명·경로 등 현장값)도 보존됩니다 — 아래 수동 언디플로이에 필요합니다."
fi

ok "제거 완료."
warn "backend 는 WAS 소관이라 제거되지 않았습니다 — WAS 배포 디렉터리의 api.war(및 풀린 디렉터리)를"
warn "  직접 언디플로이하고 WAS 를 재기동하세요. 남겨 두면 다음 설치본과 섞입니다."
