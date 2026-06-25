#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# build-from-source.sh — [대상 서버 / 폐쇄망 Rocky 9] 소스 오프라인 재빌드(신규)
#
#   사전 빌드 아티팩트(jar/dist) 대신, 번들된 소스 + 빌드 키트로 타깃에서
#   직접 재빌드한다. 외부 네트워크 호출은 전혀 하지 않는다(전부 로컬).
#
#   전제(빌드머신에서 60-collect-buildtools.sh 로 채워져 있어야 함):
#     buildtools/jdk/      Temurin JDK17 full tarball
#     buildtools/node/     Node20 tarball
#     buildtools/gradle/   Gradle 8.8 dist zip
#     buildtools/gradle-home/ populated GRADLE_USER_HOME(전 의존 jar 캐시)
#     buildtools/frontend-node_modules.tar.gz frontend node_modules(Linux x64)
#     src/{backend,frontend,ai-server}        빌드용 소스
#
#   산출물(install.sh 가 기대하는 위치에 배치 — 이후 install.sh 가 그대로 설치):
#     artifacts/backend/klid-backend.jar
#     artifacts/frontend/dist
#
#   사용법:
#     sudo ./scripts/install/build-from-source.sh          # backend + frontend 재빌드
#     SKIP_BACKEND=1 ./scripts/install/build-from-source.sh
#     SKIP_FRONTEND=1 ./scripts/install/build-from-source.sh
#     VITE_API_BASE_URL=/api/v1 VITE_TOKEN_INGRESS=all ... 빌드 인자 override 가능
#
#   ai-server 는 별도 컴파일이 없다 — 13-install-ai-server.sh 가
#   pip install --no-index --find-links vendor/wheels 로 소스 설치한다(아래 안내).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"

ONPREM="$(onprem_root)"
BT="${ONPREM}/buildtools"
SRC="${ONPREM}/src"

# 빌드 도구 해제 위치(타깃). 멱등 — 이미 풀려 있으면 재사용.
BUILD_PREFIX="${KLID_BUILD_PREFIX:-/opt/klid/buildtools}"
JDK_DIR="${BUILD_PREFIX}/jdk"
NODE_DIR="${BUILD_PREFIX}/node"
GRADLE_DIR="${BUILD_PREFIX}/gradle"
GRADLE_HOME="${BUILD_PREFIX}/gradle-home"

require_cmd tar
ensure_dir "${BUILD_PREFIX}" "${JDK_DIR}" "${NODE_DIR}" "${GRADLE_DIR}"

# ----------------------------------------------------------------------------
# 헬퍼: 단일 압축 파일을 dest 로 풀고 최상위 1단계 디렉토리를 평탄화
#   patterns: 공백 구분 glob 목록(예: "*.tar.gz *.tar.xz *.zip")
# ----------------------------------------------------------------------------
extract_flatten() {
  local src_dir="$1" dest="$2"; shift 2
  local found=""
  local p
  for p in "$@"; do
    shopt -s nullglob
    local matches=("${src_dir}"/${p})
    shopt -u nullglob
    if [[ "${#matches[@]}" -ge 1 ]]; then
      found="${matches[0]}"
      break
    fi
  done
  [[ -n "${found}" ]] || die "압축 파일을 찾을 수 없습니다: ${src_dir} ($*)"
  local tmp; tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp:-}"' RETURN
  case "${found}" in
    *.tar.gz)  tar -xzf "${found}" -C "${tmp}" ;;
    *.tar.xz)  tar -xJf "${found}" -C "${tmp}" ;;
    *.zip)     require_cmd unzip; unzip -q "${found}" -d "${tmp}" ;;
    *)         die "지원하지 않는 압축 형식: ${found}" ;;
  esac
  local entries; entries=("${tmp}"/*)
  rm -rf "${dest:?}"/*
  if [[ "${#entries[@]}" -eq 1 && -d "${entries[0]}" ]]; then
    cp -R "${entries[0]}"/. "${dest}/"
  else
    cp -R "${tmp}"/. "${dest}/"
  fi
}

# ----------------------------------------------------------------------------
# 빌드 도구 해제 + 무결성 검증
# ----------------------------------------------------------------------------
info "[build-src] JDK17 full 해제 → ${JDK_DIR}"
shopt -s nullglob; jdk_tars=("${BT}/jdk"/*.tar.gz); shopt -u nullglob
[[ "${#jdk_tars[@]}" -ge 1 ]] || die "JDK17 full tarball 없음: ${BT}/jdk/ (60-collect-buildtools.sh 미실행?)"
verify_file_sha256 "${jdk_tars[0]}" "${JDK17_FULL_SHA256:-}" "Temurin JDK17 full ${JDK17_FULL_VERSION}"
extract_flatten "${BT}/jdk" "${JDK_DIR}" "*.tar.gz"
JAVA_HOME="${JDK_DIR}"
[[ -x "${JAVA_HOME}/bin/javac" ]] || die "javac 없음: ${JAVA_HOME}/bin/javac (full JDK 아님?)"
ok "[build-src] javac: $("${JAVA_HOME}/bin/javac" -version 2>&1)"

info "[build-src] Node20 해제 → ${NODE_DIR}"
shopt -s nullglob; node_tars=("${BT}/node"/*.tar.xz "${BT}/node"/*.tar.gz); shopt -u nullglob
[[ "${#node_tars[@]}" -ge 1 ]] || die "Node20 tarball 없음: ${BT}/node/"
verify_file_sha256 "${node_tars[0]}" "${NODE20_SHA256:-}" "Node ${NODE20_VERSION}"
extract_flatten "${BT}/node" "${NODE_DIR}" "*.tar.xz" "*.tar.gz"
[[ -x "${NODE_DIR}/bin/node" ]] || die "node 없음: ${NODE_DIR}/bin/node"
ok "[build-src] node: $("${NODE_DIR}/bin/node" --version 2>&1)"

info "[build-src] Gradle ${GRADLE_DIST_VERSION} 해제 → ${GRADLE_DIR}"
shopt -s nullglob; gradle_zips=("${BT}/gradle"/*.zip); shopt -u nullglob
[[ "${#gradle_zips[@]}" -ge 1 ]] || die "Gradle dist zip 없음: ${BT}/gradle/"
verify_file_sha256 "${gradle_zips[0]}" "${GRADLE_DIST_SHA256:-}" "Gradle ${GRADLE_DIST_VERSION}"
extract_flatten "${BT}/gradle" "${GRADLE_DIR}" "*.zip"
GRADLE_BIN="${GRADLE_DIR}/bin/gradle"
[[ -x "${GRADLE_BIN}" ]] || die "gradle 실행 파일 없음: ${GRADLE_BIN}"
ok "[build-src] gradle: $(JAVA_HOME="${JAVA_HOME}" "${GRADLE_BIN}" --version 2>&1 | grep -i '^Gradle ' | head -n1)"

# gradle-home 캐시 복원(빌드머신에서 채운 캐시를 그대로 사용 — --offline 의 전제).
[[ -d "${GRADLE_HOME}" ]] && [[ -n "$(ls -A "${GRADLE_HOME}" 2>/dev/null || true)" ]] \
  || warn "[build-src] gradle-home 캐시가 비어 있습니다(${GRADLE_HOME}) — --offline 빌드가 실패할 수 있습니다."

# ----------------------------------------------------------------------------
# backend — gradle --offline bootJar -x test → artifacts/backend/klid-backend.jar
# ----------------------------------------------------------------------------
if [[ "${SKIP_BACKEND:-0}" == "1" ]]; then
  warn "[build-src] backend 재빌드 SKIP (SKIP_BACKEND=1)"
else
  BE_SRC="${SRC}/backend"
  [[ -d "${BE_SRC}" ]] || die "backend 소스 없음: ${BE_SRC} (src/ 미동봉?)"
  BE_OUT="${ONPREM}/artifacts/backend"
  ensure_dir "${BE_OUT}"
  info "[build-src] backend 오프라인 빌드(gradle --offline bootJar -x test)..."
  JAVA_HOME="${JAVA_HOME}" PATH="${JAVA_HOME}/bin:${PATH}" \
    "${GRADLE_BIN}" --offline --no-daemon \
      --gradle-user-home "${GRADLE_HOME}" \
      -p "${BE_SRC}" \
      bootJar -x test \
    || die "[build-src] backend 오프라인 빌드 실패 — gradle-home 캐시 누락 가능(08-build-from-source.md 참고)"

  # bootJar 결과(-plain.jar 제외)를 고정 이름으로 배치.
  shopt -s nullglob
  jars=()
  for j in "${BE_SRC}"/build/libs/*.jar; do
    case "${j}" in
      *-plain.jar) : ;;
      *) jars+=("${j}") ;;
    esac
  done
  shopt -u nullglob
  [[ "${#jars[@]}" -ge 1 ]] || die "[build-src] bootJar 결과 없음: ${BE_SRC}/build/libs/*.jar"
  rm -f "${BE_OUT}"/*.jar
  install -m 0644 "${jars[0]}" "${BE_OUT}/klid-backend.jar"
  sha256_write "${BE_OUT}"
  ok "[build-src] backend jar: ${BE_OUT}/klid-backend.jar  ($(du -h "${BE_OUT}/klid-backend.jar" | cut -f1))"
fi

# ----------------------------------------------------------------------------
# frontend — node_modules 복원 → npm run build(오프라인) → artifacts/frontend/dist
# ----------------------------------------------------------------------------
if [[ "${SKIP_FRONTEND:-0}" == "1" ]]; then
  warn "[build-src] frontend 재빌드 SKIP (SKIP_FRONTEND=1)"
else
  FE_SRC="${SRC}/frontend"
  [[ -d "${FE_SRC}" ]] || die "frontend 소스 없음: ${FE_SRC} (src/ 미동봉?)"
  FE_OUT="${ONPREM}/artifacts/frontend"
  ensure_dir "${FE_OUT}"

  # node_modules 복원(tarball → src/frontend/node_modules).
  NM_TARBALL="${BT}/frontend-node_modules.tar.gz"
  if [[ -d "${FE_SRC}/node_modules" ]] && [[ -n "$(ls -A "${FE_SRC}/node_modules" 2>/dev/null || true)" ]]; then
    info "[build-src] frontend node_modules 이미 존재(복원 생략): ${FE_SRC}/node_modules"
  elif [[ -f "${NM_TARBALL}" ]]; then
    info "[build-src] frontend node_modules 복원: ${NM_TARBALL}"
    rm -rf "${FE_SRC}/node_modules"
    ( cd "${FE_SRC}" && tar -xzf "${NM_TARBALL}" ) \
      || die "[build-src] node_modules 복원 실패"
    [[ -d "${FE_SRC}/node_modules" ]] || die "[build-src] node_modules 복원 후 디렉토리 없음"
  else
    die "[build-src] frontend node_modules 번들 없음: ${NM_TARBALL}
       → 빌드머신(Linux x64)에서 60-collect-buildtools.sh 로 node_modules 를 채우세요."
  fi

  # Vite 빌드 시점 주입 변수(빌드머신 20-build-frontend.sh 와 동일 기본값).
  export VITE_API_BASE_URL="${VITE_API_BASE_URL:-/api/v1}"
  export VITE_TOKEN_INGRESS="${VITE_TOKEN_INGRESS:-all}"
  export VITE_DEV_LOGIN_ENABLED="${VITE_DEV_LOGIN_ENABLED:-true}"
  export VITE_DEV_UPLOAD_ENABLED="${VITE_DEV_UPLOAD_ENABLED:-true}"
  info "[build-src] VITE_API_BASE_URL=${VITE_API_BASE_URL} VITE_TOKEN_INGRESS=${VITE_TOKEN_INGRESS} VITE_DEV_LOGIN_ENABLED=${VITE_DEV_LOGIN_ENABLED} VITE_DEV_UPLOAD_ENABLED=${VITE_DEV_UPLOAD_ENABLED}"

  info "[build-src] frontend 오프라인 빌드(npm run build)..."
  # node_modules 가 이미 있으므로 npm run build 는 네트워크 없이 동작한다.
  ( cd "${FE_SRC}" \
    && PATH="${NODE_DIR}/bin:${PATH}" npm run build --offline ) \
    || die "[build-src] frontend 오프라인 빌드 실패(npm run build) — node_modules 무결성 확인"

  [[ -d "${FE_SRC}/dist" ]] || die "[build-src] 빌드 결과(dist) 없음: ${FE_SRC}/dist"
  rm -rf "${FE_OUT}/dist"
  cp -R "${FE_SRC}/dist" "${FE_OUT}/dist"
  sha256_write "${FE_OUT}/dist"
  ok "[build-src] frontend dist: ${FE_OUT}/dist  ($(du -sh "${FE_OUT}/dist" | cut -f1))"
fi

info "================================================================"
ok "[build-src] 소스 재빌드 완료."
info "  - backend jar  → ${ONPREM}/artifacts/backend/klid-backend.jar"
info "  - frontend dist → ${ONPREM}/artifacts/frontend/dist"
info ""
info "  다음 단계: sudo ./scripts/install.sh 를 실행하면 위 산출물이 설치됩니다."
info "  (ai-server 는 별도 컴파일 없음 — install.sh 의 13-install-ai-server.sh 가"
info "   pip install --no-index --find-links vendor/wheels 로 venv 에 소스 설치합니다.)"
info "================================================================"
