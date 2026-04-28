> ⚠️ **이 디렉토리는 발주처 시연용 UI 프로토타입입니다.**
> 실제 구현은 [`../frontend/`](../frontend/)를 참조하세요.

---

# klid-la-mock — 학습데이터 저작도구 목업

AI 기반 지방정부 CCTV 관제지원시스템 (2차) 학습데이터 저작도구의 발주처 시연용 프런트엔드 목업입니다.

## 설치 및 실행

```bash
# 의존성 설치
npm install

# 개발 서버 실행 (http://localhost:5173)
npm run dev

# 프로덕션 빌드
npm run build

# 빌드 결과 미리보기
npm run preview

# 타입 검사
npm run typecheck
```

## 기술 스택

| 항목 | 버전 |
|------|------|
| React | 18 |
| TypeScript | 5 |
| Vite | 5 |
| Tailwind CSS | 3 |
| React Router | 6 |
| Zustand | 4 |
| MSW (Mock Service Worker) | 2 |

## 디렉토리 구조

```
mock/
├── src/
│   ├── main.tsx          # 앱 진입점 (Phase 2에서 MSW worker 추가)
│   ├── App.tsx           # 루트 컴포넌트 + 라우팅
│   ├── index.css         # Tailwind 베이스
│   └── vite-env.d.ts     # Vite 환경변수 타입
├── index.html
├── vite.config.ts
├── tailwind.config.ts
├── tsconfig.json
└── package.json
```

## Phase별 목업 개발 계획

| Phase | 목업 범위 |
|-------|-----------|
| Phase 0 | 프로젝트 초기화 (현재) |
| Phase 1 | 공통 레이아웃 + 사이드바 + 라우팅 |
| Phase 2 | MSW API 목 + 인증 플로우 |
| Phase 3 | 영상/이미지 목록 + 배치 파이프라인 현황 |
| Phase 6 | 라벨링 캔버스 (konva.js) |
