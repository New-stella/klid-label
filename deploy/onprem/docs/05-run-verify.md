# 05. 기동 및 검증

## 기동 순서

의존 순서대로 ai-server → backend → frontend 로 켠다(유닛에도 의존성이 박혀 있다).

```bash
sudo systemctl daemon-reload

# enable + 즉시 시작 (한 번에)
sudo systemctl enable --now klid-ai-server
sudo systemctl enable --now klid-backend
sudo systemctl enable --now klid-frontend
```

> backend 는 유닛에 `Requires=klid-ai-server` + `After=postgresql.service` 가 있어, ai-server/DB
> 준비 후 기동된다. backend 첫 기동 시 Flyway 마이그레이션으로 시작이 다소 길 수 있다(TimeoutStartSec=180).

## 상태 확인

```bash
systemctl status klid-ai-server klid-backend klid-frontend --no-pager
```

## 헬스체크 (스모크)

```bash
# ai-server
curl -fsS http://127.0.0.1:9300/health

# backend liveness
curl -fsS http://127.0.0.1:8080/api/actuator/health/liveness

# frontend (Caddy SPA)
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1/

# 프록시 경로(프론트 → backend) 점검
curl -fsS http://127.0.0.1/api/actuator/health/liveness
```

기대: ai-server `{"status":"ok"}` 류, backend liveness `{"status":"UP"}`, frontend `200`.

## 로그 위치

```bash
# systemd journald (권장)
journalctl -u klid-backend   -f
journalctl -u klid-ai-server -f
journalctl -u klid-frontend  -f

# 앱 로그 디렉토리(앱이 파일 로깅 시)
ls -al /var/log/klid
```

## 추가 스모크(선택)

```bash
# ai-server 추론 헬스(백엔드별 mock/실모델 동작 확인은 운영 시나리오에 맞춰 별도 수행)
# backend actuator(노출 토글에 따라 readiness/info 등)
curl -fsS http://127.0.0.1:8080/api/actuator/health
```

## 재시작 / 정지

```bash
sudo systemctl restart klid-backend
sudo systemctl stop klid-frontend klid-backend klid-ai-server
```

## 환경설정 변경 반영

`/etc/klid/*.env` 수정 후:

```bash
sudo systemctl restart klid-backend     # 또는 klid-ai-server
```
