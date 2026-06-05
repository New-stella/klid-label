#!/usr/bin/env python3
"""md -> hwpx 데이터 주입 PoC 스크립트.

CBD 산출물 마크다운(R2 유스케이스 명세서)의 데이터를 발주처 hwpx 템플릿의
본문 표(Contents/section1.xml)에 주입한다. 표지/제·개정이력(section0.xml)은
절대 수정하지 않는다(byte-identical 보존).

사용:
  python3 scripts/hwpx_fill.py \
    --md docs/design/R2-유스케이스명세서.md \
    --hwpx "docs/cbd/hwpx/KLID_OO_유스케이스 명세서 v1.0.hwpx" \
    --out docs/design/hwpx/

  python3 scripts/hwpx_fill.py --verify docs/design/hwpx/<생성파일>.hwpx \
    --hwpx "docs/cbd/hwpx/KLID_OO_유스케이스 명세서 v1.0.hwpx" \
    --md docs/design/R2-유스케이스명세서.md

표준 라이브러리 + lxml 만 사용한다.
"""
from __future__ import annotations

import argparse
import copy
import os
import re
import subprocess
import sys
import urllib.request
import zipfile

from lxml import etree

# --- OWPML 네임스페이스 ----------------------------------------------------
HP = "http://www.hancom.co.kr/hwpml/2011/paragraph"
HC = "http://www.hancom.co.kr/hwpml/2011/core"
OPF = "http://www.idpf.org/2007/opf/"
NS = {"hp": HP, "hc": HC, "opf": OPF}


def hp(tag: str) -> str:
    return f"{{{HP}}}{tag}"


def hc(tag: str) -> str:
    return f"{{{HC}}}{tag}"


# --- PlantUML 도구 ---------------------------------------------------------
PLANTUML_URL = (
    "https://github.com/plantuml/plantuml/releases/download/"
    "v1.2024.7/plantuml-1.2024.7.jar"
)


# ===========================================================================
# 1. 마크다운 파싱
# ===========================================================================

def parse_md_table(lines: list[str], start: int) -> tuple[list[list[str]], int]:
    """start 위치부터 시작하는 GFM 표를 파싱. (행 리스트, 다음 줄 인덱스) 반환.

    헤더 구분선(---) 행은 제외하고 데이터 행만 반환한다.
    """
    rows: list[list[str]] = []
    i = start
    while i < len(lines) and lines[i].lstrip().startswith("|"):
        raw = lines[i].strip()
        cells = [c.strip() for c in raw.strip("|").split("|")]
        # 구분선 행(--- | ---) 스킵
        if all(re.fullmatch(r":?-{2,}:?", c or "-") for c in cells):
            i += 1
            continue
        rows.append(cells)
        i += 1
    return rows, i


def extract_section_table(md: str, heading: str) -> list[list[str]]:
    """## heading 다음에 나오는 첫 GFM 표의 데이터 행(헤더행 제외)을 반환."""
    lines = md.splitlines()
    for idx, line in enumerate(lines):
        if line.strip().startswith("#") and heading in line:
            # 표 시작까지 진행
            j = idx + 1
            while j < len(lines) and not lines[j].lstrip().startswith("|"):
                # 다른 heading 이 먼저 나오면 표 없음
                if lines[j].strip().startswith("#"):
                    break
                j += 1
            if j < len(lines) and lines[j].lstrip().startswith("|"):
                rows, _ = parse_md_table(lines, j)
                # 첫 행은 컬럼 헤더 -> 제외
                return rows[1:]
    return []


def extract_subsystems(md: str) -> list[list[str]]:
    """1. 서브시스템 목록: [ID, 명, 설명] 행 리스트."""
    return extract_section_table(md, "1. 서브시스템 목록")


def extract_uc_list(md: str) -> list[list[str]]:
    """3. 유스케이스 목록: [ID, 명, 설명, 액터, UCD, 요구사항] 행."""
    return extract_section_table(md, "3. 유스케이스 목록")


def extract_actors(md: str) -> list[list[str]]:
    """4. 액터 목록: [ID, 명, 유형, 설명] 행."""
    return extract_section_table(md, "4. 액터 목록")


def extract_ucds(md: str) -> list[dict]:
    """2. 유스케이스 다이어그램(UCD): 각 UCD 의 헤더표 + plantuml 블록."""
    lines = md.splitlines()
    ucds: list[dict] = []
    i = 0
    in_section = False
    while i < len(lines):
        line = lines[i]
        if line.strip().startswith("## 2. 유스케이스 다이어그램"):
            in_section = True
            i += 1
            continue
        if in_section and re.match(r"^## \d", line.strip()) and "다이어그램" not in line:
            break
        m = re.match(r"^### (KLID-AT-UCD-\d+):\s*(.+)$", line.strip())
        if in_section and m:
            ucd_id = m.group(1)
            ucd_name = m.group(2).strip()
            # 다음 GFM 표(헤더표)에서 관련 서브시스템 추출
            j = i + 1
            while j < len(lines) and not lines[j].lstrip().startswith("|"):
                j += 1
            rows, after = parse_md_table(lines, j)
            ss_id = ss_name = ""
            for r in rows:
                if len(r) >= 4 and "관련 서브시스템 ID" in r[0]:
                    ss_id, ss_name = r[1], r[3]
            # plantuml 블록 추출
            puml = ""
            k = after
            while k < len(lines) and not lines[k].strip().startswith("```plantuml"):
                if lines[k].strip().startswith("### ") or lines[k].strip().startswith("## "):
                    break
                k += 1
            if k < len(lines) and lines[k].strip().startswith("```plantuml"):
                k += 1
                buf = []
                while k < len(lines) and not lines[k].strip().startswith("```"):
                    buf.append(lines[k])
                    k += 1
                puml = "\n".join(buf)
            ucds.append({
                "id": ucd_id, "name": ucd_name,
                "ss_id": ss_id, "ss_name": ss_name, "puml": puml,
            })
        i += 1
    return ucds


def extract_uc_specs(md: str) -> list[dict]:
    """5. 유스케이스 기술서: 각 UC 의 HTML <table> 블록을 파싱.

    반환: [{id, name, body_lines:[(label, [lines])...]}] — 본문은 1~8 섹션 텍스트.
    """
    specs: list[dict] = []
    # ### KLID-AT-UC-NNN <name> ... <table>...</table>
    blocks = re.split(r"^### (KLID-AT-UC-\d+)\s+(.+?)$", md, flags=re.MULTILINE)
    # blocks: [pre, id1, name1, body1, id2, name2, body2, ...]
    for n in range(1, len(blocks), 3):
        uc_id = blocks[n].strip()
        uc_name = blocks[n + 1].strip()
        body = blocks[n + 2]
        tbl_m = re.search(r"<table>(.*?)</table>", body, flags=re.DOTALL)
        if not tbl_m:
            continue
        tbl_html = tbl_m.group(1)
        # 두 번째 tr(colspan=4) 의 셀 내용을 추출
        trs = re.findall(r"<tr>(.*?)</tr>", tbl_html, flags=re.DOTALL)
        content_html = ""
        for tr in trs:
            if 'colspan="4"' in tr:
                content_html = re.sub(r"^.*?<td colspan=\"4\">", "", tr,
                                      flags=re.DOTALL)
                content_html = re.sub(r"</td>\s*$", "", content_html)
                break
        paragraphs = html_content_to_paragraphs(content_html)
        specs.append({"id": uc_id, "name": uc_name, "paragraphs": paragraphs})
    return specs


def strip_plain(s: str) -> str:
    """일반 공백/탭만 양끝에서 제거하고 em space(U+2003) 등 들여쓰기는 유지.

    md_uml hwpxReplacer.splitNewlinesIntoParagraphs 의 stripPlain 정책과 동일.
    `&emsp;` 가 U+2003(em space) 으로 보존된 들여쓰기는 strip 대상이 아니므로,
    들여쓰기만 있는 줄은 (em space 가 남아) 비어있지 않은 것으로 취급된다.
    """
    return re.sub(r"^[ \t]+|[ \t]+$", "", s)


# 절 제목 패턴: 줄 시작이 "1." ~ "8." 처럼 한 자리 숫자 + 점 + 공백
SECTION_TITLE_RE = re.compile(r"^\d+\.\s")


def normalize_spec_paragraphs(lines: list[str]) -> list[str]:
    """기술서 셀 문단 리스트를 양식 정합 간격으로 정규화.

    1) 공백/탭-only 줄(md_uml stripPlain 기준 비어있는 줄)은 문단으로 만들지 않는다.
       — md 소스의 `<br><br>`·줄바꿈 유래 빈 줄 중복 제거. em space(U+2003)
       들여쓰기는 strip_plain 이 보존하므로 들여쓴 실제 내용 줄은 살아남는다.
    2) 원본 빈 양식 템플릿 기술서 셀이 절 제목(1.~8.) 사이에 빈 문단을 두는 구조라
       (실측: 템플릿 콘텐츠 셀 23문단 중 제목 사이 1~2 빈 문단 존재), 시각 간격이
       전부 사라지지 않도록 **각 절 제목 앞(첫 절 제외)에 빈 문단 1개**만 복원한다.
       원본의 1~2개 편차는 1개로 통일해 양식 정합 + 중복 제거를 동시에 만족시킨다.
    """
    # 1) 공백-only 줄 제거 (들여쓰기 보존)
    kept = [ln for ln in lines if strip_plain(ln)]
    # 2) 절 제목 앞 빈 문단 1개 복원 (첫 줄 제외)
    out: list[str] = []
    for ln in kept:
        if SECTION_TITLE_RE.match(ln) and out:
            out.append("")
        out.append(ln)
    return out


def html_content_to_paragraphs(html: str) -> list[str]:
    """기술서 본문 HTML 을 문단(줄) 리스트로 변환.

    <br> -> 줄바꿈, <b>..</b>/&emsp;/&nbsp; 등 엔티티 처리, 태그 제거.
    공백-only 줄 제거 + 절 제목 간격 정규화는 normalize_spec_paragraphs 가 담당.
    """
    text = html
    text = text.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
    text = re.sub(r"</?b>", "", text)
    text = text.replace("&emsp;", "    ").replace("&nbsp;", " ")
    text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    text = re.sub(r"<[^>]+>", "", text)
    lines = [ln.rstrip() for ln in text.split("\n")]
    # 양끝 공백 줄 정리(내부는 보존 — 가독성)
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    return normalize_spec_paragraphs(lines)


# ===========================================================================
# 2. OWPML 표 조작
# ===========================================================================

def top_level_paragraphs(sec_root) -> list:
    return [c for c in sec_root if etree.QName(c).localname == "p"]


def find_table_in_p(p):
    return p.find(".//" + hp("tbl"))


def header_texts(tbl) -> list[str]:
    """표 첫 행의 셀 텍스트 리스트."""
    first_tr = tbl.find(hp("tr"))
    out = []
    for tc in first_tr.findall(hp("tc")):
        ts = [t.text for t in tc.findall(".//" + hp("t")) if t.text and t.text.strip()]
        out.append(" ".join(ts))
    return out


def set_cell_text(tc, text: str) -> None:
    """tc 셀의 첫 문단 run 의 텍스트를 text 로 설정(서식 보존).

    줄바꿈이 있으면 첫 문단을 복제해 다중 <hp:p> 생성.
    """
    sublist = tc.find(hp("subList"))
    paras = sublist.findall(hp("p"))
    template_p = paras[0]
    # 기존 문단 모두 제거 후 재구성
    for p in paras:
        sublist.remove(p)
    parts = text.split("\n") if text else [""]
    for part in parts:
        new_p = copy.deepcopy(template_p)
        # run 의 <hp:t> 설정. run 이 비어있으면 t 추가
        runs = new_p.findall(hp("run"))
        # 모든 run 텍스트 비우고 첫 run 에만 채움
        first_filled = False
        for run in runs:
            for t in run.findall(hp("t")):
                run.remove(t)
            if not first_filled:
                t = etree.SubElement(run, hp("t"))
                t.text = part
                first_filled = True
        if not first_filled and runs:
            t = etree.SubElement(runs[0], hp("t"))
            t.text = part
        sublist.append(new_p)


def clone_data_row(tbl, template_tr, row_addr: int, values: list[str]):
    """template_tr 를 복제해 row_addr 로 cellAddr 재설정, values 주입한 tr 반환."""
    new_tr = copy.deepcopy(template_tr)
    tcs = new_tr.findall(hp("tc"))
    for col, tc in enumerate(tcs):
        addr = tc.find(hp("cellAddr"))
        addr.set("rowAddr", str(row_addr))
        if col < len(values):
            set_cell_text(tc, values[col])
        else:
            set_cell_text(tc, "")
    return new_tr


def enable_table_page_split(tbl) -> None:
    """주입/복제한 본문 표가 한컴에서 페이지 경계 분할되도록 treatAsChar 를 0 으로.

    발주처 템플릿 본문 표는 <hp:tbl> 하위 <hp:pos treatAsChar="1"> ("글자처럼 취급")
    이라 표 전체가 인라인 글자로 묶여 페이지 분할이 절대 불가하다. 데이터 주입 후
    1쪽을 초과하면 분할이 깨진다. 검증된 참조 구현(md_uml hwpxReplacer line 360)과
    동일하게 treatAsChar="1"→"0" 으로 바꿔 한컴이 표를 분할하도록 한다.

    머리말·문서번호 등 미주입 표에는 호출하지 않는다(변경 금지).
    """
    pos = tbl.find(hp("pos"))
    if pos is not None and pos.get("treatAsChar") == "1":
        pos.set("treatAsChar", "0")


def scale_table_height(tbl, orig_row_cnt: int, new_row_cnt: int) -> None:
    """행 수가 늘어난 표의 <hp:sz height> 를 newRowCnt/origRowCnt 비율로 확대.

    md_uml hwpxReplacer line 257~260 과 동일: rowCnt 가 바뀐 표만 ABSOLUTE 높이를
    비례 확대한다(행 수 동일 시 변경 안 함). 표 자신의 <hp:sz>(첫 자식) 만 대상으로
    하며 중첩 표/그림의 sz 는 건드리지 않는다.
    """
    if new_row_cnt == orig_row_cnt or orig_row_cnt <= 0:
        return
    sz = tbl.find(hp("sz"))
    if sz is None or sz.get("heightRelTo") != "ABSOLUTE":
        return
    h = sz.get("height")
    if h is None or not h.isdigit():
        return
    sz.set("height", str(round(int(h) * new_row_cnt / orig_row_cnt)))


def inject_rows(tbl, data: list[list[str]], header_rows: int = 1) -> int:
    """헤더행은 보존, 나머지 데이터행을 data 로 교체(복제 주입).

    템플릿 데이터행(헤더 다음 첫 행)을 복제해 len(data) 행 생성.
    rowCnt 갱신. 반환: 주입한 데이터 행 수.
    """
    trs = tbl.findall(hp("tr"))
    orig_row_cnt = int(tbl.get("rowCnt") or len(trs))
    template_tr = trs[header_rows]  # 첫 데이터행을 템플릿으로
    # 기존 데이터행 제거
    for tr in trs[header_rows:]:
        tbl.remove(tr)
    # 데이터 주입
    for n, row in enumerate(data):
        new_tr = clone_data_row(tbl, template_tr, header_rows + n, row)
        tbl.append(new_tr)
    # rowCnt 갱신
    new_row_cnt = header_rows + len(data)
    tbl.set("rowCnt", str(new_row_cnt))
    # 페이지 분할 허용 + 행 증가분만큼 표 높이 비례 확대 (md_uml 정합)
    enable_table_page_split(tbl)
    scale_table_height(tbl, orig_row_cnt, new_row_cnt)
    return len(data)


def build_content_cell_paragraphs(tc, lines: list[str]) -> None:
    """기술서 콘텐츠 셀(tc)의 문단을 lines 로 재구성(서식 보존)."""
    sublist = tc.find(hp("subList"))
    paras = sublist.findall(hp("p"))
    template_p = paras[1] if len(paras) > 1 else paras[0]
    for p in paras:
        sublist.remove(p)
    for line in lines:
        new_p = copy.deepcopy(template_p)
        runs = new_p.findall(hp("run"))
        filled = False
        for run in runs:
            for t in run.findall(hp("t")):
                run.remove(t)
            if not filled:
                t = etree.SubElement(run, hp("t"))
                t.text = line if line.strip() else " "
                filled = True
        sublist.append(new_p)


def set_pic_in_cell(tc, image_id: str, width_hwpunit: int, height_hwpunit: int,
                    template_pic, obj_id: int) -> None:
    """colspan 셀에 그림(hp:pic)을 삽입. template_pic 복제 후 크기/참조/지오메트리 갱신.

    obj_id: 그림 객체의 유일 id/instid (복제 시 중복 방지 — Hangul 오픈 안정성).
    """
    sublist = tc.find(hp("subList"))
    for p in list(sublist.findall(hp("p"))):
        sublist.remove(p)
    p = etree.SubElement(sublist, hp("p"))
    p.set("id", "0")
    p.set("paraPrIDRef", "9")
    p.set("styleIDRef", "0")
    p.set("pageBreak", "0")
    p.set("columnBreak", "0")
    p.set("merged", "0")
    run = etree.SubElement(p, hp("run"))
    run.set("charPrIDRef", "3")
    pic = copy.deepcopy(template_pic)
    # 객체 식별자 유일화 (복제 중복 방지)
    pic.set("id", str(obj_id))
    pic.set("instid", str(obj_id))
    # 표시 크기 갱신 (sz/orgSz/curSz = HWPUNIT 표시 치수)
    for tag in ("sz", "orgSz", "curSz"):
        el = pic.find(hp(tag))
        if el is not None:
            el.set("width", str(width_hwpunit))
            el.set("height", str(height_hwpunit))
    # imgRect = 표시 사각형 4꼭짓점(HWPUNIT). 전체 이미지 표시.
    rect = pic.find(hp("imgRect"))
    if rect is not None:
        pts = rect.findall(hc("pt0")) + rect.findall(hc("pt1")) + \
            rect.findall(hc("pt2")) + rect.findall(hc("pt3"))
        coords = [(0, 0), (width_hwpunit, 0),
                  (width_hwpunit, height_hwpunit), (0, height_hwpunit)]
        for el, (x, y) in zip(pts, coords):
            el.set("x", str(x))
            el.set("y", str(y))
    # imgClip = 전체 이미지(크롭 없음), imgDim = 표시 치수로 정렬
    clip = pic.find(hp("imgClip"))
    if clip is not None:
        clip.set("left", "0")
        clip.set("right", str(width_hwpunit))
        clip.set("top", "0")
        clip.set("bottom", str(height_hwpunit))
    dim = pic.find(hp("imgDim"))
    if dim is not None:
        dim.set("dimwidth", str(width_hwpunit))
        dim.set("dimheight", str(height_hwpunit))
    img = pic.find(".//" + hc("img"))
    if img is not None:
        img.set("binaryItemIDRef", image_id)
    run.append(pic)
    # 문단 라인세그
    lsa = etree.SubElement(p, hp("linesegarray"))
    seg = etree.SubElement(lsa, hp("lineseg"))
    for k, v in {"textpos": "0", "vertpos": "0", "vertsize": "1000",
                 "textheight": "1000", "baseline": "850", "spacing": "600",
                 "horzpos": "0", "horzsize": "42520", "flags": "393216"}.items():
        seg.set(k, v)


# ===========================================================================
# 2b. 복제 후처리 — tbl id/zOrder 유일화 · linesegarray 제거 · cellAddr 가드
#     (검증된 참조 구현 md_uml 의 deduplicateTblIds / splitNewlinesIntoParagraphs
#      후처리 순서를 lxml 트리 조작으로 이식. 페이지 분할 깨짐의 실제 원인 해소.)
# ===========================================================================

def deduplicate_tbl_ids(root) -> int:
    """문서 내 모든 <hp:tbl> 의 id 를 유일하게 재채번.

    템플릿 블록을 deepcopy 복제하면 원본 id 가 그대로 중복된다. 한글은 동일 표 id
    공유 시 줄배치/페이지 계산이 깨질 수 있다(md_uml deduplicateTblIds 이식).
    첫 등장 id 는 보존, 이후 중복분만 새 유일값으로 교체. 반환: 재채번한 표 수.
    """
    seen: set[str] = set()
    next_id = [10000000]
    renumbered = 0

    def fresh() -> str:
        while str(next_id[0]) in seen:
            next_id[0] += 1
        val = str(next_id[0])
        next_id[0] += 1
        return val

    for tbl in root.iter(hp("tbl")):
        tid = tbl.get("id")
        if tid is None:
            continue
        if tid not in seen:
            seen.add(tid)
            continue
        new_id = fresh()
        seen.add(new_id)
        tbl.set("id", new_id)
        renumbered += 1
    return renumbered


def assign_unique_zorder(root) -> int:
    """zOrder 속성을 가진 모든 객체(hp:tbl/hp:pic 등)에 문서 내 유일 순번 부여.

    복제 시 zOrder 가 중복된다. 한글 정상 출력(md_uml)은 zOrder 중복을 허용하나,
    보수적으로 유일 순번을 부여해 잠재적 겹침 계산 모호성을 제거한다.
    반환: zOrder 를 갱신한 객체 수.
    """
    count = 0
    for el in root.iter():
        if el.get("zOrder") is not None:
            el.set("zOrder", str(count))
            count += 1
    return count


def strip_linesegarray(root) -> int:
    """모든 <hp:linesegarray> 를 제거.

    템플릿(docs/cbd/hwpx/*)은 빈 상태의 stale 줄배치 캐시(linesegarray)를 다수
    보유하고, 블록 복제 시 그대로 증식해 한글의 줄/쪽 재배치를 방해한다. 검증된
    참조 구현(md_uml)의 템플릿·출력은 linesegarray 가 0개이며 한글이 열 때 자동
    재계산한다. 동일 상태로 맞추기 위해 section1 전체에서 전량 제거. 반환: 제거 수.
    """
    removed = 0
    for lsa in list(root.iter(hp("linesegarray"))):
        parent = lsa.getparent()
        if parent is not None:
            parent.remove(lsa)
            removed += 1
    return removed


def find_duplicate_celladdr(root) -> list[str]:
    """표별로 (colAddr,rowAddr) 쌍 중복을 검사. 중복 발견 시 설명 문자열 리스트 반환.

    같은 hp:tbl 안에서 셀 주소가 중복되면 한컴이 파일을 손상으로 거부한다
    (md_uml hwpxReplacer line 457 가드 참고). 정상이면 빈 리스트.
    """
    problems: list[str] = []
    for tbl in root.iter(hp("tbl")):
        seen: dict[tuple, int] = {}
        for tr in tbl.findall(hp("tr")):
            for tc in tr.findall(hp("tc")):
                addr = tc.find(hp("cellAddr"))
                if addr is None:
                    continue
                key = (addr.get("colAddr"), addr.get("rowAddr"))
                seen[key] = seen.get(key, 0) + 1
        dups = [k for k, v in seen.items() if v > 1]
        if dups:
            problems.append(
                f"tbl id={tbl.get('id')} 중복 cellAddr={dups}")
    return problems


def postprocess_section1(root, stats: dict) -> None:
    """복제 후처리 일괄 적용: tbl id 유일화 → zOrder 유일화 → linesegarray 제거
    → cellAddr 중복 가드(발견 시 즉시 실패)."""
    stats["tbl_ids_renumbered"] = deduplicate_tbl_ids(root)
    stats["zorder_assigned"] = assign_unique_zorder(root)
    stats["linesegarray_removed"] = strip_linesegarray(root)
    dups = find_duplicate_celladdr(root)
    if dups:
        raise ValueError(
            "cellAddr 중복 감지 — 한컴 파일 손상 거부 위험:\n  "
            + "\n  ".join(dups))


# ===========================================================================
# 3. PlantUML 렌더링
# ===========================================================================

def ensure_plantuml(tools_dir: str) -> str | None:
    """tools/plantuml.jar 확보. 없으면 다운로드. 실패 시 None."""
    os.makedirs(tools_dir, exist_ok=True)
    jar = os.path.join(tools_dir, "plantuml.jar")
    if os.path.exists(jar) and os.path.getsize(jar) > 100000:
        return jar
    try:
        print(f"[plantuml] downloading {PLANTUML_URL}")
        urllib.request.urlretrieve(PLANTUML_URL, jar)
        if os.path.getsize(jar) > 100000:
            return jar
    except Exception as e:  # noqa: BLE001
        print(f"[plantuml] download failed: {e}")
    return None


def render_puml(jar: str, puml_text: str, out_png: str) -> bool:
    """puml 텍스트를 smetana 레이아웃으로 PNG 렌더. 성공 시 True."""
    puml_path = out_png.replace(".png", ".puml")
    # smetana 강제: @startuml 다음 줄에 !pragma layout smetana 삽입
    body = puml_text
    if "@startuml" in body:
        body = body.replace("@startuml", "@startuml\n!pragma layout smetana", 1)
    with open(puml_path, "w", encoding="utf-8") as f:
        f.write(body)
    try:
        subprocess.run(
            ["java", "-jar", jar, "-tpng", "-Playout=smetana",
             "-o", os.path.dirname(os.path.abspath(out_png)), puml_path],
            check=True, capture_output=True, timeout=120,
        )
        # plantuml 은 @startuml NAME 의 NAME 으로 파일명 생성 -> 정규화
        produced = puml_path.replace(".puml", ".png")
        if os.path.exists(produced) and produced != out_png:
            os.replace(produced, out_png)
        # @startuml 에 이름이 있으면 그 이름.png 로 떨어질 수 있음
        if not os.path.exists(out_png):
            m = re.search(r"@startuml\s+(\S+)", puml_text)
            if m:
                cand = os.path.join(os.path.dirname(out_png), m.group(1) + ".png")
                if os.path.exists(cand):
                    os.replace(cand, out_png)
        # 중간 산출물(.puml) 정리 — 출력 디렉토리에 결과물만 남긴다
        if os.path.exists(puml_path):
            os.remove(puml_path)
        return os.path.exists(out_png)
    except Exception as e:  # noqa: BLE001
        print(f"[plantuml] render failed: {e}")
        if os.path.exists(puml_path):
            os.remove(puml_path)
        return False


def png_size(path: str) -> tuple[int, int]:
    """PNG 픽셀 크기(width, height) 추출(표준 라이브러리 struct)."""
    import struct
    with open(path, "rb") as f:
        data = f.read(33)
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return (0, 0)
    w, h = struct.unpack(">II", data[16:24])
    return (w, h)


# ===========================================================================
# 4. 메인 채움 로직
# ===========================================================================

def fill_section1(sec_xml: bytes, md: str, bindata: dict, content_hpf_items: list,
                  tools_dir: str, out_dir: str,
                  start_img_index: int = 1) -> tuple[bytes, dict]:
    """section1.xml 에 md 데이터 주입. (새 xml, 통계) 반환.

    bindata: {filename: bytes} 에 추가될 이미지를 채운다.
    content_hpf_items: content.hpf 에 추가할 (id, href, mediatype) 리스트.
    start_img_index: 신규 이미지 ID 시작 번호(기존 manifest 최대 +1).
    """
    parser = etree.XMLParser(remove_blank_text=False)
    root = etree.fromstring(sec_xml, parser)
    paras = top_level_paragraphs(root)
    stats = {}

    # 표 인덱싱: 헤더 텍스트로 식별
    table_by_p = {}
    for p in paras:
        tbl = find_table_in_p(p)
        if tbl is not None:
            table_by_p[p] = tbl

    # --- 1. 서브시스템 목록 (5x3) ---
    subs = extract_subsystems(md)
    for p, tbl in table_by_p.items():
        if header_texts(tbl)[:1] == ["서브시스템 ID"]:
            stats["subsystems"] = inject_rows(tbl, subs, header_rows=1)
            break

    # --- 3. 유스케이스 목록 (6x6) ---
    ucs = extract_uc_list(md)
    for p, tbl in table_by_p.items():
        if header_texts(tbl)[:1] == ["유스케이스 ID"]:
            stats["uc_list"] = inject_rows(tbl, ucs, header_rows=1)
            break

    # --- 4. 액터 목록 (6x4) ---
    actors = extract_actors(md)
    for p, tbl in table_by_p.items():
        if header_texts(tbl)[:1] == ["액터 ID"]:
            stats["actors"] = inject_rows(tbl, actors, header_rows=1)
            break

    # --- 2. UCD (3x4) 블록 복제 ---
    ucds = extract_ucds(md)
    jar = ensure_plantuml(tools_dir)
    img_counter = [start_img_index]
    stats["ucd_blocks"] = 0
    stats["ucd_images"] = 0
    # 기존 image1 의 hp:pic 템플릿(section0 에서 가져옴) — 여기선 None 일 수 있음
    pic_template = bindata.get("__pic_template__")
    for p, tbl in list(table_by_p.items()):
        ht = header_texts(tbl)
        if ht[:1] == ["UCD ID"]:
            ucd_template_p = p
            stats["ucd_blocks"] = inject_ucd_blocks(
                root, ucd_template_p, tbl, ucds, jar, out_dir,
                bindata, content_hpf_items, img_counter, pic_template, stats)
            break

    # --- 5. 유스케이스 기술서 (2x4) 블록 복제 ---
    specs = extract_uc_specs(md)
    for p, tbl in list(table_by_p.items()):
        if header_texts(tbl)[:1] == ["유스케이스ID"]:
            stats["uc_specs"] = inject_spec_blocks(root, p, tbl, specs)
            break

    # --- 복제 후처리: tbl id/zOrder 유일화 + linesegarray 제거 + cellAddr 가드 ---
    postprocess_section1(root, stats)

    new_xml = etree.tostring(root, xml_declaration=True, encoding="UTF-8",
                             standalone=True)
    return new_xml, stats


def inject_ucd_blocks(root, template_p, template_tbl, ucds, jar, out_dir,
                      bindata, content_hpf_items, img_counter, pic_template,
                      stats) -> int:
    """UCD 템플릿 표 p 를 UCD 개수만큼 복제하여 root 내 위치에 삽입."""
    parent = template_p.getparent()
    insert_idx = list(parent).index(template_p)
    parent.remove(template_p)
    count = 0
    for n, ucd in enumerate(ucds):
        new_p = copy.deepcopy(template_p)
        tbl = find_table_in_p(new_p)
        trs = tbl.findall(hp("tr"))
        # row0: UCD ID 값(col1), UCD 명 값(col3)
        r0 = trs[0].findall(hp("tc"))
        set_cell_text(r0[1], ucd["id"])
        set_cell_text(r0[3], ucd["name"])
        # row1: 관련 서브시스템 ID(col1), 명(col3)
        r1 = trs[1].findall(hp("tc"))
        set_cell_text(r1[1], ucd["ss_id"])
        set_cell_text(r1[3], ucd["ss_name"])
        # row2: 다이어그램 셀(colspan=4)
        diagram_tc = trs[2].find(hp("tc"))
        placed = False
        if jar and ucd["puml"].strip() and pic_template is not None:
            png = os.path.join(out_dir, f"{ucd['id']}.png")
            if render_puml(jar, ucd["puml"], png):
                img_id = f"image{img_counter[0]}"
                fname = f"BinData/{img_id}.png"
                with open(png, "rb") as f:
                    bindata[fname] = f.read()
                content_hpf_items.append((img_id, fname, "image/png"))
                pw, ph = png_size(png)
                # 픽셀 -> HWPUNIT (대략 1px ≈ 75 HWPUNIT, 셀폭 제한 ~45000)
                wu = min(45000, pw * 60)
                hu = int(wu * ph / pw) if pw else 10000
                obj_id = 90000000 + img_counter[0]
                set_pic_in_cell(diagram_tc, img_id, wu, hu, pic_template, obj_id)
                img_counter[0] += 1
                stats["ucd_images"] += 1
                placed = True
        if not placed:
            set_cell_text(diagram_tc, f"(별첨 다이어그램: {ucd['name']})")
        # 복제 표도 페이지 분할 허용 (treatAsChar 1→0). 행 수는 템플릿과 동일하므로
        # md_uml 정합상 height 비례확대는 하지 않는다(콘텐츠는 그림 셀이 자동 흡수).
        enable_table_page_split(tbl)
        parent.insert(insert_idx + n, new_p)
        count += 1
    return count


# OWPML hp:tbl/@pageBreak 쪽 경계 분할 정책 (한글 '표/셀 속성 > 쪽 경계에서' UI):
#   "TABLE" = 나누지 않음(표 전체를 다음 쪽으로)
#   "CELL"  = 셀 단위로 나눔(셀 경계에서만 분할, 셀 내부 분할 금지) — 템플릿 기본값
#   "NONE"  = 나눔(셀 내부에서도 분할 허용)
#
# [2차 픽스] 1차 픽스(기술서 표 pageBreak=NONE)는 한글 실측에서 효과 없음 판명.
# 검증된 참조 구현(md_uml)은 전 표 템플릿 원본 pageBreak("CELL")을 그대로 유지하고,
# 페이지 분할 깨짐의 실제 원인은 ① 복제된 hp:tbl 의 id 중복, ② stale 줄배치 캐시
# (hp:linesegarray) 복제였다. 따라서 pageBreak 는 템플릿 원본(CELL)을 보존한다.


def inject_spec_blocks(root, template_p, template_tbl, specs) -> int:
    """기술서 템플릿 표 p 를 UC 개수만큼 복제하여 삽입.

    pageBreak 는 템플릿 원본값(CELL)을 그대로 보존한다(md_uml 정합).
    페이지 분할은 후처리(tbl id 유일화 + linesegarray 제거)로 한글이 재계산한다.
    """
    parent = template_p.getparent()
    insert_idx = list(parent).index(template_p)
    parent.remove(template_p)
    count = 0
    for n, spec in enumerate(specs):
        new_p = copy.deepcopy(template_p)
        tbl = find_table_in_p(new_p)
        trs = tbl.findall(hp("tr"))
        # row0: 유스케이스ID 값(col1), 유스케이스명 값(col3)
        r0 = trs[0].findall(hp("tc"))
        set_cell_text(r0[1], spec["id"])
        set_cell_text(r0[3], spec["name"])
        # row1: colspan 콘텐츠 셀
        content_tc = trs[1].find(hp("tc"))
        build_content_cell_paragraphs(content_tc, spec["paragraphs"])
        # 페이지 분할 허용 (treatAsChar 1→0) — 기술서 표 분할 깨짐의 진범 해소.
        # 행 수(2행)는 콘텐츠가 늘어도 동일하므로 md_uml 정합상 height 비례확대는
        # 하지 않는다(콘텐츠 셀이 길어지면 한컴이 셀 높이를 자동 재계산하며,
        # treatAsChar="0" 덕에 쪽 경계에서 분할된다).
        enable_table_page_split(tbl)
        parent.insert(insert_idx + n, new_p)
        count += 1
    return count


def find_pic_template(section0_xml: bytes):
    """section0 의 첫 hp:pic 를 이미지 삽입 템플릿으로 추출."""
    root = etree.fromstring(section0_xml)
    pic = root.find(".//" + hp("pic"))
    return copy.deepcopy(pic) if pic is not None else None


def update_content_hpf(hpf_xml: bytes, new_items: list) -> bytes:
    """content.hpf manifest 에 신규 이미지 item 추가."""
    if not new_items:
        return hpf_xml
    root = etree.fromstring(hpf_xml)
    manifest = root.find(".//" + f"{{{OPF}}}manifest")
    for img_id, href, mtype in new_items:
        item = etree.SubElement(manifest, f"{{{OPF}}}item")
        item.set("id", img_id)
        item.set("href", href)
        item.set("media-type", mtype)
        item.set("isEmbeded", "1")
    return etree.tostring(root, xml_declaration=True, encoding="UTF-8",
                          standalone=True)


# ===========================================================================
# 5. ZIP 재패키징
# ===========================================================================

def repackage(src_hwpx: str, out_hwpx: str, replacements: dict,
              extra_files: dict) -> None:
    """원본 엔트리 순서/압축 보존하며 replacements 적용, extra_files 추가."""
    zin = zipfile.ZipFile(src_hwpx, "r")
    os.makedirs(os.path.dirname(out_hwpx), exist_ok=True)
    with zipfile.ZipFile(out_hwpx, "w") as zout:
        for item in zin.infolist():
            data = replacements.get(item.filename, zin.read(item.filename))
            # 압축 방식/속성 보존
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zi.internal_attr = item.internal_attr
            zi.create_system = item.create_system
            zout.writestr(zi, data)
        # 신규 BinData 이미지 추가
        for fname, data in extra_files.items():
            if fname.startswith("__"):
                continue
            zi = zipfile.ZipInfo(fname)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(zi, data)
    zin.close()


# ===========================================================================
# 6. CLI
# ===========================================================================

def run_fill(args) -> str:
    with open(args.md, encoding="utf-8") as f:
        md = f.read()
    zin = zipfile.ZipFile(args.hwpx, "r")
    sec1 = zin.read("Contents/section1.xml")
    sec0 = zin.read("Contents/section0.xml")
    hpf = zin.read("Contents/content.hpf")
    zin.close()

    out_dir = args.out
    os.makedirs(out_dir, exist_ok=True)
    tools_dir = os.path.join(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))), "tools")

    bindata: dict = {}
    bindata["__pic_template__"] = find_pic_template(sec0)
    content_items: list = []

    # 기존 content.hpf manifest 의 최대 imageN 인덱스 산출 -> 신규는 그 다음부터
    existing_ids = re.findall(r'id="image(\d+)"', hpf.decode("utf-8"))
    start_idx = (max(int(x) for x in existing_ids) + 1) if existing_ids else 1

    new_sec1, stats = fill_section1(
        sec1, md, bindata, content_items, tools_dir, out_dir,
        start_img_index=start_idx)
    new_hpf = update_content_hpf(hpf, content_items)

    replacements = {
        "Contents/section1.xml": new_sec1,
        "Contents/content.hpf": new_hpf,
    }
    out_name = os.path.splitext(os.path.basename(args.hwpx))[0] + "_filled.hwpx"
    out_path = os.path.join(out_dir, out_name)
    repackage(args.hwpx, out_path, replacements, bindata)

    print("=== 주입 통계 ===")
    for k, v in stats.items():
        print(f"  {k}: {v}")
    print(f"=== 생성: {out_path}")
    return out_path


def run_verify(args) -> int:
    out_path = args.verify
    src = args.hwpx
    with open(args.md, encoding="utf-8") as f:
        md = f.read()
    results = []

    def check(name, cond):
        results.append((name, bool(cond)))

    # 1. ZIP 무결성 + 엔트리 존재
    try:
        zout = zipfile.ZipFile(out_path, "r")
        bad = zout.testzip()
        check("ZIP_무결성", bad is None)
        names = zout.namelist()
        check("mimetype_엔트리_존재", "mimetype" in names)
        check("section1_엔트리_존재", "Contents/section1.xml" in names)
    except Exception as e:  # noqa: BLE001
        check("ZIP_오픈", False)
        print(f"  ZIP open error: {e}")
        _report(results)
        return 1

    # 2. section0 byte-identical
    zsrc = zipfile.ZipFile(src, "r")
    check("section0_바이트동일",
          zout.read("Contents/section0.xml") == zsrc.read("Contents/section0.xml"))

    # 2b. content.hpf 이미지 ID 유일성 + 참조 무결성
    hpf_text = zout.read("Contents/content.hpf").decode("utf-8")
    img_ids = re.findall(r'id="(image\d+)"', hpf_text)
    check("이미지ID_유일성", len(img_ids) == len(set(img_ids)))
    refs = re.findall(r'binaryItemIDRef="(image\d+)"',
                      zout.read("Contents/section1.xml").decode("utf-8"))
    check("이미지참조_매니페스트_존재", all(r in set(img_ids) for r in refs))

    # 3. section1 well-formed + 행수 검증
    sec1 = zout.read("Contents/section1.xml")
    wf = True
    try:
        root = etree.fromstring(sec1)
    except Exception as e:  # noqa: BLE001
        wf = False
        print(f"  section1 parse error: {e}")
    check("section1_XML_well_formed", wf)

    if wf:
        # 그림 객체 id/instid 유일성 (복제 시 중복 -> Hangul 오픈 오류 방지)
        pics = root.findall(".//" + hp("pic"))
        pic_ids = [p.get("id") for p in pics]
        pic_insts = [p.get("instid") for p in pics]
        check("그림객체_id_유일성", len(pic_ids) == len(set(pic_ids)))
        check("그림객체_instid_유일성", len(pic_insts) == len(set(pic_insts)))

        tbls = root.findall(".//" + hp("tbl"))
        # rowCnt == 실제 tr 수
        rowcnt_ok = all(
            int(t.get("rowCnt")) == len(t.findall(hp("tr"))) for t in tbls)
        check("모든_tbl_rowCnt_정합", rowcnt_ok)

        def find_tbl(hdr0):
            for t in tbls:
                first = t.find(hp("tr"))
                tc0 = first.find(hp("tc"))
                ts = " ".join(x.text for x in tc0.findall(".//" + hp("t"))
                              if x.text and x.text.strip())
                if ts == hdr0:
                    return t
            return None

        subs = extract_subsystems(md)
        actors = extract_actors(md)
        ucs = extract_uc_list(md)
        specs = extract_uc_specs(md)
        ucds = extract_ucds(md)

        t = find_tbl("서브시스템 ID")
        check("서브시스템_행수_일치",
              t is not None and len(t.findall(hp("tr"))) == 1 + len(subs))
        t = find_tbl("유스케이스 ID")
        check("UC목록_행수_일치",
              t is not None and len(t.findall(hp("tr"))) == 1 + len(ucs))
        t = find_tbl("액터 ID")
        check("액터_행수_일치",
              t is not None and len(t.findall(hp("tr"))) == 1 + len(actors))

        # 기술서 표 개수 == UC 개수
        spec_tbls = [t for t in tbls
                     if (t.find(hp("tr")).find(hp("tc")) is not None and
                         "".join(x.text or "" for x in
                                 t.find(hp("tr")).find(hp("tc")).findall(
                                     ".//" + hp("t"))).strip() == "유스케이스ID")]
        check("기술서_블록수_UC개수_일치", len(spec_tbls) == len(specs))

        # --- 4차 픽스: 기술서 셀 빈 줄 중복 제거 검증 ---
        # 각 기술서 블록의 콘텐츠 셀(2행 colspan)이 주입한 normalize 문단과
        # 동일한 문단 수를 가지며, 연속 빈 문단(빈 줄 2개 이상)이 없는지 확인.
        spec_para_ok = True
        no_consecutive_blank = True
        for tbl, spec in zip(spec_tbls, specs):
            trs2 = tbl.findall(hp("tr"))
            if len(trs2) < 2:
                spec_para_ok = False
                break
            content_tc = trs2[1].find(hp("tc"))
            cps = content_tc.find(hp("subList")).findall(hp("p"))
            if len(cps) != len(spec["paragraphs"]):
                spec_para_ok = False
            blanks = []
            for cp in cps:
                joined = "".join(
                    t.text or "" for run in cp.findall(hp("run"))
                    for t in run.findall(hp("t")))
                blanks.append(not joined.strip())
            if any(blanks[i] and blanks[i + 1]
                   for i in range(len(blanks) - 1)):
                no_consecutive_blank = False
        check("기술서_셀_문단수_normalize_일치", spec_para_ok)
        check("기술서_셀_연속빈문단_없음", no_consecutive_blank)

        # 기술서 블록 + 그 외 표 모두 템플릿 원본 pageBreak(CELL) 유지
        # (1차 픽스 NONE 롤백 — md_uml 정합)
        check("기술서_블록_pageBreak_CELL_유지",
              len(spec_tbls) > 0 and
              all(t.get("pageBreak") == "CELL" for t in spec_tbls))
        non_spec_tbls = [t for t in tbls if t not in spec_tbls]
        check("비기술서_표_pageBreak_CELL_유지",
              all(t.get("pageBreak") == "CELL" for t in non_spec_tbls))

        # --- 2차 픽스 핵심 검증 (md_uml 정상 출력과 동일 상태) ---
        # ① 모든 hp:tbl id 유일
        tbl_ids = [t.get("id") for t in tbls]
        check("모든_tbl_id_유일",
              all(i is not None for i in tbl_ids) and
              len(tbl_ids) == len(set(tbl_ids)))
        # ② zOrder 유일 (zOrder 속성 보유 객체 한정)
        zos = [el.get("zOrder") for el in root.iter()
               if el.get("zOrder") is not None]
        check("모든_zOrder_유일", len(zos) == len(set(zos)))
        # ③ linesegarray 0개 (한글이 줄/쪽 재계산)
        lsa_cnt = len(root.findall(".//" + hp("linesegarray")))
        check("linesegarray_0개", lsa_cnt == 0)
        # ④ 표별 cellAddr (col,row) 중복 0건
        celladdr_dups = []
        for t in tbls:
            seen = {}
            for tr in t.findall(hp("tr")):
                for tc in tr.findall(hp("tc")):
                    a = tc.find(hp("cellAddr"))
                    if a is None:
                        continue
                    key = (a.get("colAddr"), a.get("rowAddr"))
                    seen[key] = seen.get(key, 0) + 1
            celladdr_dups += [k for k, v in seen.items() if v > 1]
        check("표별_cellAddr_중복_0건", len(celladdr_dups) == 0)

        # UCD 표 개수 == UCD 개수
        ucd_tbls = [t for t in tbls
                    if "".join(x.text or "" for x in
                               t.find(hp("tr")).find(hp("tc")).findall(
                                   ".//" + hp("t"))).strip() == "UCD ID"]
        check("UCD_블록수_일치", len(ucd_tbls) == len(ucds))

        # --- 3차 픽스 핵심 검증: treatAsChar (표 페이지 분할 허용) ---
        def _hdr0(t):
            tc0 = t.find(hp("tr")).find(hp("tc"))
            return "".join(x.text or "" for x in
                           tc0.findall(".//" + hp("t"))).strip()

        def _tac(t):
            pos = t.find(hp("pos"))
            return pos.get("treatAsChar") if pos is not None else None

        injected_headers = {
            "서브시스템 ID", "유스케이스 ID", "액터 ID", "UCD ID", "유스케이스ID"}
        injected_tbls = [t for t in tbls if _hdr0(t) in injected_headers]
        # ① 주입/복제 본문 표는 전부 treatAsChar="0" (페이지 분할 허용)
        check("주입표_treatAsChar_0",
              len(injected_tbls) > 0 and
              all(_tac(t) == "0" for t in injected_tbls))
        # ② 미주입 표(머리말 등)는 원본 treatAsChar 분포 그대로 유지 (변경 금지)
        src_root = etree.fromstring(zsrc.read("Contents/section1.xml"))
        src_tbls = src_root.findall(".//" + hp("tbl"))
        out_non = sorted(_tac(t) for t in tbls
                         if _hdr0(t) not in injected_headers)
        src_non = sorted(_tac(t) for t in src_tbls
                         if _hdr0(t) not in injected_headers)
        check("미주입표_treatAsChar_원본유지", out_non == src_non)
        # ③ 행 늘어난 row-주입 표는 hp:sz height 가 비례 확대됨
        src_by_hdr = {_hdr0(t): t for t in src_tbls}
        height_ok = True
        for hdr in ("서브시스템 ID", "유스케이스 ID", "액터 ID"):
            out_t = next((t for t in tbls if _hdr0(t) == hdr), None)
            src_t = src_by_hdr.get(hdr)
            if out_t is None or src_t is None:
                height_ok = False
                break
            orc, nrc = int(src_t.get("rowCnt")), int(out_t.get("rowCnt"))
            if nrc == orc:
                continue
            sh = src_t.find(hp("sz"))
            oh = out_t.find(hp("sz"))
            if sh is None or oh is None:
                height_ok = False
                break
            exp = round(int(sh.get("height")) * nrc / orc)
            if int(oh.get("height")) != exp:
                height_ok = False
                break
        check("행증가표_height_비례확대", height_ok)

    zsrc.close()
    zout.close()
    return _report(results)


def _report(results) -> int:
    print("=== 라운드트립 검증 ===")
    failed = 0
    for name, ok in results:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
        if not ok:
            failed += 1
    print(f"=== {len(results) - failed}/{len(results)} PASS")
    return 0 if failed == 0 else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description="md -> hwpx 데이터 주입 PoC")
    ap.add_argument("--md", required=True)
    ap.add_argument("--hwpx", required=True)
    ap.add_argument("--out", default="docs/design/hwpx/")
    ap.add_argument("--verify", help="검증할 출력 hwpx 경로")
    args = ap.parse_args(argv)
    if args.verify:
        return run_verify(args)
    run_fill(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
