#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 70-generate-notices.sh — [빌드머신] 반입물 라이선스 고지 집합 생성
# @step 인터넷=불필요 | 소요=약 1초 | 선행=10·20·30·40 | 재실행=안전(집계만 다시 한다)
#
#   앞 단계(10·20·30·40)가 각자 자기 반입물에서 긁어 licenses/ 아래에 떨궈 놓은
#   고지 파일과 인벤토리를 <모아서> 아래 셋을 만든다.
#
#     licenses/NOTICE      — Apache-2.0 §4(d) 전파용 집합 고지. 반입물에서 수집한
#                            META-INF/NOTICE 를 <전문 그대로> 이어 붙인다.
#     licenses/INVENTORY.md— 구성요소 × 라이선스 × 고지출처 전수 표.
#     licenses/UNRESOLVED.md — 사람이 채워야 할 목록(자동/수동 경계가 여기서 드러난다).
#
#   ★ 왜 별도 단계인가: 집합 파일은 <모든 수집이 끝난 뒤>에만 정확하다. 각 단계가 자기
#     몫만 알고 있으므로, 어느 한 단계가 NOTICE 를 만들면 그 시점에 없던 것이 빠진다.
#
#   ★ 이 단계는 <아무것도 지어내지 않는다>. 수집된 파일과 인벤토리만 읽는다.
#     비어 있으면 비어 있다고 적는다 — 라이선스 문서에서 추정은 위반보다 나쁘다.
#
#   ⚠ 실패해도 반입물 빌드를 죽이지 않는다. 대신 UNRESOLVED 가 남으면 시끄럽게 warn 한다.
#     "고지가 모자라서 납품물 자체가 안 나오는" 상태보다, 무엇이 모자란지 적힌 매체가 낫다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/licenses.sh
source "${SELF_DIR}/../lib/licenses.sh"

ONPREM="$(onprem_root)"
LIC="$(lic_root)"
ensure_dir "${LIC}"

NOW="$(date '+%Y-%m-%d %H:%M:%S%z')"
GITSHA="$(cd "$(repo_root)" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"

# ---- 0) 수집 상태 점검 ------------------------------------------------------
_areas=(backend frontend python-packages python-runtime)
_missing=()
for a in "${_areas[@]}"; do
  [[ -d "${LIC}/${a}" ]] || _missing+=("${a}")
done
if [[ "${#_missing[@]}" -gt 0 ]]; then
  warn "[notices] 아직 수집되지 않은 영역: ${_missing[*]}"
  warn "  해당 수집 단계를 건너뛰었다면 정상입니다(그 영역의 고지는 이번 매체에 없습니다)."
fi

# ---- 0-b) 반입물 ↔ 고지 스테이징 정합 검사 (기계 검사, fail-closed) ---------
#
#   ★★ 왜 있나 (2026-08-30 실사고):
#     이 단계는 <스테이징만> 읽는다. 그래서 휠을 교체한 뒤 스테이징이 다시 돌지 않으면,
#     70 을 몇 번을 돌려도 고지가 실물과 어긋난 채 그대로 재생성된다. 실제로:
#         반입물  setuptools-81.0.0 · packaging-26.2
#         고지    setuptools 84.0.0 · packaging 26.3   ← 둘 다 <반입물에 없는 버전>
#         (그리고 실제로 반입된 setuptools 81.0.0 은 고지에 <없었다>)
#     "스테이징만 보고 집계"했기 때문에 이 불일치가 조용히 통과했다.
#
#   ★ 그래서 <실물 파일 목록>과 대조한다. 35-collect-python-licenses.sh 가 자기가 읽은
#     아카이브 파일명 전수를 SOURCES.txt 에 남기고, 여기서 vendor/wheels 의 현재 내용과
#     정확히 같은지 본다. 이름·버전 파싱을 거치지 않고 <파일명 그대로> 비교하므로
#     파싱 규칙이 달라져도 어긋나지 않는다.
#
#   ★ 여기서는 die 한다 — 이 단계의 다른 실패(UNRESOLVED 잔존)와 성격이 <다르다>.
#     UNRESOLVED 는 "모른다는 것을 아는" 상태라 문서에 그대로 적히지만, 스테이징 불일치는
#     문서가 <사실이 아닌 것을 단언하는> 상태다. 틀린 고지는 빠진 고지보다 나쁘다.
#
#   ⚠ 이 대조가 가능한 것은 python 축뿐이다 — 휠이 <개별 파일>로 반입되기 때문이다.
#     backend 의존성은 api.war 안에 들어 있고 frontend 는 node_modules 를 반입하지 않아
#     "실물 목록"이라는 비교 대상 자체가 없다. 그래서 여기만 검사한다.
WHEEL_DIR="${ONPREM}/vendor/wheels"
PY_SRC_LIST="${LIC}/python-packages/SOURCES.txt"
if [[ -d "${WHEEL_DIR}" ]] && ls "${WHEEL_DIR}"/*.whl >/dev/null 2>&1; then
  if [[ ! -f "${PY_SRC_LIST}" ]]; then
    die "[notices] 고지 스테이징이 없습니다(또는 옛 형식): ${PY_SRC_LIST}
       vendor/wheels 에 휠이 있는데 그것을 읽은 기록이 없습니다 — 고지가 실물과 어긋납니다.
       → bash ${SELF_DIR}/35-collect-python-licenses.sh   (재다운로드 없음)
         bash ${SELF_DIR}/70-generate-notices.sh"
  fi
  _lic_actual="$(mktemp)"; _lic_staged="$(mktemp)"
  ( cd "${WHEEL_DIR}" && find . -maxdepth 1 -type f \( -name '*.whl' -o -name '*.tar.gz' \) \
      | sed 's#^\./##' ) | LC_ALL=C sort > "${_lic_actual}"
  LC_ALL=C sort "${PY_SRC_LIST}" > "${_lic_staged}"
  if ! cmp -s "${_lic_actual}" "${_lic_staged}"; then
    warn "[notices] ★반입물과 고지 스테이징이 어긋납니다 — 고지가 <사실과 다른> 상태입니다."
    warn "  반입물에 있는데 고지에 없음(고지 누락):"
    comm -23 "${_lic_actual}" "${_lic_staged}" | sed 's/^/      + /' >&2 || true
    warn "  고지에 있는데 반입물에 없음(유령 고지):"
    comm -13 "${_lic_actual}" "${_lic_staged}" | sed 's/^/      - /' >&2 || true
    rm -f "${_lic_actual}" "${_lic_staged}"
    die "[notices] 고지 스테이징이 낡았습니다 — 이대로 매체를 반출하면 안 됩니다.
       ⚠ 70 단계만 다시 돌려도 고쳐지지 않습니다(이 단계는 스테이징만 읽습니다).
       → bash ${SELF_DIR}/35-collect-python-licenses.sh   (재다운로드 없음)
         bash ${SELF_DIR}/70-generate-notices.sh"
  fi
  rm -f "${_lic_actual}" "${_lic_staged}"
  ok "[notices] 반입물 ↔ 고지 스테이징 정합 확인: $(wc -l < "${PY_SRC_LIST}" | tr -d ' ') 개 아카이브 일치"
fi

# ---- 1) NOTICE ----------------------------------------------------------------
#   Apache-2.0 §4(d): 배포물에 NOTICE 파일이 있으면 <그 내용을 전파>해야 한다.
#   backend 의존성 다수가 Apache-2.0 이고 실제로 META-INF/NOTICE 를 담고 있다.
NOTICE="${LIC}/NOTICE"
{
  if [[ -f "${LIC}/NOTICE.header.txt" ]]; then
    cat "${LIC}/NOTICE.header.txt"
  else
    printf '%s\n' "klid-label 온프렘 반입물 — 제3자 고지(NOTICE)"
  fi
  printf '\n생성 시각: %s\n생성 소스 커밋: %s\n' "${NOW}" "${GITSHA}"
  printf '%s\n' "생성 방법: deploy/onprem/scripts/package/70-generate-notices.sh (자동 생성 — 직접 편집 금지)"
  printf '\n%s\n' "================================================================================"
  printf '%s\n' "아래는 반입물 안에서 발견한 NOTICE 파일을 <전문 그대로> 이어 붙인 것이다."
  printf '%s\n' "요약하거나 발췌하지 않는다 — 요약한 NOTICE 는 Apache-2.0 §4(d) 를 충족하지 못한다."
  printf '%s\n\n' "================================================================================"
} > "${NOTICE}"

_notice_files=0
while IFS= read -r f; do
  [[ -n "${f}" ]] || continue
  _rel="${f#"${LIC}"/}"
  {
    printf '\n%s\n' "--------------------------------------------------------------------------------"
    printf '%s\n' "### ${_rel}"
    printf '%s\n\n' "--------------------------------------------------------------------------------"
    cat "${f}"
    printf '\n'
  } >> "${NOTICE}"
  _notice_files=$((_notice_files+1))
done < <(find "${LIC}" -type f \( -iname 'notice' -o -iname 'notice.*' \) \
           -not -name 'NOTICE.header.txt' -not -path "${LIC}/NOTICE" 2>/dev/null | sort)

if [[ "${_notice_files}" -eq 0 ]]; then
  printf '%s\n' "(반입물에서 NOTICE 파일을 한 건도 찾지 못했다. 수집 단계가 돌지 않았는지 확인할 것.)" >> "${NOTICE}"
fi
ok "[notices] NOTICE 생성: ${NOTICE} (원본 NOTICE ${_notice_files} 건 병합)"

# ---- 2) 인벤토리 병합 + 사람 보정(OVERRIDES) 적용 ---------------------------
#   ★ OVERRIDES.tsv 가 <자동/수동 경계>다. 스캐너가 못 얻은 값을 사람이 여기 적고,
#     이 단계가 그것을 덮어쓴다. 수집 스크립트나 생성 산출물을 손으로 고치지 않는다 —
#     그건 다음 수집에서 조용히 사라진다.
#   형식(탭 구분, # 주석 허용): group <TAB> component <TAB> license <TAB> notice_source <TAB> status
ALL_TSV="${LIC}/ALL.tsv"
OVERRIDES="${LIC}/manual/OVERRIDES.tsv"
lic_inventory_init "${ALL_TSV}"

_tsvs=()
while IFS= read -r t; do _tsvs+=("${t}"); done < <(find "${LIC}" -name 'INVENTORY.tsv' -type f 2>/dev/null | sort)

for t in "${_tsvs[@]}"; do
  awk 'NR>1' "${t}" >> "${ALL_TSV}"
done

_ov_applied=0
if [[ -f "${OVERRIDES}" ]]; then
  _merged="$(mktemp)"
  awk -F'\t' -v OFS='\t' '
    FNR==NR {
      if ($0 ~ /^[[:space:]]*#/ || NF < 5) next
      key = $1 SUBSEP $2
      lic[key] = $3; src[key] = $4; st[key] = $5
      next
    }
    FNR==1 { print; next }                       # 헤더 보존
    {
      key = $1 SUBSEP $2
      if (key in lic) {
        if (lic[key] != "") $4 = lic[key]
        if (src[key] != "") $5 = src[key]
        if (st[key]  != "") $6 = st[key]
        applied++
      }
      print
    }
    END { print applied+0 > "/dev/stderr" }
  ' "${OVERRIDES}" "${ALL_TSV}" > "${_merged}" 2>"${_merged}.n"
  _ov_applied="$(tail -1 "${_merged}.n" 2>/dev/null || echo 0)"
  mv "${_merged}" "${ALL_TSV}"; rm -f "${_merged}.n"
  [[ "${_ov_applied}" -gt 0 ]] && ok "[notices] 사람 보정(OVERRIDES) ${_ov_applied} 건 적용"
fi

_tot="$(awk 'NR>1' "${ALL_TSV}" | wc -l | tr -d ' ')"
_ok="$(awk -F'\t' 'NR>1 && $6=="OK"' "${ALL_TSV}" | wc -l | tr -d ' ')"
_txtmiss="$(awk -F'\t' 'NR>1 && $6=="TEXT_MISSING"' "${ALL_TSV}" | wc -l | tr -d ' ')"
_unres="$(awk -F'\t' 'NR>1 && $6=="UNRESOLVED"' "${ALL_TSV}" | wc -l | tr -d ' ')"

# ---- 3) INVENTORY.md ----------------------------------------------------------
INV_MD="${LIC}/INVENTORY.md"
{
  printf '%s\n\n' "# 반입물 제3자 라이선스 인벤토리"
  printf '%s\n' "> 자동 생성 — 직접 편집하지 마라. 값을 고치려면 \`licenses/manual/OVERRIDES.tsv\` 에"
  printf '%s\n' "> 적고 \`scripts/package/70-generate-notices.sh\` 를 다시 돌린다."
  printf '>\n'
  printf '> 생성 시각: %s / 소스 커밋: %s\n\n' "${NOW}" "${GITSHA}"
  printf '%s\n\n' "## 고지 출처(notice_source) 읽는 법"
  printf '%s\n' "| 값 | 뜻 |"
  printf '%s\n' "|---|---|"
  printf '%s\n' "| ARCHIVE | 반입물 <안에> 들어 있던 고지 파일을 그대로 복사했다. 전문이 매체에 있다. |"
  printf '%s\n' "| METADATA | 라이선스 <이름>만 메타데이터(POM·wheel METADATA·package.json)에서 읽었다. 전문은 없다. |"
  printf '%s\n' "| MANUAL | 사람이 \`licenses/manual/\` 에 채워 넣었다. |"
  printf '%s\n\n' "| NONE | 어느 축에서도 얻지 못했다. |"
  printf '%s\n\n' "## 합계"
  printf '%s\n' "| 항목 | 건수 |"
  printf '%s\n' "|---|---|"
  printf '| 구성요소 전체 | %s |\n' "${_tot}"
  printf '| OK (전문 동봉) | %s |\n' "${_ok}"
  printf '| TEXT_MISSING (이름만 확인, 전문 없음) | %s |\n' "${_txtmiss}"
  printf '| UNRESOLVED (미확인) | %s |\n' "${_unres}"
} > "${INV_MD}"

# 그룹별 표. 값에 파이프가 섞이면 표가 깨지므로 치환한다.
while IFS= read -r grp; do
  [[ -n "${grp}" ]] || continue
  {
    printf '\n## %s\n\n' "${grp}"
    printf '%s\n' "| 구성요소 | 버전 | 라이선스 | 고지출처 | 상태 |"
    printf '%s\n' "|---|---|---|---|---|"
  } >> "${INV_MD}"
  awk -F'\t' -v g="${grp}" 'NR>1 && $1==g {
        for (i=2;i<=6;i++) gsub(/\|/, "/", $i)
        printf "| %s | %s | %s | %s | %s |\n", $2, $3, $4, $5, $6
      }' "${ALL_TSV}" >> "${INV_MD}"
done < <(awk -F'\t' 'NR>1 {print $1}' "${ALL_TSV}" | sort -u)

ok "[notices] INVENTORY 생성: ${INV_MD} (전체 ${_tot} / OK ${_ok} / 전문없음 ${_txtmiss} / 미확인 ${_unres})"

# ---- 4) UNRESOLVED.md ---------------------------------------------------------
UNRES_MD="${LIC}/UNRESOLVED.md"
{
  printf '%s\n\n' "# 사람이 채워야 하는 라이선스 항목"
  printf '%s\n' "> 자동 생성. 여기 남은 항목은 <자동 수집으로는 못 얻는 것>이다."
  printf '%s\n' "> 매체 반출 전에 \`licenses/manual/OVERRIDES.tsv\` 에 적고 필요한 전문을"
  printf '%s\n' "> \`licenses/manual/texts/\` 에 넣은 뒤 이 스크립트를 다시 돌려 비워야 한다."
  printf '>\n'
  printf '> 생성 시각: %s\n\n' "${NOW}"
  printf '%s\n\n' "## TEXT_MISSING — 라이선스 이름은 아는데 전문이 매체에 없다"
  printf '%s\n' "MIT·BSD 는 <저작권 고지 원문>을 함께 배포하도록 요구하고, Apache-2.0 §4(a) 는"
  printf '%s\n\n' "라이선스 사본 제공을 요구한다. 이름만으로는 그 의무가 충족되지 않는다."
} > "${UNRES_MD}"

if [[ "${_txtmiss}" -eq 0 ]]; then
  printf '%s\n' "(없음)" >> "${UNRES_MD}"
else
  {
    printf '%s\n' "| 그룹 | 구성요소 | 버전 | 라이선스 이름 |"
    printf '%s\n' "|---|---|---|---|"
  } >> "${UNRES_MD}"
  awk -F'\t' 'NR>1 && $6=="TEXT_MISSING" {
        for (i=1;i<=4;i++) gsub(/\|/, "/", $i)
        printf "| %s | %s | %s | %s |\n", $1, $2, $3, $4
      }' "${ALL_TSV}" >> "${UNRES_MD}"
fi

{
  printf '\n%s\n\n' "## UNRESOLVED — 라이선스를 아예 확인하지 못했다"
  printf '%s\n\n' "이 항목이 하나라도 남은 채로 매체를 반출하면 안 된다."
} >> "${UNRES_MD}"

if [[ "${_unres}" -eq 0 ]]; then
  printf '%s\n' "(없음)" >> "${UNRES_MD}"
else
  {
    printf '%s\n' "| 그룹 | 구성요소 | 버전 |"
    printf '%s\n' "|---|---|---|"
  } >> "${UNRES_MD}"
  awk -F'\t' 'NR>1 && $6=="UNRESOLVED" {
        for (i=1;i<=3;i++) gsub(/\|/, "/", $i)
        printf "| %s | %s | %s |\n", $1, $2, $3
      }' "${ALL_TSV}" >> "${UNRES_MD}"
fi

# ---- 4-b) 카피레프트 요약 — 대응 소스 의무가 걸릴 수 있는 것만 따로 뽑는다 -----
#   ★ 패턴에 "General Public" 를 <통째로> 넣는다. 'GPL'·'Lesser' 만 찾으면
#     "GNU Library General Public License v2.1 or later"(hibernate 표기)를 놓친다.
#     실제로 그 표기를 놓쳐 hibernate 2건이 목록에서 빠지는 것을 밟았다(2026-08-30).
{
  printf '\n%s\n\n' "## 카피레프트(대응 소스 의무 가능) 목록"
  printf '%s\n' "아래는 라이선스 이름에 GPL/LGPL/MPL/CDDL 계열 문자열이 보이는 항목이다."
  printf '%s\n' "\`licenses/manual/LGPL-SOURCE-OFFER.md\` 의 대응 소스 안내와 <반드시> 대조하라."
  printf '%s\n\n' "(EPL·EDL 과 <선택적 이중 라이선스>인 항목은 다른 쪽을 택하면 의무가 없다 — 항목별로 판단할 것.)"
  printf '%s\n' "| 그룹 | 구성요소 | 버전 | 라이선스 |"
  printf '%s\n' "|---|---|---|---|"
} >> "${UNRES_MD}"
_cl="$(awk -F'\t' 'NR>1 && $4 ~ /General Public|GPL|Lesser|Mozilla|MPL|CDDL|Common Development/' "${ALL_TSV}" | wc -l | tr -d ' ')"
if [[ "${_cl}" -eq 0 ]]; then
  printf '%s\n' "| (없음) | | | |" >> "${UNRES_MD}"
else
  awk -F'\t' 'NR>1 && $4 ~ /General Public|GPL|Lesser|Mozilla|MPL|CDDL|Common Development/ {
        for (i=1;i<=4;i++) gsub(/\|/, "/", $i)
        printf "| %s | %s | %s | %s |\n", $1, $2, $3, $4
      }' "${ALL_TSV}" >> "${UNRES_MD}"
fi

ok "[notices] UNRESOLVED 생성: ${UNRES_MD} (전문없음 ${_txtmiss} / 미확인 ${_unres} / 카피레프트 ${_cl})"

# ---- 5) 체크섬 + 종합 판정 -----------------------------------------------------
sha256_write "${LIC}"

if [[ "${_unres}" -gt 0 ]]; then
  warn "★[notices] 라이선스 미확인 ${_unres} 건이 남아 있습니다 — 이대로 매체를 반출하지 마세요."
  warn "  목록: ${UNRES_MD}"
fi
if [[ "${_txtmiss}" -gt 0 ]]; then
  warn "[notices] 라이선스 전문이 없는 항목 ${_txtmiss} 건 — ${UNRES_MD} 를 확인하세요."
fi
if [[ "${_cl}" -gt 0 ]]; then
  warn "[notices] 카피레프트 계열 ${_cl} 건 — licenses/manual/LGPL-SOURCE-OFFER.md 를 반입물에 포함했는지 확인하세요."
fi
ok "[notices] 완료: ${LIC}"
