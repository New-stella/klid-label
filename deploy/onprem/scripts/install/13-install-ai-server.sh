#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 13-install-ai-server.sh — [대상 서버] ai-server venv 생성 + 오프라인 pip 설치
#   + 앱 소스/모델 배치 + env 템플릿 + systemd 유닛
#
#   외부 네트워크 호출 없음:
#     pip install --no-index --find-links vendor/wheels (+ sam2 로컬 소스)
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"

# ★ 이 단계는 role=ai 에서만 돈다(install.sh _build_steps) — 즉 AI 장비 전용이다.
#   그래서 설치 루트를 AI 전용 경로(KLID_AI_PREFIX, 기본 /GCLOUD/klid-at)로 바꾼다.
#   ⚠ KLID_PREFIX 를 사람이 명시했으면 덮지 않는다(단일 서버 형상 배려).
#   ⚠ 단독 실행에서도 같은 루트를 잡아야 하므로 install.sh 가 아니라 <여기서> 부른다.
klid_use_ai_prefix
klid_assert_prefix_sane
require_root

: "${KLID_PREFIX:?install.sh 에서 호출되어야 합니다}"
ONPREM="$(onprem_root)"
AI_DIR="${KLID_PREFIX}/ai"
VENV="${AI_DIR}/venv"
WHEELS="${ONPREM}/vendor/wheels"
SAM2_SRC="${ONPREM}/vendor/sam2/sam2-src"

# 런타임 python 경로 로드(11 단계가 기록)
# shellcheck source=/dev/null
source "${KLID_PREFIX}/runtime/runtime.env"
: "${KLID_PYTHON:?런타임 python 경로 누락 — 11-install-runtimes.sh 가 선행되어야 합니다}"

ensure_dir "${AI_DIR}"

# ---- 앱 소스 배치 ----
info "[ai-server] 앱 소스 배치..."
rm -rf "${AI_DIR}/app"
cp -R "${ONPREM}/artifacts/ai-server/app" "${AI_DIR}/app"
cp "${ONPREM}/artifacts/ai-server/requirements.txt" "${AI_DIR}/requirements.txt"
ok "[ai-server] 소스: ${AI_DIR}/app"

# ---- 모델/가중치 배치 ----
ensure_dir "${AI_DIR}/weights" "${AI_DIR}/.hf-cache"
if [[ -f "${ONPREM}/models/weights/yolox_s.onnx" ]]; then
  install -m 0644 "${ONPREM}/models/weights/yolox_s.onnx" "${AI_DIR}/weights/yolox_s.onnx"
  ok "[ai-server] 가중치: ${AI_DIR}/weights/yolox_s.onnx"
else
  warn "[ai-server] yolox 가중치 미동봉 — yolox 백엔드는 mock 응답으로 동작(weights_missing)"
fi
# SAM2 모델 캐시 복사
#   ★ 없을 때 조용히 넘어가지 않는다. 바로 위 yolox 가중치는 없으면 경고하는데 이쪽만
#     아무 말이 없어, 모델이 빠진 번들이 <아무 신호 없이> 설치를 통과하고 있었다.
#     대상 서버는 HF_HUB_OFFLINE=1 이라 내려받지도 못하므로, 여기서 알리지 않으면
#     SAM2 가 mock 으로 도는 사실을 운영 중에 알 방법이 없다(서버는 정상 기동하고
#     API 도 200 을 돌려준다).
#   ★ 이 분기는 <이 납품이 SAM2 를 포함하는가>를 판정하는 신호이기도 하다(SAM2_EXPECTED).
#     아래 sam2 패키지 설치의 실패 처리 강도를 이 값으로 가른다 — 모델과 패키지는 같은 기능의
#     두 반쪽이므로 한쪽만 엄격하면 나머지 반쪽으로 조용히 샌다.
if [[ -d "${ONPREM}/models/hf-cache" ]] && [[ -n "$(ls -A "${ONPREM}/models/hf-cache" 2>/dev/null)" ]]; then
  SAM2_EXPECTED=1
  cp -R "${ONPREM}/models/hf-cache/." "${AI_DIR}/.hf-cache/"
  ok "[ai-server] SAM2 모델 캐시 배치: ${AI_DIR}/.hf-cache"
else
  SAM2_EXPECTED=0
  warn "[ai-server] ★SAM2 모델 캐시 미동봉 — SAM2 분할·Track 이 mock 응답으로 동작합니다."
  warn "  이 서버는 오프라인이라 모델을 내려받지 못합니다. 기동·헬스체크·API 응답은"
  warn "  모두 정상이라 운영 중에는 드러나지 않습니다."
  warn "  조치: 빌드머신에서 패키징을 다시 수행해 models/hf-cache 를 채운 뒤 재설치하세요."
fi

# ---- venv + 오프라인 설치 ----
info "[ai-server] venv 생성: ${VENV}"
"${KLID_PYTHON}" -m venv "${VENV}"
PIP="${VENV}/bin/pip"

# pip 자체 업그레이드는 오프라인이므로 생략(번들 wheel 에 pip 가 있으면 사용 가능).
info "[ai-server] pip 오프라인 설치 (--no-index --find-links vendor/wheels)..."
# requirements.txt 에서 sam-2(git+) 라인은 제외하고 wheel 로 설치.
REQ_NO_SAM2="$(mktemp)"; trap 'rm -f "${REQ_NO_SAM2}"' EXIT
grep -ivE '(^|[[:space:]])sam-2[[:space:]]*@' "${AI_DIR}/requirements.txt" \
  | grep -vE 'git\+https' > "${REQ_NO_SAM2}" || true

"${PIP}" install --no-index --find-links "${WHEELS}" -r "${REQ_NO_SAM2}" \
  || die "[ai-server] 오프라인 wheel 설치 실패 — 06-troubleshooting.md '누락 wheel' 절 참고"

# sam2 는 vendor 한 로컬 소스로 설치(빌드 의존성도 wheels 에서 충당).
#
# ★★ 실패 처리 강도를 SAM2_EXPECTED 로 가른다 (2026-08-30 — 구 동작 "항상 warn" 폐기).
#   구 동작은 sam2 설치 실패를 warn 으로 넘겨 <설치가 초록으로 완주>했다. 그 결과:
#     ① 모델 캐시는 동봉됐으므로 ENV=prd 기동 가드(verify_models_available)는 통과한다
#        — 그 가드는 <파일 존재>만 보고 패키지가 import 되는지는 보지 않는다.
#     ② 서버는 정상 기동하고 헬스체크도 200 이다.
#     ③ 현장에서 누군가 SAM2 분할·Track 을 <처음 눌러 볼 때> 비로소 503 이 난다
#        (배포 환경은 mock 을 내보내지 않고 거부한다 — app/startup_guard.py).
#   즉 결함이 설치·기동·헬스체크를 모두 통과해 <인수 뒤에> 드러난다. 폐쇄망에서는 그 시점의
#   수정이 번들 재반입이라 가장 비싸다. 설치 시점이 마지막으로 싼 순간이다.
#
#   ★ 왜 무조건 die 가 아닌가: 문서화된 <SAM2 미사용 납품>(PREFETCH_HF=0, 01-prerequisites.md)
#     경로를 막지 않기 위해서다. 그 납품은 모델 캐시를 동봉하지 않으므로 SAM2_EXPECTED=0 이고,
#     이때는 종전대로 warn 이다(동작 변경 없음).
#   ★ 모델을 동봉했다는 것은 <이 납품이 SAM2 를 제공한다는 선언>이다. 그 선언과 실제 설치가
#     어긋나면 그것은 무시해도 되는 경고가 아니라 납품물의 결함이다.
#   ★★ 매체에서 곧바로 설치하지 않고 <쓰기 가능한 곳으로 복사한 뒤> 설치한다 (2026-08-31).
#     pip 는 로컬 소스를 설치할 때 그 디렉터리 안에 SAM_2.egg-info 를 만든다(PEP 517 의
#     "Getting requirements to build wheel" 단계). 반입 매체는 DVD·USB·읽기전용 마운트로
#     오는 것이 정상이므로 그 자리에서 빌드하면 다음으로 죽는다:
#         error: could not create 'SAM_2.egg-info': Read-only file system
#     ⚠ 이 실패는 <아래 die 문구가 지목하는 원인(setuptools 판 충돌)과 무관>한데도 같은
#       분기로 떨어져 현장을 엉뚱한 곳으로 보낸다. 실측으로 확인된 오진 경로다.
#     복사본은 설치 후 지운다 — 산출물이 아니라 빌드 임시물이다.
if [[ -d "${SAM2_SRC}" ]]; then
  SAM2_BUILD_DIR="${AI_DIR}/.build/sam2-src"
  info "[ai-server] sam2 로컬 소스 설치: ${SAM2_SRC}"
  info "[ai-server]   빌드 작업본으로 복사: ${SAM2_BUILD_DIR} (매체가 읽기전용일 수 있다)"
  rm -rf "${SAM2_BUILD_DIR}"
  mkdir -p "$(dirname "${SAM2_BUILD_DIR}")"
  cp -R "${SAM2_SRC}" "${SAM2_BUILD_DIR}" \
    || die "[ai-server] sam2 소스 복사 실패: ${SAM2_SRC} → ${SAM2_BUILD_DIR}
       디스크 공간·권한을 확인하세요(약 70MB 필요)."
  if ! "${PIP}" install --no-index --find-links "${WHEELS}" "${SAM2_BUILD_DIR}"; then
    rm -rf "${SAM2_BUILD_DIR}"
    if [[ "${SAM2_EXPECTED}" == "1" ]]; then
      die "[ai-server] sam2 설치 실패 — 이 납품은 SAM2 모델 캐시를 동봉했으므로 SAM2 를 제공한다는
       뜻입니다. 이대로 두면 서버는 정상 기동하고 헬스체크도 통과하지만, SAM2 분할·Track 은
       배포 환경에서 503 으로 거부되고 그 사실은 현장에서 기능을 처음 쓸 때에야 드러납니다.
       흔한 원인: PEP 517 빌드 격리가 vendor/wheels 안에서 빌드 의존을 풀지 못함
         — 특히 setuptools 판이 sam2 의 하한(>=61.0)과 torch 의 상한(<82)을 동시에 만족해야 합니다.
           위 pip 출력에 ResolutionImpossible 이 있으면 이 경우입니다.
       조치: 빌드머신에서 versions.sh 의 PEP517_SETUPTOOLS_PIN 을 확인하고 패키징을 다시 수행하세요
             (30-collect-ai-server.sh 의 3-c 검증이 이 조합을 대조합니다).
       SAM2 를 제공하지 않는 납품이라면 빌드머신에서 PREFETCH_HF=0 으로 <명시적으로> 빼십시오."
    fi
    warn "[ai-server] sam2 설치 실패 — 이 납품은 SAM2 모델 캐시를 동봉하지 않았습니다(SAM2 미제공 납품)."
    warn "  SAM2 분할/Track 은 사용할 수 없습니다(06-troubleshooting 참고)."
  fi
  rm -rf "${SAM2_BUILD_DIR}"
elif [[ "${SAM2_EXPECTED}" == "1" ]]; then
  die "[ai-server] sam2 소스 미동봉(${SAM2_SRC})인데 SAM2 모델 캐시는 동봉돼 있습니다 — 반입물이 불완전합니다.
       빌드머신에서 30-collect-ai-server.sh 를 다시 수행해 vendor/sam2 를 채우세요."
else
  warn "[ai-server] sam2 소스 미동봉(${SAM2_SRC}) — SAM2 기능 사용 시 필요"
fi

ok "[ai-server] 패키지 설치 완료"

# ---- env 파일 ----
ENV_DST="${KLID_ETC}/ai-server.env"
if [[ -f "${ENV_DST}" ]]; then
  info "[ai-server] env 이미 존재(보존): ${ENV_DST}"
else
  # ★ @KLID_PREFIX@ 를 치환한다 — 종전에는 install(1) 로 <그대로 복사>했다 (2026-09-08 수정).
  #   그래서 KLID_PREFIX 를 바꿔 설치해도 이 파일의 HF_HOME 만 옛 경로를 가리켰고,
  #   서버는 정상 기동하면서 SAM2 만 조용히 mock 으로 떨어졌다(오류 로그 없음).
  #   유닛 파일이 이미 같은 방식으로 치환되고 있었으므로(아래 UNIT_SRC 블록) 방식을 맞춘다.
  sed -e "s#@KLID_PREFIX@#${KLID_PREFIX}#g" \
      "${ONPREM}/config/ai-server/env.template" > "${ENV_DST}"
  chmod 0640 "${ENV_DST}"
  chown root:"${KLID_GROUP}" "${ENV_DST}"
  # 치환 누락은 조용히 지나가면 안 된다 — 남아 있으면 HF_HOME 이 문자 그대로 박힌다.
  if grep -q '@KLID_PREFIX@' "${ENV_DST}"; then
    die "[ai-server] env 치환 실패 — ${ENV_DST} 에 @KLID_PREFIX@ 가 남았습니다."
  fi
  ok "[ai-server] env 템플릿 설치: ${ENV_DST} (KLID_PREFIX=${KLID_PREFIX})"
fi

chown -R "${KLID_USER}:${KLID_GROUP}" "${AI_DIR}"

# ---- systemd 유닛 ----
UNIT_SRC="${ONPREM}/config/systemd/klid-ai-server.service"
UNIT_DST="${SYSTEMD_DIR}/klid-ai-server.service"
sed \
  -e "s#@KLID_USER@#${KLID_USER}#g" \
  -e "s#@KLID_GROUP@#${KLID_GROUP}#g" \
  -e "s#@KLID_PREFIX@#${KLID_PREFIX}#g" \
  -e "s#@KLID_ETC@#${KLID_ETC}#g" \
  "${UNIT_SRC}" > "${UNIT_DST}"
chmod 0644 "${UNIT_DST}"
ok "[ai-server] systemd 유닛 설치: ${UNIT_DST}"
