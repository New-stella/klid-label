#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# apply-gpu.sh — [대상 서버 B / ai] CPU 설치본을 GPU 판으로 바꾼다
#
#   이 델타는 <이미 설치된 CPU 판 위에> 얹는 것이다. 전체 매체가 아니다.
#   먼저 klid-onprem CPU 판으로 `install.sh --role=ai` 가 끝나 있어야 한다.
#
#   사용법:
#     sudo ./scripts/apply-gpu.sh              # 교체 + 검증(설정은 안 바꾼다)
#     sudo ./scripts/apply-gpu.sh --set-device # 위에 더해 AI_DEVICE=cuda 까지
#     ./scripts/apply-gpu.sh --check           # 아무것도 바꾸지 않고 현재 상태만 본다
#
#   ★ 이 스크립트가 하지 않는 것 — NVIDIA 드라이버 설치.
#     드라이버는 커널 모듈이라 pip 로 해결되지 않고, 이 장비는 관제지원시스템과
#     공동 배치라 드라이버를 우리가 덮어쓰면 안 된다. 드라이버는 장비 담당이 먼저 넣는다.
#     드라이버가 없으면 이 스크립트는 <교체는 하되 GPU 로 돌지 않는다>고 분명히 말한다.
#
#   ★ 되돌리기 — CPU 로 완전 복귀는 재설치다.
#     AI_DEVICE=cpu 로만 바꾸면 설정은 CPU 인데 설치된 휠은 CUDA 판 그대로다.
#     그 상태도 동작은 하나(CPU 로 돈다) 디스크를 약 6GB 더 쓴다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DELTA_ROOT="$(cd "${SELF_DIR}/.." && pwd)"
WHEELS="${DELTA_ROOT}/wheels"

AI_DIR="${AI_DIR:-/opt/klid/ai}"
VENV="${AI_DIR}/venv"
PIP="${VENV}/bin/pip"
PY="${VENV}/bin/python"
ENV_FILE="${ENV_FILE:-/etc/klid/ai-server.env}"

_c_ok=$'\033[32m'; _c_warn=$'\033[33m'; _c_err=$'\033[31m'; _c_off=$'\033[0m'
info() { printf '%s [INFO] %s\n' "$(date +%H:%M:%S)" "$*"; }
ok()   { printf '%s [%s OK %s] %s\n' "$(date +%H:%M:%S)" "${_c_ok}" "${_c_off}" "$*"; }
warn() { printf '%s [%sWARN%s] %s\n' "$(date +%H:%M:%S)" "${_c_warn}" "${_c_off}" "$*"; }
die()  { printf '%s [%sFAIL%s] %s\n' "$(date +%H:%M:%S)" "${_c_err}" "${_c_off}" "$*"; exit 1; }

_check_only=0; _set_device=0
for a in "$@"; do
  case "${a}" in
    --check)      _check_only=1 ;;
    --set-device) _set_device=1 ;;
    -h|--help)    sed -n '1,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "알 수 없는 인자: ${a} (허용: --check · --set-device)" ;;
  esac
done

# ---- 0) 현재 상태 ----
info "================================================================"
info " klid-label GPU 전환 델타"
info "================================================================"
[[ -x "${PY}" ]] || die "CPU 설치본을 찾을 수 없습니다: ${VENV}
       먼저 CPU 판 매체로 'install.sh --role=ai' 를 끝내십시오. 이 델타는 그 위에 얹는 것입니다."

_cur_torch="$("${PY}" -c 'import torch;print(torch.__version__)' 2>/dev/null || echo '(없음)')"
_cur_ort="$("${PY}" -c 'import onnxruntime as o;print(o.__version__)' 2>/dev/null || echo '(없음)')"
info "현재 torch          : ${_cur_torch}"
info "현재 onnxruntime    : ${_cur_ort}"

# 드라이버 — 없어도 교체는 하되 분명히 말한다.
_have_driver=0
if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi >/dev/null 2>&1; then
  _have_driver=1
  ok "NVIDIA 드라이버: $(nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>/dev/null | head -1)"
  info "  GPU: $(nvidia-smi --query-gpu=name,memory.total --format=csv,noheader 2>/dev/null | head -1)"
else
  warn "NVIDIA 드라이버가 없습니다(nvidia-smi 실패)."
  warn "  교체는 진행하지만 <GPU 로 돌지 않습니다> — 드라이버 설치 후 다시 확인하십시오."
  warn "  드라이버는 커널 모듈이라 이 델타에 들어 있지 않습니다(장비 담당이 설치)."
fi

if [[ "${_check_only}" -eq 1 ]]; then
  info "--check 이므로 여기서 끝냅니다(아무것도 바꾸지 않았습니다)."
  exit 0
fi

[[ "$(id -u)" -eq 0 ]] || die "root 로 실행하십시오(sudo)."
[[ -d "${WHEELS}" ]] || die "휠 디렉터리가 없습니다: ${WHEELS}"

# ---- 1) 무결성 ----
if [[ -f "${WHEELS}/SHA256SUMS" ]]; then
  info "[무결성] 휠 체크섬 대조..."
  ( cd "${WHEELS}" && sha256sum -c --quiet SHA256SUMS ) \
    || die "[무결성] 체크섬 불일치 — 매체가 손상됐습니다. 다시 받으십시오."
  ok "[무결성] 통과 ($(grep -c '' "${WHEELS}/SHA256SUMS") 개)"
else
  warn "[무결성] SHA256SUMS 가 없어 대조를 건너뜁니다."
fi

# ---- 2) CPU 판 onnxruntime 제거 ----
#   ★ 제거가 아니라 덮어쓰기로 하면 안 된다 — onnxruntime 과 onnxruntime-gpu 는
#     <같은 파이썬 모듈 이름>을 제공해 서로의 파일을 덮는다. 둘이 공존하면
#     어느 쪽이 로드될지가 설치 순서에 달리고, 그러면 CUDA provider 가 조용히 사라진다.
if "${PY}" -c 'import importlib.metadata as m; m.version("onnxruntime")' >/dev/null 2>&1; then
  info "[교체] CPU 판 onnxruntime 제거(같은 모듈명을 쓰므로 공존 불가)"
  "${PIP}" uninstall -y onnxruntime >/dev/null || die "[교체] onnxruntime 제거 실패"
  ok "[교체] onnxruntime 제거됨"
fi

# ---- 3) GPU 휠 설치 ----
#   --no-deps: 의존 해석을 하지 않는다. 이 델타는 <필요한 것을 이미 다 담고 있고>,
#     해석을 켜면 잠긴 판(filelock·fsspec·typing_extensions 등)까지 올리려 든다.
#     그 판들은 CPU 설치본에 이미 맞는 것이 들어 있다.
info "[설치] GPU 휠 오프라인 설치 ($(ls "${WHEELS}"/*.whl | wc -l | tr -d ' ') 개)..."
"${PIP}" install --no-index --no-deps --find-links "${WHEELS}" "${WHEELS}"/*.whl \
  || die "[설치] 실패 — 디스크 여유(약 8GB 필요)와 권한을 확인하십시오."
ok "[설치] 완료"

# ---- 4) 검증 — 여기가 이 스크립트의 핵심이다 ----
#   ★ GPU 전환의 실패는 조용하다. 서버는 뜨고 헬스체크도 통과하며 추론도 200 을 준다.
#     "왜 느리지"로만 나타나므로, 이 자리에서 확인하지 않으면 인수 뒤에 드러난다.
info "[검증] 교체 결과 확인..."
"${PY}" - <<'PYCHK'
import sys
try:
    import torch, onnxruntime as ort
except Exception as e:
    print(f"  [FAIL] import 실패: {e}"); sys.exit(1)

print(f"  torch            : {torch.__version__}")
print(f"  onnxruntime      : {ort.__version__}")
provs = ort.get_available_providers()
print(f"  ort providers    : {provs}")
print(f"  cuda.is_available: {torch.cuda.is_available()}")

bad = []
if "+cu" not in torch.__version__:
    bad.append("torch 가 CUDA 판이 아니다 — 휠 교체가 안 됐다")
if "CUDAExecutionProvider" not in provs:
    bad.append("onnxruntime 에 CUDAExecutionProvider 가 없다 — CPU 판이 남아 있다(YOLOX 탐지가 CPU 로 돈다)")
if not torch.cuda.is_available():
    bad.append("torch 가 GPU 를 못 본다 — 드라이버 미설치이거나 커널 모듈 미적재")
else:
    print(f"  GPU 개수         : {torch.cuda.device_count()}  ({torch.cuda.get_device_name(0)})")

if bad:
    print("\n  ── 아직 GPU 로 돌지 않는다 ──")
    for b in bad: print(f"   - {b}")
    sys.exit(2)
print("\n  ── 세 축 모두 통과 ──")
PYCHK
_rc=$?

# ---- 5) AI_DEVICE ----
if [[ "${_set_device}" -eq 1 ]]; then
  if [[ "${_rc}" -ne 0 ]]; then
    warn "[설정] 검증을 통과하지 못해 AI_DEVICE 를 바꾸지 않습니다."
    warn "       지금 cuda 로 두면 기동은 되지만 조용히 CPU 로 돕니다 — 원인을 먼저 없애십시오."
  elif [[ -f "${ENV_FILE}" ]]; then
    cp -a "${ENV_FILE}" "${ENV_FILE}.bak.$(date +%Y%m%d%H%M%S)"
    sed -i 's/^AI_DEVICE=.*/AI_DEVICE=cuda/' "${ENV_FILE}"
    grep -q '^AI_DEVICE=cuda' "${ENV_FILE}" || echo 'AI_DEVICE=cuda' >> "${ENV_FILE}"
    ok "[설정] AI_DEVICE=cuda (원본은 ${ENV_FILE}.bak.* 로 남겼습니다)"
  else
    warn "[설정] ${ENV_FILE} 이 없어 건너뜁니다."
  fi
else
  info "[설정] AI_DEVICE 는 바꾸지 않았습니다 — --set-device 를 주거나 직접 편집하십시오."
fi

info "================================================================"
if [[ "${_rc}" -eq 0 ]]; then
  ok " 휠 교체·검증 완료. 남은 것은 <사람이> 합니다:"
  info "   1) AI_DEVICE=cuda 확인:  grep AI_DEVICE ${ENV_FILE}"
  info "   2) 재기동:               sudo systemctl restart klid-ai-server"
  info "   3) 기동 로그:            journalctl -u klid-ai-server -n 50 --no-pager | grep -i device="
  info "   4) 추론 1회 후 nvidia-smi 프로세스 목록에 python 이 보이는지 확인"
else
  warn " 휠은 교체됐으나 아직 GPU 로 돌지 않습니다 — 위 항목을 해결한 뒤 다시 실행하십시오."
  info "   재실행은 안전합니다(멱등)."
fi
info "================================================================"
exit "${_rc}"
