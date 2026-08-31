#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 14-install-frontend.sh — [대상 서버] frontend dist 배치 + Apache httpd 설정
#
#   관제지원시스템 웹 서버와 동일 사양(Apache httpd)으로 정적 서빙 + /api 리버스프록시.
#   httpd 는 번들 RPM(syspkgs/rpm)에서 오프라인 설치한다. 외부 네트워크 호출 없음.
#
#   ★ 구 형상(번들 Caddy + 전용 systemd 유닛)은 폐기했다(2026-08-19). 배포 대상의 웹 서버를
#     관제와 하나로 통일하기 위함이다. 되돌리려면 이 커밋 이전 판을 참조한다.
#
#   ★ 시스템 httpd 를 쓰는 이유: RPM httpd 는 모듈 적재(conf.modules.d)와 SELinux 문맥이
#     이미 갖춰져 있다. 전용 인스턴스를 손으로 구성하면 LoadModule 목록을 우리가 관리해야
#     하고, 그건 <고객 장비에서만 깨지고 우리가 재현할 수 없는> 종류의 실패를 만든다.
#
#   ★★ 화면 산출물은 <배포 향마다 다른 벌>이다 (2026-08-31). 매체에는 두 벌이 실리고
#     (artifacts/frontend/dist/{control,portal}) 이 스크립트가 KLID_DEPLOY_FLAVOR 로 하나를 고른다.
#     ⚠ 고른 향이 매체에 없으면 <다른 향을 대신 깔지 않고 즉시 실패>한다(fail-closed).
#       잘못 깔아도 빌드·설치·httpd 가 모두 성공해 조용히 어긋나기 때문이다 —
#       설정만 바꿔서는 되돌릴 수 없다(채널이 빌드 시점에 굳는다. 04-configuration.md D-4).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
require_root

: "${KLID_PREFIX:?install.sh 에서 호출되어야 합니다}"
ONPREM="$(onprem_root)"
WEB_DIR="${KLID_PREFIX}/web"
ensure_dir "${WEB_DIR}"

BACKEND_ORIGIN="${BACKEND_ORIGIN:-http://127.0.0.1:8080}"
BIN_DIR="${KLID_PREFIX}/bin"
FE_CONFIG_DST="${KLID_ETC}/frontend.env"
FE_CONFIG_RENDERER="${BIN_DIR}/klid-frontend-config"

# ---- 0) 배포 향 판정 → 배치할 화면 산출물 선택 ----
#   ★★ 매체에는 화면 산출물이 <두 벌> 실린다(artifacts/frontend/dist/{control,portal}).
#     라우트 채널이 빌드 시점에 굳어 반대 향 화면이 산출물에서 통째로 빠지기 때문이다 —
#     관제 산출물에는 /portal 이 0건, 포털 산출물에는 내부 화면이 0건이다.
#     그래서 <어느 것을 까느냐>가 여기서 정해지고, 잘못 깔면 오류 없이 조용히 어긋난다.
#   ★ 판정은 klid_deploy_flavor 한 곳이 한다(설정 정본 > 환경변수 > control).
#     그 함수가 설정 생성기(render-frontend-config.sh)와 같은 값을 보도록 되어 있다.
FE_FLAVOR="$(klid_deploy_flavor)"
DIST_ROOT="${ONPREM}/artifacts/frontend/dist"
DIST_SRC="${DIST_ROOT}/${FE_FLAVOR}"

[[ -d "${DIST_ROOT}" ]] || die "frontend dist 없음: ${DIST_ROOT} (빌드머신에서 package.sh 를 실행했나요?)"

# 구 배치(향 구분 없는 단일 dist)를 조용히 깔지 않는다 — 그 산출물이 어느 채널로 구워졌는지
# 알 수 없고, 포털 장비에 관제 화면을 올려 놓고도 설치는 성공으로 끝난다.
if [[ ! -d "${DIST_SRC}" && -f "${DIST_ROOT}/index.html" ]]; then
  die "[frontend] 매체의 화면 산출물이 <구 배치>입니다(향 구분 없는 단일 dist): ${DIST_ROOT}
     이 판의 설치 스크립트는 향별 산출물(${DIST_ROOT}/{control,portal})을 요구합니다.
     빌드머신에서 package.sh 를 다시 실행해 매체를 새로 뜨세요."
fi

# ★ fail-closed — 이 향의 산출물이 없으면 <다른 향을 대신 깔지 않는다>.
#   잘못된 향을 까는 것은 오류 없이 조용히 어긋나는 실패다(머리 영역이 겹치고 화면이 뜨지
#   않거나, 있어야 할 메뉴가 통째로 없다). 현장에서 원인 추적이 가장 어려운 형태라 즉시 멈춘다.
if [[ ! -d "${DIST_SRC}" ]]; then
  _avail="$(cd "${DIST_ROOT}" 2>/dev/null && find . -maxdepth 1 -mindepth 1 -type d -exec basename {} \; 2>/dev/null | sort | tr '\n' ' ' || true)"
  die "[frontend] 배포 향 '${FE_FLAVOR}' 의 화면 산출물이 없습니다: ${DIST_SRC}
     매체에 있는 향: ${_avail:-(없음)}
     · 향이 틀렸다면 ${FE_CONFIG_DST} 의 KLID_DEPLOY_FLAVOR 를 고치세요(첫 설치면 KLID_DEPLOY_FLAVOR=... 로 주면 됩니다).
     · 매체에 그 향이 없다면 빌드머신에서 package.sh 를 다시 실행해 두 향을 모두 담으세요.
     ※ 다른 향의 산출물로 대신하지 않습니다 — 화면 채널은 빌드 시점에 굳어 있어
       설정만 바꿔서는 되돌릴 수 없습니다(04-configuration.md D-4)."
fi

# ---- 1) 정적 자산 배치 ----
info "[frontend] 정적 자산 배치 (배포 향: ${FE_FLAVOR})..."
rm -rf "${WEB_DIR}/dist"
cp -R "${DIST_SRC}" "${WEB_DIR}/dist"
ok "[frontend] dist: ${WEB_DIR}/dist  (원본 ${DIST_SRC})"

# ---- 1-1) 런타임 설정 정본 배치 + 생성기 설치 ----
#   ★ 값의 정본은 산출물이 아니라 ${KLID_ETC}/frontend.env 다(백엔드가 DB 접속정보를 /etc/klid
#     에서 읽는 것과 같은 관례). 예전에는 상위 로그인 주소·개발용 화면 토글이 <빌드 시점>에
#     dist 안으로 구워져, 환경마다 다시 빌드해야 했고 배포 후에는 고칠 수 없었다.
#   ★ 기존 파일은 덮지 않는다 — 재설치가 현장값을 날리면 안 된다(backend 설정과 같은 규칙).
info "[frontend] 런타임 설정 정본 배치..."
if [[ -f "${FE_CONFIG_DST}" ]]; then
  info "[frontend] 설정 정본 이미 존재(보존): ${FE_CONFIG_DST}"
else
  FE_CONFIG_SRC="${ONPREM}/config/frontend/frontend.env.template"
  [[ -f "${FE_CONFIG_SRC}" ]] || die "[frontend] 설정 템플릿 없음: ${FE_CONFIG_SRC}"
  # 설치 시 환경변수로 주면 첫 실행부터 완성된 파일이 놓인다(종전 빌드 인자와 같은 사용감):
  #   sudo VITE_CONTROL_LOGIN_URL=https://... VITE_PORTAL_LOGIN_URL=https://... ./scripts/install.sh
  #   ★ 치환값은 반드시 이스케이프한다. 로그인 주소에 쿼리(`?a=1&b=2`)가 붙는 것은 흔한데,
  #     sed 의 치환문에서 `&` 는 <매치 전체>를 뜻해 값이 조용히 뒤틀린다. `#`(구분자)·`\`도 같다.
  _sed_repl_escape() { printf '%s' "$1" | sed -e 's/[\\&#]/\\&/g'; }
  # ★ 배포 향 기본값은 control 이다 — 이 매체가 관제 연동 배포용으로 만들어지기 때문이다.
  #   포털 연동 배포는 설치 시 KLID_DEPLOY_FLAVOR=portal 을 준다. 빈 값을 기본으로 두면
  #   두 로그인 주소를 모두 요구해 관제 연동 설치가 20 단계에서 막힌다(안 쓰는 값을 요구).
  #   ★ 여기 적히는 값은 <방금 배치한 dist 와 같은 향>이어야 한다 — 그래서 위 0 단계가 이미
  #     판정한 ${FE_FLAVOR} 를 그대로 쓴다. 여기서 다시 판정하면 두 값이 갈릴 수 있고,
  #     그러면 "관제 화면을 깔아 놓고 포털 로그인 주소를 요구하는" 상태가 만들어진다.
  sed -e "s#@CONTROL_LOGIN_URL@#$(_sed_repl_escape "${VITE_CONTROL_LOGIN_URL:-}")#g" \
      -e "s#@PORTAL_LOGIN_URL@#$(_sed_repl_escape "${VITE_PORTAL_LOGIN_URL:-}")#g" \
      -e "s#@DEPLOY_FLAVOR@#$(_sed_repl_escape "${FE_FLAVOR}")#g" \
      "${FE_CONFIG_SRC}" > "${FE_CONFIG_DST}"
  # 비밀값을 담지 않는 파일이라 조이지 않는다(was.env 와 같은 판단). 반대로 <여기에 비밀값을
  # 넣으면 안 된다> — 이 파일의 내용은 브라우저로 내려간다. 템플릿 머리말이 그 구분을 설명한다.
  chmod 0644 "${FE_CONFIG_DST}"
  chown root:"${KLID_GROUP}" "${FE_CONFIG_DST}" 2>/dev/null || true
  ok "[frontend] 설정 정본 배치: ${FE_CONFIG_DST}  (★ 설치 후 필수 편집)"
fi

# 반영 명령을 대상 서버에 남긴다 — 매체가 없어도 값 변경이 한 줄로 끝나야 한다.
ensure_dir "${BIN_DIR}"
install -m 0755 "${SELF_DIR}/render-frontend-config.sh" "${FE_CONFIG_RENDERER}"
ok "[frontend] 설정 반영 명령 설치: ${FE_CONFIG_RENDERER}"

# ---- 2) httpd 오프라인 설치 ----
if command -v httpd >/dev/null 2>&1; then
  ok "[frontend] httpd 이미 설치됨: $(httpd -v 2>&1 | head -n1)"
else
  rpms=("${ONPREM}"/syspkgs/rpm/httpd*.rpm)
  if [[ -e "${rpms[0]}" ]]; then
    info "[frontend] httpd 오프라인 설치(syspkgs/rpm 로컬 저장소)..."
    # ★ 로컬 yum 저장소 방식(2026-08-30) — 근거는 common.sh 의 klid_dnf_install_from_bundle 주석.
    #   ⚠ 구 방식 폐기: `dnf install -y --disablerepo='*' syspkgs/rpm/*.rpm`(파일 직접 설치).
    #     조달이 --alldeps 로 바뀌면서 기반 패키지가 세트에 섞여 그 방식은 충돌로 실패한다.
    klid_import_rpm_gpg_keys "${ONPREM}/syspkgs/gpg" || true
    klid_dnf_install_from_bundle syspkgs "${ONPREM}/syspkgs/rpm" httpd policycoreutils-python-utils \
      || die "[frontend] httpd 설치 실패 — 06-troubleshooting.md 참고"
    ok "[frontend] httpd 설치: $(httpd -v 2>&1 | head -n1)"
  else
    die "[frontend] httpd RPM 이 번들에 없습니다: ${ONPREM}/syspkgs/rpm/httpd*.rpm
     빌드머신에서 package.sh 를 다시 실행해 syspkgs 를 수집하세요(폐쇄망에서는 내려받을 수 없습니다)."
  fi
fi

# ---- 3) 설정 배치 ----
#   conf.d 드롭인으로 넣는다. httpd 본체 설정(모듈 적재·MPM·MIME)은 배포판 것을 그대로 쓴다.
CONF_DST="/etc/httpd/conf.d/klid-frontend.conf"
sed -e "s#@WEB_ROOT@#${WEB_DIR}/dist#g" \
    -e "s#@BACKEND_ORIGIN@#${BACKEND_ORIGIN}#g" \
    "${ONPREM}/config/frontend/httpd-klid.conf.template" > "${CONF_DST}"
chmod 0644 "${CONF_DST}"
ok "[frontend] httpd 설정: ${CONF_DST} (backend=${BACKEND_ORIGIN})"

# 배포판 기본 환영 페이지가 우리 문서 루트를 가리는 것을 막는다.
if [[ -f /etc/httpd/conf.d/welcome.conf ]]; then
  mv /etc/httpd/conf.d/welcome.conf /etc/httpd/conf.d/welcome.conf.disabled
  ok "[frontend] 기본 welcome.conf 비활성화"
fi

# ---- 3-1) 런타임 설정 생성 (값이 없어도 설치는 계속한다) ----
#   ★★ 이 자리는 <되돌리기 어려운 것을 먼저 끝낸 뒤>다 (2026-08-30 순서 정정, 구속).
#     종전에는 이 생성이 <1-2 단계>, 즉 httpd 설치보다 앞에 있으면서 fail-closed 로 die 했다.
#     그런데 이 단계가 읽는 ${KLID_ETC}/frontend.env 는 <바로 위에서 이 단계가 방금 놓은>
#     빈 템플릿이다 — 그래서 <첫 설치는 구조적으로 반드시 실패>했다. set -e 라 httpd 설치·
#     SELinux 문맥·15·16·17·19 단계가 통째로 날아갔고, 현장에서는 "설치했는데 웹 서버가
#     없다"로 보였다.
#   ★ 방어를 없앤 것이 아니라 <자리를 옮겼다> — 값 누락은 맨 마지막 20 단계가 설치 전체를
#     실패로 종결시킨다(20-verify-frontend-config.sh). 여기서 조용히 넘어가면 지금보다
#     나빠지므로, 실패 사실은 아래에서 <크게> 남긴다.
#   ★ SELinux 문맥 부여(4단계)보다 <앞>에 둔다 — 여기서 만든 klid-config.js 도 restorecon
#     대상에 포함되게 하기 위해서다.
info "[frontend] 런타임 설정 생성..."
if KLID_ETC="${KLID_ETC}" KLID_PREFIX="${KLID_PREFIX}" WEB_ROOT="${WEB_DIR}/dist" \
     "${FE_CONFIG_RENDERER}"; then
  ok "[frontend] 런타임 설정 생성 완료: ${WEB_DIR}/dist/klid-config.js"
else
  FE_CONFIG_PENDING=1
  warn "----------------------------------------------------------------"
  warn "[frontend] ★런타임 설정을 아직 만들지 못했습니다 — 설치는 계속하지만 <미완성>입니다."
  warn "  ${FE_CONFIG_DST} 의 필수 값(상위 시스템 로그인 URL)이 비어 있습니다."
  warn "  설치 마지막 단계(20-verify-frontend-config.sh)가 이 상태를 <실패로 종결>합니다."
  warn "----------------------------------------------------------------"
fi

# ---- 4) SELinux ----
#   ★ 이 단계를 빠뜨리면 <설정이 맞는데도> 화면이 403 이 되고 API 프록시가 실패한다.
#     Enforcing 이 아니면 조용히 지나가므로, 끄고 검증했다가 켠 장비에서만 터진다.
if command -v getenforce >/dev/null 2>&1 && [[ "$(getenforce 2>/dev/null)" == "Enforcing" ]]; then
  info "[frontend] SELinux Enforcing — 문맥·불리언 설정..."
  # 정적 자산을 httpd 가 읽을 수 있게 문맥 부여
  if command -v semanage >/dev/null 2>&1; then
    semanage fcontext -a -t httpd_sys_content_t "${WEB_DIR}/dist(/.*)?" 2>/dev/null || true
  else
    warn "[frontend] semanage 없음(policycoreutils-python-utils) — 문맥이 재부팅 후 초기화될 수 있습니다."
  fi
  command -v restorecon >/dev/null 2>&1 && restorecon -R "${WEB_DIR}/dist" || true
  # 리버스프록시가 백엔드로 나가려면 이 불리언이 필요하다(기본 off → 프록시 503).
  if command -v setsebool >/dev/null 2>&1; then
    setsebool -P httpd_can_network_connect 1 \
      && ok "[frontend] SELinux httpd_can_network_connect=on" \
      || warn "[frontend] setsebool 실패 — API 프록시가 SELinux 에 막힐 수 있습니다."
  fi
else
  info "[frontend] SELinux 비활성 또는 미탐지 — 문맥 설정 생략"
fi

# ---- 5) 설정 검사 후 기동 ----
httpd -t || die "[frontend] httpd 설정 검사 실패 — ${CONF_DST} 를 확인하세요."
systemctl enable httpd >/dev/null 2>&1 || true
systemctl restart httpd || die "[frontend] httpd 기동 실패 — journalctl -u httpd 를 확인하세요."
ok "[frontend] httpd 기동 완료 (systemctl status httpd)"

info "[frontend] ★ 설정을 바꿀 때는 재빌드·재설치가 아니라 아래 한 줄이면 됩니다:"
info "            ${FE_CONFIG_DST} 편집 → sudo ${FE_CONFIG_RENDERER}"

if [[ "${FE_CONFIG_PENDING:-0}" == "1" ]]; then
  warn "[frontend] ★위 런타임 설정이 아직 비어 있습니다. 아래 두 줄을 끝내야 설치가 완료됩니다:"
  warn "     sudo \$EDITOR ${FE_CONFIG_DST}"
  warn "     sudo ${FE_CONFIG_RENDERER}"
fi

warn "[frontend] ★확인 항목 — 기동 성공만으로는 검증되지 않습니다."
warn "  · 화면 딥링크 새로고침이 404 가 아닌지"
warn "  · 화면에서 API 호출이 백엔드에 닿는지"
warn "  · 대용량 업로드가 통과하는지(웹 서버와 WAS 양쪽 한도를 모두 넘어야 합니다)"
