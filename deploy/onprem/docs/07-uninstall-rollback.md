# 07. 제거 / 롤백

## 제거 (데이터 보존)

서비스 중지·비활성화 + 앱/런타임 제거. **데이터·환경설정·로그는 보존**한다.

```bash
cd deploy/onprem
sudo ./scripts/uninstall.sh
```

보존되는 것: `/etc/klid`(env), `/var/lib/klid`(영상 저장소), `/var/log/klid`(로그).

## 완전 제거 (PURGE)

데이터/환경설정/로그 + `klid` 사용자까지 삭제. **복구 불가** — 영상/DB 외 데이터 손실 주의.

```bash
sudo PURGE=1 ./scripts/uninstall.sh
```

> PostgreSQL DB(klid_system/portal) 는 외부/별도 자원이라 uninstall 이 건드리지 않는다.
> DB 삭제가 필요하면 DBA 가 별도 수행한다.

## 롤백(이전 버전으로)

이 패키지는 단순 파일 배치 방식이라 버전 롤백은 "이전 패키지로 재설치"로 한다.

1. 현재 서비스 중지: `sudo systemctl stop klid-frontend klid-backend klid-ai-server`
2. 이전 버전 `deploy/onprem/` 패키지로 `sudo ./scripts/install.sh` 재실행
   (env 는 보존되므로 그대로 사용, 필요 시 수정).
3. 기동·검증: 05-run-verify.md.

> DB 스키마는 Flyway 가 관리한다. **하위 버전으로 내릴 때 마이그레이션 호환성**(이전 jar 가 최신 스키마를
> validate 통과하는지)을 반드시 확인하라. 비호환이면 DB 백업 복원이 필요할 수 있다.

## 재설치 전 백업 권장

```bash
sudo cp -a /etc/klid /etc/klid.bak.$(date +%Y%m%d)
# DB 백업(예시): pg_dump -h <host> -U <user> klid_system > klid_system.sql
```
