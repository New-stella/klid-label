#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 65-collect-copyleft-sources.sh — [빌드머신] 카피레프트 "대응 소스" 수집
# @step 인터넷=필요 | 소요=약 5초(소스 보유 시) | 선행=30·50 | 재실행=안전(받은 소스는 건너뜀)
#
#   (L)GPL 은 바이너리를 재배포할 때 <그에 대응하는 소스>를 함께 주거나, 3년간 유효한
#   서면 제공 확약을 붙이도록 요구한다(LGPL-2.1 §6 / LGPL-3.0 §4 → GPLv3 §6).
#   이 매체는 폐쇄망으로 반입되므로 "고객이 인터넷에서 받으면 된다"가 성립하지 않는다.
#   그래서 <비용이 감당되는 것은 실어서 보내고>, 감당 안 되는 것만 서면 확약으로 남긴다.
#
#   ★ 무엇을 싣고 무엇을 안 싣는지의 판단 근거는 licenses/manual/LGPL-SOURCE-OFFER.md 에 있다.
#     이 스크립트는 그 판단의 <집행부>일 뿐이고, 목록은 licenses/manual/LGPL-SOURCES.tsv 다.
#
#   ⚠ ffmpeg RPM(RPM Fusion, GPLv3+)의 SRPM 은 여기서 받지 않는다 —
#     50-collect-syspkgs.sh 가 이미 syspkgs/ffmpeg-src/ 로 받는다(기존 경로 유지).
#     여기 ffmpeg 항목은 <그것과 다른 물건>이다: opencv-python wheel 안에 번들된
#     LGPL 빌드 FFmpeg 의 소스다. 둘을 같은 것으로 보고 하나를 지우지 말 것.
#
#   ⚠ 실패해도 die 하지 않는다. 소스를 못 실으면 서면 확약이 그 자리를 대신하며,
#     그 사실은 70-generate-notices.sh 가 UNRESOLVED/카피레프트 목록으로 드러낸다.
#
#   토글: SKIP_COPYLEFT_SRC=1 로 생략(매체 용량이 문제일 때. 서면 확약은 그대로 유효).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/licenses.sh
source "${SELF_DIR}/../lib/licenses.sh"

LIC="$(lic_root)"
SRC_OUT="${LIC}/copyleft-sources"
MANIFEST="${LIC}/manual/LGPL-SOURCES.tsv"

if [[ "${SKIP_COPYLEFT_SRC:-0}" == "1" ]]; then
  warn "[copyleft] SKIP_COPYLEFT_SRC=1 — 대응 소스를 매체에 담지 않습니다."
  warn "  licenses/manual/LGPL-SOURCE-OFFER.md(서면 제공 확약)가 반드시 반입물에 포함되어야 합니다."
  exit 0
fi

if [[ ! -f "${MANIFEST}" ]]; then
  warn "[copyleft] 목록 파일이 없습니다: ${MANIFEST} — 대응 소스를 한 건도 싣지 않습니다."
  exit 0
fi

require_cmd curl
ensure_dir "${SRC_OUT}"

_n=0; _fail=0
while IFS=$'\t' read -r comp ver url sha note; do
  # 주석·빈 줄·컬럼 부족 행은 건너뛴다.
  case "${comp}" in ''|\#*) continue ;; esac
  [[ -n "${url}" ]] || continue
  case "${url}" in https://*) ;; *) warn "[copyleft] https 아닌 URL 무시: ${comp} ${url}"; continue ;; esac

  _fname="$(basename "${url%%\?*}")"
  _dst="${SRC_OUT}/$(lic_slug "${_fname}")"
  if [[ -s "${_dst}" ]]; then
    info "[copyleft] 이미 존재(생략): ${_dst}"
  else
    info "[copyleft] ${comp} ${ver} 대응 소스 다운로드..."
    if curl -fL --retry 3 --proto '=https' -o "${_dst}.part" "${url}"; then
      mv "${_dst}.part" "${_dst}"
    else
      rm -f "${_dst}.part"
      warn "[copyleft] 다운로드 실패: ${comp} ${ver} — ${url}"
      _fail=$((_fail+1)); continue
    fi
  fi
  # 체크섬은 fail-closed 로 본다(verify_file_sha256 이 불일치 시 die). 빈 값이면 강한 warn.
  verify_file_sha256 "${_dst}" "${sha}" "${comp} ${ver} 대응 소스"
  _n=$((_n+1))

  # 무엇의 소스인지 파일 옆에 남긴다 — 파일명만으로는 "왜 여기 있는지"를 알 수 없다.
  printf '%s\t%s\t%s\t%s\n' "${comp}" "${ver}" "${url}" "${note:-}" >> "${SRC_OUT}/MANIFEST.tsv"
done < "${MANIFEST}"

# ---- 대응성 검증: opencv wheel 의 FFmpeg 바이너리 ↔ 받아 둔 FFmpeg 소스 --------
#   ★★ LGPL 이 요구하는 것은 "소스"가 아니라 <그 바이너리에 대응하는 소스>다.
#     판이 어긋나면 그것은 이행이 아니라 <틀린 주장>이며, 고지가 아예 없는 것보다 나쁘다.
#     그래서 여기서 <기계로> 대조한다 — 사람이 눈으로 보면 반드시 언젠가 어긋난다.
#
#   대조 방법: wheel 안 `opencv_python.libs/libav*.so.<MAJOR>.<MINOR>.<MICRO>` 의 soname 버전과
#             소스 tarball 의 `lib*/version.h`(+`version_major.h`) 선언값을 4개 라이브러리 전부 비교.
#             ⚠ MAJOR 는 avcodec/avformat/swscale 의 경우 version.h 가 아니라 version_major.h 에
#               있다. version.h 만 읽으면 MAJOR 가 빈 값이 되어 <항상 불일치>로 오판한다(실측).
#
#   판정: 불일치 → die. 대조 불가(wheel/소스 부재, tar 실패) → warn 후 진행.
_ff_src="$(find "${SRC_OUT}" -maxdepth 1 -name 'ffmpeg-*.tar.xz' -type f 2>/dev/null | head -1)"
_cv_whl="$(find "$(onprem_root)/vendor/wheels" -maxdepth 1 -name 'opencv_python-*.whl' -type f 2>/dev/null | head -1)"
if [[ -n "${_ff_src}" && -n "${_cv_whl}" ]] && command -v unzip >/dev/null 2>&1; then
  info "[copyleft] FFmpeg 대응성 검증: $(basename "${_cv_whl}") ↔ $(basename "${_ff_src}")"
  _ffdir="$(mktemp -d)"
  _ffbase="$(basename "${_ff_src}" .tar.xz)"
  if tar -xf "${_ff_src}" -C "${_ffdir}" \
        "${_ffbase}/libavcodec/version.h"  "${_ffbase}/libavcodec/version_major.h" \
        "${_ffbase}/libavformat/version.h" "${_ffbase}/libavformat/version_major.h" \
        "${_ffbase}/libavutil/version.h"   "${_ffbase}/libavutil/version_major.h" \
        "${_ffbase}/libswscale/version.h"  "${_ffbase}/libswscale/version_major.h" 2>/dev/null; then
    _mismatch=0; _checked=0
    for _l in avcodec avformat avutil swscale; do
      _U="$(printf '%s' "${_l}" | tr '[:lower:]' '[:upper:]')"
      _vh="${_ffdir}/${_ffbase}/lib${_l}/version.h"
      _vm="${_ffdir}/${_ffbase}/lib${_l}/version_major.h"
      _maj="$(cat "${_vm}" "${_vh}" 2>/dev/null | awk -v k="LIB${_U}_VERSION_MAJOR" '$1=="#define" && $2==k {print $3; exit}')"
      _min="$(awk -v k="LIB${_U}_VERSION_MINOR" '$1=="#define" && $2==k {print $3; exit}' "${_vh}" 2>/dev/null)"
      _mic="$(awk -v k="LIB${_U}_VERSION_MICRO" '$1=="#define" && $2==k {print $3; exit}' "${_vh}" 2>/dev/null)"
      _srcver="${_maj}.${_min}.${_mic}"
      _binver="$(unzip -Z1 "${_cv_whl}" 2>/dev/null \
                   | sed -n "s|.*/lib${_l}-[0-9a-f]*\.so\.\([0-9.]*\)$|\1|p" | head -1)"
      if [[ -z "${_binver}" ]]; then
        warn "  lib${_l}: wheel 에서 찾지 못함(번들 구성 변경?) — 이 항목은 대조 생략"
        continue
      fi
      _checked=$((_checked+1))
      if [[ "${_srcver}" == "${_binver}" ]]; then
        ok "  lib${_l}: 바이너리 ${_binver} == 소스 ${_srcver}"
      else
        warn "  lib${_l}: 바이너리 ${_binver} != 소스 ${_srcver}"
        _mismatch=$((_mismatch+1))
      fi
    done
    rm -rf "${_ffdir}"
    if [[ "${_mismatch}" -gt 0 ]]; then
      die "[copyleft] ★FFmpeg 대응 소스의 판이 반입 바이너리와 다릅니다(${_mismatch} 건).
       이대로 반입하면 <대응하지 않는 소스>를 대응 소스라고 주장하게 됩니다(LGPL 위반).
       licenses/manual/LGPL-SOURCES.tsv 의 ffmpeg 행을 바이너리에 맞는 판으로 고치고
       licenses/copyleft-sources/ 의 옛 tarball 을 지운 뒤 다시 실행하세요."
    fi
    if [[ "${_checked}" -eq 0 ]]; then
      warn "[copyleft] FFmpeg 대응성을 한 건도 대조하지 못했습니다 — wheel 번들 구성을 확인하세요."
    else
      ok "[copyleft] FFmpeg 대응성 검증 통과 (${_checked}/4 라이브러리 일치)"
      printf '# 대응성 검증 %s: %s 의 libav*/libsw* soname 과 %s 의 version.h 선언이 %s 건 일치\n' \
        "$(date '+%Y-%m-%d')" "$(basename "${_cv_whl}")" "$(basename "${_ff_src}")" "${_checked}" \
        >> "${SRC_OUT}/MANIFEST.tsv"
    fi
  else
    rm -rf "${_ffdir}"
    warn "[copyleft] FFmpeg 소스에서 version 헤더를 꺼내지 못해 대응성을 검증하지 못했습니다."
  fi
else
  warn "[copyleft] FFmpeg 대응성 검증을 건너뜁니다(소스 또는 opencv wheel 부재)."
  warn "  30-collect-ai-server.sh 를 먼저 돌린 뒤 이 단계를 다시 실행하면 검증됩니다."
fi

# ---- 대응성 검증: opencv wheel 의 Qt5 바이너리 ↔ 받아 둔 qtbase 소스 -----------
#   ★ FFmpeg 와 같은 이유로 기계 대조한다. Qt 는 여기에 더해 <어느 모듈이 번들되는가>가
#     변할 수 있어, qtbase 밖 모듈이 나타나면 그 사실을 드러내야 한다 —
#     qtbase 만 싣고 있는데 wheel 이 qtsvg 를 번들하기 시작하면 그 모듈의 대응 소스가 없다.
#   ★ 소스 판은 `.qmake.conf` 의 MODULE_VERSION 이 선언한다.
_qt_src="$(find "${SRC_OUT}" -maxdepth 1 -name 'qtbase-*.tar.xz' -type f 2>/dev/null | head -1)"
if [[ -n "${_qt_src}" && -n "${_cv_whl:-}" ]] && command -v unzip >/dev/null 2>&1; then
  # wheel 안 Qt 모듈 목록과 soname 버전(예: libQt5Core-xxxx.so.5.15.18 → Core / 5.15.18)
  _qt_mods="$(unzip -Z1 "${_cv_whl}" 2>/dev/null \
                | sed -n 's|.*/libQt5\([A-Za-z0-9]*\)-[0-9a-f]*\.so\.\([0-9.]*\)$|\1 \2|p' | sort -u)"
  if [[ -z "${_qt_mods}" ]]; then
    warn "[copyleft] wheel 에서 Qt5 라이브러리를 찾지 못했습니다 — headless wheel 로 바뀌었는지 확인하세요."
    warn "  그렇다면 qtbase 대응 소스는 더 이상 필요 없습니다(LGPL-SOURCES.tsv 에서 빼도 됩니다)."
  else
    info "[copyleft] Qt5 대응성 검증: $(basename "${_cv_whl}") ↔ $(basename "${_qt_src}")"
    _qtdir="$(mktemp -d)"
    _qtbase="$(basename "${_qt_src}" .tar.xz)"
    # 배포 tarball 의 최상위 디렉터리 이름이 파일명과 다르다(…-opensource-src-… vs …-src-…).
    # 그래서 이름을 가정하지 않고 아카이브가 말하는 첫 경로를 읽는다.
    _qtroot="$(tar -tf "${_qt_src}" 2>/dev/null | head -1 | cut -d/ -f1)"
    [[ -n "${_qtroot}" ]] || _qtroot="${_qtbase}"
    if tar -xf "${_qt_src}" -C "${_qtdir}" "${_qtroot}/.qmake.conf" 2>/dev/null; then
      _qtsrcver="$(awk -F'=' '/^[[:space:]]*MODULE_VERSION[[:space:]]*=/ {gsub(/[[:space:]]/,"",$2); print $2; exit}' \
                     "${_qtdir}/${_qtroot}/.qmake.conf" 2>/dev/null)"
      _qtmis=0; _qtchk=0
      while read -r _mod _binv; do
        [[ -n "${_mod}" ]] || continue
        _qtchk=$((_qtchk+1))
        if [[ "${_binv}" == "${_qtsrcver}" ]]; then
          ok "  Qt5${_mod}: 바이너리 ${_binv} == qtbase 소스 ${_qtsrcver}"
        else
          warn "  Qt5${_mod}: 바이너리 ${_binv} != qtbase 소스 ${_qtsrcver}"
          _qtmis=$((_qtmis+1))
        fi
      done <<< "${_qt_mods}"
      rm -rf "${_qtdir}"
      if [[ "${_qtmis}" -gt 0 ]]; then
        die "[copyleft] ★Qt5 대응 소스의 판이 반입 바이너리와 다릅니다(${_qtmis} 건).
       LGPL-3.0 은 <그 바이너리의> 대응 소스를 요구합니다. LGPL-SOURCES.tsv 의 qtbase 행을
       바이너리 판에 맞추고 licenses/copyleft-sources/ 의 옛 tarball 을 지운 뒤 다시 실행하세요."
      fi
      ok "[copyleft] Qt5 대응성 검증 통과 (모듈 ${_qtchk} 개, 전부 ${_qtsrcver})"
      printf '# 대응성 검증 %s: %s 의 libQt5* soname(%s) 과 %s 의 MODULE_VERSION 이 일치 (모듈 %s 개)\n' \
        "$(date '+%Y-%m-%d')" "$(basename "${_cv_whl}")" "${_qtsrcver}" "$(basename "${_qt_src}")" "${_qtchk}" \
        >> "${SRC_OUT}/MANIFEST.tsv"
      # qtbase 밖 모듈이 섞였는지 알린다(모듈명 화이트리스트 — qtbase 소속만 나열).
      while read -r _mod _binv; do
        case " Core Gui Widgets Test XcbQpa Network Sql Xml Concurrent DBus PrintSupport OpenGL " in
          *" ${_mod} "*) ;;
          *) warn "  ⚠ Qt5${_mod} 은 qtbase 소속이 아닐 수 있습니다 — 그 서브모듈의 대응 소스를 LGPL-SOURCES.tsv 에 추가하세요." ;;
        esac
      done <<< "${_qt_mods}"
    else
      rm -rf "${_qtdir}"
      warn "[copyleft] qtbase 소스에서 .qmake.conf 를 꺼내지 못해 Qt5 대응성을 검증하지 못했습니다."
    fi
  fi
else
  warn "[copyleft] Qt5 대응성 검증을 건너뜁니다(qtbase 소스 또는 opencv wheel 부재)."
fi

# MANIFEST.tsv 중복 제거(재실행 멱등).
if [[ -f "${SRC_OUT}/MANIFEST.tsv" ]]; then
  _t="$(mktemp)"; sort -u "${SRC_OUT}/MANIFEST.tsv" > "${_t}" && mv "${_t}" "${SRC_OUT}/MANIFEST.tsv"
fi

if [[ "${_n}" -gt 0 ]]; then
  sha256_write "${SRC_OUT}"
  ok "[copyleft] 대응 소스 ${_n} 건 수집: ${SRC_OUT} ($(du -sh "${SRC_OUT}" 2>/dev/null | cut -f1))"
else
  warn "[copyleft] 대응 소스를 한 건도 싣지 못했습니다."
fi
[[ "${_fail}" -gt 0 ]] && warn "[copyleft] 실패 ${_fail} 건 — 서면 제공 확약(LGPL-SOURCE-OFFER.md)이 그 자리를 대신합니다."
exit 0
