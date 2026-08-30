#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 50-collect-syspkgs.sh — [빌드머신] 런타임 시스템 의존성 수집 (RHEL 8.9 / el8)
#
#   타깃 OS = 레드햇 엔터프라이즈 리눅스 8.9 (RHEL 8 계열, x86_64, glibc 2.28, dnf/rpm).
#   ⚠ 구 서술 폐기(2026-08-28) — "타깃 OS = Rocky Linux 9 (RHEL 9 계열, glibc 2.34)".
#     el9 RPM 은 RHEL 8 에 설치되지 않는다.
#
# ----------------------------------------------------------------------------
# ★★ ffmpeg 는 <매체에 담되 자동 설치하지 않는다> (2026-08-30 사용자 확정, 구속)
#
#   확정 두 줄:
#     ① ffmpeg·ffprobe 는 관제지원시스템 팀이 <백엔드가 도는 그 서버>에 설치한다.
#        즉 대상 장비의 <전제조건>이며, 우리 설치 스크립트가 자동으로 깔지 않는다.
#     ② 그래도 <혹시 몰라> 예비물로 매체에 함께 담는다. 관제가 설치하지 않은 것으로
#        밝혀졌을 때 <별도 명령>(scripts/install/install-ffmpeg.sh)으로만 설치한다.
#
#   왜 자동 설치를 없앴나 — 이 서버는 관제지원시스템과 <공동 배치>다. 관제가 이미 깔아 둔
#   ffmpeg 를 우리 설치가 덮어쓰면 <관제 기능이 깨진다>. 그 위험이 "우리가 자동으로 깔면
#   편하다"보다 크다. 그래서 설치는 사람이 명시적으로 부르는 별도 명령으로만 일어난다.
#
#   ⚠ 라이선스: 매체에 담으므로 <재배포가 맞다>. el8 RPM Fusion 의 ffmpeg 는 GPLv3+ 이고
#     폐쇄망이라 "고객이 인터넷에서 받으면 된다"가 성립하지 않으므로, 대응 소스(SRPM)를
#     함께 동봉한다(아래 C). 실무상 문제되지 않는다는 판단으로 <반입 자체는 확정>이며,
#     구 검토안 두 가지 — "수집을 아예 끈다" · "optional/ 로 빼 매체에서 분리한다" — 는
#     모두 <폐기>다. 되살리지 말 것.
#
#   토글(기본은 수집 포함):
#     SKIP_FFMPEG=1 ./scripts/package.sh   ffmpeg·SRPM 수집만 생략(다른 RPM 은 그대로)
#     ⚠ 끄면 매체에 예비물이 없어져 별도 설치 명령이 설치할 것을 못 찾는다. 그때는
#       관제 설치본이 <반드시> 있어야 한다.
# ----------------------------------------------------------------------------
#
#   런타임 의존성:
#     backend  : ffmpeg, ffprobe → 대상 장비 전제조건(관제 설치). 설치 스크립트는 <검증만> 한다
#                (12-install-backend.sh 끝단에서 부재 시 die).
#     ai-server: libGL.so.1 (opencv-python import) + libglib2.0 계열
#                → el8 AppStream 의 mesa-libGL / libglvnd-glx / glib2 RPM 으로 제공.
#                ⚠ opencv 를 headless 판으로 내려 이 둘을 빼려는 시도는 <실패한다> —
#                  supervision·trackers 가 opencv-python(GUI 판)을 직접 의존해 되끌어온다(실측).
#                  libGL 의존이 그대로 남으므로 이 RPM 들을 빼지 말 것.
#
#   3가지 수집물(디렉토리를 나눠 라이선스·출처를 분리 추적한다):
#     A) ffmpeg RPM (+전이 의존) → syspkgs/ffmpeg/     (GPLv3+, Vendor: RPM Fusion)
#     B) 그 외 시스템 RPM        → syspkgs/rpm/        (배포판 base/AppStream)
#     C) GPL 대응 소스(SRPM)     → syspkgs/ffmpeg-src/ (설치 대상 아님 — 라이선스 의무 충족물)
#
#   ★ C 를 왜 넣나: el8 RPM Fusion 의 ffmpeg 는 <GPLv3+> 다(el7 판 3.4.13 의 GPLv2+ 와 다르다
#     — 실측). 그 바이너리를 매체에 담아 고객에게 반입하는 것은 <재배포>이므로 GPL 의
#     "대응 소스 제공" 의무가 발생한다.
#   ★ 대상 판정 = <RPM Fusion 유래> ∩ <(L)GPL 라이선스>. 배포판 기본 리포(BaseOS/AppStream/
#     PowerTools)에서 온 의존성은 대상이 아니다 — 고객이 이미 그 배포판 사용권을 갖고 있고
#     우리가 재배포하는 주체가 아니기 때문이다.
#   ★ 같은 src.rpm 에서 나오는 서브패키지는 <한 번만> 받는다(2026-08-30 실측: ffmpeg·ffmpeg-libs·
#     libavdevice 셋 다 sourcerpm 이 ffmpeg-4.4.8-1.el8.src.rpm 이다).
#   ★ 디렉토리를 분리하는 이유: syspkgs/ffmpeg/ 는 <설치용 로컬 yum 저장소>다. 같은 곳에 src.rpm
#     을 두면 createrepo_c 가 색인하고 dnf 가 집어 예기치 않게 동작한다. ffmpeg-src/ 는 설치
#     스크립트가 건드리지 않는다(무결성 검증만 install.sh 가 한다).
#
#   ★ 수집 환경: dnf 가 있으면 네이티브로, 없으면(mac 등) docker 로 el8 컨테이너를 띄워 수집한다.
#     둘 다 없으면 graceful SKIP + 수동 수집 안내를 남긴다.
#   ★ 컨테이너는 반드시 --platform linux/amd64 로 띄운다 — Apple Silicon 에서 생략하면
#     aarch64 RPM 을 받아 놓고 "성공"으로 끝나는 조용한 실패가 된다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"

ONPREM="$(onprem_root)"
RPM_OUT="${ONPREM}/syspkgs/rpm"
FFMPEG_OUT="${ONPREM}/syspkgs/ffmpeg"
GPG_OUT="${ONPREM}/syspkgs/gpg"
# GPL 대응 소스(SRPM) — 설치 대상이 아니라 라이선스 의무 충족용 동봉물이다(헤더 C 참조).
FFMPEG_SRC_OUT="${ONPREM}/syspkgs/ffmpeg-src"
ensure_dir "${RPM_OUT}" "${FFMPEG_OUT}" "${GPG_OUT}" "${FFMPEG_SRC_OUT}"

if [[ "${SKIP_SYSPKGS:-0}" == "1" ]]; then
  info "[syspkgs] SKIP_SYSPKGS=1 — 시스템 의존성 수집 생략"
  exit 0
fi

# ---- ffmpeg 수집 여부(기본 포함) ----
#   ★ 기본이 1 이므로 아래 ffmpeg·SRPM 검증은 <사실상 무조건>이다. 이 변수는 "매체에서
#     빼고 싶을 때"만 쓰는 탈출구이며, 껐을 때 SRPM 0건이 실패로 잡히지 않게 하는 용도다
#     (수집하지 않은 것을 수집 실패로 오인하면 패키징이 통째로 죽는다).
COLLECT_FFMPEG=1
if [[ "${SKIP_FFMPEG:-0}" == "1" ]]; then
  COLLECT_FFMPEG=0
  warn "[syspkgs] SKIP_FFMPEG=1 — ffmpeg 예비물과 GPL 대응 소스를 매체에 담지 않습니다."
  warn "          이 매체로는 scripts/install/install-ffmpeg.sh 를 쓸 수 없습니다."
  warn "          대상 장비에 관제지원시스템이 설치한 ffmpeg 가 <반드시> 있어야 합니다."
fi

# el8 AppStream 패키지:
#   - mesa-libGL    : libGL.so.1 제공(opencv import)
#   - libglvnd-glx  : GLX 디스패치(mesa-libGL 의존 보강)
#   - glib2         : libglib-2.0.so.0 (opencv/그래픽 스택 의존)
# httpd — 프론트엔드 정적 서빙 + API 리버스프록시(관제지원시스템과 동일 사양).
#   mod_proxy·mod_headers·mod_deflate 는 httpd 본체 패키지에 포함된다.
#
# policycoreutils-python-utils — semanage 제공. SELinux Enforcing 장비에서 문서 루트에
#   httpd 읽기 문맥을 <영구> 부여하는 데 필요하다. restorecon 만 쓰면 정책에 규칙이 남지
#   않아 재라벨링·재부팅 후 초기화되고, 그때 화면이 403 으로 돌아간다.
#   ★ 폐쇄망에서는 없으면 설치할 방법이 없다 — 여기서 함께 받아 두지 않으면 설치 스크립트가
#     "semanage 없음" 을 경고해도 운영자가 할 수 있는 일이 없다.
#
# ⚠ 이 목록은 <서버 2대에 모두> 걸쳐 있다 — 앞 3개는 ai 서버(opencv), 뒤 2개는 app 서버(httpd).
#   역할별로 쪼개지 않는 이유는 매체를 하나로 두고 <설치 시 역할만 고르기> 때문이다.
RPM_PKGS=(mesa-libGL libglvnd-glx glib2 httpd policycoreutils-python-utils)

# ----------------------------------------------------------------------------
# 수집 명령 본문 — 네이티브 dnf / 컨테이너 양쪽이 같은 스크립트를 실행한다.
#   $1 = ffmpeg 출력, $2 = 일반 RPM 출력, $3 = GPG 키 출력, $4 = SRPM 출력 (실행 환경 기준 경로)
#
# ★ --resolve --alldeps 로 <전이 의존성 전량>을 받고, 설치는 <로컬 저장소 방식>으로 한다
#   (2026-08-30 실측 확정. 두 축을 한 세트로 이해할 것 — 하나만 취하면 깨진다):
#     · --alldeps 를 빼면 "수집 컨테이너에 이미 설치된 패키지"가 목록에서 빠진다. 타깃의 설치
#       상태는 컨테이너와 다르므로, 타깃에 없는 의존이 번들에서 누락돼 폐쇄망에서 설치가 멈춘다.
#     · 반대로 --alldeps 로 받은 세트를 `dnf install <파일들>` 로 직접 넘기면 glibc·coreutils·rpm
#       같은 기반 패키지가 이미 설치된 버전과 충돌해 <설치가 통째로 실패>한다(실측).
#     · 해법은 createrepo_c 로 <로컬 yum 저장소>를 만들어 주는 것이다. 그러면 dnf 가 스스로
#       의존성을 해소해 "필요한 것만" 설치한다 — 실측: 261개 반입 → 105개만 설치, glibc 무변경.
#   ⚠ 구 방침 폐기(2026-08-30): "--alldeps 는 쓰지 않는다"(충돌을 피하려 완전성을 버린 것이라
#     폐쇄망 전제에서 오히려 위험했다. 되돌리지 말 것).
# ★ --archlist=x86_64,noarch 로 i686 멀티리브를 제외한다(미지정 시 35개/15MB 가 헛되이 붙는다).
# ----------------------------------------------------------------------------
_collect_body() {
  cat <<'BODY'
set -euo pipefail
FF_OUT="$1"; RPM_OUT="$2"; GPG_OUT="$3"; FFSRC_OUT="$4"; shift 4
mkdir -p "${FF_OUT}" "${RPM_OUT}" "${GPG_OUT}" "${FFSRC_OUT}"
# gnupg2 — 아래 커버리지 검증이 공개키의 키 ID 를 읽는 데 쓴다(없으면 검증 자체가 불가).
dnf install -y dnf-plugins-core createrepo_c gnupg2 >/dev/null

if [ "${COLLECT_FFMPEG}" = "1" ]; then
  # ffmpeg 는 base/AppStream 에 없다 — EPEL + RPM Fusion 을 추가하고 PowerTools(CRB)를 켠다.
  dnf install -y "${EPEL_RELEASE_RPM_URL}" >/dev/null
  dnf install -y "${RPMFUSION_FREE_RELEASE_RPM_URL}" >/dev/null
  # PowerTools(Rocky/Alma) 또는 CRB(RHEL) — libSDL2 등 ffmpeg 의존 제공. 이름이 다를 수 있어 순차 시도.
  dnf config-manager --set-enabled "${EL8_CRB_REPO_ID}" >/dev/null 2>&1 \
    || dnf config-manager --set-enabled crb >/dev/null 2>&1 \
    || dnf config-manager --set-enabled codeready-builder-for-rhel-8-x86_64-rpms >/dev/null 2>&1 \
    || echo "WARN: PowerTools/CRB 활성화 실패 — ffmpeg 의존(libSDL2) 해소가 실패할 수 있습니다." >&2

  echo "[collect] ffmpeg RPM 수집(전이 의존 전량) → ${FF_OUT}"
  dnf download --resolve --alldeps --archlist=x86_64,noarch --downloaddir "${FF_OUT}" "${FFMPEG_RPM_PKGS[@]}"

  # ---- GPL 대응 소스(SRPM) 수집 — 설치 대상 아님, 라이선스 의무 충족물 ----
  #   대상 판정 = <RPM Fusion 유래> ∩ <(L)GPL 라이선스>. 배포판 기본 리포(BaseOS/AppStream/
  #   PowerTools)·EPEL 이 아니라 <우리가 재배포하는 제3자 저장소 바이너리>만 고른다.
  #   LGPL 도 대응 소스 제공 의무가 있으므로 함께 잡는다(*GPL* 매칭).
  #   같은 src.rpm 을 공유하는 서브패키지는 sourcerpm 으로 중복 제거한다.
  echo "[collect] GPL 대응 소스(SRPM) 대상 판정 → ${FFSRC_OUT}"
  _seen_srpm=""
  _src_names=()
  for _f in "${FF_OUT}"/*.rpm; do
    [ -e "${_f}" ] || continue
    _meta="$(rpm -qp --nosignature --qf '%{vendor}|%{license}|%{name}|%{sourcerpm}' "${_f}" 2>/dev/null)" || continue
    _vendor="${_meta%%|*}"; _rest="${_meta#*|}"
    _lic="${_rest%%|*}";    _rest="${_rest#*|}"
    _name="${_rest%%|*}";   _srpm="${_rest##*|}"
    case "${_vendor}" in *"RPM Fusion"*|*rpmfusion*) ;; *) continue ;; esac
    case "${_lic}"    in *GPL*)                      ;; *) continue ;; esac
    case " ${_seen_srpm} " in *" ${_srpm} "*) continue ;; esac
    _seen_srpm="${_seen_srpm} ${_srpm}"
    _src_names+=("${_name}")
    echo "  대상: ${_name} [${_lic}] ← ${_srpm}"
  done
  if [ "${#_src_names[@]}" -gt 0 ]; then
    # --source 는 dnf 가 *-source 리포를 자동으로 켜서 받는다(타깃에는 필요 없다).
    dnf download --source --downloaddir "${FFSRC_OUT}" "${_src_names[@]}"
    ls -1 "${FFSRC_OUT}" | sed 's/^/  src: /'
  else
    echo "WARN: RPM Fusion 유래 (L)GPL 패키지를 찾지 못했습니다 — SRPM 동봉 0건." >&2
    echo "      ffmpeg 수집 자체가 실패했거나 리포 구성이 바뀐 신호입니다." >&2
  fi
else
  echo "[collect] SKIP_FFMPEG=1 — ffmpeg·SRPM 수집을 생략합니다."
fi

echo "[collect] 시스템 RPM 수집(전이 의존 전량) → ${RPM_OUT}"
dnf download --resolve --alldeps --archlist=x86_64,noarch --downloaddir "${RPM_OUT}" "${RPM_PKGS[@]}"

# 로컬 yum 저장소 메타데이터 생성 — 타깃은 이 repodata 로 <의존성을 스스로 해소>한다.
#   ★ 이것이 --alldeps 를 안전하게 만드는 핵심이다. RPM 파일을 dnf 에 직접 넘기면
#     이미 설치된 기반 패키지(glibc·coreutils·rpm)와 버전이 충돌해 설치가 통째로 실패하지만,
#     저장소로 주면 dnf 가 <실제로 필요한 것만> 골라 설치한다(2026-08-30 실측: 261개 반입 → 105개 설치).
echo "[collect] 로컬 저장소 메타데이터 생성(createrepo_c)"
if [ "${COLLECT_FFMPEG}" = "1" ]; then createrepo_c --quiet "${FF_OUT}"; fi
createrepo_c --quiet "${RPM_OUT}"

# GPG 공개키 반입 — 폐쇄망 타깃에는 EPEL/RPM Fusion 키가 없어 gpgcheck 가 켜져 있으면
#   서명 검증이 불가해 설치가 거부된다. 키 파일을 함께 반입해 설치 스크립트가 rpm --import 한다.
# ---- RPM GPG 공개키 반입 ----
#   ★★ 이름 글롭(RPM-GPG-KEY-*)으로 복사하지 않는다 — 이름 규약은 배포처마다 다르다.
#     실제로 PGDG 는 `PGDG-RPM-GPG-KEY-RHEL` 을 쓰고, 그래서 55 단계가 그 키를 한 건도
#     담지 못한 채 "성공"으로 끝났다(2026-08-30). 여기(EPEL·RPM Fusion)는 우연히 이름이
#     맞아 통과했을 뿐 <같은 구조적 사각>이다. 판정을 파일 내용으로 바꾼다.
_klid_collect_gpg_keys /etc/pki/rpm-gpg "${GPG_OUT}"

# ---- 수집 후 검증: 받은 RPM 의 서명 키를 매체가 실제로 갖고 있는가 ----
#   ★ 키를 "복사 시도"만 하고 맞는지 보지 않으면, 리포가 하나 늘 때마다 같은 사고가 되살아난다.
#     여기서 RPM 전량의 서명 키 ID 를 뽑아 매체의 공개키와 대조한다(미보유면 exit 90).
if [ "${COLLECT_FFMPEG}" = "1" ]; then
  _klid_verify_gpg_coverage "${GPG_OUT}" syspkgs "${RPM_OUT}" "${FF_OUT}"
else
  _klid_verify_gpg_coverage "${GPG_OUT}" syspkgs "${RPM_OUT}"
fi
BODY
}

# 컨테이너/네이티브 공통으로 넘길 변수 정의(문자열로 직렬화).
_collect_env() {
  printf 'COLLECT_FFMPEG=%q\n' "${COLLECT_FFMPEG}"
  printf 'EPEL_RELEASE_RPM_URL=%q\n' "${EPEL_RELEASE_RPM_URL}"
  printf 'RPMFUSION_FREE_RELEASE_RPM_URL=%q\n' "${RPMFUSION_FREE_RELEASE_RPM_URL}"
  printf 'EL8_CRB_REPO_ID=%q\n' "${EL8_CRB_REPO_ID}"
  printf 'FFMPEG_RPM_PKGS=(%s)\n' "${FFMPEG_RPM_PKGS[*]}"
  printf 'RPM_PKGS=(%s)\n' "${RPM_PKGS[*]}"
}

# 수집 결과 검증 — 요청 패키지가 실제로 받혔는지 이름으로 확인(개수 비교보다 정확).
_verify_collected() {
  local dir="$1"; shift
  local pkg
  for pkg in "$@"; do
    ls "${dir}"/"${pkg}"-[0-9]*.rpm >/dev/null 2>&1 \
      || { warn "[syspkgs] 핵심 RPM 누락: ${pkg} (in ${dir}) — 불완전 세트로 간주합니다."; return 1; }
  done
  # 아키텍처 오염 검사 — --platform 미지정 등으로 타깃과 다른 아키텍처가 섞이면 fail.
  local bad
  bad="$(ls -1 "${dir}"/*.rpm 2>/dev/null | grep -cE '\.(aarch64|i686|armv7hl|ppc64le|s390x)\.rpm$' || true)"
  if [[ "${bad}" -gt 0 ]]; then
    warn "[syspkgs] 타깃(x86_64)과 다른 아키텍처 RPM ${bad} 개가 섞였습니다: ${dir}"
    warn "          컨테이너에 --platform ${EL8_BUILDER_PLATFORM} 를 지정했는지 확인하세요."
    return 1
  fi
  return 0
}

# ★ rc 90 = GPG 키 커버리지 실패(공유 조각). "dnf 가 없어 못 받았다"와 <구분해서> 보고한다.
GPG_COVERAGE_FAIL=0

collect_native() {
  command -v dnf >/dev/null 2>&1 || return 1
  info "[syspkgs] 네이티브 dnf 로 수집합니다(빌드머신이 el8 계열이라고 가정)."
  local rc=0
  bash -c "$( _collect_env; klid_rpm_gpg_snippet; _collect_body )" _ \
    "${FFMPEG_OUT}" "${RPM_OUT}" "${GPG_OUT}" "${FFMPEG_SRC_OUT}" || rc=$?
  if [[ "${rc}" -eq 90 ]]; then GPG_COVERAGE_FAIL=1; return 1; fi
  [[ "${rc}" -eq 0 ]] \
    || { warn "[syspkgs] dnf 수집 실패 — 부분 수집을 성공으로 위장하지 않고 폴백/안내로 전환합니다."; return 1; }
  return 0
}

collect_docker() {
  command -v docker >/dev/null 2>&1 || return 1
  docker info >/dev/null 2>&1 || { warn "[syspkgs] docker 데몬이 응답하지 않습니다 — 컨테이너 수집 불가."; return 1; }
  info "[syspkgs] dnf 가 없어 ${EL8_BUILDER_IMAGE} 컨테이너로 수집합니다(--platform ${EL8_BUILDER_PLATFORM})."
  local rc=0
  docker run --rm \
    --platform "${EL8_BUILDER_PLATFORM}" \
    -v "${ONPREM}/syspkgs:/out" \
    "${EL8_BUILDER_IMAGE}" \
    bash -c "$( _collect_env; klid_rpm_gpg_snippet; _collect_body )" _ \
    /out/ffmpeg /out/rpm /out/gpg /out/ffmpeg-src || rc=$?
  if [[ "${rc}" -eq 90 ]]; then GPG_COVERAGE_FAIL=1; return 1; fi
  [[ "${rc}" -eq 0 ]] \
    || { warn "[syspkgs] 컨테이너 수집 실패 — 부분 수집을 성공으로 위장하지 않고 폴백/안내로 전환합니다."; return 1; }
  return 0
}

# ----------------------------------------------------------------------------
# 실행: 네이티브 → 컨테이너 → graceful SKIP 순서
# ----------------------------------------------------------------------------
_collected=0
if collect_native; then
  _collected=1
elif [[ "${GPG_COVERAGE_FAIL}" -eq 1 ]]; then
  :
elif collect_docker; then
  _collected=1
fi

# ★ graceful SKIP 으로 흘리지 않는다 — RPM 은 받았는데 <그것을 검증할 공개키가 매체에 없다>는
#   뜻이라, 이 매체로는 타깃 설치가 반드시 실패한다.
if [[ "${GPG_COVERAGE_FAIL}" -eq 1 ]]; then
  die "[syspkgs] GPG 키 커버리지 검증 실패 — 받은 RPM 의 서명 키가 매체에 없습니다.
       리포트: ${GPG_OUT}/KEY-COVERAGE-syspkgs.txt (MISSING 행)
       이대로 매체를 만들면 타깃에서 'GPG check FAILED' 로 설치가 죽습니다.
       (임시 우회 KLID_RPM_GPGCHECK=0 은 <해결이 아니라> 서명 검증을 끄는 것입니다.)"
fi

if [[ "${_collected}" -eq 1 ]]; then
  _ok=1
  _verify_collected "${RPM_OUT}" "${RPM_PKGS[@]}" || _ok=0
  # 로컬 저장소 메타데이터가 없으면 타깃에서 의존성 해소가 불가능하다(설치 실패).
  [[ -f "${RPM_OUT}/repodata/repomd.xml" ]] \
    || { warn "[syspkgs] 로컬 저장소 메타데이터 누락: ${RPM_OUT}/repodata/repomd.xml"; _ok=0; }
  # GPG 키가 없으면 타깃에서 gpgcheck=1 설치가 거부된다.
  # ★ 이름이 아니라 <내용>으로 센다(klid_gpg_key_files) — PGDG 처럼 이름 규약이 다른 키를
  #   "없는 것"으로 오판하지 않기 위해서다.
  if [[ -z "$(klid_gpg_key_files "${GPG_OUT}")" ]]; then
    warn "[syspkgs] RPM GPG 공개키를 수집하지 못했습니다: ${GPG_OUT}"
    warn "          타깃에서 서명 검증이 불가해 설치가 거부될 수 있습니다(KLID_RPM_GPGCHECK=0 로 우회 가능)."
    _ok=0
  fi

  # ---- ffmpeg 축 검증(기본 경로에서는 항상 돈다 — COLLECT_FFMPEG 기본값이 1) ----
  if [[ "${COLLECT_FFMPEG}" -eq 1 ]]; then
    _verify_collected "${FFMPEG_OUT}" "${FFMPEG_RPM_PKGS[@]}" || _ok=0
    [[ -f "${FFMPEG_OUT}/repodata/repomd.xml" ]] \
      || { warn "[syspkgs] 로컬 저장소 메타데이터 누락: ${FFMPEG_OUT}/repodata/repomd.xml"; _ok=0; }
    # GPL 대응 소스(SRPM)가 없으면 재배포 의무를 못 채운다 — 수집 실패로 간주한다.
    #   ⚠ 설치에는 쓰이지 않으므로 "설치가 되니 괜찮다"로 넘기지 말 것. 매체를 만든 뒤에는
    #     되돌릴 수 없는 종류의 누락이다.
    if ! ls "${FFMPEG_SRC_OUT}"/*.src.rpm >/dev/null 2>&1; then
      warn "[syspkgs] GPL 대응 소스(SRPM)를 수집하지 못했습니다: ${FFMPEG_SRC_OUT}"
      warn "          el8 RPM Fusion ffmpeg 는 GPLv3+ 라 바이너리 반입 시 대응 소스 동봉이 필요합니다."
      _ok=0
    fi
  fi

  if [[ "${_ok}" -eq 1 ]]; then
    sha256_write "${RPM_OUT}"
    sha256_write "${GPG_OUT}"
    if [[ "${COLLECT_FFMPEG}" -eq 1 ]]; then
      sha256_write "${FFMPEG_OUT}"
      sha256_write "${FFMPEG_SRC_OUT}"
      ok "[syspkgs] 수집 완료 — ffmpeg $(ls -1 "${FFMPEG_OUT}"/*.rpm 2>/dev/null | wc -l | tr -d ' ') 개 / 시스템 RPM $(ls -1 "${RPM_OUT}"/*.rpm 2>/dev/null | wc -l | tr -d ' ') 개 / GPL 대응 소스 $(ls -1 "${FFMPEG_SRC_OUT}"/*.src.rpm 2>/dev/null | wc -l | tr -d ' ') 개($(du -sh "${FFMPEG_SRC_OUT}" 2>/dev/null | cut -f1))"
      info "[syspkgs] ★ ffmpeg 는 <자동 설치되지 않는다> — 관제 설치본을 덮어쓰지 않기 위해서다."
      info "          필요할 때만: sudo ./scripts/install/install-ffmpeg.sh"
    else
      ok "[syspkgs] 수집 완료 — 시스템 RPM $(ls -1 "${RPM_OUT}"/*.rpm 2>/dev/null | wc -l | tr -d ' ') 개 (SKIP_FFMPEG=1 — ffmpeg 미포함)"
    fi
    exit 0
  fi
  warn "[syspkgs] 수집 결과 검증 실패 — 아래 수동 수집 안내를 따르세요."
fi

# ----------------------------------------------------------------------------
# graceful SKIP — dnf/docker 둘 다 없거나 수집이 불완전한 경우
# ----------------------------------------------------------------------------
warn "[syspkgs] el8 RPM 을 수집하지 못했습니다(dnf/docker 부재 또는 수집 실패) — graceful SKIP."
warn "  타깃(RHEL 8.9, x86_64)용 RPM 은 el8 컨테이너에서 수집해야 합니다. 안내를 남깁니다:"
warn "    ${RPM_OUT}/README-collect-on-el8.txt"

cat > "${RPM_OUT}/README-collect-on-el8.txt" <<TXT
이 빌드머신에서 el8 RPM 을 수집하지 못했습니다(dnf/docker 부재 또는 수집 실패).

타깃 OS = ${TARGET_OS_LABEL}
  ⚠ 구 서술 폐기(2026-08-28): "Rocky Linux 9 / el9 / glibc 2.34". el9 RPM 은 RHEL 8 에 설치되지 않습니다.

아래처럼 el8 컨테이너에서 직접 수집하세요(deploy/onprem 에서 실행):

  docker run --rm --platform ${EL8_BUILDER_PLATFORM} -v "\$PWD/syspkgs:/out" ${EL8_BUILDER_IMAGE} bash -c '
    dnf install -y dnf-plugins-core &&
    dnf install -y ${EPEL_RELEASE_RPM_URL} &&
    dnf install -y ${RPMFUSION_FREE_RELEASE_RPM_URL} &&
    dnf config-manager --set-enabled ${EL8_CRB_REPO_ID} &&
    dnf install -y createrepo_c &&
    dnf download --resolve --alldeps --archlist=x86_64,noarch --downloaddir /out/ffmpeg ${FFMPEG_RPM_PKGS[*]} &&
    dnf download --resolve --alldeps --archlist=x86_64,noarch --downloaddir /out/rpm ${RPM_PKGS[*]} &&
    mkdir -p /out/ffmpeg-src &&
    dnf download --source --downloaddir /out/ffmpeg-src ffmpeg x264-libs x265-libs &&
    createrepo_c /out/ffmpeg && createrepo_c /out/rpm &&
    mkdir -p /out/gpg && cp /etc/pki/rpm-gpg/*GPG-KEY* /out/gpg/
  '

수집 후 체크섬을 기록하세요:
  ( cd syspkgs/ffmpeg     && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )
  ( cd syspkgs/rpm        && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )
  ( cd syspkgs/ffmpeg-src && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )
  ( cd syspkgs/gpg        && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )

수집 대상:
  A) ffmpeg (+전이 의존) → syspkgs/ffmpeg/     [서버 A(백엔드) 전용]
     - backend FFmpegStep(프레임 추출·duration) 이 /usr/bin/ffmpeg, /usr/bin/ffprobe 를 호출합니다.
     - ffmpeg 는 base/AppStream 에 없어 EPEL + RPM Fusion + PowerTools(CRB) 가 필요합니다.
       RHEL 8 에서 PowerTools 의 동등 리포는 codeready-builder-for-rhel-8-x86_64-rpms 입니다.
     - ★ 이 예비물은 <자동으로 설치되지 않습니다>. 대상 장비의 ffmpeg 는 관제지원시스템 팀이
       설치하는 것이 기본이고, 우리 설치는 부재를 검증만 합니다. 관제가 설치하지 않은 것으로
       확인되면 그때 sudo ./scripts/install/install-ffmpeg.sh 를 <사람이> 실행합니다.
  C) GPL 대응 소스(SRPM) → syspkgs/ffmpeg-src/
     - <설치 대상이 아닙니다>. el8 RPM Fusion 의 ffmpeg 는 GPLv3+ 라 바이너리를 매체로
       반입(=재배포)하면 대응 소스 제공 의무가 발생합니다. 폐쇄망이라 "고객이 인터넷에서
       받는다"가 성립하지 않으므로 함께 동봉합니다.
     - 대상은 <RPM Fusion 유래 (L)GPL 패키지>뿐입니다. 배포판 기본 리포(BaseOS/AppStream/
       PowerTools)에서 온 의존성은 고객이 이미 그 배포판 사용권을 가지므로 대상이 아닙니다.
     - ffmpeg·ffmpeg-libs·libavdevice 는 같은 src.rpm(ffmpeg-*.src.rpm)이라 한 번만 받습니다.

  B) ${RPM_PKGS[*]} → syspkgs/rpm/
     - mesa-libGL   : libGL.so.1 (opencv import)                       [서버 B(ai)]
     - libglvnd-glx : GLX 디스패치                                      [서버 B(ai)]
     - glib2        : libglib-2.0.so.0                                  [서버 B(ai)]
     - httpd        : 프론트엔드 정적 서빙 + API 리버스프록시              [서버 A(app)]
     - policycoreutils-python-utils : semanage (SELinux 영구 문맥 부여)   [서버 A(app)]

주의:
  - --platform ${EL8_BUILDER_PLATFORM} 를 반드시 지정하세요. Apple Silicon 에서 빠뜨리면
    aarch64 RPM 을 받아 놓고 "성공"으로 끝나는 조용한 실패가 됩니다.
  - --alldeps 와 createrepo_c 는 <한 세트>입니다. --alldeps 없이 받으면 타깃에 없는 의존이
    누락되고, createrepo_c 없이 RPM 파일을 직접 dnf 에 넘기면 기반 패키지 충돌로 설치가
    통째로 실패합니다. 로컬 저장소로 주면 dnf 가 필요한 것만 골라 설치합니다(2026-08-30 실측).
  - syspkgs/gpg/ 의 공개키(RPM-GPG-KEY-* / PGDG-RPM-GPG-KEY-* 등 <이름은 리포마다 다르다>)도
    함께 반입해야 타깃에서 서명 검증(gpgcheck=1)이 통과합니다.
TXT
