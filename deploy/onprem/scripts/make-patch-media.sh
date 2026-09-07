#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# make-patch-media.sh — [빌드머신] 패치 회차 매체를 <한 번에> 만든다
#
#   설치 매체(package.sh)와 다른 물건이다. 저것은 처음 설치용 수 GB 짜리고,
#   이것은 <이미 선 현장에 얹는> 회차 매체(수백 MB)다. RUNBOOK-002 가 그 절차다.
#
#   ★ 왜 스크립트로 만드나 — 손으로 조립하면 반드시 갈린다.
#     2026-09-06 회차는 손으로 조립됐고, 그 결과 artifacts/backend/SHA256SUMS 에
#     <api-strip.war 가 빠져> 있었다. install.sh 의 무결성 검증은 <목록에 있는 것>만
#     검사하므로, 목록에 없는 파일은 검증 없이 통과한다. 조용한 구멍이다.
#     BUILD-INFO.txt 도 두 향 중 <한 향의 컨텍스트만> 적고 있었다.
#
#   ★★ 두 향 WAR 는 <같은 자리(build/libs/api.war)에 덮어써진다>.
#     그래서 한 향을 빌드하고 <즉시 스테이징으로 옮긴 뒤> 다음 향을 빌드한다.
#     package/10-build-backend.sh 를 두 번 부르는 방식은 쓸 수 없다 — 그쪽은
#     복사 전에 `rm -f ${OUT}/*.war` 를 하므로 앞 향이 지워진다.
#
#   사용법
#     ./scripts/make-patch-media.sh --baseline <커밋>            # 필수: 현장에 깔린 판
#     ./scripts/make-patch-media.sh --baseline <커밋> --id klid-at-patch-YYYYMMDD
#     ./scripts/make-patch-media.sh --baseline <커밋> --out <디렉터리>
#     ./scripts/make-patch-media.sh --baseline <커밋> --no-tar   # tar.gz 생략
#
#   ⚠ 기준선은 <추측하지 않는다>. 현장에 실제로 깔린 판이며, 모르면 만들지 않는다.
#     증분 목록이 그 값에서 나오므로 틀리면 적용해야 할 스키마 변경이 <조용히 빠진다>.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"

REPO="$(repo_root)"
ONPREM="$(onprem_root)"

BASELINE=""; PATCH_ID=""; OUT_PARENT="${REPO}/deploy"; DO_TAR=1
for arg in "$@"; do
  case "${arg}" in
    --baseline=*) BASELINE="${arg#*=}" ;;
    --id=*)       PATCH_ID="${arg#*=}" ;;
    --out=*)      OUT_PARENT="${arg#*=}" ;;
    --no-tar)     DO_TAR=0 ;;
    --help|-h)    sed -n '3,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "알 수 없는 옵션: ${arg} (--help 로 사용법)" ;;
  esac
done
[[ -n "${BASELINE}" ]] || die "--baseline=<커밋> 이 필요합니다 — 현장에 지금 깔린 판입니다. 모르면 만들지 마세요."

git -C "${REPO}" rev-parse --verify -q "${BASELINE}^{commit}" >/dev/null \
  || die "기준선 커밋을 찾을 수 없습니다: ${BASELINE}"

HEAD_FULL="$(git -C "${REPO}" rev-parse HEAD)"
HEAD_SHORT="$(git -C "${REPO}" rev-parse --short HEAD)"
BASE_SHORT="$(git -C "${REPO}" rev-parse --short "${BASELINE}")"
[[ -n "${PATCH_ID}" ]] || PATCH_ID="klid-at-patch-$(date '+%Y%m%d')"
MEDIA="${OUT_PARENT}/${PATCH_ID}"

# ---- 0) 미커밋 경고 — 산출물과 소스가 어긋나는 유일한 원인이다 ----------------
if ! git -C "${REPO}" diff --quiet HEAD -- backend frontend ai-server deploy 2>/dev/null; then
  warn "[patch] 빌드 대상 경로에 미커밋 변경이 있습니다."
  warn "        매체는 <워킹트리>로 빌드되는데 VERSION.txt 는 HEAD 를 적습니다 — 어긋납니다."
  warn "        먼저 커밋하세요. 계속하려면 5초 안에 Ctrl-C 하지 마십시오."
  sleep 5
fi

info "[patch] 기준선 ${BASE_SHORT} → 이번 판 ${HEAD_SHORT}   (id=${PATCH_ID})"

# ---- 1) 증분 스키마 — 기준선에 없고 HEAD 에 있는 마이그레이션 ----------------
#   ★ 이 목록이 기준선에서 <기계적으로> 나와야 한다. 손으로 적으면 빠뜨린다.
MIG_DIR="backend/src/main/resources/db/migration"
_base_migs="$(git -C "${REPO}" ls-tree --name-only "${BASELINE}" "${MIG_DIR}/" | sed 's|.*/||' | sort)"
_head_migs="$(git -C "${REPO}" ls-tree --name-only HEAD          "${MIG_DIR}/" | sed 's|.*/||' | sort)"
NEW_MIGS="$(comm -13 <(printf '%s\n' "${_base_migs}") <(printf '%s\n' "${_head_migs}") || true)"
_mig_vers=""
if [[ -n "${NEW_MIGS}" ]]; then
  _mig_vers="$(printf '%s\n' "${NEW_MIGS}" | sed 's/__.*//' | paste -sd, -)"
  info "[patch] 증분 스키마 ${_mig_vers}"
else
  info "[patch] 증분 스키마 없음 — DB 변경이 없는 회차입니다."
fi

# ★ 기준선 이후 <내용이 바뀐> 기존 마이그레이션이 있으면 멈춘다.
#   증분 파일은 한 번 나가면 고치지 않는 것이 규칙이고, 어겼다면 현장 DB 의 해시와
#   어긋나 적용 수단이 그 자리에서 멈춘다. 매체를 만들기 전에 알아야 한다.
_changed_old="$(git -C "${REPO}" diff --name-only "${BASELINE}" HEAD -- "${MIG_DIR}/" \
                | sed 's|.*/||' | sort | comm -12 - <(printf '%s\n' "${_base_migs}"))"
[[ -z "${_changed_old}" ]] || die "[patch] 기준선에 이미 있던 마이그레이션이 바뀌었습니다:
${_changed_old}
     이미 적용된 파일을 고치면 현장에서 해시 불일치로 멈춥니다. 새 번호로 옮기세요."

# ---- 2) 백엔드 — 두 향을 <각각> 빌드해 즉시 스테이징으로 옮긴다 --------------
STAGE="$(mktemp -d)"; trap 'rm -rf "${STAGE}"' EXIT
BE_SRC="${REPO}/backend"
_gradle=( ./gradlew --no-daemon )
[[ -x "${BE_SRC}/gradlew" ]] || { require_cmd gradle; _gradle=( gradle --no-daemon ); }

info "[patch] backend WAR [passthrough /label-studio/api] 빌드..."
( cd "${BE_SRC}" && "${_gradle[@]}" -q clean bootWar )
[[ -f "${BE_SRC}/build/libs/api.war" ]] || die "[patch] WAR 산출물이 없습니다(passthrough)."
cp "${BE_SRC}/build/libs/api.war" "${STAGE}/api.war"

info "[patch] backend WAR [strip /label-studio] 빌드..."
( cd "${BE_SRC}" && "${_gradle[@]}" -q bootWar -PklidWebContext=/label-studio )
cp "${BE_SRC}/build/libs/api.war" "${STAGE}/api-strip.war"

# ★ 향은 <읽어서> 확인한다 — 빌드 인자를 믿지 않는다. 이 둘이 같으면 조립을 멈춘다.
_ctx_of() { unzip -p "$1" WEB-INF/jboss-web.xml 2>/dev/null | tr -d '\r' \
            | grep -o '<context-root>[^<]*</context-root>' | head -n1 \
            | sed 's|.*<context-root>||; s|</context-root>.*||'; }
CTX_PASS="$(_ctx_of "${STAGE}/api.war")"
CTX_STRIP="$(_ctx_of "${STAGE}/api-strip.war")"
[[ "${CTX_PASS}"  == "/label-studio/api" ]] || die "[patch] passthrough 향의 컨텍스트가 '${CTX_PASS}' 입니다 — 기대값 /label-studio/api"
[[ "${CTX_STRIP}" == "/label-studio"     ]] || die "[patch] strip 향의 컨텍스트가 '${CTX_STRIP}' 입니다 — 기대값 /label-studio"
ok "[patch] 두 향 확인: ${CTX_PASS} / ${CTX_STRIP}"

# ---- 3) 프론트 — 채널마다 따로 빌드한다 -------------------------------------
#   ⚠ 채널은 빌드 시점에 굳는다. 한 번만 빌드하면 반대 채널 화면이 통째로 없는
#     산출물이 나오는데, 빌드도 설치도 성공해 <조용히> 어긋난다.
FE_SRC="${REPO}/frontend"
require_cmd node npm
export VITE_BASE_PATH="${VITE_BASE_PATH:-/label-studio/}"
export VITE_API_BASE_URL="${VITE_API_BASE_URL:-/label-studio/api/v1}"
export VITE_TOKEN_INGRESS="${VITE_TOKEN_INGRESS:-localStorage}"
export VITE_DEV_LOGIN_ENABLED="${VITE_DEV_LOGIN_ENABLED:-true}"
export VITE_DEV_UPLOAD_ENABLED="${VITE_DEV_UPLOAD_ENABLED:-true}"
export VITE_CONTROL_LOGIN_URL="${VITE_CONTROL_LOGIN_URL:-}"
export VITE_PORTAL_LOGIN_URL="${VITE_PORTAL_LOGIN_URL:-}"

if [[ "${SKIP_NPM_CI:-0}" == "1" && -d "${FE_SRC}/node_modules" ]]; then
  warn "[patch] SKIP_NPM_CI=1 — 기존 node_modules 를 그대로 씁니다(재현성이 낮아집니다)."
else
  info "[patch] frontend 의존성 설치(npm ci)..."
  ( cd "${FE_SRC}" && npm ci >/dev/null )
fi
for _ch in control portal; do
  info "[patch] frontend 빌드 [${_ch}]..."
  rm -rf "${FE_SRC}/dist"
  ( cd "${FE_SRC}" && npm run "build:${_ch}" >/dev/null ) || die "[patch] ${_ch} 채널 빌드 실패"
  [[ -f "${FE_SRC}/dist/index.html" ]] || die "[patch] ${_ch} 산출물에 index.html 이 없습니다 — 반쪽입니다."
  cp -R "${FE_SRC}/dist" "${STAGE}/fe-${_ch}"
  # 라이선스 고지 동봉 — dist 가 곧 웹 문서 루트라 향마다 넣어야 웹에서 열린다.
  if [[ -d "${ONPREM}/licenses/frontend" ]]; then
    mkdir -p "${STAGE}/fe-${_ch}/licenses"
    cp -R "${ONPREM}/licenses/frontend/." "${STAGE}/fe-${_ch}/licenses/"
  else
    warn "[patch] 라이선스 고지가 없습니다(${ONPREM}/licenses/frontend) — 폰트 재배포 고지가 빠집니다."
  fi
done

# ★ 채널 교차 오염 검사 — 섞인 산출물은 눈으로 보이지 않는다.
#   ⚠ grep 은 <못 찾으면 1 을 낸다>. 이 스크립트는 pipefail 이라 `$(grep … | wc -l)` 이
#     조용히 스크립트를 죽인다(실측으로 밟았다 — 검사에 도달하기도 전에 끝났다). || true 로 감싼다.
_hits() { { grep -rl "$2" "$1" 2>/dev/null || true; } | wc -l | tr -d ' '; }

# ★★ 음성(0건)을 근거로 쓰기 전에 <이 검사기가 무엇이든 잡는지> 먼저 단언한다.
#    경로가 틀리거나 번들 이름이 바뀌면 교차 오염이 있어도 0 건이 나오는데, 그 0 은
#    "깨끗하다"가 아니라 "아무것도 안 봤다"이다.
_pos_c="$(_hits "${STAGE}/fe-control/assets" '/dashboard')"
_pos_p="$(_hits "${STAGE}/fe-portal/assets"  'portal/uploads')"
[[ "${_pos_c}" != "0" && "${_pos_p}" != "0" ]] \
  || die "[patch] 교차 검사기의 양성 대조가 실패했습니다(control:/dashboard=${_pos_c} · portal:portal/uploads=${_pos_p}).
     반드시 잡혀야 하는 것을 못 잡았습니다 — 아래 0건을 <깨끗하다>로 읽으면 안 됩니다."

_x1="$(_hits "${STAGE}/fe-control/assets" 'portal/uploads')"
_x2="$(_hits "${STAGE}/fe-portal/assets"  '/admin/endpoints')"
[[ "${_x1}" == "0" && "${_x2}" == "0" ]] \
  || die "[patch] 채널이 섞였습니다(control 안 포털 ${_x1} · portal 안 관리 ${_x2}) — 재빌드하세요."
ok "[patch] 채널 교차 오염 없음 (양성 대조 통과: control ${_pos_c} · portal ${_pos_p})"

# ---- 4) 매체 조립 -----------------------------------------------------------
rm -rf "${MEDIA}"
ensure_dir "${MEDIA}/onprem/artifacts/backend"
ensure_dir "${MEDIA}/onprem/artifacts/frontend/dist"
ensure_dir "${MEDIA}/onprem/db"
ensure_dir "${MEDIA}/gpu"

install -m 0644 "${STAGE}/api.war"       "${MEDIA}/onprem/artifacts/backend/api.war"
install -m 0644 "${STAGE}/api-strip.war" "${MEDIA}/onprem/artifacts/backend/api-strip.war"
{
  echo "# klid-label backend 빌드 기록 — 패치 회차 ${PATCH_ID}"
  echo "#   ★ WAR 가 <두 벌>이다. 어느 쪽인지는 현장 httpd 설정이 정하며 우리가 고르는 것이 아니다."
  echo "#     올릴 때는 고른 파일을 api.war 라는 이름으로 둔다(절차가 그 이름을 찾는다)."
  echo "built_at=$(date '+%Y-%m-%d %H:%M:%S%z')"
  echo "git_commit=${HEAD_SHORT}"
  echo "baseline_commit=${BASE_SHORT}"
  echo "war_passthrough=api.war        context=${CTX_PASS}    # httpd 가 /api 를 그대로 넘기는 형상"
  echo "war_strip=api-strip.war        context=${CTX_STRIP}         # httpd 가 /api 를 걷어내는 형상"
  echo "browser_api_base=/label-studio/api/v1   # 두 향에서 같다 — 프론트는 향과 무관하다"
} > "${MEDIA}/onprem/artifacts/backend/BUILD-INFO.txt"
# ★ 두 WAR 를 <모두> 목록에 넣는다. 빠진 파일은 검증 없이 통과한다(2026-09-06 회차의 구멍).
sha256_write "${MEDIA}/onprem/artifacts/backend"

cp -R "${STAGE}/fe-control" "${MEDIA}/onprem/artifacts/frontend/dist/control"
cp -R "${STAGE}/fe-portal"  "${MEDIA}/onprem/artifacts/frontend/dist/portal"
{
  printf '# klid-label frontend 빌드 기록 — 패치 회차 %s\n' "${PATCH_ID}"
  printf '#   이 값들은 <런타임 설정이 없을 때의 폴백>이다. 정본은 /etc/klid/frontend.env 다.\n'
  printf '#   ★ 채널은 빌드 시점에 굳는다 — 장비의 채널에 맞는 것 하나만 올린다.\n'
  printf 'built_at=%s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')"
  printf 'git_commit=%s\n' "${HEAD_SHORT}"
  printf 'baseline_commit=%s\n' "${BASE_SHORT}"
  printf 'node=%s\n' "$(node -v)"
  printf 'build_flavors=control portal\n'
  printf 'VITE_BASE_PATH=%s\n' "${VITE_BASE_PATH}"
  printf 'VITE_API_BASE_URL=%s\n' "${VITE_API_BASE_URL}"
  printf 'VITE_TOKEN_INGRESS=%s\n' "${VITE_TOKEN_INGRESS}"
  printf 'VITE_DEV_LOGIN_ENABLED=%s\n' "${VITE_DEV_LOGIN_ENABLED}"
  printf 'VITE_DEV_UPLOAD_ENABLED=%s\n' "${VITE_DEV_UPLOAD_ENABLED}"
  printf 'VITE_CONTROL_LOGIN_URL=%s\n' "${VITE_CONTROL_LOGIN_URL}"
  printf 'VITE_PORTAL_LOGIN_URL=%s\n' "${VITE_PORTAL_LOGIN_URL}"
} > "${MEDIA}/onprem/artifacts/frontend/BUILD-INFO.txt"
sha256_write "${MEDIA}/onprem/artifacts/frontend/dist"

# 스크립트·문서·스키마 — 매체가 실어 나르는 <수단>이다.
cp -R "${ONPREM}/scripts" "${MEDIA}/onprem/scripts"
cp -R "${ONPREM}/docs"    "${MEDIA}/onprem/docs"
cp    "${ONPREM}/db/schema.sql" "${MEDIA}/onprem/db/schema.sql"
cp    "${ONPREM}/db/README.md"  "${MEDIA}/onprem/db/README.md"

# 증분 — 이번 회차분만 싣는다(기준선에서 기계적으로 나온 목록).
ensure_dir "${MEDIA}/onprem/db/incremental"
cp "${ONPREM}/db/incremental/README.md" "${MEDIA}/onprem/db/incremental/README.md"
_mig_missing=""
while IFS= read -r m; do
  [[ -n "${m}" ]] || continue
  if [[ -f "${ONPREM}/db/incremental/${m}" ]]; then
    cp "${ONPREM}/db/incremental/${m}" "${MEDIA}/onprem/db/incremental/${m}"
  else
    _mig_missing+="${m} "
  fi
done <<< "${NEW_MIGS}"
[[ -z "${_mig_missing}" ]] || die "[patch] 증분 파일이 db/incremental 에 없습니다: ${_mig_missing}
     backend 의 마이그레이션을 db/incremental/ 로 먼저 옮기세요(파일명 그대로)."

# ★ 증분이 만드는 대상이 통합 스키마에도 있는지 — 새 장비를 세울 때 어긋나지 않게.
_absent=""
while IFS= read -r m; do
  [[ -n "${m}" ]] || continue
  while IFS= read -r t; do
    # ⚠ \b 는 BSD grep(mac)에서 동작하지 않아 <전건 미발견>으로 흘러 거짓 경고가 된다. -w 로 쓴다.
    grep -qiw "${t}" "${MEDIA}/onprem/db/schema.sql" || _absent+="${m}:${t} "
  done < <(grep -oiE 'CREATE[[:space:]]+TABLE([[:space:]]+IF[[:space:]]+NOT[[:space:]]+EXISTS)?[[:space:]]+[a-z_][a-z0-9_.]*' \
             "${MEDIA}/onprem/db/incremental/${m}" \
           | sed -E 's/.*[[:space:]]//; s/^.*\.//' \
           | grep -viE '^(if|not|exists|table)$' | sort -u)
done <<< "${NEW_MIGS}"
[[ -z "${_absent}" ]] && ok "[patch] 증분이 만드는 표가 통합 schema.sql 에 모두 있습니다" \
                      || warn "[patch] 통합 schema.sql 에 없는 대상: ${_absent}— 새 장비 설치 시 어긋납니다."

# GPU — 이 회차와 무관하나 읽기 전용 점검 수단은 함께 싣는다.
[[ -f "${REPO}/deploy/onprem-gpu-delta/scripts/check-gpu-readiness.sh" ]] \
  && cp "${REPO}/deploy/onprem-gpu-delta/scripts/check-gpu-readiness.sh" "${MEDIA}/gpu/"
[[ -f "${ONPREM}/docs/11-gpu-migration.md" ]] \
  && cp "${ONPREM}/docs/11-gpu-migration.md" "${MEDIA}/gpu/GPU-전환-가이드.md"

# 배포 가이드 — 매체를 연 사람이 <가장 먼저 보는> 파일이다. 문서 본체는 docs/ 가 소유하고
#   여기서는 그것을 README 이름으로 한 벌 더 둔다(두 번째 진실원을 만들지 않는다).
_guide="${ONPREM}/docs/13-patch-deploy-guide.md"
[[ -f "${_guide}" ]] || die "[patch] 배포 가이드가 없습니다: ${_guide}"
cp "${_guide}" "${MEDIA}/README.md"

# ---- 5) 판본 기록 -----------------------------------------------------------
_n_commits="$(git -C "${REPO}" rev-list --count "${BASELINE}..HEAD")"
_n_runtime="$(git -C "${REPO}" diff --name-only "${BASELINE}" HEAD -- backend/src ai-server frontend/src deploy | wc -l | tr -d ' ')"
{
  echo "# klid-label 패치 매체 판본 기록"
  echo "patch_id=${PATCH_ID}"
  echo "built_at=$(date '+%Y-%m-%d %H:%M:%S%z')"
  echo "baseline_commit=${BASE_SHORT}   # 현장에 지금 깔려 있는 판"
  echo "patch_commit=${HEAD_SHORT}"
  echo "patch_commit_full=${HEAD_FULL}"
  echo "commits_between=${_n_commits}"
  echo "files_changed_runtime=${_n_runtime}   # 매체에 실리는 축(백엔드·프론트·ai·배포)"
  echo "db_increments=${_mig_vers:-none}"
  echo "war_flavors=api.war(${CTX_PASS}) api-strip.war(${CTX_STRIP})"
  echo "fe_channels=control,portal"
  echo "gpu_delta_included=no   # 휠이 3.5GB 라 별도 매체"
} > "${MEDIA}/VERSION.txt"

( cd "${MEDIA}" && find . -type f ! -name 'SHA256SUMS.patch' -print0 | sort -z \
    | xargs -0 shasum -a 256 > SHA256SUMS.patch )
ok "[patch] 매체 조립 완료: ${MEDIA}  ($(du -sh "${MEDIA}" | cut -f1))"

# ---- 6) tar ----------------------------------------------------------------
if [[ "${DO_TAR}" == "1" ]]; then
  info "[patch] tar.gz 생성..."
  ( cd "${OUT_PARENT}" && COPYFILE_DISABLE=1 tar czf "${PATCH_ID}.tar.gz" \
      --exclude '.DS_Store' "${PATCH_ID}" )
  ( cd "${OUT_PARENT}" && shasum -a 256 "${PATCH_ID}.tar.gz" > "${PATCH_ID}.tar.gz.sha256" )
  ok "[patch] ${OUT_PARENT}/${PATCH_ID}.tar.gz  ($(du -h "${OUT_PARENT}/${PATCH_ID}.tar.gz" | cut -f1))"
fi
