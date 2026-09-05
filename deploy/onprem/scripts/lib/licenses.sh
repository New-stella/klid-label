# shellcheck shell=bash
# ============================================================================
# licenses.sh — 반입물 라이선스 고지 수집 공용 함수
#
#   ★ 왜 있나: 매체(deploy/onprem/)에 담아 고객에게 넘기는 순간 그것은 <재배포>다.
#     Apache-2.0 §4(d) 는 NOTICE 전파를, BSD/MIT 는 저작권 고지 동봉을, (L)GPL 은
#     라이선스 전문 + 대응 소스 제공을 요구한다. 이 저장소에는 2026-08-30 이전까지
#     NOTICE/THIRD-PARTY 류 집합 파일이 <한 건도 없었다>.
#
#   ★ 왜 자동인가: 고지 목록을 손으로 적으면 반드시 낡는다. 의존성은 lock 이 바뀔 때마다
#     움직이는데 문서는 안 움직인다. 그래서 <수집·빌드 시점에 실제 반입물에서 긁는다>.
#     각 수집 스크립트가 자기 몫을 licenses/ 아래에 떨구고, 70-generate-notices.sh 가
#     그것을 모아 NOTICE·INVENTORY 를 만든다.
#
#   ★ 자동/수동 경계 (이 파일이 강제하는 것):
#       자동  — 반입물 안에 고지 파일이 <있는> 경우: 그대로 복사한다(요약·발췌 금지).
#               메타데이터(POM <licenses> / wheel METADATA / jar MANIFEST)에서 읽히는
#               라이선스 <이름>: 인벤토리에 기록한다.
#       수동  — 위 둘 다 없는 구성요소: UNRESOLVED 에 남기고 <사람이>
#               licenses/manual/OVERRIDES.tsv 에 적는다. 추정해서 채우지 않는다.
#
#   ⚠ 이 파일의 함수는 <값을 지어내지 않는다>. 못 찾으면 못 찾았다고 기록한다.
#     라이선스는 틀린 값보다 빈칸이 안전하다(틀린 고지는 그 자체로 위반이다).
# ============================================================================

# ---- 경로 ------------------------------------------------------------------
# lic_root — 고지 산출물 루트. 수집 스크립트가 공통으로 쓴다.
lic_root() { printf '%s\n' "$(onprem_root)/licenses"; }

# lic_slug <문자열> — 파일/디렉터리 이름으로 쓸 수 있게 정규화.
#   경로 구분자·상위참조를 없애 디렉터리 탈출(CWE-22)을 막는다. 입력은 아카이브 내부
#   파일명이라 우리가 통제하지 않는 값이다.
lic_slug() {
  printf '%s\n' "$1" | tr '/\\:' '___' | sed -e 's/[^A-Za-z0-9._+-]/_/g' -e 's/^[._-]*//' | cut -c1-120
}

# lic_reset_area <상대경로...> — 해당 영역을 비우고 다시 만든다(재수집 멱등).
#   ★ 반드시 licenses/ 하위만 지운다. 인자를 그대로 rm -rf 에 넘기지 않는다.
lic_reset_area() {
  local root; root="$(lic_root)"
  local rel
  for rel in "$@"; do
    case "${rel}" in
      ''|*..*|/*) die "[licenses] 잘못된 영역 이름: ${rel}" ;;
    esac
    rm -rf "${root:?}/${rel}"
    mkdir -p "${root}/${rel}"
  done
}

# ---- 인벤토리(TSV) ---------------------------------------------------------
#   컬럼: 구분 \t 구성요소 \t 버전 \t 라이선스(있으면) \t 고지출처 \t 상태
#     고지출처 : ARCHIVE(반입물 안 고지파일) | METADATA(POM/METADATA/MANIFEST) |
#                MANUAL(사람이 OVERRIDES.tsv 에 적음) | NONE
#     상태     : OK | UNRESOLVED
LIC_TSV_HEADER=$'group\tcomponent\tversion\tlicense\tnotice_source\tstatus'

# lic_inventory_init <tsv경로>
lic_inventory_init() {
  local f="$1"
  mkdir -p "$(dirname "${f}")"
  printf '%s\n' "${LIC_TSV_HEADER}" > "${f}"
}

# lic_inventory_add <tsv경로> <group> <component> <version> <license> <notice_source> <status>
#   ★ 탭·개행을 값에서 제거한다 — TSV 가 깨지면 인벤토리 자체가 못 읽히는 파일이 된다.
#     동시에 로그 인젝션(CWE-117) 표면도 함께 닫힌다(값의 출처가 외부 아카이브다).
lic_inventory_add() {
  local f="$1"; shift
  local out="" v
  for v in "$@"; do
    v="$(printf '%s' "${v}" | tr -d '\t\r\n' | sed 's/[[:cntrl:]]//g')"
    out="${out}${v}"$'\t'
  done
  printf '%s\n' "${out%$'\t'}" >> "${f}"
}

# ---- JAR (backend) ---------------------------------------------------------
# lic_jar_copy_notices <jar> <outdir> — META-INF 의 고지 파일을 그대로 꺼낸다.
#   찾은 파일 개수를 stdout 으로 출력한다(0 이면 고지 파일 없음).
#   ★ 요약하지 않고 <전문 그대로> 복사한다. NOTICE 는 요약하면 §4(d) 를 못 채운다.
lic_jar_copy_notices() {
  local jar="$1" outdir="$2" n=0 entry
  while IFS= read -r entry; do
    [[ -n "${entry}" ]] || continue
    # 디렉터리 엔트리 제외
    [[ "${entry}" == */ ]] && continue
    mkdir -p "${outdir}"
    if unzip -p "${jar}" "${entry}" > "${outdir}/$(lic_slug "${entry#META-INF/}")" 2>/dev/null; then
      n=$((n+1))
    fi
  done < <(unzip -Z1 "${jar}" 2>/dev/null \
            | grep -iE '^META-INF/(NOTICE|LICENSE|LICENCE|COPYING|DEPENDENCIES)([.-][A-Za-z0-9._-]+)?(\.txt|\.md)?$' \
            || true)
  printf '%s\n' "${n}"
}

# lic_jar_manifest_license <jar> — MANIFEST.MF 의 Bundle-License 힌트(없으면 빈 문자열).
lic_jar_manifest_license() {
  unzip -p "$1" META-INF/MANIFEST.MF 2>/dev/null \
    | tr -d '\r' \
    | awk -F': ' 'tolower($1)=="bundle-license"{print $2; exit}' \
    | cut -c1-200
}

# ---- WHEEL (ai-server) -----------------------------------------------------
# lic_wheel_copy_notices <whl|sdist> <outdir> — dist-info 안의 고지 파일을 꺼낸다.
#   PEP 639 이후 wheel 은 `*.dist-info/licenses/**` 에 담고, 그 이전은 dist-info 바로
#   아래에 LICENSE 류를 둔다. 둘 다 훑는다(한쪽만 보면 최신/구형 중 하나를 통째로 놓친다).
lic_wheel_copy_notices() {
  local whl="$1" outdir="$2" n=0 entry
  case "${whl}" in *.whl) ;; *) printf '0\n'; return 0 ;; esac
  while IFS= read -r entry; do
    [[ -n "${entry}" ]] || continue
    [[ "${entry}" == */ ]] && continue
    mkdir -p "${outdir}"
    if unzip -p "${whl}" "${entry}" > "${outdir}/$(lic_slug "${entry##*.dist-info/}")" 2>/dev/null; then
      n=$((n+1))
    fi
  done < <(unzip -Z1 "${whl}" 2>/dev/null \
            | grep -iE '\.dist-info/(licenses/.+|(LICENSE|LICENCE|COPYING|NOTICE|AUTHORS)([.-][A-Za-z0-9._-]+)?(\.txt|\.md|\.rst)?)$' \
            || true)
  printf '%s\n' "${n}"
}

# lic_wheel_metadata_license <whl> — METADATA 에서 라이선스 표기를 뽑는다.
#   우선순위: License-Expression(PEP 639) → License: → Classifier: License :: …
#   ★ 여러 축을 순서대로 보는 이유: 최근 패키지는 License-Expression 만 두고 License 를
#     비우며, 오래된 패키지는 Classifier 에만 적는다. 한 축만 보면 조용히 빈칸이 된다.
lic_wheel_metadata_license() {
  local whl="$1" meta
  case "${whl}" in *.whl) ;; *) return 0 ;; esac
  meta="$(unzip -p "${whl}" '*.dist-info/METADATA' 2>/dev/null | tr -d '\r' | head -400)" || return 0
  local v
  v="$(printf '%s\n' "${meta}" | awk -F': ' 'tolower($1)=="license-expression"{print $2; exit}')"
  [[ -z "${v}" ]] && v="$(printf '%s\n' "${meta}" | awk -F': ' 'tolower($1)=="license" && $2!~/^[[:space:]]*$/{print $2; exit}')"
  [[ -z "${v}" ]] && v="$(printf '%s\n' "${meta}" \
      | sed -n 's/^Classifier: License :: *//p' | sed 's/^OSI Approved :: *//' \
      | paste -sd'; ' - )"
  printf '%s\n' "${v}" | cut -c1-200
}

# ---- 아카이브 해제(zstd) ---------------------------------------------------
# lic_extract_tar_zst <archive> <destdir> <내부경로...> — .tar.zst 부분 추출.
#   구현이 셋으로 갈리는 것을 흡수한다: bsdtar(자동감지) / GNU tar --zstd / zstd 파이프.
#   ★ 하나만 쓰면 빌드머신 종류에 따라 조용히 실패한다(mac 은 bsdtar, el8 은 GNU tar).
lic_extract_tar_zst() {
  local archive="$1" dest="$2"; shift 2
  mkdir -p "${dest}"
  if tar -xf "${archive}" -C "${dest}" "$@" 2>/dev/null; then return 0; fi
  if tar --zstd -xf "${archive}" -C "${dest}" "$@" 2>/dev/null; then return 0; fi
  if command -v zstd >/dev/null 2>&1 \
     && zstd -dc "${archive}" 2>/dev/null | tar -xf - -C "${dest}" "$@" 2>/dev/null; then return 0; fi
  return 1
}
