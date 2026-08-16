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

# 세션 만료·401 시 이동할 상위 시스템 로그인 페이지(H-ISSUE-02).
# 저작도구는 자체 로그인 UI 가 없어 이 값이 비면 redirect 가 불가능하고 화면이 막다른 상태가 된다.
# 기본값을 두지 않는다 — 잘못된 기본값으로 조용히 빌드되는 것보다 비어 있음이 드러나는 게 낫다.
ARG VITE_CONTROL_LOGIN_URL=
ARG VITE_PORTAL_LOGIN_URL=
ENV VITE_CONTROL_LOGIN_URL=$VITE_CONTROL_LOGIN_URL
ENV VITE_PORTAL_LOGIN_URL=$VITE_PORTAL_LOGIN_URL

# fail-closed(CWE-1188): 온프렘 빌드 스크립트(require_upstream_login_urls)를 우회해
# `docker build` 를 직접 호출해도 빈 값으로 조용히 빌드되지 않게 한다.
RUN test -n "$VITE_CONTROL_LOGIN_URL" && test -n "$VITE_PORTAL_LOGIN_URL" \
  || (echo "FATAL: VITE_CONTROL_LOGIN_URL/VITE_PORTAL_LOGIN_URL 필수 (--build-arg 로 전달)" >&2 && exit 1)

RUN npm run build

# ---- runtime stage ----
FROM nginx:alpine AS runtime
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
