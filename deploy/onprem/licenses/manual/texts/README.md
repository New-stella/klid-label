# licenses/manual/texts — 사람이 넣는 라이선스 전문

수집 스크립트가 반입물에서 전문을 얻지 못한 구성요소의 라이선스 **전문**을 여기에 넣는다.
`licenses/manual/OVERRIDES.tsv` 에서 `notice_source=MANUAL` 로 표시한 항목이 대상이다.

규칙:

- **전문 그대로** 넣는다. 요약·발췌는 이행이 아니다(요약한 NOTICE 는 Apache-2.0 §4(d) 를
  충족하지 못하고, MIT/BSD 는 저작권 고지 원문 자체를 요구한다).
- 파일 첫머리에 **어디서 받았는지**를 적는다 — 출처 URL, 받은 날짜, 파일 해시.
  `licenses/manual/fonts/D2Coding-OFL-1.1.txt` 가 그 형식의 본보기다.
- 폰트처럼 성격이 뚜렷한 것은 하위 디렉터리로 나눈다(`fonts/` 가 이미 그렇다).

지금 비어 있다면 그것은 정상이다 — 자동 수집이 전부 커버했다는 뜻이다.
`licenses/UNRESOLVED.md` 가 비어 있는지로 확인한다.
