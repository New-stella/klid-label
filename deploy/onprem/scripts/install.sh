#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# install.sh — [대상 서버 / 폐쇄망] klid-label 오프라인 설치 오케스트레이터
#
#   root 로 실행한다. 외부 네트워크 호출은 전혀 하지 않는다(모든 의존성은 번들).
#   런타임 설치 → backend → ai-server → frontend → (옵션)DB 초기화 순서.
#
#   사용법:
#     sudo ./scripts/install.sh
#     sudo SKIP_DB_INIT=1 ./scripts/install.sh    # DB 생성 단계 생략(이미 준비됨)
#
#   설치 레이아웃(고정):
#     /opt/klid/runtime/{jre,python,caddy}   런타임
#     /opt/klid/app                          backend jar
#     /opt/klid/ai                           ai-server venv + app + 모델
#     /opt/klid/web                          frontend dist + Caddyfile
#     /etc/klid/*.env                        환경설정(chmod 600)
#     /var/lib/klid/storage/{raw,deidentified}  데이터
#     /var/log/klid                          로그
#     서비스 사용자: klid
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"

require_root
require_cmd tar id useradd install

# ---- 공통 설치 변수(모든 install/* 가 export 로 공유) ----
export KLID_PREFIX="${KLID_PREFIX:-/opt/klid}"
export KLID_ETC="${KLID_ETC:-/etc/klid}"
export KLID_DATA="${KLID_DATA:-/var/lib/klid}"
export KLID_LOG="${KLID_LOG:-/var/log/klid}"
export KLID_USER="${KLID_USER:-klid}"
export KLID_GROUP="${KLID_GROUP:-klid}"
export ONPREM_ROOT
export SYSTEMD_DIR="${SYSTEMD_DIR:-/etc/systemd/system}"

info "================================================================"
info " klid-label 온프렘 설치 (대상 서버)"
info "  PREFIX : ${KLID_PREFIX}"
info "  ETC    : ${KLID_ETC}"
info "  USER   : ${KLID_USER}"
info "  패키지 : ${ONPREM_ROOT}"
info "================================================================"

# ---- 서비스 사용자/그룹 + 기본 디렉토리 생성 ----
if ! getent group "${KLID_GROUP}" >/dev/null 2>&1; then
  groupadd --system "${KLID_GROUP}"
  ok "그룹 생성: ${KLID_GROUP}"
fi
if ! id "${KLID_USER}" >/dev/null 2>&1; then
  useradd --system --gid "${KLID_GROUP}" --home-dir "${KLID_PREFIX}" \
          --shell /usr/sbin/nologin "${KLID_USER}" 2>/dev/null \
    || useradd --system --gid "${KLID_GROUP}" --home-dir "${KLID_PREFIX}" \
               --shell /bin/false "${KLID_USER}"
  ok "사용자 생성: ${KLID_USER}"
fi

ensure_dir "${KLID_PREFIX}" "${KLID_PREFIX}/runtime" \
           "${KLID_ETC}" \
           "${KLID_DATA}/storage/raw" "${KLID_DATA}/storage/deidentified" \
           "${KLID_LOG}"
chown -R "${KLID_USER}:${KLID_GROUP}" "${KLID_DATA}" "${KLID_LOG}"
chmod 750 "${KLID_ETC}"

# ---- 패키지 무결성 검증(선택, SHA256SUMS 존재 시) ----
for d in artifacts/backend artifacts/frontend/dist vendor/wheels models/weights \
         runtimes/jdk runtimes/python runtimes/caddy syspkgs/rpm syspkgs/ffmpeg; do
  # SHA256SUMS 가 없으면 검증 생략, 있으면 sha256_verify 가 불일치 시 die —
  # 손상 파일이 조용히 설치되지 않도록 || true 는 두지 않는다.
  if [[ -f "${ONPREM_ROOT}/${d}/SHA256SUMS" ]]; then
    sha256_verify "${ONPREM_ROOT}/${d}"
  fi
done

STEPS=(
  "11-install-runtimes.sh"
  "12-install-backend.sh"
  "13-install-ai-server.sh"
  "14-install-frontend.sh"
)
[[ "${SKIP_DB_INIT:-0}" == "1" ]] || STEPS+=("15-init-db.sh")

for step in "${STEPS[@]}"; do
  script="${SELF_DIR}/install/${step}"
  [[ -f "${script}" ]] || die "설치 스크립트 누락: ${script}"
  info "---- 실행: ${step} ----"
  bash "${script}"
  ok "완료: ${step}"
done

systemctl daemon-reload || warn "systemctl daemon-reload 실패 — systemd 환경인지 확인하세요."

info "================================================================"
ok " 설치 완료. 다음 단계:"
info "   1) 환경설정 편집:"
info "        sudo \$EDITOR ${KLID_ETC}/backend.env"
info "        sudo \$EDITOR ${KLID_ETC}/ai-server.env"
info "   2) 서비스 기동(05-run-verify.md 참고):"
info "        sudo systemctl enable --now klid-ai-server klid-backend klid-frontend"
info "   3) 헬스체크:"
info "        curl -fsS http://127.0.0.1:8080/api/actuator/health/liveness"
info "        curl -fsS http://127.0.0.1:9300/health"
info "================================================================"
