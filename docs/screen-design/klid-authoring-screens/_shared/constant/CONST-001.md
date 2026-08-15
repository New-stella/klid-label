---
logicraft_item: CONST-001
type: constant
version: 4
status: UNCHANGED
prev_version: null
raw: ./_raw/CONST-001.json
---

# COCO-17 키포인트 스켈레톤 상수

## kind

enum

## name

COCO17_KEYPOINT_SKELETON

## group

라벨링 상수

## value

{"keypointNames":["nose","left_eye","right_eye","left_ear","right_ear","left_shoulder","right_shoulder","left_elbow","right_elbow","left_wrist","right_wrist","left_hip","right_hip","left_knee","right_knee","left_ankle","right_ankle"],"skeletonEdges1Indexed":[[16,14],[14,12],[17,15],[15,13],[12,13],[6,12],[7,13],[6,7],[6,8],[7,9],[8,10],[9,11],[2,3],[1,2],[1,3],[2,4],[3,5],[4,6],[5,7]]}

## description

COCO-17 휴먼 포즈 스켈레톤 표준 상수 — SKELETON 타입 라벨의 category 메타 원천. keypointNames 는 0-based 인덱스 순서(1~17번 관절), skeletonEdges1Indexed 는 COCO 표준 1-indexed 관절 번호쌍 19개(하지→상체→얼굴 순). SKELETON 라벨의 geometry 는 라벨 좌표 저장값에 17×[x,y,v] 삼중값(v=0 미표기 / 1 비가시 / 2 가시)으로 저장되며, 본 상수가 각 인덱스의 관절명과 스켈레톤 토폴로지를 규정한다. 관제 데이터마트가 COCO category.keypoints / category.skeleton 을 조립할 때 참조한다(COCO-pose JSON 파일 조립 자체는 외부 위임). 서버와 화면이 각자 보유하는 스켈레톤 상수는 관절명 순서와 간선쌍이 서로 같아야 하는 계약이다 — 한쪽만 바뀌면 같은 좌표가 다른 관절로 읽힌다. 모든 컬렉션은 불변(immutable)이다.
