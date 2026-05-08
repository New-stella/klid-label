# DEV/LOCAL 외부 데이터셋 사용 가이드

> 본 문서는 dev/local 환경에서 라벨링 UI / 오토라벨링 PoC를 검증하기 위해
> 외부 공개 데이터셋을 다운로드해 사용할 때의 정책과 절차를 정의한다.
> production 시드와는 무관하다.

---

## 정책 요약

| 항목 | 정책 |
|------|------|
| 사용 범위 | dev / local 한정 |
| 저장 위치 | `storage/raw/seed-real/` (`.gitignore` 처리됨) |
| commit | 금지 (디렉토리 자체가 git 추적 대상 아님) |
| 외부 시스템 업로드 | 금지 (관제서버 / 포털 / 비식별 / Gitea로 송신 X) |
| 라이센스 명시 | 모든 데이터셋에 대해 출처 / 라이센스 / 인용 정보를 `storage/raw/seed-real/ATTRIBUTION.md`에 기록 |
| production 빌드 보호 | 시드 로더는 `@Profile("seed-real")` 또는 `@ConditionalOnProperty` 가드 필수 |

---

## 다운로드 절차

### 1. 다운로드 스크립트 실행

```bash
# 50장 (기본)
./scripts/download-dev-dataset.sh

# 일부만 (PoC)
./scripts/download-dev-dataset.sh --count 10
```

스크립트는 다음을 수행한다.
- `storage/raw/seed-real/coco-val2017-sample/`에 50장 다운로드
- 다운로드 직후 SHA-256 체크섬을 `MANIFEST.sha256`에 기록
- 외부 다운로드 URL allowlist(`images.cocodataset.org`)만 허용 (SSRF 방어)
- 실패 시 최대 3회 재시도

### 2. 무결성 검증

```bash
cd storage/raw/seed-real/coco-val2017-sample
shasum -a 256 -c MANIFEST.sha256
```

### 3. ATTRIBUTION 확인

`storage/raw/seed-real/ATTRIBUTION.md`에서 각 데이터셋의 출처와 라이센스 의무를 확인한다.
신규 데이터셋 추가 시 동일 형식으로 ATTRIBUTION에 추가한다.

---

## 권장 데이터셋

| 우선순위 | 데이터셋 | 라이센스 | 비고 |
|:--------:|----------|---------|------|
| 1 | COCO val2017 sample | 이미지별 Flickr CC-BY 2.0 등 | 학술 표준, 라이센스 메타 명확 |
| 2 | Pixabay CC0 | CC0 (Public Domain) | API key 필요 시 별도 발급 |
| 3 | Wikimedia Commons | CC-BY-SA / Public Domain | 한국 거리/CCTV 키워드 검색 |

라이센스 불명확한 이미지는 `seed-real/`에 두지 않는다.

---

## 사용 시나리오

### 라벨링 UI 시각 확인
1. 다운로드 완료 후 BE 가동 (`./gradlew bootRun --args='--spring.profiles.active=local'`)
2. 별도 시드 SQL로 `LS_DATA_RAW` / `LS_DATA_SRC`의 `FILE_PATH`를
   `seed-real/coco-val2017-sample/000000000139.jpg` 형식으로 매핑
   (현재는 매핑 SQL 미제공 — 필요 시 별도 작성)
3. FE 라벨링 화면에서 실제 이미지 렌더링 확인

### 오토라벨링 PoC
1. ai-server 가동 (`uvicorn app.main:app --port 9300`)
2. POST `/v1/label/auto-label`에 COCO 이미지 경로 전달
3. YOLO/SAM2 응답 라벨을 `LS_DATA_LBL`에 INSERT (수동 또는 AppService 호출)

---

## production 보호 (필수)

외부 데이터를 시드로 적재하는 코드(예: `SeedRealDataRunner`)를 추가할 때는
**반드시** 아래 가드 중 하나를 적용한다.

```java
// 옵션 A — Profile 가드
@Component
@Profile("seed-real")
public class SeedRealDataRunner implements ApplicationRunner { ... }

// 옵션 B — 설정 키 가드
@Component
@ConditionalOnProperty(prefix = "app.seed.real", name = "enabled", havingValue = "true")
public class SeedRealDataRunner implements ApplicationRunner { ... }
```

- `application-prd.yml`에 `seed-real` profile 또는 `app.seed.real.enabled=true`를
  **절대로** 두지 않는다.
- CI 빌드에서 `seed-real` profile이 활성화되지 않도록 `bootJar` 검증.

---

## 정리 (제거)

```bash
# 다운로드 데이터 전체 제거
rm -rf storage/raw/seed-real/
```

`storage/` 자체는 `.gitignore`에 등재되어 있어 commit 영향 없음.

---

## 참고

- `.gitignore`에 `storage/`가 등재되어 있는지 매번 확인.
- `storage/raw/seed-real/ATTRIBUTION.md`도 git 추적 대상이 아니므로,
  본 문서(`docs/dev-dataset-attribution.md`)와 라이센스 정보가 어긋나지 않도록 유지한다.
- COCO 어노테이션 JSON(`instances_val2017.json`)은 본 저장소에 동봉하지 않는다.
  필요 시 https://cocodataset.org/#download 에서 별도 수령.
