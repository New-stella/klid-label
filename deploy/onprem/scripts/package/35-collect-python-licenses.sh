#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 35-collect-python-licenses.sh — [빌드머신] ai-server 반입물의 제3자 라이선스 고지 스테이징
# @step 인터넷=불필요 | 소요=약 3초 | 선행=30 | 재실행=안전(스테이징만 다시 만든다)
#
#   ★★ 왜 별도 스크립트인가 (2026-08-30 신설):
#     이 로직은 원래 30-collect-ai-server.sh 안에 <붙어> 있었다. 그런데 이 단계가 읽는 것은
#     이미 디스크에 있는 반입물(vendor/wheels · vendor/sam2 · models/*)뿐이고, 수집(다운로드)
#     결과에 <의존하지 않는다>. 그런데도 다시 돌리려면 30 을 통째로 — 즉 수 GB 재다운로드를 —
#     해야 했다. 그래서 휠을 교체한 뒤 스테이징만 낡은 상태가 <실제로 발생했다>:
#       반입물 setuptools-81.0.0 / packaging-26.2
#       고지   setuptools 84.0.0 / packaging 26.3   ← 둘 다 반입물에 없음
#     70-generate-notices.sh 는 <스테이징만> 읽으므로 70 을 다시 돌려도 고쳐지지 않았다.
#   → 재다운로드 없이 <스테이징만> 다시 만들 수 있게 떼어냈다. 30 은 이 스크립트를 호출한다.
#
#   ★ 이 스크립트는 아무것도 내려받지 않는다. 네트워크가 없어도 돈다.
#
#   입력(이미 반입된 것):
#     vendor/wheels/*.whl|*.tar.gz   pip 의존성
#     vendor/sam2/sam2-src/          sam2 소스 vendor
#     models/weights/yolox_s.onnx    YOLOX 가중치
#     models/hf-cache/               SAM2 HF 모델 캐시(있을 때만)
#   산출:
#     licenses/python-packages/                고지 전문 + INVENTORY.tsv
#     licenses/python-packages/SOURCES.txt     이 스테이징이 읽은 <실물 파일명 전수>
#                                              (70 이 vendor/wheels 와 대조하는 기준)
#
#   사용법:
#     bash scripts/package/35-collect-python-licenses.sh
#     bash scripts/package/70-generate-notices.sh     # 반드시 뒤이어 실행
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"
# shellcheck source=../lib/licenses.sh
source "${SELF_DIR}/../lib/licenses.sh"

REPO="$(repo_root)"
ONPREM="$(onprem_root)"
AI_SRC="${REPO}/ai-server"
WHEELS="${ONPREM}/vendor/wheels"
SAM2_OUT="${ONPREM}/vendor/sam2"
WEIGHTS_OUT="${ONPREM}/models/weights"
HF_OUT="${ONPREM}/models/hf-cache"

[[ -d "${WHEELS}" ]] || die "[ai-server] 휠 디렉터리가 없습니다: ${WHEELS}
     → 먼저 scripts/package/30-collect-ai-server.sh 로 반입물을 수집하세요."

# ---- 라이선스 고지 수집 (wheel + sam2 + 가중치) -----------------------------
#   ★ 대상은 <실제로 반입되는 wheel 파일>이다. requirements.txt 를 읽어 목록을 만들면
#     torch 처럼 별도 인덱스에서 받은 것·빌드 백엔드 보강분(setuptools/wheel)이 빠진다.
#   ★ wheel 안 고지 파일은 두 자리에 있다 — PEP 639 이후는 `*.dist-info/licenses/**`,
#     그 이전은 dist-info 바로 아래. 둘 다 훑는다(한쪽만 보면 세대 하나를 통째로 놓친다).
#   ★★ opencv-python 의 `LICENSE-3RD-PARTY.txt` 가 여기서 <반드시> 딸려 나와야 한다.
#     그 파일이 이 반입물의 유일한 FFmpeg(LGPL-2.1)·Qt5(LGPL-3.0) 고지다.
#     ⚠ 그 두 건은 고지만으로 끝나지 않는다 — 대응 소스 제공 의무가 남는다.
#       licenses/manual/LGPL-SOURCE-OFFER.md 를 함께 반입할 것.
LIC_PY_PKG="$(lic_root)/python-packages"
if ! command -v unzip >/dev/null 2>&1; then
  warn "[ai-server] unzip 이 없어 wheel 라이선스 고지를 수집하지 못했습니다."
else
  lic_reset_area "python-packages"
  _pinv="${LIC_PY_PKG}/INVENTORY.tsv"
  lic_inventory_init "${_pinv}"
  _pw=0; _ptxt=0; _pnam=0; _punres=0
  # ★ 이 스테이징이 <어떤 실물 파일을 읽어 만들어졌는지>를 남긴다. 70 단계가 이 목록과
  #   vendor/wheels 실물을 대조해, 휠이 바뀌었는데 스테이징이 안 돈 상태를 <기계로> 잡는다
  #   (2026-08-30: setuptools 84.0.0 · packaging 26.3 이 고지에만 남고 실물엔 없던 사고).
  _psrc_list="${LIC_PY_PKG}/SOURCES.txt"
  : > "${_psrc_list}"
  while IFS= read -r _whl; do
    [[ -n "${_whl}" ]] || continue
    _pw=$((_pw+1))
    _wb="$(basename "${_whl}")"
    printf '%s\n' "${_wb}" >> "${_psrc_list}"
    # wheel 파일명 규약: {name}-{version}-{pytag}-...
    _wname="${_wb%%-*}"
    _wver="$(printf '%s' "${_wb#"${_wname}"-}" | cut -d- -f1)"
    _cnt="$(lic_wheel_copy_notices "${_whl}" "${LIC_PY_PKG}/$(lic_slug "${_wname}-${_wver}")")"
    _wlic="$(lic_wheel_metadata_license "${_whl}")"
    if [[ "${_cnt}" -gt 0 ]]; then
      _ptxt=$((_ptxt+1)); _psrc="ARCHIVE"; _pst="OK"
    elif [[ -n "${_wlic}" ]]; then
      _pnam=$((_pnam+1)); _psrc="METADATA"; _pst="TEXT_MISSING"
    else
      _punres=$((_punres+1)); _psrc="NONE"; _pst="UNRESOLVED"
    fi
    lic_inventory_add "${_pinv}" python-packages "${_wname}" "${_wver}" "${_wlic}" "${_psrc}" "${_pst}"
  done < <(find "${WHEELS}" -maxdepth 1 -name '*.whl' -type f | sort)

  # sdist(.tar.gz)도 반입물이다 — 이름/버전만 인벤토리에 남긴다(내부 구조가 제각각이라
  # 고지 파일 위치를 일반화할 수 없다. 전문은 사람이 OVERRIDES.tsv 로 채운다).
  while IFS= read -r _sd; do
    [[ -n "${_sd}" ]] || continue
    printf '%s\n' "$(basename "${_sd}")" >> "${_psrc_list}"
    _sb="$(basename "${_sd}" .tar.gz)"
    lic_inventory_add "${_pinv}" python-packages "${_sb}" "" "" "NONE" "UNRESOLVED"
    _punres=$((_punres+1))
  done < <(find "${WHEELS}" -maxdepth 1 -name '*.tar.gz' -type f | sort)

  # sam2 는 wheel 이 아니라 <소스 디렉터리>로 반입되므로 따로 챙긴다.
  if [[ -d "${SAM2_OUT}/sam2-src" ]]; then
    _n=0
    while IFS= read -r _f; do
      [[ -n "${_f}" ]] || continue
      mkdir -p "${LIC_PY_PKG}/sam2"
      cp -f "${_f}" "${LIC_PY_PKG}/sam2/$(lic_slug "$(basename "${_f}")")" && _n=$((_n+1))
    done < <(find "${SAM2_OUT}/sam2-src" -maxdepth 1 -type f \
               \( -iname 'LICENSE' -o -iname 'LICENSE.*' -o -iname 'NOTICE' -o -iname 'NOTICE.*' \) 2>/dev/null | sort)
    if [[ "${_n}" -gt 0 ]]; then
      lic_inventory_add "${_pinv}" python-packages "sam2 (소스 vendor)" "${SAM2_GIT_REF:-}" "" ARCHIVE OK
    else
      lic_inventory_add "${_pinv}" python-packages "sam2 (소스 vendor)" "${SAM2_GIT_REF:-}" "" NONE UNRESOLVED
      _punres=$((_punres+1))
    fi
  fi

  # YOLOX 가중치 — 바이너리라 안에 고지가 없다. 출처·라이선스는 저장소 기록이 정본이므로
  # 그 기록 파일을 그대로 동봉한다(우리가 문장을 새로 쓰지 않는다).
  if [[ -f "${WEIGHTS_OUT}/yolox_s.onnx" ]]; then
    if [[ -f "${AI_SRC}/weights/README.md" ]]; then
      mkdir -p "${LIC_PY_PKG}/yolox-weights"
      cp -f "${AI_SRC}/weights/README.md" "${LIC_PY_PKG}/yolox-weights/PROVENANCE.md"
      lic_inventory_add "${_pinv}" python-packages "yolox_s.onnx (모델 가중치)" "0.1.1rc0" "Apache-2.0" METADATA TEXT_MISSING
      _pnam=$((_pnam+1))
    else
      lic_inventory_add "${_pinv}" python-packages "yolox_s.onnx (모델 가중치)" "" "" NONE UNRESOLVED
      _punres=$((_punres+1))
    fi
  fi

  # SAM2 HF 모델 캐시 — 받았을 때만 인벤토리에 남긴다(전문은 캐시에 없다).
  if [[ -n "$(ls -A "${HF_OUT}" 2>/dev/null || true)" ]]; then
    lic_inventory_add "${_pinv}" python-packages "${HF_SAM2_MODEL_ID} (HF 모델 캐시)" "" "" NONE UNRESOLVED
    _punres=$((_punres+1))
  fi

  LC_ALL=C sort -o "${_psrc_list}" "${_psrc_list}"
  ok "[ai-server] 라이선스 고지 수집: ${LIC_PY_PKG} (wheel ${_pw} / 전문 ${_ptxt} / 이름만 ${_pnam} / 미해석 ${_punres})"
  if [[ ! -d "${LIC_PY_PKG}" ]] || ! find "${LIC_PY_PKG}" -name 'LICENSE-3RD-PARTY*' -print -quit | grep -q .; then
    warn "[ai-server] ★opencv 의 LICENSE-3RD-PARTY 고지를 찾지 못했습니다."
    warn "  그 파일이 이 반입물의 유일한 FFmpeg(LGPL-2.1)·Qt5(LGPL-3.0) 고지입니다 — 반드시 확인하세요."
  fi
  [[ "${_punres}" -gt 0 ]] && warn "[ai-server] 미해석 ${_punres} 건 — licenses/manual/OVERRIDES.tsv 에 사람이 적어야 합니다."
fi

