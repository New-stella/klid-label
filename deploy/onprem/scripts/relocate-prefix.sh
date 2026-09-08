#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# relocate-prefix.sh — [대상 서버] <이미 설치된> klid 설치 루트를 다른 경로로 옮긴다
#
#   ★ 주 용도는 AI 장비다. 현장은 이미 /GCLOUD 아래 서 있는데(WAS=/GCLOUD/JBOSS/jboss-eap-8.1,
#     웹 문서 루트=/GCLOUD/WebApp/label-studio) ai-server 만 /opt/klid 아래 있어 혼자 달랐다.
#     그래서 AI 장비의 설치 루트를 /GCLOUD/klid-at 으로 맞춘다(KLID_AI_PREFIX).
#     AI 장비에는 runtime/python 과 ai/ 만 깔리므로(11·13 단계는 role=ai 전용) 이 이동이 곧
#     "ai-server 를 옮긴다"와 같다.
#
#   설치 루트만 옮긴다. 설정(/etc/klid)·데이터(/var/lib/klid)·로그(/var/log/klid)·
#   영상 저장소(NAS)는 <그대로 둔다>. 그 넷은 루트 밖이고 경로를 바꿀 이유가 다르다.
#
#   사용법
#     sudo ./scripts/relocate-prefix.sh --to=/GCLOUD/klid-at
#     sudo ./scripts/relocate-prefix.sh --from=/opt/klid --to=/GCLOUD/klid-at
#     sudo ./scripts/relocate-prefix.sh --to=/GCLOUD/klid-at --dry-run   # 무엇을 할지만 출력
#     sudo ./scripts/relocate-prefix.sh --to=/GCLOUD/klid-at --copy      # mv 대신 복사(원본 보존)
#
#   ★★ 이 스크립트가 존재하는 이유 — `mv` 하나로는 끝나지 않는다.
#     파이썬 venv 는 <자기 절대경로를 안에 적어 둔다>. 옮기기만 하면
#     `bin/uvicorn` 의 shebang 이 옛 경로를 가리켜 서비스가 즉시 죽거나,
#     더 나쁘게는 시스템 파이썬으로 흘러 <의존 없이 기동 실패>한다.
#     ⚠ 그런데 venv 를 새로 만드는 것도 답이 아니다 — GPU 전환을 마친 장비는
#       CUDA 판 휠 22 개(약 3.6 GiB)가 들어 있고, 그 휠은 <GPU 델타 매체에만> 있다.
#       현장에 매체가 없으면 재생성은 되돌릴 수 없는 파괴다. 그래서 <경로만 고쳐> 옮긴다.
#
#   ★ 옮긴 뒤에 조용히 깨지는 자리 (전부 이 스크립트가 처리한다)
#     · ${PREFIX}/ai/venv/pyvenv.cfg        home = 번들 파이썬 경로
#     · ${PREFIX}/ai/venv/bin/*             shebang · VIRTUAL_ENV
#     · ${PREFIX}/runtime/runtime.env       KLID_PYTHON=
#     · ${PREFIX}/bin/*                     설치 루트 기본값이 박힌 보조 스크립트
#     · /etc/systemd/system/klid-*.service  WorkingDirectory · ExecStart · ReadWritePaths
#     · /etc/klid/ai-server.env             HF_HOME  ← ★기동은 되고 SAM2 만 조용히 죽는다
#     · /etc/klid/*.properties · *.env      ffmpeg 등 루트 기준 경로
#     · /etc/httpd/conf.d/klid-frontend.conf  DocumentRoot
#     · SELinux fcontext 규칙               옛 경로에 걸려 있다
#
#   ⚠ 이 스크립트는 <서비스를 멈춘다>. 무중단 이동이 아니다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# ★ 설치 루트 불일치 가드를 끈다 — 이 스크립트가 <바로 그 불일치를 고치는 도구>다.
#   끄지 않으면 가드가 해결책을 막는 자기잠금이 된다.
export KLID_SKIP_PREFIX_GUARD=1
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"

FROM=""; TO=""; DRY=0; MODE="move"; ASSUME_YES=0
for arg in "$@"; do
  case "${arg}" in
    --from=*)  FROM="${arg#*=}" ;;
    --to=*)    TO="${arg#*=}" ;;
    --dry-run) DRY=1 ;;
    --copy)    MODE="copy" ;;
    --yes|-y)  ASSUME_YES=1 ;;
    --help|-h) sed -n '3,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "알 수 없는 옵션: ${arg} (--help 로 사용법)" ;;
  esac
done

# 기본 출발지는 <구 기본값>이다. KLID_PREFIX 는 이미 새 값이므로 그것을 쓰면 안 된다.
FROM="${FROM:-/opt/klid}"
# 목적지 기본값은 AI 장비 루트다 — 이 도구의 주 용도가 그것이다.
TO="${TO:-${KLID_AI_PREFIX}}"
# 끝 슬래시를 떼지 않으면 sed 치환 결과에 `//` 가 섞이고 systemd 유닛이 지저분해진다.
#   ⚠ `/` 자체는 떼지 않는다 — 떼면 빈 문자열이 되어 "인자가 없다"는 <틀린 사유>로 거절하고,
#     받은 사람은 인자를 다시 붙이며 같은 값을 재시도한다(실측). 거절은 맞되 이유가 맞아야 한다.
[[ "${FROM}" == "/" ]] || FROM="${FROM%/}"
[[ "${TO}"   == "/" ]] || TO="${TO%/}"

[[ -n "${TO}" ]] || die "--to=<새 경로> 가 필요합니다."
[[ "${FROM}" != "${TO}" ]] || die "출발지와 목적지가 같습니다: ${FROM}"
case "${TO}" in /*) ;; *) die "--to 는 절대경로여야 합니다: ${TO}" ;; esac
# 루트로 옮기면 시스템이 통째로 섞인다.
#   ⚠ 막는 것은 `/` 하나뿐이다 — 최상위 한 칸(`/GCLOUD` 같은)은 <막지 않는다>.
#     정당한 선택일 수 있어서다. 대신 오타(`--to=/GCLOUD` 를 `/GCLOUD/klid-at` 대신 준 경우)는
#     설치물이 그 아래로 흩어질 뿐 복구 가능하고, --yes 없이 돌리면 확인 프롬프트가 먼저 보여준다.
#     ★ --yes 로 자동화할 때는 목적지를 <두 번 읽어라>. 그때는 확인 프롬프트가 없다.
[[ "${TO}" != "/" ]] || die "위험한 목적지: ${TO} (루트로는 옮길 수 없습니다)"
case "${FROM}" in /|/usr|/etc|/var|/opt|/home) die "위험한 출발지: ${FROM}" ;; esac

require_root
require_cmd sed grep find

_run() { if [[ "${DRY}" == "1" ]]; then printf '  (dry-run) %s\n' "$*"; else "$@"; fi; }

banner() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

# ---- 0) 사전 점검 ----------------------------------------------------------
banner "0. 사전 점검"
[[ -d "${FROM}" ]] || die "설치 루트가 없습니다: ${FROM}
   이미 옮겼거나 다른 경로에 설치돼 있습니다. 현재 위치는 아래로 확인하세요:
     systemctl cat klid-ai-server | grep WorkingDirectory"
[[ ! -e "${TO}" ]] || die "목적지가 이미 있습니다: ${TO}
   덮어쓰지 않습니다. 비우거나 다른 경로를 지정하세요."

_parent="$(dirname "${TO}")"
[[ -d "${_parent}" ]] || { info "목적지 상위 디렉터리 생성: ${_parent}"; _run mkdir -p "${_parent}"; }

# 여유 공간 — GPU 판 venv 는 7 GiB 급이다. 복사 모드면 두 벌이 동시에 존재한다.
#   ⚠ `||true` 를 빼지 말 것 — dry-run 은 목적지를 <만들지 않으므로> df 가 실패하고,
#     `set -e` 아래에서 대입문의 명령 치환 실패는 스크립트를 <메시지 없이> 끝낸다(실측).
#     그래서 여유 공간을 못 읽으면 죽는 대신 <모른다고 말하고> 넘어간다.
_need_kb="$(du -sk "${FROM}" 2>/dev/null | cut -f1 || true)"; _need_kb="${_need_kb:-0}"
_free_kb="$(df -Pk "${_parent}" 2>/dev/null | awk 'NR==2{print $4}' || true)"
if [[ -n "${_free_kb}" ]]; then
  info "설치 루트 크기: $(( _need_kb / 1024 )) MiB · 목적지 여유: $(( _free_kb / 1024 )) MiB"
else
  info "설치 루트 크기: $(( _need_kb / 1024 )) MiB · 목적지 여유: 확인 불가(아직 없는 경로)"
fi
if [[ -n "${_free_kb}" && "${_free_kb}" -lt "${_need_kb}" ]]; then
  # 같은 파일시스템이면 mv 는 공간을 쓰지 않는다 — 그때만 통과시킨다.
  if [[ "${MODE}" == "move" ]] \
     && [[ "$(stat -c %d "${FROM}" 2>/dev/null)" == "$(stat -c %d "${_parent}" 2>/dev/null)" ]]; then
    info "같은 파일시스템 — mv 는 추가 공간을 쓰지 않습니다."
  else
    die "목적지 여유 공간이 부족합니다(필요 $(( _need_kb / 1024 )) MiB)."
  fi
fi

# 어떤 역할이 깔려 있는지 — 있는 것만 건드린다.
UNITS=()
for u in klid-ai-server klid-backend; do
  [[ -f "/etc/systemd/system/${u}.service" ]] && UNITS+=("${u}")
done
HAS_AI=0;  [[ -d "${FROM}/ai" ]]  && HAS_AI=1
HAS_WEB=0; [[ -d "${FROM}/web" ]] && HAS_WEB=1
info "감지: 유닛=[${UNITS[*]:-없음}] ai=${HAS_AI} web=${HAS_WEB}"

printf '\n  %s  →  %s   (%s)\n\n' "${FROM}" "${TO}" "${MODE}"
if [[ "${DRY}" == "0" && "${ASSUME_YES}" == "0" ]]; then
  confirm "위 경로로 이동합니다. 서비스가 잠시 멈춥니다. 계속할까요?" || die "사용자 취소"
fi

# ---- 1) 서비스 정지 --------------------------------------------------------
banner "1. 서비스 정지"
for u in "${UNITS[@]:-}"; do
  [[ -n "${u}" ]] || continue
  info "정지: ${u}"
  _run systemctl stop "${u}" || warn "정지 실패(이미 멈춰 있을 수 있음): ${u}"
done
if [[ "${HAS_WEB}" == "1" ]] && systemctl is-active --quiet httpd 2>/dev/null; then
  info "정지: httpd"
  _run systemctl stop httpd || warn "httpd 정지 실패"
fi

# ---- 2) 이동 --------------------------------------------------------------
banner "2. 설치 루트 이동"
if [[ "${MODE}" == "copy" ]]; then
  # -a 로 심링크·권한·시각을 보존한다. HF 캐시가 심링크 구조라 -a 가 아니면 캐시가 부푼다.
  info "복사: ${FROM} → ${TO} (원본 보존)"
  _run cp -a "${FROM}" "${TO}"
else
  info "이동: ${FROM} → ${TO}"
  _run mv "${FROM}" "${TO}"
fi

# ---- 3) venv 경로 교정 -----------------------------------------------------
#   ★ 여기가 이 스크립트의 핵심이다. 텍스트 파일만 고른다 — 바이너리(.so·.pyd)를
#     sed 로 건드리면 길이가 달라져 <조용히 깨진다>. grep -I 가 그 필터다.
if [[ "${HAS_AI}" == "1" ]]; then
  banner "3. venv 경로 교정 (재생성 아님 — GPU 휠을 보존한다)"
  VENV="${TO}/ai/venv"
  if [[ -d "${VENV}" ]]; then
    # __pycache__ 는 절대경로가 박혀 있고 재생성되므로 지운다(교정 대상에서 제외).
    info "  __pycache__ 정리"
    _run find "${VENV}" -type d -name '__pycache__' -prune -exec rm -rf {} +

    info "  옛 경로를 담은 텍스트 파일 탐색..."
    if [[ "${DRY}" == "1" ]]; then
      _n="$(grep -rlI -- "${FROM}" "${VENV}" 2>/dev/null | wc -l | tr -d ' ')"
      printf '  (dry-run) 텍스트 파일 %s 건 치환 예정\n' "${_n}"
    else
      _cnt=0
      while IFS= read -r f; do
        LC_ALL=C sed -i "s#${FROM}#${TO}#g" "$f"
        _cnt=$(( _cnt + 1 ))
      done < <(grep -rlI -- "${FROM}" "${VENV}" 2>/dev/null || true)
      ok "  venv 텍스트 파일 ${_cnt} 건 교정"
    fi

    # pyvenv.cfg 의 home 은 <번들 파이썬>을 가리킨다. 위 치환에 포함되지만,
    # 그 값이 실제로 존재하는지는 따로 봐야 한다 — 없으면 venv 가 통째로 죽는다.
    if [[ "${DRY}" == "0" && -f "${VENV}/pyvenv.cfg" ]]; then
      _home="$(awk -F' = ' '/^home/{print $2}' "${VENV}/pyvenv.cfg" | tr -d '\r')"
      if [[ -n "${_home}" && ! -d "${_home}" ]]; then
        warn "  ★ pyvenv.cfg 의 home 이 존재하지 않습니다: ${_home}"
        warn "    번들 파이썬이 설치 루트 밖에 있거나 경로가 더 어긋난 경우입니다."
      else
        ok "  pyvenv.cfg home 확인: ${_home}"
      fi
    fi
  else
    warn "venv 없음: ${VENV} (ai 역할이 설치되지 않았을 수 있습니다)"
  fi

  # ★ 옛 경로를 가리키는 <절대 심링크>를 다시 건다.
  #   HF 캐시는 상대 심링크가 정상이라 보통 여기 걸리는 것이 없다. 그러나 걸린다면
  #   경고만 하고 넘어가서는 안 된다 — 끊어진 캐시 링크의 증상은 오류가 아니라
  #   <SAM2 가 조용히 mock 으로 떨어지는 것>이고, 그건 현장에서 기능을 처음 쓸 때에야 드러난다.
  if [[ "${DRY}" == "0" ]]; then
    _fixed=0
    while IFS= read -r l; do
      _t="$(readlink "$l")"
      case "${_t}" in
        "${FROM}"|"${FROM}"/*)
          ln -sfn "${TO}${_t#"${FROM}"}" "$l"; _fixed=$(( _fixed + 1 )) ;;
      esac
    done < <(find "${TO}" -type l 2>/dev/null || true)
    [[ "${_fixed}" == "0" ]] || ok "  옛 경로를 가리키던 절대 심링크 ${_fixed} 건 재연결"
  fi

  # 그러고도 끊어진 링크가 남으면 그것은 <이동 이전부터 깨져 있던 것>이다. 보고만 한다.
  if [[ "${DRY}" == "0" && -d "${TO}/ai/.hf-cache" ]]; then
    _broken="$(find "${TO}/ai/.hf-cache" -xtype l 2>/dev/null | wc -l | tr -d ' ')"
    if [[ "${_broken}" != "0" ]]; then
      warn "★ 모델 캐시에 끊어진 심링크 ${_broken} 건 — SAM2 가 mock 으로 떨어집니다."
      warn "  옛 경로를 가리키던 것은 위에서 재연결했으므로, 남은 것은 <이동 전부터>"
      warn "  깨져 있던 링크입니다. 모델 캐시 반입이 불완전했을 수 있습니다."
      warn "  find ${TO}/ai/.hf-cache -xtype l   로 확인하세요."
    else
      ok "  모델 캐시 심링크 정상"
    fi
  fi
fi

# ---- 4) 설치 루트 안의 나머지 텍스트 참조 ----------------------------------
banner "4. 설치 루트 안의 나머지 참조 교정"
for d in runtime bin web app; do
  [[ -d "${TO}/${d}" ]] || continue
  if [[ "${DRY}" == "1" ]]; then
    _n="$(grep -rlI -- "${FROM}" "${TO}/${d}" 2>/dev/null | wc -l | tr -d ' ')"
    printf '  (dry-run) %s/ : %s 건\n' "${d}" "${_n}"
  else
    _cnt=0
    while IFS= read -r f; do
      LC_ALL=C sed -i "s#${FROM}#${TO}#g" "$f"; _cnt=$(( _cnt + 1 ))
    done < <(grep -rlI -- "${FROM}" "${TO}/${d}" 2>/dev/null || true)
    [[ "${_cnt}" == "0" ]] || ok "  ${d}/ : ${_cnt} 건 교정"
  fi
done

# ---- 5) 설치 루트 <밖>의 참조 ----------------------------------------------
#   여기를 빠뜨리는 것이 가장 흔한 실패다. 파일은 그대로 있는데 아무도 새 경로를 모른다.
banner "5. 설치 루트 밖의 참조 교정"
OUTSIDE=()
for u in "${UNITS[@]:-}"; do
  [[ -n "${u}" ]] && OUTSIDE+=("/etc/systemd/system/${u}.service")
done
[[ -d "${KLID_ETC}" ]] && while IFS= read -r f; do OUTSIDE+=("$f"); done \
  < <(grep -rlI -- "${FROM}" "${KLID_ETC}" 2>/dev/null || true)
[[ -f /etc/httpd/conf.d/klid-frontend.conf ]] && OUTSIDE+=("/etc/httpd/conf.d/klid-frontend.conf")

_touched=0
for f in "${OUTSIDE[@]:-}"; do
  [[ -n "${f}" && -f "${f}" ]] || continue
  grep -qI -- "${FROM}" "${f}" 2>/dev/null || continue
  info "  교정: ${f}"
  if [[ "${DRY}" == "0" ]]; then
    cp -a "${f}" "${f}.bak-relocate-$(date '+%Y%m%d-%H%M%S')"
    LC_ALL=C sed -i "s#${FROM}#${TO}#g" "${f}"
  fi
  _touched=$(( _touched + 1 ))
done
[[ "${_touched}" != "0" ]] || info "  교정 대상 없음"

info "  systemd 재적재"
_run systemctl daemon-reload

# ---- 6) 소유권 · SELinux ---------------------------------------------------
banner "6. 소유권 · SELinux 문맥"
if getent group "${KLID_GROUP}" >/dev/null 2>&1 && getent passwd "${KLID_USER}" >/dev/null 2>&1; then
  info "  소유권: ${KLID_USER}:${KLID_GROUP} → ${TO}"
  _run chown -R "${KLID_USER}:${KLID_GROUP}" "${TO}"
else
  warn "  계정 ${KLID_USER}:${KLID_GROUP} 없음 — 소유권을 바꾸지 않습니다."
fi

# ★ 웹 문서 루트만 SELinux 라벨이 필요하다(httpd_sys_content_t). 설치 때 프론트 단계가
#   옛 경로에 fcontext 규칙을 걸어 뒀으므로 새 경로에 다시 건다.
#   ⚠ 나머지(ai·app·runtime)는 설치 스크립트도 라벨을 건드리지 않는다. 새 경로가
#     기본 라벨로 떨어져도 systemd 유닛 실행에는 대개 문제가 없으나, enforcing 에서
#     기동이 막히면 아래 §검증의 audit 확인 안내를 따를 것.
if [[ "${HAS_WEB}" == "1" ]] && command -v semanage >/dev/null 2>&1; then
  info "  SELinux fcontext: ${TO}/web/dist"
  _run semanage fcontext -d "${FROM}/web/dist(/.*)?" 2>/dev/null || true
  _run semanage fcontext -a -t httpd_sys_content_t "${TO}/web/dist(/.*)?" 2>/dev/null || true
fi
if command -v restorecon >/dev/null 2>&1; then
  info "  restorecon: ${TO}"
  _run restorecon -R "${TO}" 2>/dev/null || warn "  restorecon 실패(무시 가능)"
fi

# ---- 7) 기동 · 검증 --------------------------------------------------------
banner "7. 기동 · 검증"
for u in "${UNITS[@]:-}"; do
  [[ -n "${u}" ]] || continue
  info "기동: ${u}"
  _run systemctl start "${u}" || warn "기동 실패: ${u} — journalctl -u ${u} -n 100"
done
if [[ "${HAS_WEB}" == "1" ]] && systemctl is-enabled --quiet httpd 2>/dev/null; then
  _run systemctl start httpd || warn "httpd 기동 실패"
fi

if [[ "${DRY}" == "0" ]]; then
  # 잔여 스캔 — "옮겼다"와 "아무도 옛 경로를 안 본다"는 다른 명제다.
  banner "잔여 옛 경로 스캔"
  _left=0
  for scan in "${TO}" "${KLID_ETC}" /etc/systemd/system /etc/httpd/conf.d; do
    [[ -e "${scan}" ]] || continue
    _n="$(grep -rlI -- "${FROM}" "${scan}" 2>/dev/null | grep -v '\.bak-relocate-' | wc -l | tr -d ' ')"
    if [[ "${_n}" != "0" ]]; then
      warn "  ${scan} : ${_n} 건 잔존"
      grep -rlI -- "${FROM}" "${scan}" 2>/dev/null | grep -v '\.bak-relocate-' | sed 's/^/      /'
      _left=$(( _left + _n ))
    fi
  done
  [[ "${_left}" != "0" ]] || ok "  옛 경로 참조 0 건"

  if [[ "${HAS_AI}" == "1" ]]; then
    banner "ai-server 헬스 확인"
    sleep 5
    _code="$(curl -fsS -o /dev/null -w '%{http_code}' "http://127.0.0.1:9300/health" 2>/dev/null || echo 000)"
    if [[ "${_code}" == "200" ]]; then
      ok "  /health 200"
    else
      warn "  /health 응답 ${_code} — journalctl -u klid-ai-server -n 100"
    fi
  fi
fi

banner "완료"
cat <<EOS

  설치 루트: ${FROM}  →  ${TO}

  ★ 헬스체크 200 은 <절반의 확인>이다. ai-server 는 모델을 못 찾아도 기동하고
    200 을 준다 — YOLOX 는 빈 detections, SAM2 는 mock 으로 조용히 떨어진다.
    반드시 아래 둘을 확인할 것:

      grep -E 'HF_HOME|YOLOX_WEIGHTS_PATH' ${KLID_ETC}/ai-server.env
      # → HF_HOME 이 ${TO}/ai/.hf-cache 를 가리켜야 한다

      추론을 한 번 돌려 detections 가 실제로 나오는지 확인한다.
      GPU 장비면 그 사이 nvidia-smi 에 프로세스가 잡혀야 한다.

  ★ GPU 판 확인(전환을 마친 장비):
      ${TO}/ai/venv/bin/python -c "import torch, onnxruntime as ort; \\
        print(torch.__version__, torch.cuda.is_available()); \\
        print('CUDAExecutionProvider' in ort.get_available_providers())"
      # → 2.12.0+cu126 True / True 세 값이 모두 맞아야 GPU 로 도는 것이다.

  ★ 되돌리기: --copy 로 돌렸다면 원본 ${FROM} 이 그대로 있다. 서비스를 멈추고
    이 스크립트를 --from=${TO} --to=${FROM} 으로 다시 돌리거나, 백업해 둔
    *.bak-relocate-* 설정을 되돌린 뒤 원본을 기동한다.
    mv 로 돌렸다면 원본은 없다 — 되돌리기도 이 스크립트를 반대로 돌리는 것이다.

EOS
