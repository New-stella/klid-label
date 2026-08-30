#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 40-collect-runtimes.sh — [빌드머신] 대상 서버 런타임 바이너리 수집
# @step 인터넷=필요 | 소요=약 5초(tarball 보유 시) | 선행=없음 | 재실행=안전(tarball 은 재사용, 고지용 71MB 는 매번 받는다)
#
#   대상 서버(폐쇄망)에 Python 이 없다고 가정하고 번들한다.
#   ★ 웹 서버는 여기서 받지 않는다 — httpd 를 배포판 RPM(syspkgs)으로 설치한다(2026-08-19 전환).
#   ★ 자바 런타임도 여기서 받지 않는다 — 아래 「자바를 반입하지 않는 이유」 참조(2026-08-30 전환).
#   모두 linux x86_64(glibc) 빌드여야 한다(versions.sh 의 URL 참고).
#
#   수집물:
#     runtimes/python/  : python-build-standalone 3.11 (tar.gz)
#     licenses/python-runtime/ : 그 파이썬이 안고 있는 제3자 고지 19종 (아래 참조)
#
#   ---- 자바를 반입하지 않는 이유 (2026-08-30 사용자 확정, 구속) ----
#   배포 형상이 <외부 WAS 에 WAR 반입>으로 확정됐다(@design DEPLOY-001 · RUNBOOK-001).
#   대상 장비에는 관제지원시스템 WAS 와 동일 사양인 Tomcat 10.1.x + Java 17 이 이미 돌고 있고
#   (실측 17.0.19), 백엔드는 그 WAS 가 기동한다. 우리가 JRE 를 한 벌 더 반입하면 실제로 쓰이지
#   않는 자바가 매체·디스크·보안 점검 표면에만 더해진다.
#   ⚠ 구 방식 폐기(2026-08-30) — "runtimes/jdk/ 에 Temurin JRE 17 tar.gz 를 받아 대상 서버의
#     /opt/klid/runtime/jre 로 설치하고 systemd 가 java -jar 로 기동한다".
#   ⚠ ai-server 의 Python 런타임은 <WAR 와 무관한 별도 프로세스>라 그대로 반입한다.
#     자바를 뺀다고 함께 빼지 말 것.
#   ⚠ 소스 재빌드용 JDK17 full(buildtools/jdk, versions.sh 의 JDK17_FULL_*)은 <별개 핀>이다.
#     그쪽도 지금은 반입 대상이 아니지만(빌드 키트 기본 제외) 이유가 다르다 — package.sh 참조.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"
# shellcheck source=../lib/licenses.sh
source "${SELF_DIR}/../lib/licenses.sh"

ONPREM="$(onprem_root)"
PY_OUT="${ONPREM}/runtimes/python"
require_cmd curl
ensure_dir "${PY_OUT}"

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

# 다운로드 직후 공식 체크섬과 대조한다(fail-closed). 미검증(빈 값)이면 강한 warn 후 진행.
info "[runtimes] CPython standalone ${PYTHON_STANDALONE_VERSION}..."
PY_FILE="$(download "${PYTHON_STANDALONE_URL}" "${PY_OUT}")"
verify_file_sha256 "${PY_FILE}" "${PYTHON_STANDALONE_SHA256:-}" "CPython ${PYTHON_STANDALONE_VERSION}"


# ---- 번들 파이썬의 제3자 라이선스 고지 -------------------------------------
#   ★★ 우리가 반입하는 install_only tarball 에는 <licenses/ 가 없다>(실측). 그래서 이 매체는
#     2026-08-30 이전까지 OpenSSL·Berkeley DB·ncurses·libedit·tcl 등 <파이썬 안에 정적으로
#     들어 있는 제3자 고지를 한 건도 담지 않은 채> 나가고 있었다. 파이썬 바이너리를 매체에
#     실어 넘기는 것은 그 라이브러리들의 재배포이므로 고지가 반드시 따라가야 한다.
#   ★ 같은 릴리스의 full 아카이브에만 python/licenses/*.txt 가 있다. 71MB 를 받아 168KB 를
#     꺼내고 <아카이브는 버린다> — 반입물은 여전히 install_only 다(형상 변경 아님).
#   ★ 수동으로 한 번 복사해 두지 않는 이유: PYTHON_STANDALONE_* 핀을 올리는 순간 그 사본이
#     조용히 낡는다. 고지가 낡는 것은 고지가 없는 것보다 나쁘다(틀린 사실을 주장한다).
LIC_PY_DIR="$(lic_root)/python-runtime"
info "[runtimes] 번들 파이썬 제3자 라이선스 고지 수집..."
if [[ -z "${PYTHON_STANDALONE_FULL_URL:-}" ]]; then
  warn "[runtimes] PYTHON_STANDALONE_FULL_URL 미설정 — 파이썬 제3자 고지를 수집하지 못했습니다."
else
  _lic_tmp="$(mktemp -d)"
  # shellcheck disable=SC2064  # 현재 값으로 고정해 트랩을 건다(의도).
  trap "rm -rf '${_lic_tmp}'" EXIT
  _full="${_lic_tmp}/cpython-full.tar.zst"
  if curl -fL --retry 3 --proto '=https' -o "${_full}" "${PYTHON_STANDALONE_FULL_URL}"; then
    verify_file_sha256 "${_full}" "${PYTHON_STANDALONE_FULL_SHA256:-}" "CPython ${PYTHON_STANDALONE_VERSION} full(고지 추출용)"
    if lic_extract_tar_zst "${_full}" "${_lic_tmp}/x" 'python/licenses'; then
      lic_reset_area "python-runtime"
      cp -f "${_lic_tmp}/x/python/licenses/"* "${LIC_PY_DIR}/" 2>/dev/null || true
      _lic_n="$(find "${LIC_PY_DIR}" -type f -name '*.txt' | wc -l | tr -d ' ')"
      if [[ "${_lic_n}" -gt 0 ]]; then
        # 인벤토리 — 파일명 LICENSE.<component>.txt 에서 구성요소 이름을 얻는다.
        _inv="${LIC_PY_DIR}/INVENTORY.tsv"
        lic_inventory_init "${_inv}"
        while IFS= read -r _f; do
          _c="$(basename "${_f}")"; _c="${_c#LICENSE.}"; _c="${_c%.txt}"
          lic_inventory_add "${_inv}" python-runtime "${_c}" "" "" ARCHIVE OK
        done < <(find "${LIC_PY_DIR}" -type f -name 'LICENSE.*.txt' | sort)
        ok "[runtimes] 파이썬 제3자 고지 ${_lic_n} 종 수집: ${LIC_PY_DIR}"
      else
        warn "[runtimes] full 아카이브에서 고지 파일을 찾지 못했습니다(경로 변경 의심)."
      fi
    else
      warn "[runtimes] full 아카이브 해제 실패 — zstd 를 읽을 수 있는 tar 또는 zstd 명령이 필요합니다."
      warn "  설치 예: dnf install -y zstd   /   brew install zstd"
    fi
  else
    warn "[runtimes] full 아카이브 다운로드 실패 — 파이썬 제3자 고지를 수집하지 못했습니다."
    warn "  ${PYTHON_STANDALONE_FULL_URL}"
  fi
  rm -rf "${_lic_tmp}"; trap - EXIT
fi

# 자바 런타임(runtimes/jdk)은 수집하지 않는다 — 위 헤더 「자바를 반입하지 않는 이유」 참조.
# 이전 판으로 만든 패키지에 남아 있을 수 있어 안내만 남긴다(자동 삭제하지 않는다 — 오래된
# 매체를 그대로 재사용하는 현장에서 우리가 지울 판단 근거가 없다).
#
# ★ 판정은 <실제 tarball 이 있는가>로 한다 (2026-08-30 수정).
#   ⚠ 구 판정 폐기 — `ls -A` 로 "디렉터리가 비어 있지 않으면 경고". 이 디렉터리에는 git 이
#     빈 디렉터리를 추적하려고 둔 .gitkeep 이 <항상> 있어, JRE 가 한 건도 없는 정상 매체에서
#     경고가 매번 떴다. 늘 뜨는 경고는 읽히지 않게 되어 진짜 잔재를 가린다.
_jdk_leftover=0
if [[ -d "${ONPREM}/runtimes/jdk" ]]; then
  _jdk_leftover="$(find "${ONPREM}/runtimes/jdk" -type f \
    \( -name '*.tar.gz' -o -name '*.tgz' -o -name '*.tar.zst' -o -name '*.tar.xz' -o -name '*.zip' \) \
    2>/dev/null | wc -l | tr -d ' ')"
fi
if [[ "${_jdk_leftover}" != "0" ]]; then
  warn "[runtimes] runtimes/jdk 에 이전 판의 JRE tarball ${_jdk_leftover}개가 남아 있습니다 — WAR 반입 형상에서는 쓰이지 않습니다."
  warn "           매체 용량을 줄이려면 반출 전에 수동으로 비우세요: rm -f ${ONPREM}/runtimes/jdk/*.tar.*"
fi
unset _jdk_leftover
