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
ARG VITE_TOKEN_INGRESS=all
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_TOKEN_INGRESS=$VITE_TOKEN_INGRESS

RUN npm run build

# ---- runtime stage ----
FROM nginx:alpine AS runtime
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
