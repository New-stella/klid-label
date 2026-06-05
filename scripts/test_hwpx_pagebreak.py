#!/usr/bin/env python3
"""기술서 표 페이지 분할 깨짐 회귀 테스트 (2차 픽스).

증상: "5. 유스케이스 기술서" 표(13개 블록)가 한글에서 페이지 경계를 넘어갈 때
분할이 깨진다. 1차 픽스(기술서 표 pageBreak="NONE")는 한글 실측에서 효과 없음 판명.

확정 원인(검증된 참조 구현 md_uml XML 비교):
  ① 복제된 hp:tbl 의 id 중복 (md_uml 출력은 전부 unique)
  ② stale 줄배치 캐시 hp:linesegarray 복제 (md_uml 출력은 0개)
  pageBreak 는 원인이 아니며 템플릿 원본값 CELL 을 그대로 보존한다.

수정:
  - pageBreak 롤백: 기술서 표 포함 전 표 CELL 유지 (md_uml 정합)
  - 모든 hp:tbl id 유일 재채번
  - zOrder 유일 순번 부여
  - hp:linesegarray 전량 제거 (한글이 줄/쪽 재계산)
  - 표별 cellAddr (col,row) 중복 0건 (한컴 손상 거부 예방)

given/when/then 주석으로 의도를 명시. 빌드 산출물(_filled.hwpx)을 검증한다.
실행: python3 -m pytest scripts/test_hwpx_pagebreak.py -v
"""
from __future__ import annotations

import os
import zipfile

from lxml import etree

HP = "http://www.hancom.co.kr/hwpml/2011/paragraph"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "docs/cbd/hwpx/KLID_OO_유스케이스 명세서 v1.0.hwpx")
OUT = os.path.join(ROOT, "docs/design/hwpx/KLID_OO_유스케이스 명세서 v1.0_filled.hwpx")


def hp(t: str) -> str:
    return f"{{{HP}}}{t}"


def _header0(tbl) -> str:
    tc0 = tbl.find(hp("tr")).find(hp("tc"))
    return "".join(
        x.text or "" for x in tc0.findall(".//" + hp("t"))
    ).strip()


def _root(path: str):
    z = zipfile.ZipFile(path)
    root = etree.fromstring(z.read("Contents/section1.xml"))
    z.close()
    return root


def _tables(path: str):
    return _root(path).findall(".//" + hp("tbl"))


def test_기술서_블록표_13개는_pageBreak_CELL_유지() -> None:
    # given: 생성된 _filled.hwpx 의 모든 표
    tbls = _tables(OUT)
    # when: 기술서 블록 표(header0 == '유스케이스ID')만 추린다
    spec = [t for t in tbls if _header0(t) == "유스케이스ID"]
    # then: 13개 모두 템플릿 원본 CELL 유지 (1차 픽스 NONE 롤백)
    assert len(spec) == 13, f"기술서 블록 표는 13개여야 함, 실제 {len(spec)}"
    pbs = {t.get("pageBreak") for t in spec}
    assert pbs == {"CELL"}, f"기술서 블록 pageBreak 가 CELL 이어야 함, 실제 {pbs}"


def test_그외_표도_CELL_유지() -> None:
    # given: 생성된 _filled.hwpx 의 모든 표
    tbls = _tables(OUT)
    # when: 기술서 블록이 아닌 표 (머리말·서브시스템·UC목록·액터·UCD)
    others = [t for t in tbls if _header0(t) != "유스케이스ID"]
    # then: 전부 'CELL' 유지
    for t in others:
        assert t.get("pageBreak") == "CELL", (
            f"비기술서 표 header0={_header0(t)!r} 은 CELL 유지여야 함, "
            f"실제 {t.get('pageBreak')}"
        )


def test_원본_템플릿_기술서표는_CELL_그대로() -> None:
    # given/then: 원본은 절대 미수정 — 기술서 템플릿도 CELL 그대로
    tbls = _tables(SRC)
    spec = [t for t in tbls if _header0(t) == "유스케이스ID"]
    assert len(spec) == 1
    assert spec[0].get("pageBreak") == "CELL"


def test_모든_tbl_id_유일() -> None:
    # given: 생성된 _filled.hwpx 의 모든 표
    tbls = _tables(OUT)
    # when: 표 id 목록
    ids = [t.get("id") for t in tbls]
    # then: 누락 없이 전부 유일 (복제로 인한 id 중복이 원인 #1)
    assert all(i is not None for i in ids), "id 누락 표 존재"
    assert len(ids) == len(set(ids)), (
        f"tbl id 중복 — 총 {len(ids)}개 중 unique {len(set(ids))}개"
    )


def test_모든_zOrder_유일() -> None:
    # given: section1 전체 트리
    root = _root(OUT)
    # when: zOrder 속성을 가진 객체의 zOrder 값
    zos = [el.get("zOrder") for el in root.iter()
           if el.get("zOrder") is not None]
    # then: 전부 유일 순번
    assert len(zos) > 0
    assert len(zos) == len(set(zos)), (
        f"zOrder 중복 — 총 {len(zos)}개 중 unique {len(set(zos))}개"
    )


def test_linesegarray_0개() -> None:
    # given/when: section1 전체 linesegarray (md_uml 정상 출력은 0개)
    root = _root(OUT)
    lsas = root.findall(".//" + hp("linesegarray"))
    # then: 0개 — 한글이 줄/쪽 배치를 재계산 (stale 캐시 복제가 원인 #2)
    assert len(lsas) == 0, f"linesegarray 가 남아있음: {len(lsas)}개"


def test_표별_cellAddr_중복_0건() -> None:
    # given: 생성된 _filled.hwpx 의 모든 표
    tbls = _tables(OUT)
    # when/then: 같은 표 안에서 (colAddr,rowAddr) 쌍 중복이 없어야 함
    #            (한컴 손상 거부 예방 — md_uml hwpxReplacer line 457)
    for t in tbls:
        seen: dict = {}
        for tr in t.findall(hp("tr")):
            for tc in tr.findall(hp("tc")):
                a = tc.find(hp("cellAddr"))
                if a is None:
                    continue
                key = (a.get("colAddr"), a.get("rowAddr"))
                seen[key] = seen.get(key, 0) + 1
        dups = [k for k, v in seen.items() if v > 1]
        assert not dups, f"tbl id={t.get('id')} cellAddr 중복: {dups}"


# ---------------------------------------------------------------------------
# 3차 픽스 — 진범: treatAsChar="1" (글자처럼 취급 → 표 페이지 분할 절대 불가).
#   md_uml hwpxReplacer line 360: 주입/복제 표는 treatAsChar="1"→"0" 으로 바꿔
#   한컴이 표를 페이지 경계에서 분할하도록 한다.
#   treatAsChar 속성은 <hp:tbl> 하위 <hp:pos> 요소에 존재한다(실측).
# ---------------------------------------------------------------------------

def _pos_tac(tbl):
    pos = tbl.find(hp("pos"))
    return pos.get("treatAsChar") if pos is not None else None


# 데이터를 주입/복제하는 본문 표의 header0 집합 (머리말·문서번호 표는 제외)
_INJECTED_HEADERS = {
    "서브시스템 ID", "유스케이스 ID", "액터 ID", "UCD ID", "유스케이스ID",
}


def test_주입_본문표는_treatAsChar_0() -> None:
    # given: 생성된 _filled.hwpx 의 모든 표
    tbls = _tables(OUT)
    # when: 데이터를 주입/복제한 본문 표만 추린다
    injected = [t for t in tbls if _header0(t) in _INJECTED_HEADERS]
    # then: 전부 treatAsChar="0" (페이지 분할 허용 — 진범 해소)
    assert len(injected) > 0, "주입 본문 표가 하나도 없음"
    bad = [(_header0(t), _pos_tac(t)) for t in injected if _pos_tac(t) != "0"]
    assert not bad, f"treatAsChar 가 0 이 아닌 주입 표: {bad}"


def test_미주입_표는_원본_treatAsChar_유지() -> None:
    # given: 원본 템플릿과 출력의 미주입 표(머리말·문서번호 등) 비교
    out_tbls = [t for t in _tables(OUT) if _header0(t) not in _INJECTED_HEADERS]
    src_tbls = [t for t in _tables(SRC) if _header0(t) not in _INJECTED_HEADERS]
    # when/then: 미주입 표는 변경 금지 — 원본 treatAsChar 값 분포를 그대로 유지
    src_vals = sorted(_pos_tac(t) for t in src_tbls)
    out_vals = sorted(_pos_tac(t) for t in out_tbls)
    assert out_vals == src_vals, (
        f"미주입 표 treatAsChar 변경됨: 원본 {src_vals} != 출력 {out_vals}"
    )


def test_원본_템플릿_본문표는_treatAsChar_1_그대로() -> None:
    # given/then: 원본은 절대 미수정 — 본문 표 treatAsChar="1" 그대로 보존
    injected = [t for t in _tables(SRC) if _header0(t) in _INJECTED_HEADERS]
    assert len(injected) > 0
    assert all(_pos_tac(t) == "1" for t in injected), (
        "원본 본문 표 treatAsChar 가 1 이 아님 — 원본 손상 의심"
    )


def test_행늘어난_표는_hp_sz_height_비례확대() -> None:
    # given: 원본 대비 행 수가 늘어난 row-주입 표(서브시스템·UC목록·액터)
    src_by_hdr = {_header0(t): t for t in _tables(SRC)}
    out_by_hdr = {_header0(t): t for t in _tables(OUT)}
    # when/then: 각 표의 hp:sz height 가 newRowCnt/origRowCnt 비율로 확대됨
    for hdr in ("서브시스템 ID", "유스케이스 ID", "액터 ID"):
        src_t, out_t = src_by_hdr[hdr], out_by_hdr[hdr]
        orig_rc = int(src_t.get("rowCnt"))
        new_rc = int(out_t.get("rowCnt"))
        if new_rc == orig_rc:
            continue
        src_h = int(src_t.find(hp("sz")).get("height"))
        out_h = int(out_t.find(hp("sz")).get("height"))
        expected = round(src_h * new_rc / orig_rc)
        assert out_h == expected, (
            f"{hdr}: height 비례확대 실패 orig={src_h} "
            f"new={out_h} expected={expected} (rc {orig_rc}->{new_rc})"
        )


# ---------------------------------------------------------------------------
# 4차 픽스 — 기술서 셀 빈 줄 중복.
#   증상: 기술서 콘텐츠 셀에 md 소스의 `<br><br>`·줄바꿈 유래 공백-only 문단이
#         섞여 절 제목 사이에 빈 줄이 2줄씩 표시됨.
#   원인: build_content_cell_paragraphs 가 모든 줄(빈 줄 포함)을 문단화.
#   수정: normalize_spec_paragraphs — 공백-only 줄 제거(들여쓰기 보존) +
#         절 제목(1.~8.) 앞에 빈 문단 1개만 복원(원본 양식 정합).
#   실측 근거: 원본 빈 양식 기술서 콘텐츠 셀은 절 제목 사이에 빈 문단을 둔다
#             (1~2개 편차) → 1개로 통일.
# ---------------------------------------------------------------------------


def _spec_content_cell(tbl):
    """기술서 블록 표의 콘텐츠 셀(2행 colspan) subList 문단 리스트."""
    trs = tbl.findall(hp("tr"))
    content_tc = trs[1].find(hp("tc"))
    return content_tc.find(hp("subList")).findall(hp("p"))


def _para_text(p) -> str:
    return "".join(
        t.text or "" for run in p.findall(hp("run"))
        for t in run.findall(hp("t"))
    )


def test_원본_템플릿_기술서셀은_절제목사이_빈문단_보유() -> None:
    # given: 원본 빈 양식 템플릿의 기술서 콘텐츠 셀 (절 사이 간격 정책 근거)
    spec = [t for t in _tables(SRC) if _header0(t) == "유스케이스ID"]
    assert len(spec) == 1
    paras = _spec_content_cell(spec[0])
    blanks = sum(1 for p in paras if not _para_text(p).strip())
    # then: 원본이 절 제목 사이에 빈 문단을 두는 구조 → 정규화 시 '절 사이 1개'
    #       복원이 양식 정합 (실측: 23문단 중 약 절반이 빈 문단)
    assert blanks > 0, (
        "원본 템플릿 기술서 셀에 빈 문단이 없음 — 간격 정책 근거 불성립"
    )


def test_기술서셀_연속_빈문단_없음() -> None:
    # given: 생성된 _filled.hwpx 의 기술서 블록 표 전부
    spec = [t for t in _tables(OUT) if _header0(t) == "유스케이스ID"]
    assert len(spec) == 13
    # when/then: 어떤 셀에도 빈 문단이 2개 연속(빈 줄 2줄)으로 나오지 않는다
    for tbl in spec:
        paras = _spec_content_cell(tbl)
        blanks = [not _para_text(p).strip() for p in paras]
        consec = [i for i in range(len(blanks) - 1)
                  if blanks[i] and blanks[i + 1]]
        assert not consec, (
            f"기술서 셀에 연속 빈 문단 발견 idx={consec}"
        )


def test_기술서셀_절제목_앞_빈문단_1개_복원() -> None:
    # given: 생성된 첫 기술서 블록 콘텐츠 셀
    import re as _re
    spec = [t for t in _tables(OUT) if _header0(t) == "유스케이스ID"]
    paras = _spec_content_cell(spec[0])
    texts = [_para_text(p) for p in paras]
    sec_re = _re.compile(r"^\d+\.\s")
    # when: 절 제목(1.~8.) 문단 인덱스
    title_idx = [i for i, t in enumerate(texts) if sec_re.match(t)]
    # then: 첫 절 제목을 제외한 각 절 제목 바로 앞 문단은 빈 문단(간격 1개)
    assert len(title_idx) == 8, f"절 제목 8개여야 함, 실제 {len(title_idx)}"
    for i in title_idx[1:]:
        assert i >= 1 and not texts[i - 1].strip(), (
            f"절 제목 idx={i} 앞 문단이 빈 문단이 아님: {texts[i - 1]!r}"
        )
    # 첫 절 제목 앞에는 불필요한 빈 문단이 없어야 한다(셀 선두 공백 제거)
    assert title_idx[0] == 0, (
        f"첫 절 제목 앞에 불필요한 문단 존재 idx={title_idx[0]}"
    )
