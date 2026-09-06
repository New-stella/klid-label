#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# deploy-update.sh — [대상 서버 A] 이미 설치된 현장에 <새 판>을 올린다 (업데이트 배포)
#
#   설치(install.sh)는 처음 한 번이고, 이 스크립트는 그 뒤의 매 회차다.
#     사전점검 → 증분 마이그레이션 확인 → 기존 WAR 백업 → WAR 교체 → 실제 헬스 확인
#
#   ★ WAR 배포 자체는 다시 만들지 않는다 — install/17-deploy-jboss.sh 에 위임한다.
#     그쪽이 컨텍스트 판정(WAR 안 jboss-web.xml)·JVM 옵션 배선·.deployed/.failed 판정을
#     이미 갖고 있다. 여기서 다시 구현하면 두 번째 진실원이 되어 반드시 갈린다.
#     이 스크립트가 더하는 것은 <업데이트에만 필요한 것> 넷이다:
#       ① 순서 강제 (DB 먼저)  ② 기존 WAR 백업  ③ 실제 헬스 호출  ④ 롤백 수단
#
#   ★★ ① 순서 강제가 이 스크립트의 핵심이다.
#     새 WAR 는 새 표를 전제로 돈다. DB 가 뒤처진 채 WAR 만 올라가면
#     <기동은 성공하고> 화면·배치가 그 표를 처음 건드릴 때 터진다(조용한 실패).
#     그래서 미적용 증분이 하나라도 있으면 <배포하지 않고 멈춘다>.
#     ⚠ 반대로 DB 를 먼저 올리는 것은 안전하다 — 옛 WAR 는 새 표를 안 볼 뿐이다.
#       그래서 순서를 "DB → WAR" 로 고정한다. 되돌릴 수 없는 쪽을 뒤에 둔다.
#
#   ★ 백업은 <파일만> 뜬다. DB 는 뜨지 않는다.
#     운영 DB 백업은 용량·시점·보관 정책이 현장 소관이고, 이 스크립트가 임의로 뜨면
#     디스크를 채우거나 "백업이 있다"는 잘못된 안심을 준다. 대신 배포 전에 <확인만> 묻는다.
#
#   사용법
#     sudo ./scripts/deploy-update.sh                      기본 (증분 확인 → WAR 교체 → 검증)
#     sudo ./scripts/deploy-update.sh --check              아무것도 바꾸지 않고 현황만
#     sudo ./scripts/deploy-update.sh --with-migrations    증분까지 이 스크립트가 적용한다
#     sudo ./scripts/deploy-update.sh --restart            배포 후 WAS 재기동
#     sudo ./scripts/deploy-update.sh --rollback           직전 백업 WAR 로 되돌린다
#       --jboss-home=<경로> / --war-name=<이름> / --was-unit=<유닛명>  → 17 에 그대로 넘김
#       --skip-db-check   ⚠ 증분 확인을 건너뛴다(DB 접속 정보가 없는 장비에서만)
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"

ONPREM="$(onprem_root)"
WAR_SRC="${ONPREM}/artifacts/backend/api.war"
DEPLOY_SH="${SELF_DIR}/install/17-deploy-jboss.sh"
MIG_SH="${SELF_DIR}/apply-migrations.sh"
BACKUP_DIR="${KLID_BACKUP_DIR:-/var/lib/klid/backup/war}"

CHECK_ONLY=0; DO_ROLLBACK=0; WITH_MIG=0; SKIP_DB=0
PASS_THRU=()
for arg in "$@"; do
  case "${arg}" in
    --check)           CHECK_ONLY=1 ;;
    --rollback)        DO_ROLLBACK=1 ;;
    --with-migrations) WITH_MIG=1 ;;
    --skip-db-check)   SKIP_DB=1 ;;
    --restart|--retire-legacy) PASS_THRU+=("${arg}") ;;
    --jboss-home=*|--war-name=*|--was-unit=*) PASS_THRU+=("${arg}") ;;
    --help|-h)         sed -n '3,38p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "알 수 없는 옵션: ${arg} (--help 로 사용법)" ;;
  esac
done

[[ "${CHECK_ONLY}" -eq 1 ]] || require_root

# ---------------------------------------------------------------------------
# 배포 위치 탐지 — 17 과 같은 방식으로 찾는다(값을 여기 박아 두지 않는다).
# ---------------------------------------------------------------------------
detect_deploy_dir() {
  local jh="${JBOSS_HOME:-}"
  # ★ "${arr[@]:-}" 는 빈 배열에서 <빈 문자열 인자 1개>를 만든다(set -u 회피용 관용구의 함정).
  #   여기서는 순회만이라 무해하지만, 아래 위임 호출에서는 그 빈 인자가 상대 스크립트의
  #   "알 수 없는 옵션" 으로 죽는다. 두 자리 모두 같은 가드를 쓴다.
  for a in ${PASS_THRU[@]+"${PASS_THRU[@]}"}; do [[ "${a}" == --jboss-home=* ]] && jh="${a#*=}"; done
  if [[ -z "${jh}" ]]; then
    for c in /opt/jboss-eap* /opt/EAP* /opt/rh/eap* /usr/share/jbossas /opt/jboss; do
      [[ -d "${c}/bin" ]] && { jh="${c}"; break; }
    done
  fi
  [[ -n "${jh}" && -d "${jh}" ]] || return 1
  printf '%s\n' "${jh}/standalone/deployments"
}

DEPLOY_DIR="$(detect_deploy_dir || true)"

current_war() {   # 현재 배포된 WAR 경로(없으면 빈 문자열)
  [[ -n "${DEPLOY_DIR}" && -d "${DEPLOY_DIR}" ]] || return 0
  local w
  for w in "${DEPLOY_DIR}"/*.war; do
    [[ -e "${w}" ]] || continue
    [[ "$(basename "${w}")" == label-studio.war ]] && continue   # 1차 저작도구는 대상 아님
    printf '%s\n' "${w}"; return 0
  done
}

war_stamp() {     # WAR 식별 표시 — 크기·수정시각·해시 앞 12자리
  local w="$1"
  [[ -f "${w}" ]] || { printf '(없음)'; return 0; }
  printf '%s · %s · sha256:%.12s' \
    "$(du -h "${w}" | cut -f1)" \
    "$(date -r "${w}" '+%Y-%m-%d %H:%M' 2>/dev/null || echo '?')" \
    "$(_hash_file sha256 "${w}")"
}

# ---------------------------------------------------------------------------
# 롤백 — 직전 백업 WAR 를 되돌린다
# ---------------------------------------------------------------------------
if [[ "${DO_ROLLBACK}" -eq 1 ]]; then
  [[ -d "${BACKUP_DIR}" ]] || die "[rollback] 백업 디렉터리가 없습니다: ${BACKUP_DIR}"
  latest="$(find "${BACKUP_DIR}" -maxdepth 1 -type f -name '*.war' -print | sort | tail -n1)"
  [[ -n "${latest}" ]] || die "[rollback] 되돌릴 백업 WAR 가 없습니다: ${BACKUP_DIR}"
  cur="$(current_war)"
  info "[rollback] 되돌릴 백업 : $(basename "${latest}")  ($(war_stamp "${latest}"))"
  info "[rollback] 현재 배포본 : ${cur:-(없음)}  ($(war_stamp "${cur:-/nonexistent}"))"
  warn "[rollback] ★ DB 는 되돌리지 않습니다 — 이미 적용된 증분은 그대로 남습니다."
  warn "[rollback]   옛 WAR 는 새 표를 안 볼 뿐이라 대개 무해하지만, 새 표에 <이미 쌓인 데이터>는"
  warn "[rollback]   옛 WAR 가 다루지 못합니다. 그 판단은 사람이 합니다."
  confirm "위 백업으로 되돌릴까요?" || die "[rollback] 사용자가 중단했습니다."
  [[ -n "${cur}" ]] && install -m 0644 "${latest}" "${cur}" || die "[rollback] 현재 배포본을 찾지 못했습니다."
  rm -f "${cur}.deployed" "${cur}.failed" "${cur}.dodeploy" 2>/dev/null || true
  touch "${cur}.dodeploy"
  ok "[rollback] 되돌렸습니다 — .dodeploy 를 놓았습니다. WAS 가 집어가는지 확인하세요."
  exit 0
fi

# ---------------------------------------------------------------------------
# 1) 사전점검
# ---------------------------------------------------------------------------
info "[pre] 매체 : ${ONPREM}"
[[ -f "${ONPREM}/VERSION.built" ]] && sed 's/^/          /' "${ONPREM}/VERSION.built" \
  || warn "[pre] VERSION.built 가 없습니다 — 패키징 마무리(90-finalize-media.sh)가 돌지 않은 매체일 수 있습니다."

[[ -f "${WAR_SRC}" ]] || die "[pre] 반입 WAR 가 없습니다: ${WAR_SRC}
     이 매체로는 업데이트할 수 없습니다(패키징 10-build-backend.sh 가 수집합니다)."
info "[pre] 새 WAR   : $(war_stamp "${WAR_SRC}")"

CUR_WAR="$(current_war)"
if [[ -n "${CUR_WAR}" ]]; then
  info "[pre] 현재 WAR : $(basename "${CUR_WAR}")  ($(war_stamp "${CUR_WAR}"))"
  if [[ "$(_hash_file sha256 "${WAR_SRC}")" == "$(_hash_file sha256 "${CUR_WAR}")" ]]; then
    ok "[pre] 새 WAR 와 현재 배포본이 <같습니다> — 바이너리는 바뀔 것이 없습니다."
  fi
else
  warn "[pre] 배포된 WAR 를 찾지 못했습니다(DEPLOY_DIR=${DEPLOY_DIR:-미탐지})."
  warn "[pre]   최초 설치라면 이 스크립트가 아니라 install.sh 를 쓰세요."
fi

# ---------------------------------------------------------------------------
# 2) 증분 마이그레이션 — DB 가 WAR 보다 앞서야 한다
# ---------------------------------------------------------------------------
MIG_PENDING="?"
if [[ "${SKIP_DB}" -eq 1 ]]; then
  warn "[db] --skip-db-check — 증분 확인을 건너뜁니다. DB 가 뒤처져 있어도 배포됩니다."
elif [[ ! -x "${MIG_SH}" && ! -f "${MIG_SH}" ]]; then
  warn "[db] apply-migrations.sh 가 없습니다 — 증분 확인을 건너뜁니다: ${MIG_SH}"
else
  info "[db] 증분 마이그레이션 현황 확인"
  if mig_out="$(bash "${MIG_SH}" --status 2>&1)"; then
    printf '%s\n' "${mig_out}" | sed 's/^/          /'
    # ★ 정규식은 POSIX 로 쓴다 — \+ 는 GNU sed 전용이라 다른 sed 에서는 <아무것도 못 잡는다>.
    #   그리고 못 잡았을 때 0 으로 떨어지면 미적용분이 있는데도 배포가 진행된다(fail-open).
    #   그래서 파싱 실패는 0 이 아니라 <중단>이다.
    MIG_PENDING="$(printf '%s\n' "${mig_out}" | sed -n 's/.*미적용 \([0-9][0-9]*\)건.*/\1/p' | tail -1)"
    [[ "${MIG_PENDING}" =~ ^[0-9]+$ ]] || die "[db] 증분 현황 출력에서 미적용 건수를 읽지 못했습니다.
     건수를 모르는 채로 배포하면 DB 가 뒤처진 것을 놓칩니다 — 확인 없이 진행하지 않습니다.
     직접 확인:  ./scripts/apply-migrations.sh --status"
    if [[ "${MIG_PENDING}" != "0" ]]; then
      if [[ "${WITH_MIG}" -eq 1 && "${CHECK_ONLY}" -eq 0 ]]; then
        warn "[db] 미적용 증분 ${MIG_PENDING}건 — --with-migrations 이므로 지금 적용합니다."
        confirm "운영 DB 를 변경합니다. 백업은 확보돼 있습니까? 계속할까요?" \
          || die "[db] 사용자가 중단했습니다."
        bash "${MIG_SH}" || die "[db] 증분 적용에 실패해 배포를 중단합니다(WAR 는 그대로입니다)."
        MIG_PENDING="0"
      elif [[ "${CHECK_ONLY}" -eq 0 ]]; then
        # ★ 순서 역전 차단 — 여기서 멈추는 것이 이 스크립트의 존재 이유다.
        die "[db] ★ 미적용 증분이 ${MIG_PENDING}건 있습니다 — WAR 를 올리지 않고 멈춥니다.
     새 WAR 는 새 표를 전제로 도는데 DB 가 뒤처져 있으면 <기동은 성공하고> 화면·배치가
     그 표를 처음 건드릴 때 터집니다. 어떤 회귀도 이것을 잡지 못합니다.
     먼저:  ./scripts/apply-migrations.sh          (또는 이 스크립트에 --with-migrations)
     신규 설치 직후라면:  ./scripts/apply-migrations.sh --mark-only"
      fi
    fi
  else
    printf '%s\n' "${mig_out}" | sed 's/^/          /'
    warn "[db] 증분 현황을 확인하지 못했습니다(DB 접속 정보 미제공일 수 있습니다)."
    warn "[db]   확인 없이 배포하려면 --skip-db-check 를 명시하세요."
    [[ "${CHECK_ONLY}" -eq 1 ]] || die "[db] 증분 확인 실패로 중단합니다."
  fi
fi

if [[ "${CHECK_ONLY}" -eq 1 ]]; then
  info "— --check, 아무것도 바꾸지 않았습니다 —"; exit 0
fi

# ---------------------------------------------------------------------------
# 3) 기존 WAR 백업 (되돌릴 수단을 먼저 확보한 뒤에 바꾼다)
# ---------------------------------------------------------------------------
if [[ -n "${CUR_WAR}" ]]; then
  ensure_dir "${BACKUP_DIR}"
  bak="${BACKUP_DIR}/$(basename "${CUR_WAR}" .war).$(date '+%Y%m%d%H%M%S').war"
  install -m 0644 "${CUR_WAR}" "${bak}" || die "[backup] 기존 WAR 백업 실패: ${bak}"
  ok "[backup] 기존 WAR 보존: ${bak}"
else
  warn "[backup] 백업할 기존 WAR 가 없습니다 — 롤백 수단 없이 진행합니다."
fi

# ---------------------------------------------------------------------------
# 4) WAR 배포 — 17-deploy-jboss.sh 에 위임
# ---------------------------------------------------------------------------
[[ -f "${DEPLOY_SH}" ]] || die "[deploy] 배포 스크립트가 없습니다: ${DEPLOY_SH}"
info "[deploy] install/17-deploy-jboss.sh 에 위임합니다 ${PASS_THRU[*]:-}"
# ★ 빈 배열을 그대로 펼치면 <빈 문자열 인자 1개>가 넘어가 위임받는 쪽이 "알 수 없는 옵션" 으로
#   즉시 죽는다 — 옵션 없이 이 스크립트를 부르는 것이 <기본 사용법>이라 그 경로가 항상 깨진다.
bash "${DEPLOY_SH}" ${PASS_THRU[@]+"${PASS_THRU[@]}"} || die "[deploy] ★ 배포 실패 — 되돌리려면: $0 --rollback"

# ---------------------------------------------------------------------------
# 5) 검증 — 안내가 아니라 <실제로 부른다>
#    17 은 확인용 curl 을 화면에 찍어 주기만 한다. 업데이트 회차에서는 그것으로 부족하다.
# ---------------------------------------------------------------------------
APP_CONTEXT="${APP_CONTEXT:-}"
if [[ -z "${APP_CONTEXT}" ]] && command -v unzip >/dev/null 2>&1; then
  APP_CONTEXT="$(unzip -p "${WAR_SRC}" WEB-INF/jboss-web.xml 2>/dev/null \
    | tr -d '\r' | grep -o '<context-root>[^<]*</context-root>' | head -n1 \
    | sed 's|.*<context-root>||; s|</context-root>.*||')"
fi
APP_CONTEXT="${APP_CONTEXT:-/label-studio/api}"
HEALTH_URL="http://127.0.0.1:${WAS_PORT:-8080}${APP_CONTEXT}/actuator/health/liveness"

if command -v curl >/dev/null 2>&1; then
  info "[verify] 헬스 확인: ${HEALTH_URL}"
  _ok=0
  for _i in $(seq 1 30); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${HEALTH_URL}" || echo 000)"
    [[ "${code}" == "200" ]] && { _ok=1; break; }
    sleep 2
  done
  if [[ "${_ok}" -eq 1 ]]; then
    ok "[verify] 헬스 200 — 새 판이 응답합니다."
  else
    warn "[verify] ★ 60초 안에 헬스 200 을 받지 못했습니다(마지막 코드=${code:-?})."
    warn "[verify]   WAS 를 아직 재기동하지 않았다면 정상입니다(--restart 미지정)."
    warn "[verify]   재기동 후에도 200 이 아니면 컨텍스트 향이 다를 수 있습니다 — 지금 판정에 쓴 값: ${APP_CONTEXT}"
    warn "[verify]   되돌리려면: $0 --rollback"
  fi
else
  warn "[verify] curl 이 없어 자동 확인을 못 했습니다. 직접 확인하세요: ${HEALTH_URL}"
fi

ok "[done] 업데이트 배포 완료 — DB 증분 ${MIG_PENDING} 미적용 · WAR 교체됨"
info "[done] 되돌리기: $0 --rollback   (백업: ${BACKUP_DIR})"
