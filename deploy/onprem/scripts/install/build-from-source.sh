#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# build-from-source.sh — [대상 서버 / 폐쇄망 RHEL 8.9] 소스 오프라인 재빌드(신규)
#   ⚠ 구 서술 폐기(2026-08-28) — "Rocky 9".
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
#     artifacts/backend/api.war          ← 반입 정본(외부 WAS 반입, @design DEPLOY-001)
#     artifacts/backend/klid-backend.jar ← 베어메탈 형상용(반입 대상 아님)
#       ★ 빌드머신 수집(package/10-build-backend.sh)은 2026-08-30 부터 이 jar 를 <담지 않는다>
#         (WITH_BACKEND_JAR=1 일 때만). 여기서는 계속 만든다 — 이 스크립트는 <타깃 장비>에서
#         돌고 그 결과물은 매체가 아니라 설치 장비에 생기며, 베어메탈 복귀 경로가 그것을 쓴다.
#     artifacts/frontend/dist/{control,portal}
#       ★★ 화면 산출물은 <배포 향마다 따로> 만든다 — 빌드머신 경로(package/20-build-frontend.sh)와
#         같은 규칙이다. 라우트 채널이 빌드 시점에 굳어 반대 향 화면이 산출물에서 통째로
#         빠지므로(관제 산출물에 /portal 0건 · 포털 산출물에 내부 화면 0건), 한 번만 빌드하면
#         포털향 설치에 포털 화면이 하나도 없는 산출물이 올라간다. 오류가 없어 조용히 어긋난다.
#         ⚠ 대가: frontend 재빌드 시간이 <약 2배>다(채널당 1회). backend 는 그대로다.
#
#   ★ 빌드 키트는 2026-08-30 부터 <기본 반입 대상이 아니다>(현장 재빌드 요구 없음 확인).
#     따라서 이 스크립트는 <빌드 키트를 명시적으로 함께 반입한 패키지>에서만 동작한다.
#     빌드머신에서 WITH_BUILDTOOLS=1 ./scripts/package.sh 로 수집해야 한다.
#
#   사용법:
#     sudo ./scripts/install/build-from-source.sh          # backend + frontend 재빌드
#     SKIP_BACKEND=1 ./scripts/install/build-from-source.sh
#     SKIP_FRONTEND=1 ./scripts/install/build-from-source.sh
#     BUILD_FLAVORS="control" ./scripts/install/build-from-source.sh   # 한 향만 재빌드
#     VITE_API_BASE_URL=/api/v1 VITE_TOKEN_INGRESS=localStorage ... 빌드 인자 override 가능
#
#   ★ 상위 로그인 주소(VITE_CONTROL_LOGIN_URL / VITE_PORTAL_LOGIN_URL)는 <빌드에 필요 없다>.
#     런타임 설정(/etc/klid/frontend.env → klid-config.js)에서 읽으므로 여기 주는 값은
#     런타임 설정이 없을 때의 폴백일 뿐이다. 필수 값 검사는 설치 시점으로 옮겼다
#     (install/render-frontend-config.sh 가 비면 생성을 거부한다).
#     ⚠ 구 서술 폐기(2026-08-30): "재빌드 시 필수다 — 미설정이면 빌드 중단". 그대로 두면
#       주소를 바꾸려고 대상 서버에서 재빌드하게 만든다(런타임 주입을 도입한 이유가 그것이다).
#       같은 파일 본문(빌드 인자 조립부)이 이미 반대로 적고 있어 자기모순이었다.
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

# ----------------------------------------------------------------------------
# 사전 확인 — 빌드 키트가 반입되지 않았으면 여기서 <명확히> 끝낸다.
#   빌드 키트는 기본 제외라(2026-08-30) "패키지가 손상됐다"가 아니라 "이 패키지는 소스 재빌드용이
#   아니다"가 정상 상태다. 그 구분을 안내하지 않으면 현장이 매체 손상으로 오인한다.
# ----------------------------------------------------------------------------
_kit_missing=""
_kit_need() {  # _kit_need <경로> <설명>
  if [[ ! -d "$1" ]] || [[ -z "$(ls -A "$1" 2>/dev/null || true)" ]]; then
    _kit_missing="${_kit_missing}${_kit_missing:+, }$2"
  fi
}
_kit_need "${BT}/jdk"    "buildtools/jdk (Temurin JDK17 full)"
_kit_need "${BT}/node"   "buildtools/node (Node 20)"
_kit_need "${BT}/gradle" "buildtools/gradle (Gradle dist)"
[[ -d "${SRC}/backend" || -d "${SRC}/frontend" ]] \
  || _kit_missing="${_kit_missing}${_kit_missing:+, }src/{backend,frontend} (빌드용 소스)"

if [[ -n "${_kit_missing}" ]]; then
  warn "이 패키지에는 오프라인 빌드 키트가 반입되어 있지 않습니다(누락: ${_kit_missing})."
  warn ""
  warn "이는 손상이 아니라 <기본 형상>입니다 — 2026-08-30 부터 빌드 키트는 반입 대상에서 빠졌습니다"
  warn "(현장 재빌드 요구가 없음을 확인. 라이선스 표면·매체 용량·보안 표면이 함께 줄어듭니다)."
  warn ""
  warn "  · 소스 재빌드가 <필요 없다면>: 이 스크립트를 쓰지 말고 사전 빌드 아티팩트로 설치하세요."
  warn "        sudo ./scripts/install.sh"
  warn "  · 소스 재빌드가 <필요하다면>: 빌드머신(인터넷 O)에서 키트를 포함해 다시 수집한 뒤 반입하세요."
  warn "        WITH_BUILDTOOLS=1 ./scripts/package.sh"
  warn ""
  warn "상세: docs/08-build-from-source.md · docs/02-build-package.md"
  die "빌드 키트 없음 — 소스 재빌드를 진행할 수 없습니다."
fi

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
  # ★ bootWar 도 함께 만든다 — 반입 정본이 api.war 이기 때문이다(@design DEPLOY-001).
  #   jar 만 만들면 재빌드 결과로는 <배포할 수 없는> 산출물만 나온다.
  info "[build-src] backend 오프라인 빌드(gradle --offline bootJar bootWar -x test)..."
  JAVA_HOME="${JAVA_HOME}" PATH="${JAVA_HOME}/bin:${PATH}" \
    "${GRADLE_BIN}" --offline --no-daemon \
      --gradle-user-home "${GRADLE_HOME}" \
      -p "${BE_SRC}" \
      bootJar bootWar -x test \
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
  ok "[build-src] backend jar(개발/베어메탈용): ${BE_OUT}/klid-backend.jar  ($(du -h "${BE_OUT}/klid-backend.jar" | cut -f1))"

  # WAR(반입 정본). ⚠ 구 주석 폐기(2026-09-04): "이름이 곧 웹 컨텍스트라 rename 금지" —
  #   EAP 에서는 WAR 안 jboss-web.xml 이 컨텍스트(/api)를 정하므로 파일명은 무엇이든 된다.
  war_src="${BE_SRC}/build/libs/api.war"
  [[ -f "${war_src}" ]] || die "[build-src] WAR 산출물 없음: ${war_src} — src/backend 의 build.gradle 에 bootWar 설정이 있는지 확인"
  rm -f "${BE_OUT}"/*.war
  install -m 0644 "${war_src}" "${BE_OUT}/api.war"
  ok "[build-src] backend WAR(반입 정본): ${BE_OUT}/api.war  ($(du -h "${BE_OUT}/api.war" | cut -f1))"

  # ★ SHA256SUMS 는 WAR 배치 뒤에 쓴다(목록에서 반입 정본이 빠지지 않도록).
  sha256_write "${BE_OUT}"
fi

# ----------------------------------------------------------------------------
# frontend — node_modules 복원 → 향별 오프라인 빌드 → artifacts/frontend/dist/{control,portal}
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
  # 토큰 인계 채널 기본값에 URL 쿼리 채널을 넣지 않는다 — 관제/포털은 동일 origin 브라우저
  # 저장소로 JWT 를 인계하며(ADR-012) `?token=` 은 접근 로그·리퍼러·히스토리에 남는다(CWE-598).
  # 레거시 호환이 필요한 현장만 VITE_TOKEN_INGRESS 를 명시 override 한다.
  export VITE_TOKEN_INGRESS="${VITE_TOKEN_INGRESS:-localStorage}"
  export VITE_DEV_LOGIN_ENABLED="${VITE_DEV_LOGIN_ENABLED:-true}"
  export VITE_DEV_UPLOAD_ENABLED="${VITE_DEV_UPLOAD_ENABLED:-true}"
  # 상위 시스템 로그인 URL — <빌드에 요구하지 않는다>. 이 값들은 런타임 설정
  # (/etc/klid/frontend.env → klid-config.js)에서 읽으며, 여기 주는 값은 런타임 설정이 없을
  # 때의 폴백일 뿐이다. fail-closed 가드는 설치 시점으로 옮겼다
  # (install/render-frontend-config.sh — 필수 값이 비면 생성을 거부한다).
  # ⚠ 되살리지 말 것: 되살리면 대상 서버에서 재빌드해야만 주소를 바꿀 수 있는 상태로 돌아간다.
  export VITE_CONTROL_LOGIN_URL="${VITE_CONTROL_LOGIN_URL:-}"
  export VITE_PORTAL_LOGIN_URL="${VITE_PORTAL_LOGIN_URL:-}"
  info "[build-src] VITE_API_BASE_URL=${VITE_API_BASE_URL} VITE_TOKEN_INGRESS=${VITE_TOKEN_INGRESS} VITE_DEV_LOGIN_ENABLED=${VITE_DEV_LOGIN_ENABLED} VITE_DEV_UPLOAD_ENABLED=${VITE_DEV_UPLOAD_ENABLED}"
  info "[build-src] VITE_CONTROL_LOGIN_URL=${VITE_CONTROL_LOGIN_URL} VITE_PORTAL_LOGIN_URL=${VITE_PORTAL_LOGIN_URL}"

  # ---- 배포 향별 빌드 ------------------------------------------------------
  #   ★ 두 벌을 <스테이징>에 모두 만든 뒤에 교체한다. 앞서 지우면 두 번째 빌드가 실패했을 때
  #     아무 산출물도 없는 상태가 남고, 설치가 그 자리에서 멈춘다.
  #   ★ 어느 향만 만들지 좁히려면 BUILD_FLAVORS="control" 처럼 준다. 기본은 둘 다다 —
  #     좁히는 것이 기본이면 "포털향인데 관제 산출물만 있는" 조용한 어긋남이 되돌아온다.
  read -r -a _fe_flavors <<< "${BUILD_FLAVORS:-control portal}"
  for _f in "${_fe_flavors[@]}"; do
    case "${_f}" in
      control|portal) ;;
      *) die "[build-src] 알 수 없는 배포 향: '${_f}' (허용: control | portal)" ;;
    esac
  done

  FE_STAGE="${FE_OUT}/.dist.staging"
  rm -rf "${FE_STAGE}"
  ensure_dir "${FE_STAGE}"

  for _f in "${_fe_flavors[@]}"; do
    info "[build-src] frontend 오프라인 빌드 [${_f}] (npm run build:${_f})..."
    # node_modules 가 이미 있으므로 npm run build 는 네트워크 없이 동작한다.
    # 앞 채널 산출물이 섞이지 않게 매번 비우고 시작한다.
    rm -rf "${FE_SRC}/dist"
    ( cd "${FE_SRC}" \
      && PATH="${NODE_DIR}/bin:${PATH}" npm run "build:${_f}" --offline ) \
      || die "[build-src] frontend 오프라인 빌드 실패(npm run build:${_f}) — node_modules 무결성 확인"

    [[ -d "${FE_SRC}/dist" ]] || die "[build-src] 빌드 결과(dist) 없음: ${FE_SRC}/dist (${_f})"
    [[ -f "${FE_SRC}/dist/index.html" ]] || die "[build-src] ${_f} 산출물에 index.html 이 없습니다 — 빌드가 반쪽입니다."
    cp -R "${FE_SRC}/dist" "${FE_STAGE}/${_f}"
    ok "[build-src] frontend [${_f}]: $(du -sh "${FE_STAGE}/${_f}" | cut -f1)"
  done

  rm -rf "${FE_OUT}/dist"
  mv "${FE_STAGE}" "${FE_OUT}/dist"
  sha256_write "${FE_OUT}/dist"
  ok "[build-src] frontend dist: ${FE_OUT}/dist  ($(du -sh "${FE_OUT}/dist" | cut -f1))"
  for _f in "${_fe_flavors[@]}"; do
    info "[build-src]   · ${_f} → ${FE_OUT}/dist/${_f}"
  done
fi

info "================================================================"
ok "[build-src] 소스 재빌드 완료."
info "  - backend WAR  → ${ONPREM}/artifacts/backend/api.war        (반입 정본 — WAS 에 올린다)"
info "  - backend jar  → ${ONPREM}/artifacts/backend/klid-backend.jar (개발/베어메탈용)"
info "  - frontend dist → ${ONPREM}/artifacts/frontend/dist/{control,portal}  (설치가 배포 향으로 하나를 고른다)"
info ""
info "  다음 단계: sudo ./scripts/install.sh 를 실행하면 위 산출물이 설치됩니다."
info "  (ai-server 는 별도 컴파일 없음 — install.sh 의 13-install-ai-server.sh 가"
info "   pip install --no-index --find-links vendor/wheels 로 venv 에 소스 설치합니다.)"
info "================================================================"
