#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 10-build-backend.sh — [빌드머신] backend 산출물(WAR + JAR) 빌드 + 수집
#
#   ./gradlew bootJar bootWar 로 두 산출물을 만들어 artifacts/backend/ 로 복사한다.
#
#   ★ 반입 정본은 api.war 다 (@design DEPLOY-001 · RUNBOOK-001, 2026-08-30 사용자 확정).
#     대상 장비의 외부 WAS(Tomcat 10.1.x + Java 17)에 이 WAR 를 올린다.
#     WAR 가 없으면 <실패>다 — 형상이 성립하지 않는 반입물을 만들어 내보내지 않는다.
#   ★ klid-backend.jar 는 <개발 환경 전용>이라 반입 대상이 아니다(DEPLOY-001 의 build_artifacts).
#     설치 스크립트(12-install-backend.sh)는 WAR 형상에서 이 jar 를 설치하지 않고,
#     베어메탈 형상 토글(INSTALL_BACKEND_SYSTEMD_UNIT=1)에서만 쓴다.
#     여기서 계속 만들어 두는 이유는 개발 형상과 베어메탈 복귀 경로가 그 산출물을 쓰기 때문이며,
#     두 산출물은 같은 소스에서 나오므로 내용이 갈릴 일이 없다.
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

# ★ WAR 도 함께 만든다(@design DEPLOY-001). 외부 WAS 반입 형상의 산출물이다.
#   실행 가능 JAR 를 없애지 않는 이유: 개발·단독 기동 형상이 아직 그 산출물을 쓴다.
#   두 산출물은 같은 소스에서 나오므로 내용이 갈릴 일이 없다.
info "[backend] Gradle bootJar + bootWar 빌드 (Java 17 필요)..."
if [[ -x "${BE_SRC}/gradlew" ]]; then
  ( cd "${BE_SRC}" && ./gradlew --no-daemon clean bootJar bootWar )
else
  require_cmd gradle
  ( cd "${BE_SRC}" && gradle --no-daemon clean bootJar bootWar )
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

# ---- WAR 수집 (외부 WAS 반입용 — 반입 정본) ----
#   ★ 파일 이름이 곧 웹 컨텍스트다. 이름을 바꾸면 프론트엔드와 관제의 호출 주소가
#     전부 어긋나므로 빌드가 정한 이름을 그대로 옮긴다(rename 금지).
war_src="${BE_SRC}/build/libs/api.war"
if [[ -f "${war_src}" ]]; then
  rm -f "${OUT}"/*.war
  install -m 0644 "${war_src}" "${OUT}/api.war"
  ok "[backend] WAR 수집: ${OUT}/api.war  ($(du -h "${OUT}/api.war" | cut -f1))"
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
