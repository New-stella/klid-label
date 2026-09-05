#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 10-build-backend.sh — [빌드머신] backend 산출물(WAR) 빌드 + 수집
# @step 인터넷=필요(gradle 의존 해석) | 소요=약 20초(캐시 웜)~수 분(콜드) | 선행=없음 | 재실행=안전(매번 재빌드 — 실패해도 매체의 api.war 는 남는다)
#
#   ./gradlew bootWar 로 반입 정본을 만들어 artifacts/backend/ 로 복사한다.
#
#   ★ 반입 정본은 api.war 다 (@design DEPLOY-001 · RUNBOOK-001, 2026-08-30 사용자 확정).
#     대상 장비의 외부 WAS(JBoss EAP 8.1 + Java 17)에 이 WAR 를 올린다.
#     WAR 가 없으면 <실패>다 — 형상이 성립하지 않는 반입물을 만들어 내보내지 않는다.
#   ★ klid-backend.jar 는 <반입 대상이 아니므로 기본으로 만들지도 담지도 않는다>
#     (2026-08-30 사용자 확정 — 매체에서 제외). DEPLOY-001 의 build_artifacts 가 그 jar 를
#     <개발 환경 전용>으로 규정하고, 설치 스크립트(12-install-backend.sh)도 WAR 형상에서는
#     설치하지 않는다. 그런데 수집은 계속하고 있어 83.6 MiB 가 매체에 실려 있었다 —
#     규정과 동작이 어긋난 상태였고 이 단계가 그것을 맞춘다.
#     ⚠ 구 동작 폐기(2026-08-30) — "bootJar 를 항상 만들어 artifacts/backend/ 로 무조건 복사".
#
#   토글:
#     WITH_BACKEND_JAR=1  베어메탈 형상용 실행 가능 jar 도 빌드·수집(기본 0)
#
#   ★ 능력을 없앤 것이 아니다. 베어메탈 형상(INSTALL_BACKEND_SYSTEMD_UNIT=1)은 그 jar 를 쓰므로
#     위 토글로 켤 수 있게 남긴다. 두 산출물은 같은 소스에서 나오므로 내용이 갈릴 일이 없고,
#     토글을 켜면 그 자리에서 다시 만들어진다.
#   ★ bootJar 를 <빌드 자체에서> 뺀다(복사만 거르지 않는다). bootWar 는 bootJar 에 의존하지
#     않으므로(태스크 그래프 실측: compileJava → processResources → classes →
#     resolveMainClassName → bootWar) 빼도 WAR 경로가 성립하고, clean 빌드마다 아무도 쓰지 않는
#     87 MiB 아카이브를 한 벌 더 조립하지 않아도 된다.
#   결과 jar 패턴: backend/build/libs/*.jar (plain.jar 제외).
#
#   ★ 라이선스 고지도 여기서 <함께> 수집한다 (2026-08-30 신설).
#     반입물에 실제로 실린 jar 목록은 이 단계에서만 확정되므로, 고지 수집도 여기서 해야
#     목록이 어긋나지 않는다. 손으로 적은 목록은 lock 이 바뀌는 순간 낡는다.
#     산출: licenses/backend/<artifact>/…  (jar 안 META-INF 고지 <전문 그대로>)
#           licenses/backend/INVENTORY.tsv (라이선스 이름 — POM <licenses> 에서 해석)
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/licenses.sh
source "${SELF_DIR}/../lib/licenses.sh"

REPO="$(repo_root)"
ONPREM="$(onprem_root)"
BE_SRC="${REPO}/backend"
OUT="${ONPREM}/artifacts/backend"

[[ -d "${BE_SRC}" ]] || die "backend 디렉토리를 찾을 수 없습니다: ${BE_SRC}"
ensure_dir "${OUT}"

# ---- 빌드 대상 결정 ----
#   기본은 bootWar 하나다(@design DEPLOY-001). 외부 WAS 반입 형상의 산출물이 그것뿐이다.
#   WITH_BACKEND_JAR=1 이면 베어메탈 형상용 bootJar 도 함께 만든다.
GRADLE_TASKS=(clean bootWar)
if [[ "${WITH_BACKEND_JAR:-0}" == "1" ]]; then
  GRADLE_TASKS=(clean bootJar bootWar)
  info "[backend] 실행 가능 jar 도 함께 빌드·수집합니다(WITH_BACKEND_JAR=1 — 베어메탈 형상용)."
else
  info "[backend] 실행 가능 jar(klid-backend.jar)는 만들지 않습니다(기본값 — 반입 대상 아님)."
  info "          베어메탈 형상(INSTALL_BACKEND_SYSTEMD_UNIT=1)이 필요하면 빌드머신에서"
  info "            WITH_BACKEND_JAR=1 ./scripts/package.sh"
  info "          로 다시 수집하세요."
fi

info "[backend] Gradle ${GRADLE_TASKS[*]} 빌드 (Java 17 필요)..."
if [[ -x "${BE_SRC}/gradlew" ]]; then
  ( cd "${BE_SRC}" && ./gradlew --no-daemon "${GRADLE_TASKS[@]}" )
else
  require_cmd gradle
  ( cd "${BE_SRC}" && gradle --no-daemon "${GRADLE_TASKS[@]}" )
fi

# ---- 실행 가능 JAR 수집 (기본 <생략> — 반입 대상이 아니다) ----
#   ★ 삭제는 토글과 무관하게 <항상> 한다. 앞선 실행이 남긴 jar 가 그대로 있으면 매체에 다시
#     실리고, 아래 sha256_write 가 그것을 SHA256SUMS 에 적어 "반입 대상 아닌 파일"이
#     무결성 목록의 일부로 굳는다. 껐는데도 남아 있는 상태를 만들지 않는다.
rm -f "${OUT}"/*.jar
if [[ "${WITH_BACKEND_JAR:-0}" == "1" ]]; then
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
  cp "${jars[0]}" "${OUT}/klid-backend.jar"
  ok "[backend] 수집: ${OUT}/klid-backend.jar  ($(du -h "${OUT}/klid-backend.jar" | cut -f1))"
  warn "[backend] 이 jar 는 <반입 정본이 아니다> — 베어메탈 형상에서만 쓰이며,"
  warn "          WAR 반입 형상으로 내보낼 매체라면 다시 WITH_BACKEND_JAR 없이 수집하세요."
fi

# ---- WAR 수집 (외부 WAS 반입용 — 반입 정본) ----
#   ⚠ 구 서술 폐기(2026-09-05): "파일 이름이 곧 웹 컨텍스트다".
#     대상 WAS(JBoss EAP)에서는 WAR 안 WEB-INF/jboss-web.xml 이 컨텍스트를 정하므로
#     이름과 컨텍스트가 분리된다. 그래도 이름은 그대로 옮긴다 — 반입 문서·설치·런북이
#     api.war 라는 이름으로 산출물을 찾기 때문이다(주소가 아니라 절차의 문제).
war_src="${BE_SRC}/build/libs/api.war"
if [[ -f "${war_src}" ]]; then
  rm -f "${OUT}"/*.war
  install -m 0644 "${war_src}" "${OUT}/api.war"
  ok "[backend] WAR 수집: ${OUT}/api.war  ($(du -h "${OUT}/api.war" | cut -f1))"
  # ★ 어떤 향으로 만든 WAR 인지 <산출물에서 읽어> 기록한다 — 나중에 매체만 보고도 알 수 있어야 한다.
  _ctx="$(unzip -p "${OUT}/api.war" WEB-INF/jboss-web.xml 2>/dev/null \
          | tr -d '\r' | grep -o '<context-root>[^<]*</context-root>' \
          | head -n1 | sed 's|.*<context-root>||; s|</context-root>.*||')"
  case "${_ctx}" in
    /label-studio/api) _flavor="passthrough (웹이 /api 를 그대로 넘기는 형상)" ;;
    /label-studio)     _flavor="strip (웹이 /api 를 걷어내는 형상)" ;;
    "")                _flavor="읽지 못함 — jboss-web.xml 이 없다면 컨텍스트가 파일명을 따라간다" ;;
    *)                 _flavor="아는 두 향 중 어느 쪽도 아님 — 의도한 값인지 확인" ;;
  esac
  {
    echo "# klid-label backend 빌드 기록"
    echo "#   웹 컨텍스트는 두 향 중 하나다. 어느 쪽인지는 현장 httpd 설정이 정한다."
    echo "#   다른 향으로 만들려면: ./gradlew bootWar -PklidWebContext=<값>"
    echo "built_at=$(date '+%Y-%m-%d %H:%M:%S%z')"
    echo "git_commit=$(cd "${BE_SRC}" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo "web_context=${_ctx:-unknown}"
    echo "web_context_flavor=${_flavor}"
    echo "browser_api_base=/label-studio/api/v1   # 두 향에서 같다 — 프론트는 재빌드 대상이 아니다"
  } > "${OUT}/BUILD-INFO.txt"
  ok "[backend] 웹 컨텍스트 = ${_ctx:-?}  (${_flavor})"
else
  die "[backend] WAR 산출물을 찾을 수 없습니다: ${war_src} — build.gradle 의 bootWar 설정을 확인하세요."
fi

# ---- 제3자 라이선스 고지 수집 ----------------------------------------------
#   ★ 대상은 <실제로 반입되는 아카이브>다. WAR 가 반입 정본이므로 WAR 를 읽는다.
#     lockfile 을 읽어 목록을 만들면 "빌드에 안 실린 것"까지 고지하게 되고, 반대로
#     플러그인이 끼워 넣은 jar(spring-boot-jarmode-tools 등)는 <빠진다>.
#   ★ 고지 <전문>은 jar 안 META-INF 를 그대로 복사한다(요약 금지 — 요약한 NOTICE 는
#     Apache-2.0 §4(d) 를 못 채운다). 라이선스 <이름>만 POM 에서 해석해 인벤토리에 적는다.
#   ★ 라이선스 수집 실패로 반입물 빌드를 죽이지 않는다 — 여기서 die 하면 "고지가 조금
#     모자라서 납품물 자체가 안 나오는" 상태가 된다. 대신 UNRESOLVED 로 남겨 70 단계가
#     집계하고, 사람이 licenses/manual/OVERRIDES.tsv 로 메운다.
BE_LIC_DIR="$(lic_root)/backend"
if ! command -v unzip >/dev/null 2>&1; then
  warn "[backend] unzip 이 없어 라이선스 고지를 수집하지 못했습니다 — 반입 전 반드시 채우세요."
else
  lic_reset_area "backend"
  BE_INV="${BE_LIC_DIR}/INVENTORY.tsv"
  lic_inventory_init "${BE_INV}"

  # 라이선스 <이름> 해석표(artifact \t version \t license). python3 이 없거나 실패하면 빈 표.
  BE_NAMES="$(mktemp)"; : > "${BE_NAMES}"
  if command -v python3 >/dev/null 2>&1; then
    python3 "${SELF_DIR}/../lib/backend_licenses.py" \
      --archive "${OUT}/api.war" \
      --lockfile "${BE_SRC}/gradle.lockfile" \
      --out-tsv "${BE_NAMES}" \
      || warn "[backend] 라이선스 이름 해석에 실패했습니다(고지 전문 복사는 계속합니다)."
  else
    warn "[backend] python3 이 없어 라이선스 <이름> 해석을 건너뜁니다(고지 전문은 복사됩니다)."
  fi

  BE_TMP="$(mktemp -d)"
  # WAR 안의 라이브러리만 푼다(WEB-INF/lib). bootJar 형상(BOOT-INF/lib)도 함께 받아 둔다.
  ( cd "${BE_TMP}" && unzip -qq -o "${OUT}/api.war" 'WEB-INF/lib/*.jar' 'BOOT-INF/lib/*.jar' 2>/dev/null ) || true

  _n_jar=0; _n_note=0; _n_unres=0
  while IFS= read -r _jar; do
    [[ -n "${_jar}" ]] || continue
    _n_jar=$((_n_jar+1))
    _base="$(basename "${_jar}")"
    _slug="$(lic_slug "${_base%.jar}")"
    _cnt="$(lic_jar_copy_notices "${_jar}" "${BE_LIC_DIR}/${_slug}")"
    # 이름 해석표에서 찾기 — 키는 <jar 파일명>이다(1열). artifact+version 으로 되조립하면
    # classifier 붙은 파일(querydsl-jpa-5.1.0-jakarta.jar)이 조용히 미해석으로 떨어진다.
    _lic="$(awk -F'\t' -v b="${_base}" '$1==b {print $4; exit}' "${BE_NAMES}" 2>/dev/null || true)"
    _ver="$(awk -F'\t' -v b="${_base}" '$1==b {print $3; exit}' "${BE_NAMES}" 2>/dev/null || true)"
    [[ -z "${_lic}" ]] && _lic="$(lic_jar_manifest_license "${_jar}")"
    if [[ "${_cnt}" -gt 0 ]]; then
      _n_note=$((_n_note+1)); _src="ARCHIVE"; _st="OK"
    elif [[ -n "${_lic}" ]]; then
      _src="METADATA"; _st="OK"
    else
      _src="NONE"; _st="UNRESOLVED"; _n_unres=$((_n_unres+1))
    fi
    lic_inventory_add "${BE_INV}" backend "${_base%.jar}" "${_ver}" "${_lic}" "${_src}" "${_st}"
  done < <(find "${BE_TMP}" -name '*.jar' -type f | sort)

  rm -rf "${BE_TMP}"; rm -f "${BE_NAMES}"
  ok "[backend] 라이선스 고지 수집: ${BE_LIC_DIR} (jar ${_n_jar} / 고지파일 보유 ${_n_note} / 미해석 ${_n_unres})"
  [[ "${_n_unres}" -gt 0 ]] && warn "[backend] 라이선스 미해석 ${_n_unres} 건 — licenses/manual/OVERRIDES.tsv 에 사람이 적어야 합니다."
fi

# ★ SHA256SUMS 는 <WAR 를 복사한 뒤> 써야 한다. 앞에서 쓰면 목록에 api.war 가 빠지고,
#   install.sh 의 무결성 검증이 <반입 정본만 검증 없이 통과>시키는 구멍이 된다.
#   ⚠ 구 순서 폐기(2026-08-30) — jar 복사 직후에 sha256_write 를 호출하던 것.
sha256_write "${OUT}"
