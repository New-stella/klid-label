-- ============================================================================
-- V33 — 포털 사용자 메타·이벤트 어노테이션 오버레이 두 표 신설
--       @design ERD-018 · API-234 · API-235 · API-236 · API-237 · UC-024 · AC-1068
--
--   ls_portal_user_meta       (8칸)  — 촬영환경·프레임 설명·개인정보 판정·시계열 메타의 K/V 오버레이
--   ls_portal_user_evnt_anno  (6칸)  — 이벤트 어노테이션 구조체 오버레이
--
-- ----------------------------------------------------------------------------
-- * 왜 이 두 표가 필요한가
-- ----------------------------------------------------------------------------
--   포털 사용자가 데이터마트에서 불러온 영상의 메타와 이벤트 어노테이션을 확인·수정·추가한다
--   (UC-024 step2·4·5 / AC-1068). 그 결과는 <포털 전용 저장소에만> 쌓이고 원본과 승인 시점
--   동결본을 수정하지 않는다 — 단방향이라 데이터마트로 되돌아가지 않는다.
--   그 요구를 담을 자리가 어디에도 없었다. 이 파일이 그 자리를 만든다.
--
--   ★ 원장에 합치거나 소유자 구분으로 섞지 않는다. 구분을 한 번만 잊으면 남의 오버레이가
--     정본으로 읽히는 fail-open 이 되고, 그 누락은 새 조회 경로가 생길 때마다 다시 열린다.
--     분리는 조회 필터가 아니라 <표 자체>로 지킨다(ls_portal_user_label 과 같은 근거).
--
-- ----------------------------------------------------------------------------
-- * 왜 두 표로 나누는가 (한 표에 섞지 않는다)
-- ----------------------------------------------------------------------------
--   메타 넷은 내부 원장과 같은 <키/값>이고 이벤트 어노테이션은 <구조체>다. 한 표에 섞으면
--   본문 폭과 조회 모양이 서로를 제약한다. 어노테이션을 키/값으로 펴는 안은 채택하지 않았다 —
--   산출 문서와 모양이 갈려 내보낼 때마다 재조립이 필요하고 사고 단계 같은 중첩이 무너진다.
--
-- ----------------------------------------------------------------------------
-- * 표준용어 근거 (규칙 3 — 우선순위 (1)행안부 공통표준 -> (2)사업표준 -> (3)신규)
-- ----------------------------------------------------------------------------
--   판정은 docs/ 아래 CSV 정본 전수 대조로 한다(검색 API 는 상한 때문에 "미등록" 오판을 낸다).
--   <신규 등록이 필요 없다> — 구성 단어가 전부 등재어다.
--
--     사용자   USER  (행안부 공통표준단어 2차)      일련번호 SN    (행안부 공통표준단어 2차)
--     값       VL    (행안부 공통표준단어 5차)      내용     CN    (행안부 공통표준단어 2차)
--     메타     META  (사업표준단어)                 키       KEY   (사업표준단어)
--     이벤트   EVNT  (사업표준단어)                 어노테이션 ANNO (사업표준단어)
--     원시     RAW   (사업표준단어)
--     포털사용자번호 PORTAL_USER_NO — 사업표준용어 등재분(ls_data_raw·ls_portal_user_label 과 동일)
--
--   ⚠ 값 약어는 행안부 VL 이다. 사업 사전의 VALUE 가 아니다(우선순위 (1)).
--
--   타입·폭은 <대응하는 내부 원장 칸과 같게> 맞춘다 — 그래야 넘쳐서 잘리는 지점이 같다.
--     meta_key  character varying(64)   = ls_data_meta.meta_key
--     meta_vl   character varying(2000) = ls_data_meta.meta_vl
--     anno_cn   jsonb                   = ls_evnt_anno.anno_cn
--
-- ----------------------------------------------------------------------------
-- * ★ 프레임 참조는 영상 축 메타일 때 <비운다>
-- ----------------------------------------------------------------------------
--   src_data_src_sn 은 nullable 이다. 프레임 축 메타일 때만 채우고 영상 축 메타는 비운다 —
--   채우면 <없는 프레임을 가리킨다>. 이 축 구분은 창구가 원소마다 표시해 주고받는다.
--
-- ----------------------------------------------------------------------------
-- * ★★ 유일 제약을 <두 벌>로 나누는 이유 — NULL 은 서로 다른 값이다
-- ----------------------------------------------------------------------------
--   설계가 정한 적재 키는 (포털사용자, 영상, 프레임, 메타키) 하나다. 그런데 그것을 단일
--   UNIQUE 로 만들면 <영상 축 행에서 성립하지 않는다> — PostgreSQL 의 기본 유일 인덱스는
--   NULL 을 서로 다른 값으로 보므로, 프레임이 비어 있는 행끼리는 아무리 같아도 충돌하지 않는다.
--   그러면 같은 키를 다시 저장할 때 덮어쓰지 않고 <행이 계속 쌓이고>, ON CONFLICT 도 걸리지
--   않아 저장 창구가 조용히 중복을 만든다.
--
--   그래서 조건부 유일 인덱스 두 벌로 나눈다. 둘을 합치면 설계가 정한 그 키 하나다.
--     · 프레임 축 : 프레임이 있는 행에 대해 (사용자, 영상, 프레임, 키)
--     · 영상  축 : 프레임이 없는 행에 대해 (사용자, 영상, 키)
--
--   NULLS NOT DISTINCT 를 쓰지 않는 이유는 그 문법이 PostgreSQL 15 이상 전용이라, 배포 대상
--   판올림 상태에 따라 <신규 설치가 통째로 실패>할 수 있기 때문이다. 조건부 인덱스는 어느
--   판에서나 성립하고 ON CONFLICT 의 대상 추론에도 그대로 쓸 수 있다.
--
-- ----------------------------------------------------------------------------
-- * 삭제 전파
-- ----------------------------------------------------------------------------
--   원천 영상이 지워지면 두 오버레이도 함께 지운다(ON DELETE CASCADE). 원본을 수정하지 않는
--   것과 삭제 전파는 <다른 축>이다 — 부모가 사라진 오버레이는 가리킬 대상이 없다.
--   ls_portal_user_label 의 fk_ls_portal_user_label_raw 와 같은 규약이다.
--
-- ----------------------------------------------------------------------------
-- * 멱등 · 두 경로 수렴
-- ----------------------------------------------------------------------------
--   CREATE TABLE IF NOT EXISTS / CREATE UNIQUE INDEX IF NOT EXISTS 라 두 번 돌아도 안전하다.
--   신규 설치(빈 DB)와 기존 DB 어느 쪽에서도 같은 형상에 도달한다. 외래키는 카탈로그를 보고
--   없을 때만 만든다(ADD CONSTRAINT 에는 IF NOT EXISTS 가 없다).
--
-- ----------------------------------------------------------------------------
-- * 잠금과 배포 창 (2노드 Active-Active)
-- ----------------------------------------------------------------------------
--   새 표를 만들 뿐이라 기존 표를 잠그지 않는다. 외래키 생성이 부모(ls_data_raw)에
--   SHARE ROW EXCLUSIVE 를 잠깐 잡지만 <새 표가 비어 있어> 검증 대상 행이 없다.
--
--   ★ 이 변경은 <하위호환>이다. 구 jar 노드는 두 표를 모르므로 아무것도 읽지 않고, 새 jar
--     노드만 쓰고 읽는다. 롤링 재기동으로 무중단 배포가 가능하다.
--
-- ★ 롤백 절차 — Flyway 는 down-migration 을 하지 않으므로 DBA 가 수동 적용.
--   ⚠ 되돌리면 <포털 사용자가 저장한 메타·이벤트 어노테이션이 함께 사라진다>. 그 값은 어디에도
--     사본이 없다(단방향 오버레이라 원장에 반영되지 않는다). 비가역이다.
--
--     DROP TABLE IF EXISTS ls_portal_user_evnt_anno;
--     DROP TABLE IF EXISTS ls_portal_user_meta;
--     DELETE FROM FLYWAY_SCHEMA_HISTORY WHERE VERSION = '33';
--
-- ----------------------------------------------------------------------------
-- * 이번에 손대지 않는 것 (빠뜨린 것이 아니다)
-- ----------------------------------------------------------------------------
--   · ls_data_meta · ls_evnt_anno — 원본 원장은 그대로다. 오버레이가 그것을 덮어쓰지 않는다.
--   · 보존기간 만료 자동 삭제(DFEAT-055) 의 스윕 대상 확대 — 그 기능이 소유하는 별개 축이다.
--     지금은 영상이 지워질 때만 연쇄로 정리된다.
--   · 본인이 올린 자산의 메타·어노테이션 — 그쪽은 오버레이를 쓰지 않고 <그 자산의> 원장에
--     그대로 앉는다(가려야 할 남의 원본이 없다). 그래서 이 표에 자리가 필요 없다.
--
-- ----------------------------------------------------------------------------
-- * 작성 규칙 (이 저장소에서 실제로 깨진 적이 있는 것들)
-- ----------------------------------------------------------------------------
--   · 달러-중괄호 플레이스홀더 표기를 <주석에도> 쓰지 않는다. Flyway 가 placeholder 로 읽어
--     "No value provided for placeholder" 로 파일 전체가 적용되지 않는다.
--   · 스키마 리터럴(public 등)을 박지 않는다. 대상 스키마는 커넥션의 search_path(기본 klid_at)가
--     정한다.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) 포털 사용자 메타 오버레이
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ls_portal_user_meta (
    user_meta_sn    bigint GENERATED BY DEFAULT AS IDENTITY,
    portal_user_no  character varying(100) NOT NULL,
    src_raw_sn      bigint NOT NULL,
    src_data_src_sn bigint,
    meta_key        character varying(64) NOT NULL,
    meta_vl         character varying(2000),
    reg_dt          timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt        timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ls_portal_user_meta_pkey PRIMARY KEY (user_meta_sn)
);

COMMENT ON TABLE ls_portal_user_meta IS
    '포털사용자메타 — 포털 사용자가 데이터마트 영상에서 확인·수정·추가한 메타 값의 단방향 오버레이. 촬영환경·프레임 설명·개인정보 판정·시계열 메타를 담으며 원본(LS_DATA_META)과 동결 스냅샷을 수정하지 않는다. 본인이 올린 자산은 이 표를 쓰지 않는다(가려야 할 남의 원본이 없다).';
COMMENT ON COLUMN ls_portal_user_meta.user_meta_sn IS '포털사용자메타일련번호 — PK.';
COMMENT ON COLUMN ls_portal_user_meta.portal_user_no IS '포털사용자번호 — 포털 발급 토큰 sub 클레임. 본인 데이터만 조회/수정하도록 IDOR 차단 키.';
COMMENT ON COLUMN ls_portal_user_meta.src_raw_sn IS '원시데이터일련번호 — 대상 데이터마트 영상(LS_DATA_RAW).';
COMMENT ON COLUMN ls_portal_user_meta.src_data_src_sn IS '데이터원천일련번호 — 프레임 축 메타일 때 대상 프레임(LS_DATA_SRC). 영상 축 메타는 비운다. 지어내면 없는 프레임을 가리킨다.';
COMMENT ON COLUMN ls_portal_user_meta.meta_key IS '메타키 — 내부 원장(LS_DATA_META.META_KEY)과 같은 키 규격·같은 폭.';
COMMENT ON COLUMN ls_portal_user_meta.meta_vl IS '메타값 — 내부 원장(LS_DATA_META.META_VL)과 같은 폭이라 넘치면 잘리는 지점도 같다.';
COMMENT ON COLUMN ls_portal_user_meta.reg_dt IS '등록일시 — 최초 저장 일시.';
COMMENT ON COLUMN ls_portal_user_meta.mdfcn_dt IS '수정일시 — 마지막 저장 일시.';

-- 적재 키 (포털사용자, 영상, 프레임, 메타키) — 프레임 유무로 두 벌로 나눈다(파일 머리말 참조).
CREATE UNIQUE INDEX IF NOT EXISTS uk_lpum_key
    ON ls_portal_user_meta (portal_user_no, src_raw_sn, src_data_src_sn, meta_key)
    WHERE src_data_src_sn IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_lpum_key_video
    ON ls_portal_user_meta (portal_user_no, src_raw_sn, meta_key)
    WHERE src_data_src_sn IS NULL;

CREATE INDEX IF NOT EXISTS idx_lpum_user
    ON ls_portal_user_meta USING btree (portal_user_no, src_raw_sn);

DO $$
BEGIN
    -- 카탈로그 조회는 <대상 표로 스코프>한다. conname 만 보면 다른 스키마·다른 표의 동명 제약에
    -- 걸려 조용히 건너뛰고, 그러면 이 표에 외래키가 영영 생기지 않는다(이 저장소의 실사고 규칙 1).
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = to_regclass('ls_portal_user_meta')
                      AND conname = 'fk_ls_portal_user_meta_raw'
                      AND contype = 'f') THEN
        ALTER TABLE ls_portal_user_meta
            ADD CONSTRAINT fk_ls_portal_user_meta_raw
            FOREIGN KEY (src_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;
    END IF;
END
$$;

-- ----------------------------------------------------------------------------
-- 2) 포털 사용자 이벤트 어노테이션 오버레이
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ls_portal_user_evnt_anno (
    user_evnt_anno_sn bigint GENERATED BY DEFAULT AS IDENTITY,
    portal_user_no    character varying(100) NOT NULL,
    src_raw_sn        bigint NOT NULL,
    anno_cn           jsonb NOT NULL,
    reg_dt            timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt          timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ls_portal_user_evnt_anno_pkey PRIMARY KEY (user_evnt_anno_sn)
);

COMMENT ON TABLE ls_portal_user_evnt_anno IS
    '포털사용자이벤트어노테이션 — 포털 사용자가 확인·수정·추가한 이벤트 어노테이션의 단방향 오버레이. 원본(LS_EVNT_ANNO)과 승인 시점 동결본을 수정하지 않으며 관제 통지·산출물 재생성을 일으키지 않는다. 영상 축이라 프레임 참조를 두지 않는다.';
COMMENT ON COLUMN ls_portal_user_evnt_anno.user_evnt_anno_sn IS '포털사용자이벤트어노테이션일련번호 — PK.';
COMMENT ON COLUMN ls_portal_user_evnt_anno.portal_user_no IS '포털사용자번호 — 포털 발급 토큰 sub 클레임. 본인 데이터만 조회/수정하도록 IDOR 차단 키.';
COMMENT ON COLUMN ls_portal_user_evnt_anno.src_raw_sn IS '원시데이터일련번호 — 대상 데이터마트 영상(LS_DATA_RAW).';
COMMENT ON COLUMN ls_portal_user_evnt_anno.anno_cn IS '어노테이션내용 — 내부 원장(LS_EVNT_ANNO.ANNO_CN)과 같은 구조체를 그대로 담는다. 키별로 펴지 않는다(산출 문서와 모양이 갈리면 재조립이 필요하고 중첩이 무너진다).';
COMMENT ON COLUMN ls_portal_user_evnt_anno.reg_dt IS '등록일시 — 최초 저장 일시.';
COMMENT ON COLUMN ls_portal_user_evnt_anno.mdfcn_dt IS '수정일시 — 마지막 저장 일시.';

-- 적재 키 (포털사용자, 영상) — 영상당 한 벌이고 다시 저장하면 덮어쓴다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_lpuea_raw
    ON ls_portal_user_evnt_anno (portal_user_no, src_raw_sn);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = to_regclass('ls_portal_user_evnt_anno')
                      AND conname = 'fk_ls_portal_user_evnt_anno_raw'
                      AND contype = 'f') THEN
        ALTER TABLE ls_portal_user_evnt_anno
            ADD CONSTRAINT fk_ls_portal_user_evnt_anno_raw
            FOREIGN KEY (src_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;
    END IF;
END
$$;
