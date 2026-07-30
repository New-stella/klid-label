# syntax=docker/dockerfile:1
# ============================================================================
# klid-label mock-server (FastAPI) — 외부 벤더 API 목 서버 (로컬/테스트 전용)
#   ⚠ 인증 없음 + VLM 콜백 SSRF 표면 — 운영/외부망에 노출 금지.
#     신뢰된 네트워크(로컬/CI/내부 테스트망)에서만 기동할 것.
#
#   베이스: python:3.11-slim — 무거운 AI 의존성이 없어(순수 FastAPI/httpx/pydantic)
#     경량 slim 이미지로 충분하다. ai-server 와 달리 torch/CUDA 불필요.
# ============================================================================
FROM python:3.11-slim

WORKDIR /app

# 시스템 의존성 — KPST 목이 산출하는 비식별 영상에 '비식별 완료' 워터마크를 굽는 데 쓴다.
#   ffmpeg      : drawtext 필터(libfreetype 포함 빌드)로 프레임에 텍스트를 직접 인코딩
#   fonts-nanum : 한글 폰트(/usr/share/fonts/truetype/nanum/NanumGothic.ttf).
#                 drawtext 에 fontfile 절대경로로 지정하므로 fontconfig 탐색에 의존하지 않는다.
# 둘 중 하나라도 없으면 워터마킹은 자동으로 '원본 복사' 폴백이라 목 자체는 계속 동작한다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg fonts-nanum \
    && rm -rf /var/lib/apt/lists/*

# 의존성 먼저 설치(레이어 캐시 활용). 순수 파이썬 패키지라 빌드 도구 불필요.
COPY requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

# 애플리케이션 코드
COPY app ./app

# 비루트 유저로 실행(권한 최소화).
RUN useradd -r -m -d /home/app app \
    && chown -R app:app /app
USER app

EXPOSE 9400

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "9400"]
