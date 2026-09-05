#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# install.sh — [대상 서버 / 폐쇄망] klid-label 오프라인 설치 오케스트레이터
#
#   root 로 실행한다. 외부 네트워크 호출은 전혀 하지 않는다(모든 의존성은 번들).
#   (옵션)PostgreSQL → 런타임 설치 → backend → ai-server → frontend → (옵션)DB 초기화 순서.
#
#   사용법:
#     sudo ./scripts/install.sh                   # 역할 미지정 = 전체 설치(종전 동작)
#     sudo ./scripts/install.sh --role=app        # 서버 A: 프론트(httpd) + 백엔드(WAR) + DB
#     sudo ./scripts/install.sh --role=ai         # 서버 B: ai-server(파이썬·휠·모델)
#     sudo USE_BUNDLED_POSTGRES=0 ./scripts/install.sh  # 외부(기존) PG 사용 — 번들 PG 설치 생략
#     sudo SKIP_DB_INIT=1 ./scripts/install.sh    # DB 생성 단계 생략(이미 준비됨)
#     sudo INSTALL_BACKEND_SYSTEMD_UNIT=1 ./scripts/install.sh  # 베어메탈 형상(jar + systemd) — 아래 ★
#
#   단계 선택(부분 실행 — 재실행은 멱등이라 안전하다):
#     ./scripts/install.sh --list                 # 이 역할에서 도는 단계만 보고 끝낸다(root 불요·무변경)
#     sudo ./scripts/install.sh --only=13         # 그 단계만 실행(여러 개면 --only=12,14)
#     sudo ./scripts/install.sh --skip=10,11      # 그 단계만 빼고 실행
#     ★ 번호(10)·번호접두(13-)·전체 파일명 아무거나 받는다. --only 와 --skip 은 함께 쓸 수 없다.
#     ★ 이미 끝난 단계를 빼고 <실패한 단계만> 다시 돌릴 때 쓴다. 각 단계는 앞 단계의 실행
#       <여부>가 아니라 <결과물 존재>를 확인하므로, 건너뛰어 빠진 것이 있으면 그 자리에서
#       "무엇이 없다"고 말하며 멈춘다(조용히 잘못된 상태로 끝나지 않는다).
#
#   ★★ 대상 장비는 2대다 (2026-08-30 확정):
#       A) app : httpd(정적 서빙 + /api 프록시) + 외부 WAS 에 api.war + (옵션)PostgreSQL·DB 초기화
#                ffmpeg·ffprobe 는 <이 서버의 전제조건>이며 관제지원시스템 팀이 설치한다.
#                우리는 설치하지 않고 <검증만> 한다(19-verify-ffmpeg.sh — 없으면 die).
#       B) ai  : ai-server(번들 파이썬 + 오프라인 휠 + YOLOX/SAM2 모델). httpd 불필요.
#                장비에 GPU 가 있으나 <이번 반입은 CPU 전용>이다(torch CPU 휠).
#     ★ 매체는 하나다 — 물리적으로 쪼개지 않고 <설치할 때 역할만 고른다>.
#     ★ 하위호환: --role 을 주지 않으면 all(전체 설치)이라 기존 단일 서버 절차가 그대로 동작한다.
#
#   ★ 배포 형상 = <외부 WAS 에 api.war 반입> (@design DEPLOY-001 · RUNBOOK-001, 2026-08-30 확정).
#     이 스크립트는 backend 를 <배치까지만> 하고 기동하지 않으며, 자바 런타임도 설치하지 않는다
#     (대상 장비의 WAS 가 JBoss EAP 8.1 + Java 17 로 이미 돌고 있다).
#     WAS 배포와 WAS 설정 이관(docs/10-was-settings.md)은 설치 후 <사람이> 수행한다.
#
#   설치 레이아웃(고정):
#     /opt/klid/runtime/python               ai-server 런타임(웹 서버는 배포판 httpd,
#                                            자바는 외부 WAS 가 제공 — 반입하지 않는다)
#     /opt/klid/app                          backend 산출물(api.war — WAS 배포 원본)
#     /opt/klid/ai                           ai-server venv + app + 모델
#     /opt/klid/web/dist                     frontend 정적 자산(httpd 문서 루트)
#     /etc/klid/*.env                        환경설정(chmod 600)
#     /nas-storage/...                       영상·프레임 저장(NAS 마운트, STORAGE_RAW_PATH)
#     /var/lib/klid                          런타임 데이터(저장소 외)
#     /var/log/klid                          로그
#     서비스 사용자: klid
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"

# ---- 인자 파싱(역할) ----
#   환경변수 KLID_ROLE 로도 줄 수 있다. 인자가 있으면 인자가 이긴다.
_role_arg=""
_only_arg=""
_skip_arg=""
_list_only=0
for _a in "$@"; do
  case "${_a}" in
    --role=*) _role_arg="${_a#--role=}" ;;
    --only=*) _only_arg="${_a#--only=}" ;;
    --skip=*) _skip_arg="${_a#--skip=}" ;;
    --list)   _list_only=1 ;;
    -h|--help)
      sed -n '1,55p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) die "알 수 없는 인자: ${_a} (허용: --role=app|ai|all · --only=N[,N] · --skip=N[,N] · --list)" ;;
  esac
done

[[ -z "${_only_arg}" || -z "${_skip_arg}" ]] \
  || die "--only 와 --skip 은 함께 쓸 수 없습니다(무엇이 도는지 모호해진다)."

# 「10」·「13-」·「13-install-ai-server.sh」 아무 표기나 받아 단계 파일명과 맞춘다.
_step_matches() {
  local step="$1" spec="$2" tok
  local _toks
  IFS=',' read -r -a _toks <<< "${spec}"
  for tok in "${_toks[@]}"; do
    tok="${tok// /}"
    [[ -n "${tok}" ]] || continue
    [[ "${step}" == "${tok}" || "${step}" == "${tok}-"* || "${step}" == "${tok}"*".sh" ]] && return 0
  done
  return 1
}

# 이 역할에서 도는 단계 목록을 만든다(부작용 없음 — --list 가 root 없이 쓸 수 있어야 한다).
_build_steps() {
  STEPS=()
  klid_role_has app && STEPS+=("10-install-postgresql.sh")
  klid_role_has ai  && STEPS+=("11-install-runtimes.sh")
  klid_role_has app && STEPS+=("12-install-backend.sh")
  klid_role_has ai  && STEPS+=("13-install-ai-server.sh")
  klid_role_has app && STEPS+=("14-install-frontend.sh")
  if klid_role_has app && [[ "${SKIP_DB_INIT:-0}" != "1" ]]; then
    STEPS+=("15-init-db.sh")
    STEPS+=("16-load-schema.sh")
  fi
  # ★ JBoss EAP 자동 배포 — 손으로 하던 구간(WAR 복사·JAVA_OPTS 배선·권한)을 대신한다.
  #   기본은 켜져 있다. 베어메탈 토글이나 다른 WAS 를 쓰면 SKIP_JBOSS_DEPLOY=1 로 끈다.
  #   ⚠ 재기동은 하지 않는다(같은 WAS 에 다른 앱이 있을 수 있다) — 스크립트가 명령을 찍는다.
  if klid_role_has app && [[ "${SKIP_JBOSS_DEPLOY:-0}" != "1" ]]; then
    STEPS+=("17-deploy-jboss.sh")
  fi
  klid_role_has app && STEPS+=("19-verify-ffmpeg.sh")
  klid_role_has app && STEPS+=("20-verify-frontend-config.sh")
  klid_role_has app && STEPS+=("21-verify-ai-server-url.sh")

  if [[ -n "${_only_arg}" || -n "${_skip_arg}" ]]; then
    local _sel=() step
    for step in "${STEPS[@]}"; do
      if [[ -n "${_only_arg}" ]]; then
        _step_matches "${step}" "${_only_arg}" && _sel+=("${step}")
      else
        _step_matches "${step}" "${_skip_arg}" || _sel+=("${step}")
      fi
    done
    if [[ -n "${_only_arg}" && "${#_sel[@]}" -eq 0 ]]; then
      warn "[step] --only=${_only_arg} 에 맞는 단계가 이 역할(${KLID_ROLE})에 없습니다."
      warn "       이 역할에서 도는 단계: ${STEPS[*]}"
      die  "[step] 실행할 단계가 없습니다 — 역할(--role)과 단계 번호를 확인하세요."
    fi
    STEPS=("${_sel[@]}")
  fi
}

# ★ --list 는 아무것도 바꾸지 않으므로 root·도구 요구를 걸지 않는다 — 걸면 "실행 전에
#   무엇이 도는지 확인한다"는 목적 자체가 성립하지 않는다. 실제 설치 경로는 그대로 요구한다.
if [[ "${_list_only}" -ne 1 ]]; then
  require_root
  require_cmd tar id useradd install
fi

# 설치 역할 — all(기본) | app | ai. 잘못된 값은 여기서 die(조용히 all 로 흘리지 않는다).
export KLID_ROLE
KLID_ROLE="$(klid_normalize_role "${_role_arg:-${KLID_ROLE:-all}}")"

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
# backend 를 systemd 유닛(java -jar)으로 돌리는 <베어메탈 형상> 토글(기본 0 = WAR 반입 형상).
#   1 이면 12 단계가 jar + klid-backend.service 를 설치하고, 10 단계가 PG 기동순서 drop-in 을 만든다.
#   ★ 반드시 export 한다 — 두 단계가 각각 다른 서브셸에서 이 값을 읽는다.
export INSTALL_BACKEND_SYSTEMD_UNIT="${INSTALL_BACKEND_SYSTEMD_UNIT:-0}"

info "================================================================"
info " klid-label 온프렘 설치 (대상 서버)"
info "  역할   : ${KLID_ROLE}  $( [[ "${KLID_ROLE}" == "app" ]] && echo '(서버 A: 프론트+백엔드)' \
                                 || { [[ "${KLID_ROLE}" == "ai" ]] && echo '(서버 B: ai-server)' \
                                      || echo '(전체 — 단일 서버 구성)'; } )"
info "  PREFIX : ${KLID_PREFIX}"
info "  ETC    : ${KLID_ETC}"
info "  USER   : ${KLID_USER}"
info "  패키지 : ${ONPREM_ROOT}"
info "================================================================"

# ---- --list: 여기서 끝낸다 ----
#   ★ 아래부터는 <장비를 바꾸는> 구간이다(사용자·그룹·디렉터리 생성). 목록만 보려는 사람이
#     그 부작용을 겪으면 안 되고, 실행 전에 확인하려는 것이므로 root 도 요구하지 않는다.
if [[ "${_list_only}" -eq 1 ]]; then
  _build_steps
  info "[step] 역할 ${KLID_ROLE} 에서 실행될 단계(${#STEPS[@]}개):"
  for step in "${STEPS[@]}"; do info "         ${step}"; done
  exit 0
fi

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
#   ★ syspkgs/gpg 를 빠뜨리지 말 것 — 이 디렉토리의 공개키(이름 규약은 리포마다 다르다)는 klid_import_rpm_gpg_keys 가
#     rpm --import 해 <이후 모든 RPM 설치의 서명 검증 기준>이 되는 파일이다. 검증 대상에서
#     빠지면 "무결성 기준 그 자체"만 검증 없이 반입되는 셈이 된다.
#   ★ syspkgs/ffmpeg-src 는 설치 대상이 아니라 GPL 대응 소스(SRPM) 동봉물이다. 설치에 쓰이지는
#     않지만 라이선스 의무 충족물이므로 전송 손상 여부는 똑같이 검증한다.
#   ★ runtimes/jdk 는 목록에 없다 — 자바 런타임을 반입하지 않기 때문이다(2026-08-30,
#     외부 WAS 반입 형상). 이전 판 매체에 그 디렉토리가 남아 있어도 설치가 쓰지 않는다.
#
#   ★★ 검증 목록은 <역할을 따른다> (2026-08-30 서버 2대 분리). ai 서버에 없는
#     artifacts/frontend/dist 를 검증하려 들면 안 된다 — 매체를 역할별로 추려 옮긴 경우
#     "없는 것"을 검증 실패로 오인하게 된다.
#     ⚠ 다만 파일이 <있는데 손상된 것>은 여전히 잡아야 하므로, 매체를 통째로 옮겼다면
#       그 역할이 쓰지 않는 디렉토리는 그냥 검증 대상에서 빠질 뿐이다(삭제하지 않는다).
#
#   역할별 반입물:
#     app : artifacts/backend · artifacts/frontend/dist · syspkgs/{rpm,gpg,postgresql}
#           + syspkgs/{ffmpeg,ffmpeg-src}  ← 설치는 안 하지만 매체에 동봉된다(수동 설치용)
#     ai  : vendor/wheels · models/weights · runtimes/python · syspkgs/{rpm,gpg}
#   ★ syspkgs/rpm·gpg 는 <양쪽 모두>다 — app 은 httpd·semanage, ai 는 mesa-libGL·glib2.
VERIFY_DIRS=(syspkgs/rpm syspkgs/gpg)
if klid_role_has app; then
  VERIFY_DIRS+=(artifacts/backend artifacts/frontend/dist syspkgs/postgresql
                syspkgs/ffmpeg syspkgs/ffmpeg-src)
fi
if klid_role_has ai; then
  VERIFY_DIRS+=(vendor/wheels models/weights runtimes/python)
fi
for d in "${VERIFY_DIRS[@]}"; do
  # SHA256SUMS 가 없으면 검증 생략, 있으면 sha256_verify 가 불일치 시 die —
  # 손상 파일이 조용히 설치되지 않도록 || true 는 두지 않는다.
  if [[ -f "${ONPREM_ROOT}/${d}/SHA256SUMS" ]]; then
    sha256_verify "${ONPREM_ROOT}/${d}"
  fi
done

# ---- 실행 단계 — 역할에 해당하는 것만 돈다 ----
#   ★ 단계 스크립트 자신도 역할 게이트를 갖는다(11·19). 여기서 목록을 추리는 것은
#     "안 도는 단계의 로그를 아예 안 남기기" 위한 것이고, 스크립트 쪽 게이트는
#     <직접 실행>했을 때의 안전망이다. 두 겹 중 하나만 두지 말 것.
_build_steps

if [[ -n "${_only_arg}" || -n "${_skip_arg}" ]]; then
  warn "[step] 부분 실행입니다 — 실행 대상: ${STEPS[*]}"
  warn "       건너뛴 단계의 결과물이 없으면 그 자리에서 멈춥니다(조용히 넘어가지 않는다)."
fi

[[ "${#STEPS[@]}" -gt 0 ]] || die "실행할 단계가 없습니다(KLID_ROLE=${KLID_ROLE}) — 역할 지정을 확인하세요."

for step in "${STEPS[@]}"; do
  script="${SELF_DIR}/install/${step}"
  [[ -f "${script}" ]] || die "설치 스크립트 누락: ${script}"
  info "---- 실행: ${step} ----"
  bash "${script}"
  ok "완료: ${step}"
done

systemctl daemon-reload || warn "systemctl daemon-reload 실패 — systemd 환경인지 확인하세요."

info "================================================================"
ok " 설치 완료(역할: ${KLID_ROLE}). 남은 단계는 <사람이> 해야 한다:"
if klid_role_has app; then
  info " [서버 A / app]"
  info "   1) 환경설정 편집:"
  info "        sudo \$EDITOR ${KLID_ETC}/application.properties   # ★ WAR 형상에서 WAS 가 읽는 파일"
  info "        (${KLID_ETC}/backend.env 는 베어메탈 형상용 — WAS 는 읽지 않는다)"
  info "      ★ 주소 항목을 먼저 맞춘다(04-configuration.md 「주소 한 표」):"
  info "          AI_SERVER_URL       ← ai-server 장비(서버 B). 기본값 loopback 은 2대 구성에서 틀리다"
  info "          CONTROL_DB_*  ← 외부 DB 를 쓰면 그 주소"
  info "          KPST_DEID_BASE_URL  ← 동거 비식별 서버"
  info "          CONTROL_NOTIFY_URL  ← 관제 통지 수신처(CONTROL_NOTIFY_ENABLED=true 일 때)"
  info "        고친 뒤 주소만 다시 확인:  sudo KLID_ROLE=app ${SELF_DIR}/install/21-verify-ai-server-url.sh"
  info "   2) ★ WAS 설정 이관 — 건너뛰면 대용량 업로드만 조용히 깨진다:"
  info "        docs/10-was-settings.md 의 점검 체크리스트를 끝까지 수행"
  info "        (기동·일반 요청은 정상이라 이 단계를 빠뜨려도 배포 시점에는 아무 신호가 없다)"
  info "   3) ★ WAR 배포 — 백엔드는 이 스크립트가 기동하지 않는다:"
  info "        ${KLID_PREFIX}/app/api.war 를 WAS 배포 디렉터리로 <사람이> 복사"
  info "        (파일 이름이 곧 웹 컨텍스트 /api 다 — 이름을 바꾸지 말 것)"
  info "        설정 예시: ${ONPREM_ROOT}/config/was/"
  info "   3-1) ★ ${KLID_ETC}/was.env 에 WAS 현장값을 적는다 (유닛명·WAS_HOME·로그 경로·실행 계정)"
  info "        운영 런북(docs/09-operations-runbook.md)의 백엔드 조작 명령이 이 값을 읽는다 —"
  info "        비워 두면 런북 명령이 전부 자리표시자로 남는다. 앱 설정이 아니므로 비밀값 금지."
  info "   4) 웹 서버 기동(05-run-verify.md 참고):  sudo systemctl enable --now httpd"
  info "   5) 헬스체크:  curl -fsS http://127.0.0.1:8080/api/actuator/health/liveness   # WAS 기동 후"
  info "   ※ ffmpeg·ffprobe 는 이 서버의 <전제조건>이며 관제지원시스템 팀이 설치한다."
  info "      우리 설치는 검증만 한다(19단계). 관제가 설치하지 않은 것이 확인된 경우에만:"
  info "        sudo ${SELF_DIR}/install/install-ffmpeg.sh"
fi
if klid_role_has ai; then
  info " [서버 B / ai]"
  info "   1) 환경설정 편집:  sudo \$EDITOR ${KLID_ETC}/ai-server.env"
  info "   2) 서비스 기동:    sudo systemctl enable --now klid-ai-server"
  info "   3) 헬스체크:       curl -fsS http://127.0.0.1:9300/health"
  info "   ※ 이번 반입은 <CPU 전용>이다(torch CPU 휠). 장비에 GPU 가 있어도 사용하지 않는다."
  info "   ※ 서버 A 의 backend.env/application.properties 의 AI_SERVER_URL 이 이 장비를"
  info "      가리켜야 한다(기본값 localhost 는 단일 서버 구성 기준이다)."
fi
info "================================================================"
