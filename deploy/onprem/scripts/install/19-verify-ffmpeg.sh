#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 19-verify-ffmpeg.sh — [대상 서버 A / app 역할] ffmpeg·ffprobe 전제조건 검증
#
#   ★★ ffmpeg 는 <우리 반입물이 아니라 대상 장비의 전제조건>이다 (2026-08-30 사용자 확정).
#     관제지원시스템 팀이 <백엔드가 도는 그 서버>에 설치한다. 우리 설치 스크립트는
#     <자동으로 설치하지 않는다> — 이 서버는 관제와 공동 배치라 관제가 깔아 둔 것을
#     덮어쓰면 관제 기능이 깨지기 때문이다.
#
#   ★ 그래서 이 검증이 <유일한 안전핀>이다. ffmpeg 가 없으면 backend 의 프레임 추출·
#     영상 메타 조회(FFmpegStep / BrampVideoProbe)가 죽는데, 서버는 <정상 기동하고
#     헬스체크도 통과>한다. 배치를 실제로 돌려야 드러난다 — 그것도 운영 중에.
#     따라서 warn 으로 넘기지 않고 die 한다.
#
#   ★ ffmpeg 와 ffprobe 를 <둘 다> 본다. 일부 배포·최소 설치는 ffmpeg 만 깔린다.
#     우리는 영상 길이·해상도 조회에 ffprobe 를 별도로 쓴다(application.yml 의
#     authoring.ffmpeg.ffprobe-binary).
#
#   ★ 검증 대상 경로는 <백엔드 설정에서 읽는다>. 하드코딩하면 운영자가 설정을 바꿨을 때
#     "검증한 것"과 "런타임이 실제로 쓰는 것"이 갈려 검증이 무의미해진다.
#
#   ★ 이 단계는 서버 A(app 역할) 전용이다. ai-server 는 ffmpeg 실행 파일을 쓰지 않는다
#     (실측: ai-server 소스에 ffmpeg/ffprobe 문자열 0건, subprocess/Popen 0건).
#     ai 서버에서 이걸 돌리면 <정상 설치를 실패시킨다>.
#
#   ★ 실행 순서상 <마지막>에 둔다. 여기서 die 해도 앞 단계(WAR 배치·프론트·DB)는 이미
#     끝나 있어, 운영자가 ffmpeg 를 마련한 뒤 install.sh 를 다시 돌리면 된다(멱등).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"

ONPREM="$(onprem_root)"
KLID_ETC="${KLID_ETC:-/etc/klid}"

if ! klid_role_has was; then
  info "[ffmpeg] 이 역할에는 백엔드가 없으므로 검증을 건너뜁니다(KLID_ROLE=${KLID_ROLE:-all})."
  info "         ai-server 는 ffmpeg 실행 파일을 쓰지 않습니다."
  exit 0
fi

# ---- 어느 설정 파일이 <실제로 읽히는가> ----
#   · WAR 반입(정본) : /etc/klid/application.properties (WAS 가 읽는다)
#   · 베어메탈       : /etc/klid/backend.env            (systemd EnvironmentFile)
PROPS="${KLID_ETC}/application.properties"
ENVF="${KLID_ETC}/backend.env"
if [[ "${INSTALL_BACKEND_SYSTEMD_UNIT:-0}" == "1" ]]; then
  PRIMARY="${ENVF}";  SECONDARY="${PROPS}"; PRIMARY_LABEL="backend.env(베어메탈 형상)"
else
  PRIMARY="${PROPS}"; SECONDARY="${ENVF}";  PRIMARY_LABEL="application.properties(WAR 반입 형상)"
fi

read_pair() {  # read_pair <file> — "<ffmpeg>|<ffprobe>" 출력
  printf '%s|%s' "$(klid_config_value "$1" FFMPEG_BIN)" "$(klid_config_value "$1" FFPROBE_BIN)"
}

pair="$(read_pair "${PRIMARY}")"
FF_CFG="${pair%%|*}"; FP_CFG="${pair##*|}"
SRC="${PRIMARY_LABEL}"

# 1차가 비어 있으면 2차 → 템플릿 → versions.sh 기본값 순으로 내려간다.
if [[ -z "${FF_CFG}" || -z "${FP_CFG}" ]]; then
  pair2="$(read_pair "${SECONDARY}")"
  [[ -n "${FF_CFG}" ]] || { FF_CFG="${pair2%%|*}"; SRC="${SECONDARY}"; }
  [[ -n "${FP_CFG}" ]] || { FP_CFG="${pair2##*|}"; SRC="${SECONDARY}"; }
fi
if [[ -z "${FF_CFG}" || -z "${FP_CFG}" ]]; then
  tpl="${ONPREM}/config/backend/application.properties.template"
  [[ -n "${FF_CFG}" ]] || { FF_CFG="$(klid_config_value "${tpl}" FFMPEG_BIN)";  SRC="설정 템플릿"; }
  [[ -n "${FP_CFG}" ]] || { FP_CFG="$(klid_config_value "${tpl}" FFPROBE_BIN)"; SRC="설정 템플릿"; }
fi
# 그래도 비면 versions.sh 의 단일 출처(설치 경로) → 그마저 없으면 bare 이름(PATH 해석).
#   ★ bare 폴백은 application.yml 의 기본값(${FFMPEG_BIN:ffmpeg})과 <같은 동작>이다.
[[ -n "${FF_CFG}" ]] || { FF_CFG="${FFMPEG_BIN_PATH:-ffmpeg}";   SRC="versions.sh 기본값"; }
[[ -n "${FP_CFG}" ]] || { FP_CFG="${FFPROBE_BIN_PATH:-ffprobe}"; SRC="versions.sh 기본값"; }

info "[ffmpeg] 검증 대상 경로(출처: ${SRC}) — FFMPEG_BIN=${FF_CFG} · FFPROBE_BIN=${FP_CFG}"

# 두 설정 파일이 서로 다른 값을 갖고 있으면 경고한다(둘은 같아야 한다 — 12 단계 안내 참조).
if [[ -f "${PRIMARY}" && -f "${SECONDARY}" ]]; then
  pair2="$(read_pair "${SECONDARY}")"
  s_ff="${pair2%%|*}"; s_fp="${pair2##*|}"
  if [[ -n "${s_ff}" && "${s_ff}" != "${FF_CFG}" ]] || [[ -n "${s_fp}" && "${s_fp}" != "${FP_CFG}" ]]; then
    warn "[ffmpeg] 두 설정 파일의 ffmpeg 경로가 다릅니다 — 형상을 바꾸면 검증하지 않은 경로가 쓰입니다."
    warn "         ${PRIMARY}   : ${FF_CFG} / ${FP_CFG}   ← 지금 검증하는 값"
    warn "         ${SECONDARY} : ${s_ff} / ${s_fp}"
  fi
fi

FF="$(klid_resolve_bin "${FF_CFG}")"
FP="$(klid_resolve_bin "${FP_CFG}")"

# 복붙 가능한 한 줄 — "어딘가에 스크립트가 있다"로 끝내지 않는다.
SCRIPTS_ROOT="$(cd "${SELF_DIR}/.." && pwd)"
INSTALL_CMD="sudo ${SELF_DIR}/install-ffmpeg.sh"
guidance() {
  warn "----------------------------------------------------------------"
  warn "[ffmpeg] 대상 장비에 ffmpeg/ffprobe 가 준비되어야 합니다. 선택지는 둘입니다:"
  warn "  1) 관제지원시스템 팀에 설치를 요청한다 (기본·권장)"
  warn "     — 이 서버는 관제와 공동 배치이며 ffmpeg 는 관제가 설치하는 것이 원칙입니다."
  warn "  2) 매체에 동봉된 예비물로 직접 설치한다 (관제가 설치하지 않는 것이 확인된 경우):"
  warn ""
  warn "       ${INSTALL_CMD}"
  warn ""
  warn "     — 자동으로 실행되지 않습니다. 관제 설치본을 덮어쓰지 않기 위해 <사람이> 부릅니다."
  warn "  설치 후 설치 스크립트를 다시 실행하세요:  sudo ${SCRIPTS_ROOT}/install.sh --role=app"
  warn "----------------------------------------------------------------"
}

MISSING=0
[[ -n "${FF}" && -x "${FF}" ]] || MISSING=1
[[ -n "${FP}" && -x "${FP}" ]] || MISSING=1
if [[ "${MISSING}" -eq 1 ]]; then
  warn "[ffmpeg] 실행 파일을 찾을 수 없습니다(또는 실행 권한이 없습니다)."
  [[ -n "${FF}" && -x "${FF}" ]] || warn "         ffmpeg  : ${FF_CFG} → ${FF:-(PATH 에서 해석 실패)}"
  [[ -n "${FP}" && -x "${FP}" ]] || warn "         ffprobe : ${FP_CFG} → ${FP:-(PATH 에서 해석 실패)}"
  warn "         ★ ffmpeg 만 있고 ffprobe 가 없는 경우도 실패입니다 — 우리는 영상 길이·해상도"
  warn "           조회에 ffprobe 를 <별도로> 씁니다(authoring.ffmpeg.ffprobe-binary)."
  guidance
  die "[ffmpeg] 전제조건 미충족 — 설치를 중단합니다.
     (backend 의 프레임 추출·영상 메타 조회가 동작하지 않습니다. 서버는 기동되고 헬스체크도
      통과하므로, 이 단계를 넘기면 운영 중 배치에서야 드러납니다.)"
fi

# 실행 가능 여부 · -version rc · 아주 작은 입력으로 실동작까지 확인한다.
#   서브셸로 감싸는 이유: 헬퍼의 die 는 원인만 말한다. 여기서 조치 안내를 덧붙여야 한다.
if ! ( klid_verify_ffmpeg_runtime "${FF}" "${FP}" ); then
  guidance
  die "[ffmpeg] 설치되어 있으나 정상 동작하지 않습니다 — 설치를 중단합니다."
fi

ok "[ffmpeg] 전제조건 충족 — ${FF} / ${FP}"
