# Dev 시드 이미지 설정 가이드

> **적용 범위**: dev/local 환경 전용. production/staging 금지.

## 사용 순서

### 1단계: 이미지 다운로드

```bash
./scripts/download-dev-dataset.sh
```

COCO val2017 annotations를 파싱하여 이벤트별 디렉토리에 이미지를 저장합니다.

- Python3 필요 (annotation JSON 파싱)
- Python3 없으면 하드코딩 ID 방식(50장)으로 자동 폴백

### 2단계: 시드 구조에 적용

```bash
# 변경사항 미리 확인 (dry-run)
./scripts/apply-seed-images.sh --dry-run

# 실제 적용
./scripts/apply-seed-images.sh
```

`storage/raw/seed/{rawSn}/frame_1.jpg ~ frame_5.jpg` 구조로 이미지를 복사합니다.

- 멱등성 보장 — 재실행 시 덮어씁니다.
- 소스 이미지가 5장보다 적으면 순환(모듈로)으로 채웁니다.

---

## 이벤트별 카테고리 매핑

| 이벤트 | RAW_SN | 소스 디렉토리 | COCO 카테고리 |
|--------|--------|-------------|-------------|
| EVT_FALL | 9001,9007,9013,9019,9025,9031 | `fall-violence-abnormal/` | person (cat_id=1) 30장 |
| EVT_VIOLENCE | 9002,9008,9014,9020,9026,9032 | `fall-violence-abnormal/` | person (cat_id=1) 30장 |
| EVT_ABNORMAL | 9004,9010,9016,9022,9028 | `fall-violence-abnormal/` | person (cat_id=1) 30장 |
| EVT_ACCIDENT | 9003,9009,9015,9021,9027,9033 | `traffic-accident/` | car/motorcycle/bus/truck (cat_id=3,4,6,8) 20장 |
| EVT_FLOOD | 9005,9011,9017,9023,9029 | `flood-fire/` | outdoor 임시 대체 (cat_id=10,13,17,72) 10장 |
| EVT_FIRE | 9006,9012,9018,9024,9030 | `flood-fire/` | outdoor 임시 대체 (cat_id=10,13,17,72) 10장 |

> **참고**: flood/fire 이미지는 COCO에 침수·산불 전용 카테고리가 없어 outdoor 카테고리로 임시 대체합니다.
> YOLO/SAM2 동작 검증 목적으로는 객체(사람/차량)가 포함된 이미지가 적합합니다.

---

## .gitignore 안내

`storage/raw/seed-real/` 디렉토리는 `.gitignore` 처리되어 있습니다.

```
# 확인 방법
git status  # seed-real/ 이 untracked로 표시되지 않아야 함
```

`git add storage/` 실행 금지. 실수로 staged 된 경우:

```bash
git rm -r --cached storage/raw/seed-real/
```

---

## 라이센스 안내 (COCO CC-BY 4.0)

- **이미지**: 이미지별 상이. 대부분 Flickr CC-BY 2.0 / 일부 CC-BY-NC 2.0
- **어노테이션**: Creative Commons Attribution 4.0 International (CC BY 4.0)
- 전체 의무 사항: [`storage/raw/seed-real/ATTRIBUTION.md`](../storage/raw/seed-real/ATTRIBUTION.md) 참조

인용 (외부 공개 시 필수):

```
T.-Y. Lin, M. Maire, S. Belongie, J. Hays, P. Perona, D. Ramanan, P. Dollar,
and C. L. Zitnick. "Microsoft COCO: Common Objects in Context."
European Conference on Computer Vision (ECCV), 2014.
```
