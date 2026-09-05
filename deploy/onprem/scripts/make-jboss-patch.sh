#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# make-jboss-patch.sh — [빌드머신] 보정 패치 묶음을 만든다 (JBoss 전환 + 스키마 현행화 + 새 WAR)
#
#   ★ 왜 전체 매체를 다시 뜨지 않나
#     이미 현장에 들어간 매체(약 1.4GiB) 중 이번에 바뀌는 것은
#     api.war(88MB) + 스크립트·문서·설정 예시(수 MB) 뿐이다. 나머지(파이썬 휠·모델·
#     시스템 RPM·프론트 dist)는 한 바이트도 같다. 전체를 다시 반입하면 심의·전송·검증을
#     통째로 다시 하게 되므로 <바뀌는 것만> 낸다.
#
#   나오는 것
#     klid-at-jboss-patch-<날짜>.tgz
#       ├ apply.sh          현장에서 실행 — 기존 매체 위에 덮어쓴다
#       ├ payload/          덮어쓸 파일들
#       ├ REMOVED.txt       지워야 할 파일 목록(톰캣 예시 3종)
#       └ SHA256SUMS        무결성 대조표
#
#   사용법
#     ./scripts/make-jboss-patch.sh [--out=<디렉터리>]
#
#   ⚠ api.war 가 최신인지 먼저 확인한다. 이 스크립트는 빌드하지 않는다:
#       cd backend && JAVA_HOME=... ./gradlew bootWar -x test
#       cp build/libs/api.war deploy/onprem/artifacts/backend/api.war
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ONPREM="$(cd "${SELF_DIR}/.." && pwd)"
OUT_DIR="${ONPREM}/.."

for arg in "$@"; do
  case "${arg}" in
    --out=*) OUT_DIR="${arg#*=}" ;;
    --help|-h) sed -n '3,26p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "알 수 없는 옵션: ${arg}" >&2; exit 1 ;;
  esac
done

STAMP="$(date '+%Y%m%d')"
NAME="klid-at-jboss-patch-${STAMP}"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
ROOT="${WORK}/${NAME}"
PAY="${ROOT}/payload"
mkdir -p "${PAY}"

echo "== 패치 묶음 생성: ${NAME}"

# ---- 0) 전제 확인 — WAR 에 서술자가 들어 있는가 -----------------------------
WAR="${ONPREM}/artifacts/backend/api.war"
[[ -f "${WAR}" ]] || { echo "✗ api.war 없음: ${WAR}" >&2; exit 1; }
if command -v unzip >/dev/null 2>&1; then
  # ★ 목록을 변수에 먼저 담는다. `unzip -l | grep -q` 를 쓰면 set -o pipefail 아래에서
  #   grep 이 먼저 끝나며 unzip 이 SIGPIPE 로 죽어 <찾았는데 실패로 분기>한다(실측).
  _war_list="$(unzip -l "${WAR}" 2>/dev/null || true)"
  for d in WEB-INF/jboss-deployment-structure.xml WEB-INF/jboss-web.xml; do
    printf '%s' "${_war_list}" | grep -qF "${d}" \
      || { echo "✗ WAR 에 ${d} 가 없습니다 — 재빌드가 필요합니다." >&2; exit 1; }
  done
  echo "  ✓ WAR 서술자 2종 확인"
fi

# ---- 1) payload 구성 --------------------------------------------------------
#   ★ 큰 것(vendor·models·syspkgs·frontend dist)은 담지 않는다 — 안 바뀐다.
copy() {  # copy <onprem 기준 상대경로>
  # ★ local 을 한 줄에 몰아 쓰지 않는다 — set -u 아래에서 같은 local 문의 뒤 변수가
  #   앞 변수를 참조하면 "unbound variable" 로 죽는다(bash 5.3 실측).
  local rel="$1"
  local src="${ONPREM}/${rel}"
  local dst="${PAY}/${rel}"
  [[ -e "${src}" ]] || { echo "  · 건너뜀(없음): ${rel}"; return 0; }
  mkdir -p "$(dirname "${dst}")"
  cp -a "${src}" "${dst}"
}

copy artifacts/backend/api.war
copy artifacts/backend/SHA256SUMS
# ★ db/ 는 반드시 넣는다 — 현장 매체(2026-09-02)에는 V27~V31 이 빠져 있어
#   schema.sql 이 낡았다. 스키마가 낡아도 앱은 <기동에 성공>하므로 조용히 깨진다.
copy db
copy scripts
copy config/was
copy config/backend/application.properties.template
copy config/backend/env.template
copy config/backend/was.env.template
copy docs
copy README.md
copy VERSION
copy 반입요청서.md

echo "  ✓ payload 구성 완료"

# ---- 2) 삭제 대상 -----------------------------------------------------------
cat > "${ROOT}/REMOVED.txt" <<'EOF'
config/was/setenv.sh.example
config/was/server-connector.xml.example
config/was/context-api.xml.example
EOF

# ---- 3) apply.sh ------------------------------------------------------------
cat > "${ROOT}/apply.sh" <<'APPLY'
#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# apply.sh — [대상 서버] JBoss 보정 패치를 기존 반입 매체 위에 덮어쓴다
#
#   사용법
#     ./apply.sh --media=/nas-storage1/USB/20260902/klid-at-20260902/onprem
#     옵션: --dry-run   무엇이 바뀌는지만 보여 준다
#
#   ⚠ 매체가 읽기 전용(DVD/USB)이면 먼저 쓰기 가능한 곳으로 복사한 뒤 그 경로를 주세요.
# ============================================================================
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MEDIA=""; DRY=0
for a in "$@"; do
  case "$a" in
    --media=*) MEDIA="${a#*=}" ;;
    --dry-run) DRY=1 ;;
    --help|-h) sed -n '3,13p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "알 수 없는 옵션: $a" >&2; exit 1 ;;
  esac
done
[[ -n "${MEDIA}" ]] || { echo "✗ --media=<반입 매체의 onprem 디렉터리> 가 필요합니다." >&2; exit 1; }
[[ -d "${MEDIA}/scripts" && -d "${MEDIA}/docs" ]] \
  || { echo "✗ 반입 매체로 보이지 않습니다(scripts/·docs/ 가 없음): ${MEDIA}" >&2; exit 1; }

echo "== 무결성 대조"
( cd "${SELF}" && sha256sum -c SHA256SUMS >/dev/null ) \
  || { echo "✗ 패치 묶음이 손상됐습니다 — 다시 전송하세요." >&2; exit 1; }
echo "  ✓ OK"

echo "== 덮어쓸 파일"
( cd "${SELF}/payload" && find . -type f | sed 's#^\./#  #' | head -40 )
_n="$( cd "${SELF}/payload" && find . -type f | wc -l | tr -d ' ' )"
echo "  ... 총 ${_n} 개"
echo "== 지울 파일"
sed 's/^/  /' "${SELF}/REMOVED.txt"

if [[ "${DRY}" -eq 1 ]]; then echo; echo "(--dry-run — 아무것도 바꾸지 않았습니다)"; exit 0; fi

_bak="${MEDIA}.bak.$(date '+%Y%m%d%H%M%S')"
echo "== 원본 보존: ${_bak}"
cp -a "${MEDIA}" "${_bak}" 2>/dev/null || {
  echo "  ⚠ 매체 전체 백업에 실패했습니다(용량 부족?). 바뀌는 파일만 백업합니다."
  mkdir -p "${_bak}"
  ( cd "${SELF}/payload" && find . -type f -print0 ) | while IFS= read -r -d '' f; do
    [[ -e "${MEDIA}/${f}" ]] || continue
    mkdir -p "${_bak}/$(dirname "${f}")"; cp -a "${MEDIA}/${f}" "${_bak}/${f}"
  done
}

echo "== 덮어쓰기"
cp -a "${SELF}/payload/." "${MEDIA}/"

echo "== 삭제"
while IFS= read -r rel; do
  [[ -n "${rel}" ]] || continue
  if [[ -e "${MEDIA}/${rel}" ]]; then rm -f "${MEDIA}/${rel}"; echo "  삭제: ${rel}"; fi
done < "${SELF}/REMOVED.txt"

echo "== 실행 권한 복구"
chmod 0755 "${MEDIA}"/scripts/*.sh "${MEDIA}"/scripts/install/*.sh "${MEDIA}"/scripts/package/*.sh 2>/dev/null || true

echo "== 매체 WAR 무결성"
( cd "${MEDIA}/artifacts/backend" && sha256sum -c SHA256SUMS ) || {
  echo "✗ WAR 대조 실패" >&2; exit 1; }

cat <<'EOF'

== 완료. 이어서 할 것 ==

  cd <매체>/onprem

  1) DB 접속 정보           sudo ./scripts/install/set-db-config.sh
  2) JBoss 자동 배포        sudo ./scripts/install/17-deploy-jboss.sh --restart
  3) undertow/io 설정       sudo ./scripts/install/18-jboss-settings.sh --check
                            sudo ./scripts/install/18-jboss-settings.sh --apply
  4) 확인                   curl -i http://127.0.0.1:8080/api/actuator/health/liveness

  ⚠ 아직 12단계(설정 파일 배치)를 안 했다면 1) 앞에 먼저:
       sudo KLID_USER=jboss KLID_GROUP=jboss ./scripts/install-step.sh 12-install-backend.sh
EOF
APPLY
chmod 0755 "${ROOT}/apply.sh"

# ---- 4) 체크섬 --------------------------------------------------------------
( cd "${ROOT}" && find . -type f ! -name SHA256SUMS -print0 | sort -z \
    | xargs -0 shasum -a 256 > SHA256SUMS )

# ---- 5) 묶기 ----------------------------------------------------------------
mkdir -p "${OUT_DIR}"
TGZ="${OUT_DIR}/${NAME}.tgz"
tar -C "${WORK}" -czf "${TGZ}" "${NAME}"
( cd "${OUT_DIR}" && shasum -a 256 "$(basename "${TGZ}")" > "${NAME}.tgz.sha256" )

echo
echo "== 완료"
echo "  ${TGZ}"
echo "  $(du -h "${TGZ}" | cut -f1)  ·  파일 $(tar -tzf "${TGZ}" | grep -vc '/$') 개"
echo "  체크섬: ${OUT_DIR}/${NAME}.tgz.sha256"
echo
echo "  현장 적용:"
echo "    tar -xzf ${NAME}.tgz && cd ${NAME}"
echo "    ./apply.sh --media=<반입 매체>/onprem --dry-run   # 먼저 확인"
echo "    ./apply.sh --media=<반입 매체>/onprem"
