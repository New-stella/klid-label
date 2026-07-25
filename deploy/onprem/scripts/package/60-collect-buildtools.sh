#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 60-collect-buildtools.sh — [빌드머신] 오프라인 "소스 재빌드" 키트 수집(신규)
#
#   사전 빌드 아티팩트(jar/dist)와 별개로, 폐쇄망 타깃(Rocky 9)에서 소스를
#   인터넷 없이 재빌드하기 위한 빌드 도구 + 의존성 캐시 + 소스를 번들한다.
#   빌드 도구 바이너리만으로는 오프라인 빌드가 닫히지 않는다 — gradle 의존
#   캐시(GRADLE_USER_HOME)와 frontend node_modules 까지 채워야 한다.
#
#   ★ 이 스크립트는 인터넷이 필요하다(빌드머신에서만 실행). gradle-home/node_modules
#     populate 단계는 실제로 gradle/npm 을 호출하므로 권장 환경은 rockylinux:9 컨테이너다.
#
#   ★ node_modules 는 플랫폼 의존 바이너리(esbuild 등)를 포함한다 → 반드시
#     Linux x64 에서 `npm ci` 로 채워야 한다. mac(Darwin)에서 수집하면 타깃에서
#     동작하지 않으므로, Darwin 에서는 경고 후 node_modules 단계만 SKIP 한다.
#
#   번들 산출물:
#     buildtools/jdk/      : Temurin JDK17 full tarball (javac 포함)
#     buildtools/node/     : Node20 tarball
#     buildtools/gradle/   : Gradle 8.8 dist zip
#     buildtools/gradle-home/ : populated GRADLE_USER_HOME(전 의존 jar 캐시, 플랫폼 무관)
#     buildtools/frontend-node_modules.tar.gz : frontend node_modules(Linux x64 전용)
#     src/{backend,frontend,ai-server} : 빌드용 소스(플랫폼 무관)
#
#   옵션:
#     SKIP_NODE_MODULES=1   node_modules populate 생략(이미 채웠거나 별도 수집)
#     SKIP_GRADLE_HOME=1    gradle-home populate 생략
#     SKIP_SRC=1            소스 복사 생략
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"

REPO="$(repo_root)"
ONPREM="$(onprem_root)"
BT="${ONPREM}/buildtools"
JDK_OUT="${BT}/jdk"
NODE_OUT="${BT}/node"
GRADLE_OUT="${BT}/gradle"
GRADLE_HOME_OUT="${BT}/gradle-home"
SRC_OUT="${ONPREM}/src"

require_cmd curl tar
ensure_dir "${JDK_OUT}" "${NODE_OUT}" "${GRADLE_OUT}" "${GRADLE_HOME_OUT}"

# download <url> <dest_dir> — 멱등(이미 있으면 생략). 받은 파일 경로를 stdout 으로 출력.
download() {
  local url="$1" dest_dir="$2"
  local fname; fname="$(basename "${url%%\?*}")"
  local out="${dest_dir}/${fname}"
  if [[ -s "${out}" ]]; then
    info "이미 존재(생략): ${out}" >&2
    printf '%s\n' "${out}"
    return 0
  fi
  info "다운로드: ${url}" >&2
  curl -fL --retry 3 --proto '=https' -o "${out}.part" "${url}" \
    && mv "${out}.part" "${out}" \
    || { rm -f "${out}.part"; die "다운로드 실패: ${url}"; }
  ok "수집: ${out}  ($(du -h "${out}" | cut -f1))" >&2
  printf '%s\n' "${out}"
}

# ----------------------------------------------------------------------------
# 1) JDK17 full (javac 포함)
# ----------------------------------------------------------------------------
info "[buildtools] (1/6) Temurin JDK17 full ${JDK17_FULL_VERSION}..."
JDK_FILE="$(download "${JDK17_FULL_URL}" "${JDK_OUT}")"
verify_file_sha256 "${JDK_FILE}" "${JDK17_FULL_SHA256:-}" "Temurin JDK17 full ${JDK17_FULL_VERSION}"

# ----------------------------------------------------------------------------
# 2) Node 20
# ----------------------------------------------------------------------------
info "[buildtools] (2/6) Node ${NODE20_VERSION}..."
NODE_FILE="$(download "${NODE20_URL}" "${NODE_OUT}")"
verify_file_sha256 "${NODE_FILE}" "${NODE20_SHA256:-}" "Node ${NODE20_VERSION}"

# ----------------------------------------------------------------------------
# 3) Gradle 8.8 dist zip
# ----------------------------------------------------------------------------
info "[buildtools] (3/6) Gradle ${GRADLE_DIST_VERSION}..."
GRADLE_FILE="$(download "${GRADLE_DIST_URL}" "${GRADLE_OUT}")"
verify_file_sha256 "${GRADLE_FILE}" "${GRADLE_DIST_SHA256:-}" "Gradle ${GRADLE_DIST_VERSION}"

# ----------------------------------------------------------------------------
# 4) gradle-home populate — 전 의존 jar 캐시(플랫폼 무관)
#    backend 에서 GRADLE_USER_HOME 를 buildtools/gradle-home 으로 지정해
#    의존성을 모두 내려받아 캐시를 채운다. 그 후 락/임시 파일을 정리한다.
# ----------------------------------------------------------------------------
if [[ "${SKIP_GRADLE_HOME:-0}" == "1" ]]; then
  warn "[buildtools] (4/6) gradle-home populate SKIP (SKIP_GRADLE_HOME=1)"
else
  BE_SRC="${REPO}/backend"
  [[ -d "${BE_SRC}" ]] || die "[buildtools] backend 디렉토리 없음: ${BE_SRC}"
  info "[buildtools] (4/6) gradle-home populate: ${GRADLE_HOME_OUT}"
  # gradlew 래퍼가 있으면 우선(래퍼가 gradle 8.8 dist 를 받아 사용 — 동일 GRADLE_USER_HOME 캐시).
  # 없으면 PATH 의 gradle 사용.
  if [[ -x "${BE_SRC}/gradlew" ]]; then
    ( cd "${BE_SRC}" \
      && GRADLE_USER_HOME="${GRADLE_HOME_OUT}" ./gradlew --no-daemon bootJar -x test ) \
      || die "[buildtools] gradle-home populate 실패(bootJar) — 인터넷/의존성 확인"
  else
    require_cmd gradle
    ( cd "${BE_SRC}" \
      && GRADLE_USER_HOME="${GRADLE_HOME_OUT}" gradle --no-daemon bootJar -x test ) \
      || die "[buildtools] gradle-home populate 실패(bootJar) — 인터넷/의존성 확인"
  fi
  # 잠금/임시/데몬 산출물 정리(캐시 jar 는 보존). 멱등.
  find "${GRADLE_HOME_OUT}" -type f -name '*.lock' -delete 2>/dev/null || true
  rm -rf "${GRADLE_HOME_OUT}/daemon" "${GRADLE_HOME_OUT}/.tmp" 2>/dev/null || true
  ok "[buildtools] gradle-home 캐시 채움: ${GRADLE_HOME_OUT}  ($(du -sh "${GRADLE_HOME_OUT}" | cut -f1))"
fi

# ----------------------------------------------------------------------------
# 5) frontend node_modules populate (Linux x64 전용 — esbuild 등 plat 바이너리)
#    Darwin 에서는 plat 불일치 경고 후 SKIP. 타깃은 Linux x64 이므로 mac 산출물 금지.
# ----------------------------------------------------------------------------
NM_TARBALL="${BT}/frontend-node_modules.tar.gz"
if [[ "${SKIP_NODE_MODULES:-0}" == "1" ]]; then
  warn "[buildtools] (5/6) node_modules populate SKIP (SKIP_NODE_MODULES=1)"
else
  uname_s="$(uname -s)"
  if [[ "${uname_s}" == "Darwin" ]]; then
    warn "[buildtools] (5/6) node_modules populate SKIP — 빌드머신이 Darwin(mac)입니다."
    warn "  node_modules 는 플랫폼 의존 바이너리(esbuild 등)를 포함하므로 반드시 Linux x64 에서"
    warn "  수집해야 합니다(mac 산출물은 Rocky 9 타깃에서 동작하지 않음)."
    warn "  → rockylinux:9 컨테이너에서 60-collect-buildtools.sh 를 실행해 node_modules 를 채우세요."
  else
    FE_SRC="${REPO}/frontend"
    [[ -d "${FE_SRC}" ]] || die "[buildtools] frontend 디렉토리 없음: ${FE_SRC}"
    require_cmd npm tar
    info "[buildtools] (5/6) frontend node_modules populate (npm ci, Linux x64)..."
    ( cd "${FE_SRC}" && npm ci ) \
      || die "[buildtools] npm ci 실패 — node/npm 및 네트워크 확인"
    [[ -d "${FE_SRC}/node_modules" ]] || die "[buildtools] node_modules 미생성: ${FE_SRC}/node_modules"
    # node_modules 를 tarball 로 묶는다(타깃에서 src/frontend/node_modules 로 복원).
    rm -f "${NM_TARBALL}"
    ( cd "${FE_SRC}" && tar -czf "${NM_TARBALL}" node_modules ) \
      || die "[buildtools] node_modules tar 실패"
    ok "[buildtools] node_modules 번들: ${NM_TARBALL}  ($(du -h "${NM_TARBALL}" | cut -f1))"
  fi
fi

# ----------------------------------------------------------------------------
# 6) 빌드용 소스 복사(플랫폼 무관)
#    제외: .git, node_modules, backend/build, frontend/dist 등 대용량/비필요.
#
#    ⚠ deploy/onprem/src/ 는 gitignore 된 **생성물**이다(정본 아님). copy_src 가 매 패키징마다
#      rm -rf 후 저장소 정본(${REPO}/backend 등)에서 새로 복사하므로, 그 안의 낡은 사본을 직접
#      수정해도 패키지에 반영되지 않는다(=구버전 취약 코드가 폐쇄망으로 새지 않는다, S-26).
#      소스 수정은 반드시 저장소 정본에서 한다.
# ----------------------------------------------------------------------------
if [[ "${SKIP_SRC:-0}" == "1" ]]; then
  warn "[buildtools] (6/6) 소스 복사 SKIP (SKIP_SRC=1)"
else
  info "[buildtools] (6/6) 빌드용 소스 복사 → ${SRC_OUT}"
  ensure_dir "${SRC_OUT}"
  copy_src() {
    local name="$1"
    local from="${REPO}/${name}"
    local to="${SRC_OUT}/${name}"
    [[ -d "${from}" ]] || { warn "[buildtools] 소스 없음(생략): ${from}"; return 0; }
    rm -rf "${to}"
    # tar 파이프로 제외 패턴을 적용해 복사(멱등 — to 를 먼저 제거).
    ensure_dir "${to}"
    ( cd "${from}" && tar -cf - \
        --exclude='./.git' \
        --exclude='./node_modules' \
        --exclude='./build' \
        --exclude='./dist' \
        --exclude='./.gradle' \
        --exclude='./venv' \
        --exclude='./.venv' \
        --exclude='./__pycache__' \
        . ) | ( cd "${to}" && tar -xf - ) \
      || die "[buildtools] 소스 복사 실패: ${name}"
    ok "[buildtools] 소스: ${to}  ($(du -sh "${to}" | cut -f1))"
  }
  copy_src "backend"
  copy_src "frontend"
  copy_src "ai-server"
fi

# ---- 무결성 체크섬(다운로드 tarball/zip) ----
sha256_write "${JDK_OUT}"
sha256_write "${NODE_OUT}"
sha256_write "${GRADLE_OUT}"

ok "[buildtools] 오프라인 빌드 키트 수집 완료."
