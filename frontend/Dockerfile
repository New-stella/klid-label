# syntax=docker/dockerfile:1
# ============================================================================
# klid-label frontend (React + Vite) — 정적 빌드 후 nginx 서빙
#   build  : node:20 → npm ci && npm run build
#   runtime: nginx:alpine + nginx.conf (SPA fallback + /api 프록시)
# ============================================================================

# ---- dev stage (Vite dev server — import.meta.env.DEV=true, /dev/login 등 dev 기능 활성) ----
# compose 의 klid-frontend 는 이 스테이지를 사용한다 (build/nginx 는 정적 배포용으로 보존).
FROM node:20-alpine AS dev
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

EXPOSE 5174
# 컨테이너 외부 접속을 위해 0.0.0.0 바인딩. 프록시 대상/HMR 포트는 compose env 로 주입.
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0", "--port", "5174"]

# ---- build stage ----
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# 빌드 타임 환경변수 (Vite 는 빌드 시 import.meta.env 를 정적 치환)
ARG VITE_API_BASE_URL=/api/v1
# 토큰 인계 채널: 'url' | 'cookie' | 'localStorage' | 'both' | 'all'
# 기본값에 URL 쿼리 채널을 넣지 않는다 — 관제/포털은 동일 origin 브라우저 저장소로 JWT 를
# 인계하며(ADR-012) `?token=` 는 접근 로그·리퍼러·히스토리에 JWT 를 남긴다(CWE-598).
ARG VITE_TOKEN_INGRESS=localStorage
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_TOKEN_INGRESS=$VITE_TOKEN_INGRESS

# 세션 만료·401 시 이동할 상위 시스템 로그인 페이지.
# ★ 이 값들은 이제 <런타임>에 주입한다 — 이미지/번들에 굽지 않는다. 서빙 문서 루트의
#   klid-config.js 가 값을 심고(대상 서버에서는 /etc/klid/frontend.env 가 정본), 앱은
#   런타임 → 빌드 순으로 읽는다(src/lib/runtimeConfig.ts).
#   컨테이너에서 실제 주소를 쓰려면 그 파일을 볼륨으로 얹는다:
#     -v ./klid-config.js:/usr/share/nginx/html/klid-config.js:ro
# ★ 구 fail-closed RUN 가드는 <제거했다>. 값이 비어도 빌드가 성공해야 <환경 무관 산출물>이
#   되고, 그게 이번 전환의 목적이다. 미설정 방어는 설치 시점(render-frontend-config.sh)에 있다.
#   여기 남은 ARG 는 런타임 설정이 없을 때의 폴백일 뿐이다.
ARG VITE_CONTROL_LOGIN_URL=
ARG VITE_PORTAL_LOGIN_URL=
ENV VITE_CONTROL_LOGIN_URL=$VITE_CONTROL_LOGIN_URL
ENV VITE_PORTAL_LOGIN_URL=$VITE_PORTAL_LOGIN_URL

RUN npm run build

# ---- runtime stage ----
FROM nginx:alpine AS runtime
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
