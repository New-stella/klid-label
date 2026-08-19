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

DIST_SRC="${ONPREM}/artifacts/frontend/dist"
[[ -d "${DIST_SRC}" ]] || die "frontend dist 없음: ${DIST_SRC} (빌드머신에서 package.sh 를 실행했나요?)"

# ---- 1) 정적 자산 배치 ----
info "[frontend] 정적 자산 배치..."
rm -rf "${WEB_DIR}/dist"
cp -R "${DIST_SRC}" "${WEB_DIR}/dist"
ok "[frontend] dist: ${WEB_DIR}/dist"

# ---- 2) httpd 오프라인 설치 ----
if command -v httpd >/dev/null 2>&1; then
  ok "[frontend] httpd 이미 설치됨: $(httpd -v 2>&1 | head -n1)"
else
  rpms=("${ONPREM}"/syspkgs/rpm/httpd*.rpm)
  if [[ -e "${rpms[0]}" ]]; then
    info "[frontend] httpd 오프라인 설치(syspkgs/rpm)..."
    # 전이 의존성까지 함께 받아 뒀으므로 디렉터리 전체를 대상으로 준다.
    if ! dnf install -y --disablerepo='*' --setopt=gpgcheck=0 "${ONPREM}"/syspkgs/rpm/*.rpm; then
      warn "[frontend] dnf 설치 실패 — rpm -Uvh 폴백을 시도합니다(의존성 미해소 시 실패)."
      rpm -Uvh --replacepkgs "${ONPREM}"/syspkgs/rpm/*.rpm \
        || die "[frontend] httpd 설치 실패 — 06-troubleshooting.md 참고"
    fi
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

warn "[frontend] ★확인 항목 — 기동 성공만으로는 검증되지 않습니다."
warn "  · 화면 딥링크 새로고침이 404 가 아닌지"
warn "  · 화면에서 API 호출이 백엔드에 닿는지"
warn "  · 대용량 업로드가 통과하는지(웹 서버와 WAS 양쪽 한도를 모두 넘어야 합니다)"
