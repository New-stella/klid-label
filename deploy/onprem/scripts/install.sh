#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# install.sh — [대상 서버 / 폐쇄망] klid-label 오프라인 설치 오케스트레이터
#
#   root 로 실행한다. 외부 네트워크 호출은 전혀 하지 않는다(모든 의존성은 번들).
#   (옵션)PostgreSQL → 런타임 설치 → backend → ai-server → frontend → (옵션)DB 초기화 순서.
#
#   사용법:
#     sudo ./scripts/install.sh
#     sudo USE_BUNDLED_POSTGRES=0 ./scripts/install.sh  # 외부(기존) PG 사용 — 번들 PG 설치 생략
#     sudo SKIP_DB_INIT=1 ./scripts/install.sh    # DB 생성 단계 생략(이미 준비됨)
#
#   설치 레이아웃(고정):
#     /opt/klid/runtime/{jre,python,caddy}   런타임
#     /opt/klid/app                          backend jar
#     /opt/klid/ai                           ai-server venv + app + 모델
#     /opt/klid/web                          frontend dist + Caddyfile
#     /etc/klid/*.env                        환경설정(chmod 600)
#     /nas-storage/...                       영상·프레임 저장(NAS 마운트, STORAGE_RAW_PATH)
#     /var/lib/klid                          런타임 데이터(저장소 외)
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
# 영상/프레임 저장 베이스 — NAS 마운트(env.template 와 동일 기본값). 로컬 디스크 아님.
export STORAGE_RAW_PATH="${STORAGE_RAW_PATH:-/nas-storage}"
export STORAGE_DEIDENTIFIED_PATH="${STORAGE_DEIDENTIFIED_PATH:-/nas-storage}"
export ONPREM_ROOT
export SYSTEMD_DIR="${SYSTEMD_DIR:-/etc/systemd/system}"
# 번들 PostgreSQL 사용 여부(기본 1). 0 이면 외부(기존) PG 를 쓰고 10-install-postgresql.sh 가 스킵.
export USE_BUNDLED_POSTGRES="${USE_BUNDLED_POSTGRES:-1}"

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
           "${KLID_DATA}" \
           "${KLID_LOG}"
chown -R "${KLID_USER}:${KLID_GROUP}" "${KLID_DATA}" "${KLID_LOG}"
chmod 750 "${KLID_ETC}"

# ---- 영상 저장소(NAS) 검증 ----
# 영상/프레임은 NAS 마운트(STORAGE_RAW_PATH/STORAGE_DEIDENTIFIED_PATH)에 저장된다 — 로컬 디스크 아님.
# 관제 적재·v1→v2 이관본의 절대경로(/nas-storage/...)가 이 베이스로 시작해야 서빙된다(startsWith 가드).
# NAS 는 사전 마운트가 전제: mkdir 시 마운트포인트가 가려질 수 있고, chown -R 은 기존 v1 대용량
# 파일 소유권을 훼손하므로 둘 다 하지 않는다. 존재·쓰기권한만 검증한다(부재여도 설치는 계속 — 서비스 기동 전 마운트).
# 운영자가 backend.env 에서 경로를 커스텀했으면 그 값으로 검증(install 검증경로 ↔ 런타임경로 일치).
if [ -f "${KLID_ETC}/backend.env" ]; then
  # cut 으로 값 추출 후 xargs 로 공백 trim + 둘러싼 따옴표 제거.
  # 공백-only/빈값/빈따옴표("")는 빈문자가 되어 아래 [ -n ] 에서 export 스킵 → 기본값 유지.
  _env_raw="$(grep -E '^[[:space:]]*STORAGE_RAW_PATH=' "${KLID_ETC}/backend.env" | tail -1 | cut -d= -f2- | xargs 2>/dev/null || true)"
  _env_deid="$(grep -E '^[[:space:]]*STORAGE_DEIDENTIFIED_PATH=' "${KLID_ETC}/backend.env" | tail -1 | cut -d= -f2- | xargs 2>/dev/null || true)"
  [ -n "${_env_raw}" ]  && export STORAGE_RAW_PATH="${_env_raw}"
  [ -n "${_env_deid}" ] && export STORAGE_DEIDENTIFIED_PATH="${_env_deid}"
  info "backend.env 의 저장소 경로 사용: raw=${STORAGE_RAW_PATH} deid=${STORAGE_DEIDENTIFIED_PATH}"
fi
for sp in "${STORAGE_RAW_PATH}" "${STORAGE_DEIDENTIFIED_PATH}"; do
  if [ ! -d "${sp}" ]; then
    warn "영상 저장소가 아직 없습니다: ${sp} — **서비스 기동 전** NAS 를 이 경로로 마운트해야 영상/프레임이 저장·서빙된다."
  elif ! runuser -u "${KLID_USER}" -- test -w "${sp}" 2>/dev/null; then
    warn "${KLID_USER} 가 ${sp} 에 쓸 수 없습니다 — NAS 의 해당 하위 디렉터리만 ${KLID_USER} 쓰기 가능하도록 권한 부여(전체 chown -R 금지)."
  else
    ok "영상 저장소(NAS) 확인: ${sp}"
  fi
done

# ---- 패키지 무결성 검증(선택, SHA256SUMS 존재 시) ----
for d in artifacts/backend artifacts/frontend/dist vendor/wheels models/weights \
         runtimes/jdk runtimes/python runtimes/caddy syspkgs/rpm syspkgs/ffmpeg \
         syspkgs/postgresql; do
  # SHA256SUMS 가 없으면 검증 생략, 있으면 sha256_verify 가 불일치 시 die —
  # 손상 파일이 조용히 설치되지 않도록 || true 는 두지 않는다.
  if [[ -f "${ONPREM_ROOT}/${d}/SHA256SUMS" ]]; then
    sha256_verify "${ONPREM_ROOT}/${d}"
  fi
done

STEPS=(
  "10-install-postgresql.sh"
  "11-install-runtimes.sh"
  "12-install-backend.sh"
  "13-install-ai-server.sh"
  "14-install-frontend.sh"
)
[[ "${SKIP_DB_INIT:-0}" == "1" ]] || STEPS+=("15-init-db.sh")
# 16: db/schema.sql 로드 (SPRING_FLYWAY_ENABLED=false 운영). SCHEMA_LOAD_RUN=1 일 때만
#     실제 로드, 아니면 수동 안내만. Flyway 부트스트랩 구성이면 SKIP_SCHEMA_LOAD=1 로 생략.
[[ "${SKIP_DB_INIT:-0}" == "1" ]] || STEPS+=("16-load-schema.sh")
# 17: 포털 DB(PORTAL_DB_*) 복제본 스키마 로드. Flyway 는 control 에만 붙으므로 포털 스키마는
#     설치가 책임진다 — 빠뜨리면 메타 복제가 조용히 0건으로 유지된다(로그·헬스로 안 드러남).
[[ "${SKIP_DB_INIT:-0}" == "1" ]] || STEPS+=("17-load-portal-schema.sh")

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
