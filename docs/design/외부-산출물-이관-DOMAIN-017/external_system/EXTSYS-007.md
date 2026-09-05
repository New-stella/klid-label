---
logicraft_item: EXTSYS-007
type: external_system
version: 1
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T01:10:32.605Z
status: NEW
prev_version: null
content_hash: bedb3e1931bd13cdbb06c7863eb5e100238468e7cb6937d5b888cf6077f053b3
stale: false
raw: ./_raw/EXTSYS-007.json
links:
  provided_by_backward: ["[[INT-012]]"]
---

# 외부 어노테이션 산출물 제공처

## kind

data

## status

active

## criticality

medium

## description

외부에서 이미 라벨링이 끝난 산출물을 저작도구로 넘겨 주는 제공처다. 첫 대상은 1차 어노테이션 산출물이며, 다른 형식의 산출물이 생겨도 같은 자리에서 받는다.

## 넘겨받는 형태
한 영상 분량이 폴더 하나로 묶여 오고, 그 안에 프레임 이미지와 그것을 설명하는 문서가 짝을 이룬다. 문서에는 영상 단위 정보와 프레임 단위 정보, 도형 라벨과 텍스트 항목이 함께 담긴다.

## 주고받는 방식
파일을 밀어 넣는 쪽이 아니라 우리가 당겨 오는 쪽이다. 검수자가 관리 화면에 폴더 경로를 넣으면 서버가 그 폴더를 훑어 무엇이 몇 건 들어오는지와 처리할 수 없는 항목이 무엇인지를 먼저 보여주고, 검수자가 확인한 뒤에야 실제 적재가 일어난다. 실시간으로 주고받는 통신 규약은 없다.

## 개인정보 관점
비식별 처리가 끝난 상태로 올 수도 있고 원본 그대로 올 수도 있으며, 어느 쪽인지는 가져올 때 사람이 지정한다. 따라서 받는 시점에는 개인정보가 담겨 있을 수 있다고 보고 다룬다.

## 확인되지 않은 것
제공 주체의 이름과 담당 조직, 산출물 전달 주기는 아직 확인되지 않아 비워 둔다. 지어내지 않는다.

## environments

_(empty)_

## implementation

### status

planned

### modules

_(empty)_

### records

_(empty)_

### progress

0

### subtasks

_(empty)_

## compliance_tags

_(empty)_

## used_by_domains

_(empty)_

## data_sensitivity

pii

## shared_with_projects

_(empty)_
