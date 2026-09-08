#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 11-install-runtimes.sh — [대상 서버 B / ai 역할] 번들 런타임 설치
#   Python + 시스템 RPM(opencv 런타임 의존)
#
#   ★★ 역할 = ai 전용이다 (2026-08-30 서버 2대 분리). 여기서 설치하는 것은 전부
#     ai-server 가 쓰는 것이다 — 번들 파이썬과 opencv 런타임 의존(libGL/glib2).
#     app 역할(프론트+백엔드) 서버에서는 이 단계가 통째로 skip 된다.
#   ★ 웹 서버(httpd)는 여기서 다루지 않는다 — 14-install-frontend.sh 가 RPM 으로 설치한다.
#
#   ★★ ffmpeg 는 여기서 설치하지 않는다 (2026-08-30 사용자 확정, 구속).
#     ffmpeg·ffprobe 는 <서버 A 의 전제조건>이며 관제지원시스템 팀이 설치한다. 이 장비는
#     관제와 공동 배치라 우리가 자동으로 깔면 관제 설치본을 덮어쓸 수 있다.
#     · 부재 검증 : 19-verify-ffmpeg.sh (app 역할, 마지막 단계 — 없으면 die)
#     · 수동 설치 : install-ffmpeg.sh   (사람이 명시적으로 부를 때만)
#     ⚠ 구 동작 폐기(2026-08-30) — "syspkgs/ffmpeg 로컬 저장소에서 ffmpeg 를 자동 설치하고
#       설치 여부를 warn 으로만 알린다". 되살리지 말 것(자동 설치가 관제를 깬다).
#   ★ 자바 런타임은 여기서 설치하지 않는다(2026-08-30 전환) — 백엔드는 <외부 WAS 에 WAR 반입>
#     형상이고(@design DEPLOY-001), 대상 장비의 WAS(JBoss EAP 8.1)가 이미 Java 17 로 돌고 있다.
#     ⚠ 구 동작 폐기(2026-08-30) — "runtimes/jdk 의 Temurin JRE tarball 을 /opt/klid/runtime/jre
#       로 풀고 runtime.env 에 KLID_JAVA 를 기록한다".
#     ⚠ Python 은 ai-server 전용이라 그대로 설치한다 — 함께 걷어내지 말 것.
#
#   타깃 OS = 레드햇 엔터프라이즈 리눅스 8.9 (RHEL 8 계열, x86_64, glibc 2.28, dnf/rpm).
#   ⚠ 구 서술 폐기(2026-08-28) — "Rocky Linux 9 (RHEL 9 계열, glibc 2.34)".
#     - RPM(mesa-libGL 등) : syspkgs/rpm/*.rpm 오프라인 설치(로컬 yum 저장소 방식).
#
#   외부 네트워크 호출 없음. 모든 산출물은 runtimes/, syspkgs/ 번들에서 사용.
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
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"
require_root

# verify_first_tarball <dir> <algo> <expected> <label>
#   설치 직전, 번들 디렉토리의 단일 tar.gz 무결성을 기대 체크섬과 대조(fail-closed).
#   기대값이 비어있으면(미검증) 강한 warn 후 진행. SHA256SUMS 묶음 검증과 독립적.
verify_first_tarball() {
  local dir="$1" algo="$2" expected="$3" label="$4"
  shopt -s nullglob; local t=("${dir}"/*.tar.gz); shopt -u nullglob
  [[ "${#t[@]}" -ge 1 ]] || return 0   # tar.gz 가 없으면 건너뜀
  if [[ "${algo}" == "sha512" ]]; then
    verify_file_sha512 "${t[0]}" "${expected}" "${label}"
  else
    verify_file_sha256 "${t[0]}" "${expected}" "${label}"
  fi
}

: "${KLID_PREFIX:?install.sh 에서 호출되어야 합니다}"

# ---- 역할 게이트 ----
#   ai 역할에서만 돈다. app 서버에는 파이썬도 opencv 의존도 필요 없다.
#   ⚠ 역할 미지정(KLID_ROLE=all)이면 종전대로 실행된다 — 단일 서버 설치 하위호환.
if ! klid_role_has ai; then
  info "[runtime] 역할이 ai 가 아니므로 런타임 설치를 건너뜁니다(KLID_ROLE=${KLID_ROLE:-all})."
  info "          app 서버는 파이썬·opencv 런타임 의존을 쓰지 않습니다."
  info "          (httpd 는 14-install-frontend.sh 가, ffmpeg 는 관제가 설치합니다)"
  exit 0
fi

ONPREM="$(onprem_root)"
RT="${KLID_PREFIX}/runtime"
ensure_dir "${RT}/python"

# extract_single <src_dir> <dest_dir> — src_dir 의 단일 tar.gz 를 dest 로 풀고
#                                       최상위 1단계 디렉토리를 평탄화
extract_tar_flatten() {
  local src_dir="$1" dest="$2"
  shopt -s nullglob
  local tarballs=("${src_dir}"/*.tar.gz)
  shopt -u nullglob
  [[ "${#tarballs[@]}" -ge 1 ]] || die "압축 파일을 찾을 수 없습니다: ${src_dir}/*.tar.gz"
  local tmp; tmp="$(mktemp -d)"
  tar -xzf "${tarballs[0]}" -C "${tmp}"
  # 최상위가 단일 디렉토리면 그 내용을 dest 로, 아니면 그대로 dest 로
  local entries; entries=("${tmp}"/*)
  if [[ "${#entries[@]}" -eq 1 && -d "${entries[0]}" ]]; then
    rm -rf "${dest:?}"/*
    cp -R "${entries[0]}"/. "${dest}/"
  else
    rm -rf "${dest:?}"/*
    cp -R "${tmp}"/. "${dest}/"
  fi
  rm -rf "${tmp}"
}

# ---- 자바 런타임: 설치하지 않는다(WAS 가 제공) ----
#   백엔드는 api.war 로 외부 WAS 에 반입된다. 자바는 그 WAS 의 것을 쓴다.
info "[runtime] 자바 런타임은 설치하지 않습니다 — 백엔드는 외부 WAS(Java 17)에 WAR 로 반입됩니다."

# ---- Python (standalone) ----
info "[runtime] Python 설치 → ${RT}/python"
verify_first_tarball "${ONPREM}/runtimes/python" sha256 "${PYTHON_STANDALONE_SHA256:-}" "CPython ${PYTHON_STANDALONE_VERSION}"
extract_tar_flatten "${ONPREM}/runtimes/python" "${RT}/python"
PYBIN=""
for cand in "${RT}/python/bin/python3.11" "${RT}/python/bin/python3" "${RT}/python/python/bin/python3"; do
  [[ -x "${cand}" ]] && { PYBIN="${cand}"; break; }
done
[[ -n "${PYBIN}" ]] || die "Python 설치 실패: ${RT}/python/bin/python3* 없음"
ok "[runtime] python: $("${PYBIN}" --version 2>&1)"


# ---- 시스템 RPM 오프라인 설치 (opencv 런타임 의존) ----
#   syspkgs/rpm/ : mesa-libGL·libglvnd-glx·glib2·httpd·policycoreutils-python-utils + 전이 의존 전량
#
#   ★ 이 디렉토리는 <로컬 yum 저장소>(repodata 포함)로 반입된다. RPM 파일을 dnf 에 직접
#     넘기지 않는 이유는 common.sh 의 klid_dnf_install_from_bundle 주석 참조 —
#     요약하면 --alldeps 로 받은 기반 패키지(glibc 등)가 이미 설치된 버전과 충돌해
#     <설치가 통째로 실패>하기 때문이다. 저장소로 주면 dnf 가 필요한 것만 고른다.
#   ⚠ 구 방식 폐기(2026-08-30): `dnf install -y --disablerepo='*' <RPM 파일 목록>`.
#
#   ★ mesa-libGL·libglvnd-glx 를 빼지 말 것 — opencv 를 headless 판으로 내려 이 의존을
#     없애려는 시도는 실패한다. supervision·trackers 가 opencv-python(GUI 판)을 직접
#     의존해 되끌어오고, 그 판이 libGL.so.1 을 찾는다(2026-08-30 실측).

# 폐쇄망 타깃에는 배포판 공개키가 없을 수 있다 — 번들 키를 먼저 등록한다.
klid_import_rpm_gpg_keys "${ONPREM}/syspkgs/gpg" || true

info "[runtime] 시스템 RPM 오프라인 설치(syspkgs/rpm 로컬 저장소)"
klid_dnf_install_from_bundle syspkgs "${ONPREM}/syspkgs/rpm" mesa-libGL libglvnd-glx glib2 \
  || warn "[runtime] 시스템 RPM 설치 일부 미해결 — 06-troubleshooting.md 참고"
# ★ httpd·policycoreutils-python-utils 는 14-install-frontend.sh 가 같은 저장소에서 설치한다
#   (웹 서버 설정과 SELinux 문맥 부여를 그쪽이 함께 다루므로 설치 시점을 맞춘다).
#   그래서 syspkgs/rpm 은 <서버 2대 모두>에 필요하다 — 여기서 앞 3개, 14 에서 뒤 2개를 쓴다.

# 런타임 위치 기록(다음 스크립트가 참조)
#   ⚠ KLID_JAVA 는 더 이상 기록하지 않는다(2026-08-30) — 자바는 WAS 소유다.
#     소비처는 13-install-ai-server.sh 하나이며 KLID_PYTHON 만 읽는다.
#   ⚠ KLID_FFMPEG/KLID_FFPROBE 도 기록하지 않는다(2026-08-30) — 이 단계는 ai 역할 전용이고
#     ffmpeg 는 app 서버 축이다. 읽는 곳이 0건이었고, ai 서버에 남기면 "여기에도 ffmpeg 가
#     있다"는 잘못된 신호가 된다. backend 가 쓰는 값은 /etc/klid 설정이 단일 출처다.
{
  echo "KLID_PYTHON=${PYBIN}"
} > "${KLID_PREFIX}/runtime/runtime.env"
ok "[runtime] 런타임 경로 기록: ${KLID_PREFIX}/runtime/runtime.env"
