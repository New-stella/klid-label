# 03. [대상 서버] 오프라인 설치

폐쇄망 **Rocky Linux 9** 대상 서버에서 root 로 실행한다. **외부 네트워크 호출이 전혀 없다**(모든 의존성은 번들).

## 실행

```bash
cd deploy/onprem
sudo ./scripts/install.sh
```

옵션:

```bash
# DB 자동 생성 단계 생략(DBA 가 이미 준비한 경우)
sudo SKIP_DB_INIT=1 ./scripts/install.sh

# 기존 nginx 사용(Caddy 대신 nginx.conf 템플릿만 배치)
sudo USE_NGINX=1 ./scripts/install.sh

# 설치 경로/사용자 변경(기본 /opt/klid, klid)
sudo KLID_PREFIX=/opt/klid KLID_USER=klid ./scripts/install.sh
```

## 단계별 동작

| 단계 | 스크립트 | 동작 |
|------|----------|------|
| 0 | `install.sh` | `klid` 사용자/그룹 + 디렉토리 생성, SHA256 무결성 검증 |
| 1 | `install/11-install-runtimes.sh` | JRE/Python/Caddy + **ffmpeg 정적** 설치 + **RPM**(mesa-libGL/glib2) 오프라인 설치 |
| 2 | `install/12-install-backend.sh` | jar 배치 + `backend.env` + systemd 유닛 |
| 3 | `install/13-install-ai-server.sh` | venv + `pip --no-index` 설치 + 모델 배치 + 유닛 |
| 4 | `install/14-install-frontend.sh` | dist 배치 + Caddyfile + 유닛 (+ 80포트 setcap) |
| 5 | `install/15-init-db.sh` | (옵션) DB/유저 생성 안내 또는 수행 |

설치는 **멱등**하다(재실행 안전). 이미 존재하는 `*.env` 는 덮어쓰지 않아 사용자 편집을 보존한다.

## 오프라인 설치 보장

- ai-server: `pip install --no-index --find-links vendor/wheels` + sam2 로컬 소스. PyPI/인터넷 접근 0.
- 런타임: 번들 tar.gz 압축 해제만.
- ffmpeg: 번들 정적 tarball 을 `/opt/klid/runtime/ffmpeg/bin/` 로 풀어 배치(인터넷 미접근).
- RPM: `dnf install -y --disablerepo='*' syspkgs/rpm/*.rpm`(폴백 `rpm -Uvh --replacepkgs`)로 로컬 설치.

## 설치 후 즉시 할 일

1. `/etc/klid/backend.env` 편집 — DB 비밀번호, JWT_SECRET, STREAM_SIGN_SECRET, webhook HMAC 등 (04 참고).
2. `/etc/klid/ai-server.env` 편집 — 보통 기본값으로 충분(yolox CPU).
3. DB 준비 확인 — `15-init-db.sh` 출력의 DDL 또는 DBA 준비 결과.
4. 05-run-verify.md 로 기동·검증.

## 시스템 의존성이 번들에 없을 때 (수동 보강)

정상 패키지라면 ffmpeg(정적)·RPM 이 번들되어 있어 자동 설치된다. 번들이 비어 install 이 경고만 내면:

- **ffmpeg 누락**: 빌드머신에서 `50-collect-syspkgs.sh` 를 다시 실행해 `syspkgs/ffmpeg/*.tar.xz` 를 채운다.
  급하면 대상에 정적 바이너리를 `/opt/klid/runtime/ffmpeg/bin/{ffmpeg,ffprobe}`(chmod +x)로 직접 배치하거나,
  `backend.env` 의 `FFMPEG_BIN`/`FFPROBE_BIN` 에 절대경로를 지정한다.
- **RPM(libGL/glib2) 누락**: 사내 미러가 있으면 `sudo dnf install -y mesa-libGL libglvnd-glx glib2`.
  폐쇄망이면 rockylinux:9 컨테이너에서 `dnf download --resolve` 로 받아 `syspkgs/rpm/` 에 채워 재설치.

ffmpeg/ffprobe 가 없으면 backend FFmpegStep(프레임추출·duration)이, libGL.so.1 이 없으면 ai-server opencv 가 실패한다.
