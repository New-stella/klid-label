#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 10-build-backend.sh — [빌드머신] backend Spring Boot bootJar 빌드 + 수집
#
#   ./gradlew bootJar 로 실행 가능한 fat jar 를 만든 뒤
#   artifacts/backend/ 로 복사한다.
#   결과 jar 패턴: backend/build/libs/*.jar (plain.jar 제외).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"

REPO="$(repo_root)"
ONPREM="$(onprem_root)"
BE_SRC="${REPO}/backend"
OUT="${ONPREM}/artifacts/backend"

[[ -d "${BE_SRC}" ]] || die "backend 디렉토리를 찾을 수 없습니다: ${BE_SRC}"
ensure_dir "${OUT}"

info "[backend] Gradle bootJar 빌드 (Java 17 필요)..."
if [[ -x "${BE_SRC}/gradlew" ]]; then
  ( cd "${BE_SRC}" && ./gradlew --no-daemon clean bootJar )
else
  require_cmd gradle
  ( cd "${BE_SRC}" && gradle --no-daemon clean bootJar )
fi

# bootJar 결과만 선택(-plain.jar 는 라이브러리 jar 이므로 제외)
shopt -s nullglob
jars=()
for j in "${BE_SRC}"/build/libs/*.jar; do
  case "${j}" in
    *-plain.jar) : ;;       # 제외
    *) jars+=("${j}") ;;
  esac
done
shopt -u nullglob

[[ "${#jars[@]}" -ge 1 ]] || die "bootJar 결과를 찾을 수 없습니다: ${BE_SRC}/build/libs/*.jar"

# 단일 실행 jar 를 고정 이름으로 복사(설치 스크립트가 이 이름을 참조)
rm -f "${OUT}"/*.jar
cp "${jars[0]}" "${OUT}/klid-backend.jar"
ok "[backend] 수집: ${OUT}/klid-backend.jar  ($(du -h "${OUT}/klid-backend.jar" | cut -f1))"

sha256_write "${OUT}"
