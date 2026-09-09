#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 60-collect-buildtools.sh — [빌드머신] 오프라인 "소스 재빌드" 키트 수집(신규)
# @step 인터넷=필요 | 소요=미측정(GB 급 수집) | 선행=WITH_BUILDTOOLS=1 | 재실행=안전(받은 것은 건너뜀)
#
#   사전 빌드 아티팩트(jar/dist)와 별개로, 폐쇄망 타깃(RHEL 8.9)에서 소스를
#   인터넷 없이 재빌드하기 위한 빌드 도구 + 의존성 캐시 + 소스를 번들한다.
#   빌드 도구 바이너리만으로는 오프라인 빌드가 닫히지 않는다 — gradle 의존
#   캐시(GRADLE_USER_HOME)와 frontend node_modules 까지 채워야 한다.
#
#   ★★ 이 단계는 <기본 수집 대상이 아니다> (2026-08-30 사용자 확정, 구속).
#     package.sh 는 WITH_BUILDTOOLS=1 일 때만 이 스크립트를 부른다. 현장 재빌드 요구가 없음을
#     확인했고, 빼면 라이선스 표면(JDK full 한 벌 · node_modules 645 패키지, MPL-2.0 3건)·
#     매체 용량·타깃의 빌드 도구 체인(보안 표면)이 함께 줄어든다.
#     ⚠ 여기 JDK17 full 은 <소스 빌드용>이다 — 실행용 JRE(versions.sh 의 TEMURIN_JRE_*)와 별개이며,
#       둘 다 반입되지 않지만 이유가 다르다(그쪽은 대상 WAS 가 Java 17 을 제공하기 때문).
#     이 스크립트를 직접 실행하면 토글과 무관하게 수집한다(수동 보강 경로).
#
#   ★ 이 스크립트는 인터넷이 필요하다(빌드머신에서만 실행). gradle-home/node_modules
#     populate 단계는 실제로 gradle/npm 을 호출하므로 권장 환경은 el8 컨테이너다.
#
#   ★ node_modules 는 플랫폼 의존 바이너리(esbuild 등)를 포함한다 → 반드시
#     Linux x64 에서 `npm ci` 로 채워야 한다. mac(Darwin)에서 직접 수집하면 타깃에서
#     동작하지 않으므로, Darwin 에서는 docker 로 el8(linux/amd64) 컨테이너를 띄워 채운다.
#     ⚠ 구 동작(2026-08-28 이전): Darwin 이면 무조건 SKIP 이라 mac 빌드머신에서는 오프라인
#       빌드 키트가 영영 닫히지 않았다. docker 가 없을 때만 SKIP 으로 남긴다.
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
  # Darwin(mac)에서는 네이티브 npm ci 산출물이 타깃(Linux x64)에서 동작하지 않는다.
  # docker 가 있으면 el8 컨테이너(linux/amd64)에서 채우고, 없을 때만 SKIP 한다.
  if [[ "${uname_s}" == "Darwin" ]] && command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    FE_SRC="${REPO}/frontend"
    [[ -d "${FE_SRC}" ]] || die "[buildtools] frontend 디렉토리 없음: ${FE_SRC}"
    [[ -s "${NODE_FILE}" ]] || die "[buildtools] Node20 tarball 이 없습니다: ${NODE_FILE}"
    info "[buildtools] (5/6) node_modules populate — ${EL8_BUILDER_IMAGE} 컨테이너(${EL8_BUILDER_PLATFORM})"
    info "  빌드머신이 Darwin 이라 네이티브 npm ci 산출물을 쓸 수 없습니다(esbuild 등 plat 바이너리)."
    rm -f "${NM_TARBALL}"
    # 번들해 둔 Node20(linux-x64) tarball 을 컨테이너 안에서 풀어 그 npm 으로 ci 를 돌린다
    # (컨테이너 배포판의 node 버전에 의존하지 않도록 — BUILD_NODE_MAJOR 핀과 정합).
    docker run --rm \
      --platform "${EL8_BUILDER_PLATFORM}" \
      -v "${FE_SRC}:/fe" \
      -v "${NODE_FILE}:/node.tar.xz:ro" \
      -v "${BT}:/bt" \
      "${EL8_BUILDER_IMAGE}" \
      bash -c '
        set -euo pipefail
        dnf install -y tar xz gzip findutils >/dev/null
        mkdir -p /opt/node && tar -xJf /node.tar.xz -C /opt/node --strip-components=1
        export PATH=/opt/node/bin:$PATH
        echo "[collect] node $(node --version) / npm $(npm --version)"
        cd /fe
        npm ci
        tar -czf /bt/frontend-node_modules.tar.gz node_modules
      ' || die "[buildtools] 컨테이너 npm ci 실패 — 네트워크/frontend 락파일 확인"
    [[ -s "${NM_TARBALL}" ]] || die "[buildtools] node_modules 번들 미생성: ${NM_TARBALL}"
    ok "[buildtools] node_modules 번들: ${NM_TARBALL}  ($(du -h "${NM_TARBALL}" | cut -f1))"
  elif [[ "${uname_s}" == "Darwin" ]]; then
    warn "[buildtools] (5/6) node_modules populate SKIP — 빌드머신이 Darwin(mac)이고 docker 도 없습니다."
    warn "  node_modules 는 플랫폼 의존 바이너리(esbuild 등)를 포함하므로 반드시 Linux x64 에서"
    warn "  수집해야 합니다(mac 산출물은 RHEL 8.9 타깃에서 동작하지 않음)."
    warn "  → docker 를 설치하거나, el8 컨테이너에서 60-collect-buildtools.sh 를 실행하세요:"
    warn "     docker run --rm --platform ${EL8_BUILDER_PLATFORM} -v \"\$PWD/../..:/work\" -w /work/deploy/onprem \\"
    warn "       ${EL8_BUILDER_IMAGE} ./scripts/package/60-collect-buildtools.sh"
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
#    ⚠ __pycache__ 는 선두 고정 패턴('./__pycache__')만으로는 부족하다. GNU tar 는 '/' 가 든
#      패턴을 선두에 고정해 해석하므로 중첩된 app/__pycache__ 를 놓친다(bsdtar 는 잡는다).
#      빌드머신의 tar 방언에 결과가 좌우되지 않도록 비고정 패턴을 함께 둔다. 반대로
#      './build' · './dist' 는 <고정이 의도>다 — 비고정으로 바꾸면 소스 트리 안의 동명
#      디렉터리까지 통째로 빠진다.
#
#    ⚠ deploy/onprem/src/ 는 gitignore 된 **생성물**이다(정본 아님). klid_export_source 가 매 패키징마다
#      rm -rf 후 저장소 정본(${REPO}/backend 등)에서 새로 복사하므로, 그 안의 낡은 사본을 직접
#      수정해도 패키지에 반영되지 않는다(=구버전 취약 코드가 폐쇄망으로 새지 않는다, S-26).
#      소스 수정은 반드시 저장소 정본에서 한다.
# ----------------------------------------------------------------------------
if [[ "${SKIP_SRC:-0}" == "1" ]]; then
  warn "[buildtools] (6/6) 소스 복사 SKIP (SKIP_SRC=1)"
else
  info "[buildtools] (6/6) 빌드용 소스 복사 → ${SRC_OUT}"
  ensure_dir "${SRC_OUT}"
  # ★★ 구 방식(tar --exclude 나열)은 폐기했다 — 제외 목록은 fail-open 이라
  #   `backend/storage/`(원본 영상 mp4 11,012 · 프레임 jpg 6,380 · 447MB 는 비식별 전)가
  #   그대로 실릴 상태였다. 개인정보가 매체로 나가는 경로다. 되살리지 말 것.
  #   판정은 git 추적 목록이 한다(.gitignore 가 이미 정본) — klid_export_source 주석 참조.
  #   ⚠ 그 헬퍼는 2026-09-05 에 만들어졌는데 <호출처가 없어 그동안 돌지 않았다>.
  klid_export_source "backend"   "${SRC_OUT}"
  klid_export_source "frontend"  "${SRC_OUT}"
  klid_export_source "ai-server" "${SRC_OUT}"
  klid_write_source_info "${SRC_OUT}"
fi

# ---- 무결성 체크섬(다운로드 tarball/zip) ----
sha256_write "${JDK_OUT}"
sha256_write "${NODE_OUT}"
sha256_write "${GRADLE_OUT}"

ok "[buildtools] 오프라인 빌드 키트 수집 완료."
