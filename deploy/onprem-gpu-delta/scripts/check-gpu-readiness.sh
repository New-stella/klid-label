#!/usr/bin/env bash
set -uo pipefail
# ============================================================================
# check-gpu-readiness.sh — [대상 서버 B / ai] GPU 로 돌 준비가 됐는지 <읽기만> 한다
#
#   델타 매체가 없어도 돈다. 이 스크립트는 **아무것도 바꾸지 않는다** — 지금 상태를 읽고
#   「무엇을 해야 GPU 로 도는가」를 한 줄로 판정한다.
#
#   왜 필요한가:
#     GPU 전환의 실패는 <조용하다>. 드라이버가 없거나 CPU 판 휠이 그대로여도
#     ai-server 는 정상 기동하고 헬스체크도 통과하며, 그 사실은 추론이 느려질 때에야
#     드러난다. 사람이 눈으로 확인할 자리를 만들어 두는 것이 이 스크립트의 목적이다.
#
#   사용법:
#     ./check-gpu-readiness.sh              # 판정만
#     AI_DIR=/opt/klid/ai ./check-gpu-readiness.sh
#
#   종료코드:  0 = GPU 로 돌고 있다   1 = 조치 필요(무엇인지 화면에 적는다)   2 = 확인 불가
# ============================================================================

AI_DIR="${AI_DIR:-/opt/klid/ai}"
VENV="${AI_DIR}/venv"
PY="${VENV}/bin/python"
ENV_FILE="${ENV_FILE:-/etc/klid/ai-server.env}"

_ok=$'\033[32m'; _wa=$'\033[33m'; _er=$'\033[31m'; _off=$'\033[0m'
line()  { printf '  %-28s %s\n' "$1" "$2"; }
head2() { printf '\n== %s\n' "$1"; }

NEED=()          # 조치 목록 — 비어 있으면 준비 완료
UNKNOWN=0        # 확인 자체가 안 된 항목이 있으면 1

echo "klid-label GPU 준비 상태 점검  ($(date '+%Y-%m-%d %H:%M:%S'))"

# ---- ① 장치와 드라이버 -----------------------------------------------------
head2 "① 장치·드라이버"
if command -v nvidia-smi >/dev/null 2>&1; then
  _drv="$(nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>/dev/null | head -n1)"
  _gpu="$(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | head -n1)"
  if [[ -n "${_drv}" ]]; then
    line "GPU" "${_gpu:-?}"
    line "드라이버" "${_drv}"
    # CUDA 12 계열 런타임을 쓰려면 드라이버가 525 이상이어야 한다.
    _major="${_drv%%.*}"
    if [[ "${_major}" =~ ^[0-9]+$ ]] && (( _major < 525 )); then
      line "판정" "${_er}드라이버가 낮다${_off} — CUDA 12 휠은 525 이상을 요구한다"
      NEED+=("드라이버를 525 이상으로 올린다 (장비 담당 소관 — 우리가 덮어쓰지 않는다)")
    else
      line "판정" "${_ok}충족${_off}"
    fi
  else
    line "nvidia-smi" "${_er}응답 없음${_off} — 드라이버가 적재되지 않았다"
    NEED+=("NVIDIA 드라이버를 설치·적재한다")
  fi
else
  line "nvidia-smi" "${_er}없음${_off}"
  NEED+=("NVIDIA 드라이버를 설치한다 (없으면 이 장비는 GPU 로 돌 수 없다)")
fi

# ---- ② 설치된 파이썬 패키지 판 ---------------------------------------------
head2 "② 설치된 휠 (CPU 판인가 CUDA 판인가)"
if [[ -x "${PY}" ]]; then
  _t="$("${PY}" - <<'PY' 2>/dev/null
try:
    import torch
    print(torch.__version__)
except Exception:
    print("")
PY
)"
  if [[ -n "${_t}" ]]; then
    line "torch" "${_t}"
    case "${_t}" in
      *+cu*)  line "판정" "${_ok}CUDA 판${_off}" ;;
      *+cpu*) line "판정" "${_er}CPU 판${_off}"
              NEED+=("GPU 델타를 적용해 torch 를 CUDA 판으로 교체한다") ;;
      *)      line "판정" "${_wa}판을 읽지 못했다${_off} — 지역 빌드일 수 있다"; UNKNOWN=1 ;;
    esac
  else
    line "torch" "${_er}import 실패${_off}"
    NEED+=("ai-server 설치 상태를 먼저 확인한다 (torch 를 불러오지 못한다)")
  fi

  _o="$("${PY}" - <<'PY' 2>/dev/null
try:
    import importlib.metadata as md
except Exception:
    import importlib_metadata as md
for name in ("onnxruntime-gpu", "onnxruntime"):
    try:
        print(f"{name}=={md.version(name)}")
    except Exception:
        pass
PY
)"
  if [[ -n "${_o}" ]]; then
    while IFS= read -r l; do line "onnxruntime" "${l}"; done <<< "${_o}"
    if ! grep -q '^onnxruntime-gpu==' <<< "${_o}"; then
      NEED+=("onnxruntime 을 onnxruntime-gpu 로 교체한다 (패키지 이름 자체가 다르다)")
    fi
  else
    line "onnxruntime" "${_wa}설치돼 있지 않다${_off}"; UNKNOWN=1
  fi
else
  line "venv" "${_er}없음: ${VENV}${_off}"
  NEED+=("ai-server 를 먼저 설치한다 (이 델타는 설치본 위에 얹는 것이다)")
  UNKNOWN=1
fi

# ---- ③ 런타임이 실제로 GPU 를 잡는가 ---------------------------------------
head2 "③ 런타임 실측 (설정이 아니라 실제로 잡히는가)"
if [[ -x "${PY}" ]]; then
  _av="$("${PY}" - <<'PY' 2>/dev/null
try:
    import torch
    print("Y" if torch.cuda.is_available() else "N")
except Exception:
    print("?")
PY
)"
  case "${_av}" in
    Y) line "torch.cuda.is_available()" "${_ok}True${_off}" ;;
    N) line "torch.cuda.is_available()" "${_er}False${_off}"
       NEED+=("위 ①②를 해소한 뒤 다시 확인한다 — 지금은 GPU 가 잡히지 않는다") ;;
    *) line "torch.cuda.is_available()" "${_wa}확인 불가${_off}"; UNKNOWN=1 ;;
  esac
else
  line "실측" "${_wa}venv 가 없어 건너뜀${_off}"; UNKNOWN=1
fi

# ---- ④ 설정값 --------------------------------------------------------------
head2 "④ 설정 (AI_DEVICE)"
if [[ -f "${ENV_FILE}" ]]; then
  _dev="$(grep -E '^[[:space:]]*AI_DEVICE=' "${ENV_FILE}" | tail -n1 | cut -d= -f2- | tr -d '"'"'"' \r')"
  line "${ENV_FILE}" "AI_DEVICE=${_dev:-(미지정)}"
  if [[ "${_dev}" != "cuda" ]]; then
    NEED+=("AI_DEVICE=cuda 로 바꾸고 ai-server 를 재기동한다")
  fi
else
  line "${ENV_FILE}" "${_wa}파일 없음${_off}"; UNKNOWN=1
  NEED+=("환경 파일을 확인한다: ${ENV_FILE}")
fi

# ---- 판정 ------------------------------------------------------------------
head2 "판정"
if (( ${#NEED[@]} == 0 )); then
  if (( UNKNOWN == 1 )); then
    printf '  %s확인 못 한 항목이 있다%s — 위 표에서 「확인 불가」를 먼저 해소하세요.\n' "${_wa}" "${_off}"
    exit 2
  fi
  printf '  %sGPU 로 돌고 있다%s — 조치할 것이 없습니다.\n' "${_ok}" "${_off}"
  exit 0
fi

printf '  %s조치 %d건%s\n' "${_er}" "${#NEED[@]}" "${_off}"
_i=0
for n in "${NEED[@]}"; do _i=$((_i+1)); printf '   %d) %s\n' "${_i}" "${n}"; done
cat <<'GUIDE'

  ── 다음에 할 일 ────────────────────────────────────────────────
  · 「GPU 델타를 적용한다」가 목록에 있으면 델타 매체가 따로 필요합니다.
    이 패치 매체에는 들어 있지 않습니다(휠만 3.5GB 를 넘습니다).
    절차는 같은 폴더의 `GPU-전환-가이드.md` 를 보세요.
  · 「드라이버」 항목은 우리 소관이 아닙니다 — 이 장비는 관제지원시스템과
    공동 배치라 드라이버를 우리가 덮어쓰지 않습니다. 장비 담당에게 요청하세요.
GUIDE
exit 1
