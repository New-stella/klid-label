---
logicraft_item: CONST-002
type: constant
version: 3
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:54.243Z
status: NEW
prev_version: null
content_hash: 5ed80cb10c739908ea45beded3f1e630d5fe2e1b438a4a61d54a9aaa1d590c53
stale: false
raw: ./_raw/CONST-002.json
links:
  based_on: ["[[ADR-019]]"]
  uses_constant_backward: ["[[ERD-019]]"]
---

# CocoClasses — COCO-80 검출 클래스 allowlist

## kind

enum

## name

CocoClasses.LABELS

## unit

count

## group

오토라벨 검출 클래스

## value

["person","bicycle","car","motorcycle","airplane","bus","train","truck","boat","traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat","dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack","umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball","kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket","bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple","sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair","couch","potted plant","bed","dining table","toilet","tv","laptop","mouse","remote","keyboard","cell phone","microwave","oven","toaster","sink","refrigerator","book","clock","vase","scissors","teddy bear","hair drier","toothbrush"]

## brownfield

### notes

정본=백엔드 CocoClasses.LABELS. ai-server COCO_ID2LABEL과 80종 완전 일치(2026-07-24 실측).

### status

new

### decided_by

ADR-019

### change_kind

- constant-add
- contract

### diff_summary

COCO-80 검출 클래스 allowlist 상수 신설 — 라벨 마스터 DTCT_TYPE_CD 매핑 진실원 + ai-server COCO_ID2LABEL 계약 정합

## decided_in

- ADR-019

## description

COCO 80 검출 클래스 allowlist — 라벨 마스터 검출유형(LS_LABEL.DTCT_TYPE_CD) 매핑의 진실원 계약.

**값 규격**: index 가 곧 COCO class_id 다(0=person … 79=toothbrush). 저작도구가 보유한 이 목록과 추론 서버가 보유한 클래스 목록은 **개수·순서·문자열이 모두 같아야** 한다 — 순서가 어긋나면 추론 결과의 class_id 가 다른 라벨로 귀속되며, 이는 오류 없이 조용히 틀린 라벨을 만든다. 두 목록의 일치는 계약 검증 대상이다.

**용도**:
- 라벨 마스터 생성·수정 시 검출유형 입력을 이 allowlist 로 검증한다(자유 텍스트 금지 — 미검증 입력이 검출 대상 구성에 그대로 흘러가면 안 된다).
- AI 탐지 온라인·배치 경로가 검출 대상을 재구성할 때, 화면이 보낸 요청을 신뢰하지 않고 **이 allowlist 와 라벨 마스터 매핑의 교집합**만 추론 서버로 전달한다.

매핑되지 않은 라벨은 검출 대상이 되지 않는다. 운영자가 COCO 매핑을 지정하기 전까지 AI 탐지는 0건으로 게이팅된다.
