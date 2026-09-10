-- ============================================================================
-- 학습데이터 저작도구 — DB 생성 스크립트 (klid_at)
--
-- 빈 데이터베이스에 이 파일을 그대로 실행하면 전체 스키마가 선다. 손질 없이 쓴다.
--
--   psql -d <데이터베이스> -f KLID_AT_schema.sql
--
-- 담긴 것 — 스키마 1 · 표 80 · 뷰 4 · 시퀀스 54
--           인덱스 224 · 제약 171 · 주석 표 33·컬럼 305
--
-- ★ 초기 데이터는 이 파일에 없다 — KLID_AT_initdata.sql 을 <이 파일 다음에> 실행한다.
--   코드값과 설정 기본값이 없으면 스키마는 서도 앱이 돌지 않는다.
--
-- ★ 스키마 생성부터 포함하므로 접속 세션의 탐색 경로와 무관하게 항상 klid_at 에 만들어진다.
--
-- ★ 순서는 의존 관계를 만족하는 순서다. 보기 좋게 절을 나누려고 옮기면 로드가 깨진다.
--   표를 찾으려면 이름으로 검색하고, 그 뜻은 바로 뒤따르는 COMMENT 를 본다.
--
-- 만든 방식 — 손으로 쓰지 않았다. 형상 관리의 스키마 변경 파일 36개를 클린
--   데이터베이스에 전량 적용한 뒤 덤프한 것이라 실제 스키마와 어긋날 수 없다.
--   재생성: deploy/onprem/scripts/gen-cbd-init-sql.sh
-- ⚠ 수기 편집 금지(생성물). 고칠 것이 있으면 스키마 변경 파일을 고치고 다시 생성한다.
-- ⚠ 설치용 스키마 파일과 다른 파일이다 — 그쪽은 주석을 담지 않는다. 둘을 합치지 말 것.
--
-- 생성 시각: 2026-09-09 17:44:27+0900
-- ============================================================================

--
-- PostgreSQL database dump
--

\restrict shNvhYfPJZSvv7G3dq1sTTp65l0cW4ZgevLaGCssUXmO9GpHtIiwFeVlPFsClFp

-- Dumped from database version 16.13
-- Dumped by pg_dump version 16.13 (Homebrew)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: klid_at; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA klid_at;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: ls_acnt_user; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_acnt_user (
    user_no bigint NOT NULL,
    user_id character varying(20),
    user_nm character varying(100) DEFAULT ''::character varying NOT NULL,
    user_eml_addr character varying(320),
    use_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone,
    last_lgn_dt timestamp without time zone
);


--
-- Name: TABLE ls_acnt_user; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_acnt_user IS '사용자 마스터(저작도구 소유). 역할 클레임 시점에 관제가 브라우저 localStorage 로 인계한 값(userId·userNm)으로 자동 등록·갱신된다. 인가 역할은 이 테이블이 아니라 LS_USER_ROLE 이 단일 진실원이다.';


--
-- Name: COLUMN ls_acnt_user.user_no; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_acnt_user.user_no IS '사용자번호(PK). ★JWT subject(sub)에서만 취한다 — 요청 바디의 값을 신뢰하지 않는다. LS_USER_ROLE.USER_NO 등과 같은 값이다.';


--
-- Name: COLUMN ls_acnt_user.user_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_acnt_user.user_id IS '사용자아이디(표시용). 관제 인계값이며 미수신이면 null 이다(지어내지 않는다). 인증·인가 판정에 쓰지 않는다.';


--
-- Name: COLUMN ls_acnt_user.user_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_acnt_user.user_nm IS '사용자명(표시용). 관제 인계값이며 미수신이면 빈 문자열이다. 배정·검수·통계 화면의 이름 표시 원천이다.';


--
-- Name: COLUMN ls_acnt_user.user_eml_addr; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_acnt_user.user_eml_addr IS '사용자이메일주소. 관제 인계 키에 없으므로 자동등록 사용자는 null 이고, 구 관제 마스터에서 이관된 행만 값을 갖는다.';


--
-- Name: COLUMN ls_acnt_user.use_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_acnt_user.use_yn IS '사용여부(Y/N). 작업자 목록·배정 후보에서 비활성 사용자를 제외하는 필터축이다. ★자동등록은 이 값을 갱신하지 않는다 — 운영자가 비활성화한 사용자가 재클레임으로 되살아나면 안 된다.';


--
-- Name: COLUMN ls_acnt_user.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_acnt_user.reg_dt IS '등록일시.';


--
-- Name: COLUMN ls_acnt_user.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_acnt_user.mdfcn_dt IS '수정일시. 자동등록이 표시 정보를 실제로 갱신했을 때만 변한다(값이 같으면 갱신하지 않는다).';


--
-- Name: COLUMN ls_acnt_user.last_lgn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_acnt_user.last_lgn_dt IS '최종로그인일시 — 맨 나중에 접속한 시각. 한 번도 접속하지 않았으면 NULL(기본값을 넣지 않는다).';


--
-- Name: ls_acnt_user_no_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

CREATE SEQUENCE klid_at.ls_acnt_user_no_seq
    START WITH 9000000000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: SEQUENCE ls_acnt_user_no_seq; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON SEQUENCE klid_at.ls_acnt_user_no_seq IS '관제 인계 진입자(비숫자 sub) userNo 자동발급 시퀀스. 관제 숫자 userNo·dev 시드와 겹치지 않는 높은 범위에서 발급한다. 자동증가 발급 후 LS_ACNT_USER 로 원자 upsert.';


--
-- Name: ls_ai_srvr; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_ai_srvr (
    srvr_id character varying(20) NOT NULL,
    srvr_nm character varying(100),
    srvr_addr character varying(200) NOT NULL,
    srvr_type_cd character varying(20) NOT NULL,
    srvr_stts_cd character varying(20) NOT NULL,
    chck_dt timestamp without time zone,
    chck_fail_nocs numeric(10,0) DEFAULT 0 NOT NULL,
    reg_dt timestamp without time zone NOT NULL,
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone,
    chck_scs_nocs numeric(10,0) DEFAULT 0 NOT NULL,
    CONSTRAINT ck_ls_ai_srvr_nocs_nonneg CHECK (((chck_fail_nocs >= (0)::numeric) AND (chck_scs_nocs >= (0)::numeric))),
    CONSTRAINT ck_ls_ai_srvr_srvr_id_format CHECK (((srvr_id)::text ~ '^[a-z0-9_-]{1,20}$'::text)),
    CONSTRAINT ck_ls_ai_srvr_stts_cd CHECK (((srvr_stts_cd)::text = ANY ((ARRAY['AVAILABLE'::character varying, 'UNAVAILABLE'::character varying, 'DRAINING'::character varying, 'DISABLED'::character varying])::text[]))),
    CONSTRAINT ck_ls_ai_srvr_type_cd CHECK (((srvr_type_cd)::text = ANY ((ARRAY['INFERENCE'::character varying, 'TIMESERIES'::character varying])::text[])))
);


--
-- Name: TABLE ls_ai_srvr; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_ai_srvr IS 'AI서버 — 추론 요청을 나눠 보낼 서버(노드) 목록의 단일 진실원. 기존 설정값은 원장이 비었을 때 최초 1회 씨앗으로만 쓰이며, 그 뒤로는 이 표가 이긴다(설정과 원장을 둘 다 진실원으로 두면 어느 쪽이 이기는지가 코드에 흩어진다). ★가용 상태인 행이 0이 되면 AI 기능 전체가 멈추므로 마지막 가용 노드의 강등은 애플리케이션이 거부한다 — 그 판정은 조회 후 UPDATE 가 아니라 가용 행 전체를 잠그는 조건부 UPDATE 여야 한다(서로 다른 행을 동시에 내리면 행 잠금이 부딪히지 않아 둘 다 통과한다).';


--
-- Name: COLUMN ls_ai_srvr.srvr_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr.srvr_id IS '서버식별자 — 소문자·숫자·하이픈·밑줄 20자 이하. 하이픈과 밑줄은 맨 앞·맨 뒤에 와도 된다. 값이 외부 벤더 요청과 기록에 그대로 실리므로 공백·개행·제어문자는 받지 않는다.';


--
-- Name: COLUMN ls_ai_srvr.srvr_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr.srvr_nm IS 'AI서버명 — 실제 장비 호스트명 등 사람이 읽는 이름. 형식 제약이 없다(식별자 축과 표시 축을 갈라 둔 이유가 이것이다).';


--
-- Name: COLUMN ls_ai_srvr.srvr_addr; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr.srvr_addr IS 'AI서버주소 — 호출 기준 주소(스킴 포함). ★내부 토폴로지라 로그·오류 응답에 전문을 싣지 않는다.';


--
-- Name: COLUMN ls_ai_srvr.srvr_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr.srvr_type_cd IS 'AI서버유형코드 — INFERENCE(추론 서버) | TIMESERIES(외부 시계열 분석). 두 축은 부하의 성질이 근본적으로 달라(전자는 동기 호출, 후자는 논블로킹 제출 + 콜백) 같은 목록에서 섞어 고르면 안 된다.';


--
-- Name: COLUMN ls_ai_srvr.srvr_stts_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr.srvr_stts_cd IS 'AI서버상태코드 — AVAILABLE(가용) | UNAVAILABLE(이용불가) | DRAINING(정비중) | DISABLED(비활성). ★금지 전이 두 개가 있다: 이용불가→정비중(죽은 노드는 정비 대상이 아니다. 이미 배제됐다) · 정비중→이용불가(정비 중 상태점검 실패는 상태를 바꾸지 않고 기록만 한다). 전이 규칙은 애플리케이션 상태 enum 이 소유한다.';


--
-- Name: COLUMN ls_ai_srvr.chck_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr.chck_dt IS 'AI서버점검일시 — 마지막 상태점검 시각.';


--
-- Name: COLUMN ls_ai_srvr.chck_fail_nocs; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr.chck_fail_nocs IS 'AI서버점검실패건수 — 연속 실패 횟수. 한 번의 네트워크 흔들림으로 노드를 내리지 않기 위해 연속 실패를 센다(성공하면 0으로 되돌린다).';


--
-- Name: COLUMN ls_ai_srvr.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr.reg_dt IS 'AI서버등록일시.';


--
-- Name: COLUMN ls_ai_srvr.mdfr_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr.mdfr_id IS 'AI서버수정자아이디 — 노드를 마지막으로 바꾼 사람. 별도 감사 테이블을 두지 않고 이 두 컬럼과 로그로 충당한다(연동 서버 주소 설정과 같은 축).';


--
-- Name: COLUMN ls_ai_srvr.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr.mdfcn_dt IS 'AI서버수정일시.';


--
-- Name: COLUMN ls_ai_srvr.chck_scs_nocs; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr.chck_scs_nocs IS 'AI서버점검성공건수 — 연속 성공 횟수. 이용불가 노드의 복귀 판정 축이며, 실패가 한 번이라도 끼면 0으로 되돌아간다(흔들리는 노드를 되살렸다 다시 내리는 왕복을 막는다). ★기존 점검실패건수를 부호 있는 카운터로 겸용하지 않고 컬럼을 따로 둔 이유는, 한 컬럼에 두 축을 담으면 읽는 쪽마다 해석이 갈리기 때문이다. 폴링 노드의 메모리에 두지 않는 이유는 2노드가 틱을 나눠 갖고 재기동으로도 사라지기 때문이다.';


--
-- Name: ls_ai_srvr_altmnt; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_ai_srvr_altmnt (
    altmnt_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    srvr_id character varying(20) NOT NULL,
    altmnt_dt timestamp without time zone NOT NULL,
    altmnt_rsn character varying(4000)
);


--
-- Name: TABLE ls_ai_srvr_altmnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_ai_srvr_altmnt IS 'AI서버배정 — 영상을 어느 노드로 보내기로 했는지의 기록. ★영상당 한 건(raw_sn 유니크)인 것이 「같은 영상의 프레임은 같은 노드로 간다」는 보장의 전부다. 추적기는 노드 프로세스의 로컬 메모리에 있어 프레임이 두 노드로 흩어지면 추적이 끊긴다. 2노드가 같은 영상을 동시에 배정하면 진 쪽은 예외를 받는 대신 이긴 쪽의 배정을 그대로 읽는다(하려던 일이 이미 끝나 있을 뿐이라 실패가 아니다). ⚠ 배정 대상은 배치 축(영상 단위)뿐이다 — 상호작용 요청은 식별자가 요청 1건짜리라 기록해도 재사용되지 않고 쓰레기 행만 쌓인다.';


--
-- Name: COLUMN ls_ai_srvr_altmnt.altmnt_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_altmnt.altmnt_sn IS 'AI서버배정일련번호.';


--
-- Name: COLUMN ls_ai_srvr_altmnt.raw_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_altmnt.raw_sn IS 'AI서버배정원시데이터일련번호 — 배정 대상 영상. ★유니크다. 물리 외래키는 걸지 않는다 — 이 행은 추적 불연속의 원인을 나중에 가릴 근거라 영상보다 오래 살아남을 수 있다.';


--
-- Name: COLUMN ls_ai_srvr_altmnt.srvr_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_altmnt.srvr_id IS 'AI서버아이디 — 이 영상을 보내기로 한 장비. ★외래키를 걸지 않는다(V26): 이 표는 처리가 도는 동안 프레임을 한 노드에 묶어 두는 자리이고, 처리가 끝나면 그 묶음은 의미가 없다(추적기 메모리가 이미 사라졌다). 끝난 배정까지 장비 삭제를 막으면 한 번이라도 영상을 처리한 장비는 영구히 교체 불가가 된다. 장비 행이 지워져도 어느 장비였는지는 남아야 하므로 값은 그대로 둔다 — 위탁 원장(ls_webhook_idempotency.srvr_id)과 같은 축이다. 처리 중인 영상의 보호는 외래키가 아니라 정비중(DRAINING) 상태와 유형별 마지막 가용 장비 보호가 담당한다.';


--
-- Name: COLUMN ls_ai_srvr_altmnt.altmnt_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_altmnt.altmnt_dt IS 'AI서버배정일시.';


--
-- Name: COLUMN ls_ai_srvr_altmnt.altmnt_rsn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_altmnt.altmnt_rsn IS 'AI서버배정사유 — 노드 이탈로 다시 배정했을 때만 채운다. ★이 값이 있으면 추적 불연속의 원인이 재배정이고, 없는데 추적이 끊겼으면 추적기 만료·노드 재기동·캐시 축출 셋 중 하나다. 그 대조가 오진을 막는 유일한 수단이라, 기록은 재배정과 같은 트랜잭션에서 남긴다(비동기로 빼면 「기록이 없다」와 「아직 기록이 안 됐다」가 구분되지 않는다).';


--
-- Name: ls_ai_srvr_altmnt_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

CREATE SEQUENCE klid_at.ls_ai_srvr_altmnt_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ls_ai_srvr_usg; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_ai_srvr_usg (
    srvr_id character varying(20) NOT NULL,
    usg_type_cd character varying(20) NOT NULL,
    wtng_nocs numeric(10,0) DEFAULT 0 NOT NULL,
    prcs_nocs numeric(10,0) DEFAULT 0 NOT NULL,
    chck_dt timestamp without time zone,
    reg_dt timestamp without time zone NOT NULL,
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone,
    CONSTRAINT ck_ls_ai_srvr_usg_nocs_nonneg CHECK (((wtng_nocs >= (0)::numeric) AND (prcs_nocs >= (0)::numeric))),
    CONSTRAINT ck_ls_ai_srvr_usg_type_cd CHECK (((usg_type_cd)::text = ANY ((ARRAY['BATCH'::character varying, 'INTERACTIVE'::character varying])::text[])))
);


--
-- Name: TABLE ls_ai_srvr_usg; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_ai_srvr_usg IS 'AI서버용도 — 노드 x 용도 조합의 부하 관측값. ★장비마다 값 하나를 두지 않는 것이 이 표의 존재 이유다: 두 용도는 장비 안에서 실행이 격리돼 있어 한쪽이 밀려 있다는 사실이 다른 쪽의 응답을 늦추지 않는다. 두 값을 합쳐 보면 장비 안에서 갈라 놓은 것을 부르는 쪽에서 다시 붙이는 셈이 되어, 일괄 처리가 밀린 장비를 화면 요청이 피할 이유가 없는데도 피하게 된다. 노드를 고를 때 보는 값은 그 요청 자신의 용도에 해당하는 행 하나뿐이다. ⚠ 여기 담기는 것은 상태점검 배치가 마지막으로 관측한 값이며, 상대가 잠그지 않고 세는 근사값이다 — 정확한 수로 등식을 세우지 말 것. 노드를 지우면 함께 사라진다(관측값이라 노드보다 오래 살 이유가 없고, 남으면 같은 식별자로 세운 새 장비에 죽은 장비의 부하가 되살아난다).';


--
-- Name: COLUMN ls_ai_srvr_usg.srvr_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_usg.srvr_id IS 'AI서버용도AI서버아이디 — 관측 대상 노드. 원장이 사라지면 이 행도 함께 사라진다(CASCADE).';


--
-- Name: COLUMN ls_ai_srvr_usg.usg_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_usg.usg_type_cd IS 'AI서버용도유형코드 — BATCH(일괄 처리) | INTERACTIVE(화면에서 쓰는 요청). 「슬롯」은 표준용어에 없어 이 표가 실제로 담는 것인 용도로 적었다. ⚠ 셋째 용도가 생기면 상대가 알려주기로 했다 — 그 전까지 모르는 값은 저장하지 않고(체크 제약) 조회 시에도 무시한다(모르는 값 하나 때문에 조회 전체를 실패로 만들지 않는다).';


--
-- Name: COLUMN ls_ai_srvr_usg.wtng_nocs; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_usg.wtng_nocs IS 'AI서버용도대기건수 — 그 용도의 큐 길이. 실효 부하는 이 값 단독이 아니라 처리건수와의 합이다(아래 참조).';


--
-- Name: COLUMN ls_ai_srvr_usg.prcs_nocs; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_usg.prcs_nocs IS 'AI서버용도처리건수 — 그 용도가 지금 처리 중인 건수(0 또는 1 — 용도별 동시 처리가 하나다). ★대기 건수만 보면 한가한 장비와 이미 하나를 잡고 있는 장비가 똑같이 「없음」으로 보여 요청의 절반을 바쁜 쪽으로 보내게 된다. 그래서 이 값을 함께 두고 대기건수와의 합을 실효 부하로 쓴다.';


--
-- Name: COLUMN ls_ai_srvr_usg.chck_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_usg.chck_dt IS 'AI서버용도점검일시 — ★우리가 관측한 시각이다. 상대가 응답에 실어 주는 관측 시각은 상대 장비의 시계라, 우리 시계에서 빼면 시계 오차가 그대로 지연으로 잡힌다. 신선도는 우리 폴링 주기로 판단한다.';


--
-- Name: COLUMN ls_ai_srvr_usg.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_usg.reg_dt IS 'AI서버용도등록일시.';


--
-- Name: COLUMN ls_ai_srvr_usg.mdfr_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_usg.mdfr_id IS 'AI서버용도수정자아이디.';


--
-- Name: COLUMN ls_ai_srvr_usg.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_ai_srvr_usg.mdfcn_dt IS 'AI서버용도수정일시.';


--
-- Name: ls_auth_work_lock; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_auth_work_lock (
    work_lock_sn bigint NOT NULL,
    lck_target_cd character varying(20) NOT NULL,
    data_raw_sn bigint,
    data_src_sn bigint,
    lck_stts_cd character varying(20) NOT NULL,
    lck_id character varying(64) NOT NULL,
    lock_owner_id character varying(30),
    lck_dt timestamp without time zone NOT NULL,
    expry_dt timestamp without time zone,
    rmv_dt timestamp without time zone,
    rmv_rsn character varying(4000),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_auth_work_lock_work_lock_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_auth_work_lock ALTER COLUMN work_lock_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_auth_work_lock_work_lock_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_authrt_grant_atmpt; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_authrt_grant_atmpt (
    atmpt_se_cd character varying(20) NOT NULL,
    atmpt_idntfr character varying(36) NOT NULL,
    bgng_dt timestamp without time zone NOT NULL,
    atmpt_nmtm integer DEFAULT 0 NOT NULL,
    expd_dt timestamp without time zone NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_authrt_grant_atmpt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_authrt_grant_atmpt IS '권한 자가부여 시도 횟수 — 노드 공유 rate limit 집계(분 단위 윈도우).';


--
-- Name: COLUMN ls_authrt_grant_atmpt.atmpt_se_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_authrt_grant_atmpt.atmpt_se_cd IS '시도 구분 코드 — ACCOUNT(계정별) / GLOBAL(엔드포인트 전역).';


--
-- Name: COLUMN ls_authrt_grant_atmpt.atmpt_idntfr; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_authrt_grant_atmpt.atmpt_idntfr IS '시도 식별자 — 계정 축은 요청자 sub, 전역 축은 GLOBAL 고정값.';


--
-- Name: COLUMN ls_authrt_grant_atmpt.bgng_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_authrt_grant_atmpt.bgng_dt IS '집계 윈도우 시작일시 — 분 단위로 절삭된 버킷 키.';


--
-- Name: COLUMN ls_authrt_grant_atmpt.atmpt_nmtm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_authrt_grant_atmpt.atmpt_nmtm IS '해당 윈도우의 누적 자가부여 시도 횟수.';


--
-- Name: COLUMN ls_authrt_grant_atmpt.expd_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_authrt_grant_atmpt.expd_dt IS '만료일시 — 경과 시 정리(중복 실행 무해한 조건부 DELETE).';


--
-- Name: COLUMN ls_authrt_grant_atmpt.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_authrt_grant_atmpt.reg_dt IS '등록일시.';


--
-- Name: COLUMN ls_authrt_grant_atmpt.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_authrt_grant_atmpt.mdfcn_dt IS '수정일시.';


--
-- Name: ls_bat_rty_wtng; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_bat_rty_wtng (
    bat_rty_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    rty_nmtm integer DEFAULT 0 NOT NULL,
    max_rty_nmtm integer DEFAULT 3 NOT NULL,
    stts_cd character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    rty_prnmnt_dt timestamp without time zone,
    last_err_msg_cn character varying(2000),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_bat_rty_wtng_bat_rty_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_bat_rty_wtng ALTER COLUMN bat_rty_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_bat_rty_wtng_bat_rty_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_batch_proc_log; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_batch_proc_log (
    batch_proc_log_sn bigint NOT NULL,
    job_id character varying(64) NOT NULL,
    data_raw_sn bigint,
    data_src_sn bigint,
    proc_step_cd character varying(30) NOT NULL,
    proc_stts_cd character varying(20) NOT NULL,
    bgng_dt timestamp without time zone,
    end_dt timestamp without time zone,
    rtry_nmtm integer DEFAULT 0 NOT NULL,
    err_cd character varying(50),
    err_msg_cn character varying(4000),
    req_payload_cn text,
    resp_payload_cn text,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_batch_proc_log_batch_proc_log_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_batch_proc_log ALTER COLUMN batch_proc_log_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_batch_proc_log_batch_proc_log_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_clip_schedule_que; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_clip_schedule_que (
    que_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    job_type_cd character varying(20) NOT NULL,
    stts_cd character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    rtry_nmtm integer DEFAULT 0 NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    bgng_dt timestamp without time zone,
    cmptn_dt timestamp without time zone,
    last_err_msg_cn character varying(2000)
);


--
-- Name: COLUMN ls_clip_schedule_que.job_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_clip_schedule_que.job_type_cd IS '작업유형코드. 라벨링 배치 파이프라인 입구 작업 종류(LABELING_BATCH). 표준도메인 코드V20.';


--
-- Name: COLUMN ls_clip_schedule_que.stts_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_clip_schedule_que.stts_cd IS '상태코드. PENDING → IN_PROGRESS → DONE/FAILED. 형제 큐(LS_BAT_RTY_WTNG 등)와 같은 형태다.';


--
-- Name: COLUMN ls_clip_schedule_que.rtry_nmtm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_clip_schedule_que.rtry_nmtm IS '재시도횟수. 재시도는 행안부 공통표준 RTRY 다(사업 RTY 가 아니다).';


--
-- Name: COLUMN ls_clip_schedule_que.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_clip_schedule_que.reg_dt IS '등록일시. 큐 적재 시각이며 폴링 정렬 기준이다.';


--
-- Name: COLUMN ls_clip_schedule_que.bgng_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_clip_schedule_que.bgng_dt IS '시작일시. 워커가 작업을 집어 IN_PROGRESS 로 전이한 시각.';


--
-- Name: COLUMN ls_clip_schedule_que.cmptn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_clip_schedule_que.cmptn_dt IS '완료일시.';


--
-- Name: COLUMN ls_clip_schedule_que.last_err_msg_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_clip_schedule_que.last_err_msg_cn IS '마지막오류메시지내용. 값은 정화 후 적재한다 — 원문을 그대로 넣지 않는다(CWE-117/209).';


--
-- Name: ls_clip_schedule_que_que_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_clip_schedule_que ALTER COLUMN que_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_clip_schedule_que_que_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_control_notify_fallback; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_control_notify_fallback (
    queue_sn bigint NOT NULL,
    idmp_key character varying(128) NOT NULL,
    evnt_type_cd character varying(20) NOT NULL,
    raw_sn bigint NOT NULL,
    payload_cn text NOT NULL,
    rtry_nmtm integer DEFAULT 0 NOT NULL,
    max_rtry_nmtm integer DEFAULT 5 NOT NULL,
    stts_cd character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    last_err_msg_cn character varying(2000),
    next_rtry_dt timestamp without time zone,
    dlq_dt timestamp without time zone,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    send_rslt_cd character varying(16)
);


--
-- Name: COLUMN ls_control_notify_fallback.send_rslt_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_control_notify_fallback.send_rslt_cd IS '발송 결과 코드 - SUCCESS/FAILED (STTS_CD=큐 처리상태와 분리)';


--
-- Name: ls_control_notify_fallback_queue_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_control_notify_fallback ALTER COLUMN queue_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_control_notify_fallback_queue_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_aug; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_aug (
    data_aug_sn bigint NOT NULL,
    src_sn bigint NOT NULL,
    aug_type_cd character varying(20) NOT NULL,
    aug_proc_stts_cd character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    lbl_intgrt_pct numeric(5,2),
    dcsn_user_no character varying(50),
    dcsn_dt timestamp without time zone,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reg_user_no character varying(50),
    idmp_key character varying(128),
    otsd_job_id character varying(200),
    rtry_nmtm integer DEFAULT 0 NOT NULL,
    dead_letter_at timestamp without time zone,
    prompt_cn character varying(4000),
    new_raw_sn bigint
);


--
-- Name: COLUMN ls_data_aug.aug_proc_stts_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug.aug_proc_stts_cd IS '증강 처리/검수 상태 코드. PENDING(요청·처리중) / ACCEPTED(검수 승인 또는 해상도 파생 생성 완료) / REJECTED(검수 반려 또는 처리 실패 롤업) / CANCELED(사용자 취소 종결, V154 신설). 외부 처리 축(LS_DATA_AUG_JOB.JOB_STTS_CD = RECEIVED/RUNNING/SUCCEEDED/FAILED/CANCELED)과는 다른 코드 공간이므로 두 축을 섞어 판정하지 말 것. 처리 실패 판정은 이 컬럼이 아니라 DEAD_LETTER_AT(처리 실패 전용 마커)로 한다.';


--
-- Name: COLUMN ls_data_aug.prompt_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug.prompt_cn IS '외부 증강 위탁 시 전송한 prompt(요청 조건 5필드: time/season/weather/terrain/severity) JSON 원문. V153 이전 요청 및 해상도 파생(RESL_*)은 NULL.';


--
-- Name: COLUMN ls_data_aug.new_raw_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug.new_raw_sn IS '신규원시일련번호 - 이 증강/해상도 요청이 생성한 파생 영상(LS_DATA_RAW.RAW_SN). 생성 전/실패 시 NULL. V155 이전 행은 백필하지 않아 NULL(등재 게이트 그랜드퍼더링 대상). FK 미설정 - V146 계보 링크 제외 규칙 준수';


--
-- Name: ls_data_aug_data_aug_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_aug ALTER COLUMN data_aug_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_aug_data_aug_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_aug_dscd; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_aug_dscd (
    data_aug_dscd_sn bigint NOT NULL,
    data_aug_sn bigint NOT NULL,
    new_raw_sn bigint NOT NULL,
    orgnl_raw_sn bigint,
    dscd_dt timestamp without time zone NOT NULL,
    dscd_rsn character varying(4000),
    rstr_dt timestamp without time zone,
    rstr_rsn character varying(4000),
    del_prcs_dt timestamp without time zone,
    del_dt timestamp without time zone,
    file_del_dt timestamp without time zone,
    vdo_file_path character varying(1000),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone,
    aug_type_cd character varying(20),
    prompt_cn character varying(4000),
    file_del_rtry_nmtm integer DEFAULT 0 NOT NULL,
    file_del_fail_dt timestamp without time zone,
    file_del_fail_rsn character varying(4000)
);


--
-- Name: TABLE ls_data_aug_dscd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_data_aug_dscd IS '증강 파생영상 폐기 원장 - 반려 시 표식, 유예 경과 후 실삭제, 유예 내 복구. 실삭제 후에도 비석으로 존속(감사 추적 + 파일 삭제 재시도 단서)';


--
-- Name: COLUMN ls_data_aug_dscd.data_aug_dscd_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.data_aug_dscd_sn IS '데이터증강폐기일련번호 - PK';


--
-- Name: COLUMN ls_data_aug_dscd.data_aug_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.data_aug_sn IS '데이터증강일련번호 - 폐기 대상 증강 요청(LS_DATA_AUG). FK 미설정(실삭제가 비석을 지우지 않게)';


--
-- Name: COLUMN ls_data_aug_dscd.new_raw_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.new_raw_sn IS '신규원시일련번호 - 폐기 대상 파생 영상(LS_DATA_RAW). NOT NULL - 매핑 없는 그랜드퍼더링 증강은 실삭제 대상이 아니라 애초에 행을 만들지 않는다';


--
-- Name: COLUMN ls_data_aug_dscd.orgnl_raw_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.orgnl_raw_sn IS '원본원시일련번호 - 파생의 부모 영상. 감사(원본 오삭제 사후 검출) + 파생 비디오 경로 재구성 단서';


--
-- Name: COLUMN ls_data_aug_dscd.dscd_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.dscd_dt IS '폐기일시 - 소프트 삭제 표식 시각(= 유예 기산점)';


--
-- Name: COLUMN ls_data_aug_dscd.dscd_rsn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.dscd_rsn IS '폐기사유 - 검수 반려 사유 스냅샷(복구 시 검수행에서 지워지므로 여기 보존)';


--
-- Name: COLUMN ls_data_aug_dscd.rstr_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.rstr_dt IS '복구일시 - 값이 있으면 폐기 취소됨(실삭제 대상 아님)';


--
-- Name: COLUMN ls_data_aug_dscd.rstr_rsn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.rstr_rsn IS '복구사유 - 되돌린 이유(누가=MDFCN_ID, 언제=RSTR_DT)';


--
-- Name: COLUMN ls_data_aug_dscd.del_prcs_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.del_prcs_dt IS '삭제처리일시 - 실삭제 배치의 원자 클레임 마커(2노드 중복 집행 방지). 오래된 클레임은 재클레임 허용';


--
-- Name: COLUMN ls_data_aug_dscd.del_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.del_dt IS '삭제일시 - DB 행 실삭제 커밋 시각';


--
-- Name: COLUMN ls_data_aug_dscd.file_del_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.file_del_dt IS '파일삭제일시 - 생성 파일까지 정리 완료 시각. DEL_DT 가 있고 이 값이 NULL 이면 파일 삭제 재시도 대상';


--
-- Name: COLUMN ls_data_aug_dscd.vdo_file_path; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.vdo_file_path IS '영상파일경로 - 삭제 직전 기록한 파생 비디오 경로(RAW 행이 사라진 뒤 재시도용 단서)';


--
-- Name: COLUMN ls_data_aug_dscd.aug_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.aug_type_cd IS '증강유형코드 - 폐기 당시 증강 종류 스냅샷(LS_DATA_AUG 행은 실삭제로 사라진다)';


--
-- Name: COLUMN ls_data_aug_dscd.prompt_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.prompt_cn IS '프롬프트내용 - 폐기 당시 생성 조건 스냅샷. 중복 증강 허용 이후 같은 (영상×종류) 파생을 구분하는 유일한 축';


--
-- Name: COLUMN ls_data_aug_dscd.file_del_rtry_nmtm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.file_del_rtry_nmtm IS '파일삭제재시도횟수 - 파일 정리 시도 누적. 상한 초과 시 FILE_DEL_FAIL_DT 로 종결';


--
-- Name: COLUMN ls_data_aug_dscd.file_del_fail_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.file_del_fail_dt IS '파일삭제실패일시 - 자동 정리 포기(데드레터) 시각. 값이 있으면 재시도 큐에서 제외되고 사람이 수동 정리';


--
-- Name: COLUMN ls_data_aug_dscd.file_del_fail_rsn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_dscd.file_del_fail_rsn IS '파일삭제실패사유 - 포기 사유(경로 원문 미포함)';


--
-- Name: ls_data_aug_dscd_data_aug_dscd_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_aug_dscd ALTER COLUMN data_aug_dscd_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_aug_dscd_data_aug_dscd_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_aug_job; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_aug_job (
    aug_job_sn bigint NOT NULL,
    data_aug_sn bigint NOT NULL,
    job_seq integer NOT NULL,
    idmp_key character varying(128) NOT NULL,
    otsd_job_id character varying(200),
    job_stts_cd character varying(20) NOT NULL,
    tot_nocs integer DEFAULT 0 NOT NULL,
    err_cd character varying(50),
    err_msg_cn character varying(1000),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_data_aug_job; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_data_aug_job IS '증강 외부 위탁 작업 — LS_DATA_AUG 1행이 input_files 100 상한으로 분할 위탁된 job 단위.';


--
-- Name: COLUMN ls_data_aug_job.data_aug_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_job.data_aug_sn IS '증강 결과 식별자(LS_DATA_AUG FK).';


--
-- Name: COLUMN ls_data_aug_job.job_seq; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_job.job_seq IS '분할 위탁 순서(1부터). 프레임 250장이면 1,2,3.';


--
-- Name: COLUMN ls_data_aug_job.idmp_key; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_job.idmp_key IS '저작도구가 발급한 request_id(=Idempotency-Key). 외부 job_id 가 아니다.';


--
-- Name: COLUMN ls_data_aug_job.otsd_job_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_job.otsd_job_id IS '외부 시스템이 202 응답으로 발급한 job_id. 접수 전/실패 시 NULL.';


--
-- Name: COLUMN ls_data_aug_job.job_stts_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_job.job_stts_cd IS '외부 작업 상태(RECEIVED/RUNNING/SUCCEEDED/FAILED/CANCELED). 검수 결과 축과 별개.';


--
-- Name: COLUMN ls_data_aug_job.tot_nocs; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_job.tot_nocs IS '이 job 으로 위탁한 입력 파일 총건수(최대 100).';


--
-- Name: COLUMN ls_data_aug_job.err_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_job.err_cd IS '실패 오류 코드. 위탁 실패를 조용히 삼키지 않기 위한 사유 기록.';


--
-- Name: COLUMN ls_data_aug_job.err_msg_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_job.err_msg_cn IS '실패 오류 메시지(요약). 절대경로/시크릿/스택트레이스 미포함.';


--
-- Name: ls_data_aug_job_aug_job_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_aug_job ALTER COLUMN aug_job_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_aug_job_aug_job_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_aug_job_file; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_aug_job_file (
    aug_job_file_sn bigint NOT NULL,
    aug_job_sn bigint NOT NULL,
    file_seq integer NOT NULL,
    src_sn bigint NOT NULL,
    rslt_file_path_nm character varying(500),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_data_aug_job_file; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_data_aug_job_file IS '증강 위탁 파일 매핑 — 위탁 순서↔프레임(SRC_SN) 대응과 외부 산출 경로.';


--
-- Name: COLUMN ls_data_aug_job_file.aug_job_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_job_file.aug_job_sn IS '위탁 작업 식별자(LS_DATA_AUG_JOB FK).';


--
-- Name: COLUMN ls_data_aug_job_file.file_seq; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_job_file.file_seq IS '위탁 입력 순서(input_files[].sequence). 증강 1건 전체에서 1부터 증가.';


--
-- Name: COLUMN ls_data_aug_job_file.src_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_job_file.src_sn IS '위탁한 비식별 프레임(LS_DATA_SRC) 식별자. 결과를 되붙일 대상.';


--
-- Name: COLUMN ls_data_aug_job_file.rslt_file_path_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_aug_job_file.rslt_file_path_nm IS '외부가 반환한 증강 산출 이미지 경로. 수신 전 NULL, 건수 불일치 시 미적재.';


--
-- Name: ls_data_aug_job_file_aug_job_file_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_aug_job_file ALTER COLUMN aug_job_file_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_aug_job_file_aug_job_file_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_aug_lbl_map; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_aug_lbl_map (
    data_aug_lbl_map_sn bigint NOT NULL,
    data_aug_sn bigint NOT NULL,
    orgnl_data_lbl_sn bigint,
    data_lbl_sn bigint NOT NULL,
    coord_recalc_yn character(1) DEFAULT 'N'::character varying NOT NULL,
    scale_x numeric(10,6),
    scale_y numeric(10,6),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_data_aug_lbl_map_data_aug_lbl_map_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_aug_lbl_map ALTER COLUMN data_aug_lbl_map_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_aug_lbl_map_data_aug_lbl_map_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_aug_rvw; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_aug_rvw (
    data_aug_rvw_sn bigint NOT NULL,
    data_aug_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    data_src_sn bigint NOT NULL,
    rvw_stts_cd character varying(20) NOT NULL,
    lbl_intgrt_pct numeric(5,2),
    rjct_rsn character varying(4000),
    rvw_id character varying(30),
    rvw_dt timestamp without time zone,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_data_aug_rvw_data_aug_rvw_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_aug_rvw ALTER COLUMN data_aug_rvw_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_aug_rvw_data_aug_rvw_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_ingest; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_ingest (
    rcptn_sn bigint NOT NULL,
    rcptn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    prcs_stts_cd character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    raw_sn bigint,
    rty_cnt integer DEFAULT 0 NOT NULL,
    prcs_dt timestamp without time zone,
    nxtm_rtry_dt timestamp without time zone,
    err_msg character varying(4000),
    vms_clip_id character varying(128) NOT NULL,
    vms_cctv_id character varying(64),
    vdo_file_nm character varying(300) NOT NULL,
    raw_file_path_nm character varying(500) NOT NULL,
    src_type character varying(20) NOT NULL,
    sht_dt timestamp without time zone,
    file_fmt character varying(20),
    vdo_cdc character varying(20),
    file_sz bigint,
    lclgv_nm character varying(100),
    vdo_len_sec numeric(10,0),
    fps character varying(10),
    frme_cnt numeric(10,0),
    asprt_rt character varying(20),
    wdth numeric(10,0),
    vrtc numeric(10,0),
    resl character varying(20),
    "bit" character varying(20),
    pxl character varying(20),
    wgs84_lat numeric(10,7),
    wgs84_lot numeric(10,7),
    cctv_nm character varying(300),
    cctv_hgt numeric(4,1),
    main_surv_pan_ang integer,
    evnt_id character varying(50),
    evnt_nm character varying(200),
    mntr_cn character varying(4000),
    lclgv_cd character varying(20),
    evnt_type_cd character varying(20),
    anony_incl_yn character(1) DEFAULT 'N'::bpchar,
    psdo_incl_yn character(1) DEFAULT 'N'::bpchar,
    prvc_incl_yn character(1) DEFAULT 'Y'::bpchar,
    evnt_clsf_cd character(2),
    evnt_ctgry_cd character(4),
    vrfc_evnt_type_cd character varying(20),
    og_cd character varying(20)
);


--
-- Name: TABLE ls_data_ingest; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_data_ingest IS '관제 인입 — 관제서버가 학습용 영상 메타를 직접 INSERT 하는 수신 창구. 행은 감사 추적을 위해 영구 보존한다.';


--
-- Name: COLUMN ls_data_ingest.rcptn_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.rcptn_sn IS '수신일련번호(PK). 관제가 미지정 시 IDENTITY 발급.';


--
-- Name: COLUMN ls_data_ingest.rcptn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.rcptn_dt IS '수신일시. 폴링 순서(FIFO) 기준.';


--
-- Name: COLUMN ls_data_ingest.prcs_stts_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.prcs_stts_cd IS '저작도구 처리상태코드. PENDING(미처리) 기본. 관제는 이 컬럼을 채우지 않아도 된다. (V172 개명 — 구 PROC_STTS_CD. 처리=PRCS 표준단어, 같은 테이블 PRCS_DT 와 약어 통일)';


--
-- Name: COLUMN ls_data_ingest.raw_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.raw_sn IS '적재 결과 영상 식별자(LS_DATA_RAW.RAW_SN). 적재 전 NULL. FK 는 걸지 않는다 — 영상 정리 후에도 수신 기록은 남아야 한다.';


--
-- Name: COLUMN ls_data_ingest.rty_cnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.rty_cnt IS '적재 재시도 횟수. 파일 미도착은 실패가 아니라 미처리로 두고 다음 주기에 재시도한다.';


--
-- Name: COLUMN ls_data_ingest.prcs_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.prcs_dt IS '처리일시 — 저작도구가 이 행을 처리한 시각. 종결(적재 완료/실패) 시각이자, 미처리 상태에서는 <최초 파일 미도착 관측 시각>(대기 예산 앵커)이다. 대기 상한은 관제가 준 RCPTN_DT 가 아니라 이 값 기준으로 잰다(설계 §6-0-1-a ㉠ — RCPTN_DT 는 INSERT 주체가 관제라 과거 시각이 들어오면 도착 즉시 종결된다). 재큐 시 NULL 로 비워 예산을 리셋한다.';


--
-- Name: COLUMN ls_data_ingest.nxtm_rtry_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.nxtm_rtry_dt IS '차기 재시도 예정 일시. 폴링 후보는 PENDING AND (이 값 NULL OR <= 현재)다 — 파일 미도착 관측 시 이 값을 뒤로 밀어(backoff) 고착 행이 FIFO 앞자리를 잠식하지 못하게 한다(설계 §6-0-1-a ㉢). 재큐 시 NULL 로 비운다. (V175 개명 — 구 NXTM_RTY_DT. 표준 우선순위 ①행안부→②사업 재판정으로 재시도=RTRY(공통표준단어) 채택. 같은 테이블 RTY_CNT 는 기존 자산이라 이번 범위 밖 — 별도 라운드)';


--
-- Name: COLUMN ls_data_ingest.err_msg; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.err_msg IS '적재 실패 사유(요약). 절대경로/시크릿/스택트레이스 미포함.';


--
-- Name: COLUMN ls_data_ingest.vms_clip_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.vms_clip_id IS 'VMS 클립 아이디(관제 video.id). 중복 INSERT 방어 UK.';


--
-- Name: COLUMN ls_data_ingest.vms_cctv_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.vms_cctv_id IS 'VMS CCTV 아이디(관제 video.cctv_mng_no). NULL 허용 — CCTV 식별자가 없는 영상(수동 업로드 등)이 존재한다(2026-08-12 관제 확정). 그런 영상은 관제가 CCTV_NM 에 대체 표기를 채워 보낸다.';


--
-- Name: COLUMN ls_data_ingest.vdo_file_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.vdo_file_nm IS '동영상 파일명(관제 video.filename).';


--
-- Name: COLUMN ls_data_ingest.raw_file_path_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.raw_file_path_nm IS '원시 파일 경로명 — 관제 NAS 실경로(strg_uri).';


--
-- Name: COLUMN ls_data_ingest.src_type; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.src_type IS '출처유형(RELAY/USER_ULD/GENERATED/ORIGINAL). 경계축은 "관제가 만들었나 / 저작도구가 만들었나"이며 외부 위탁 여부가 아니다. AUGMENTED 는 저작도구 파생이라 인입으로 오지 않는다.';


--
-- Name: COLUMN ls_data_ingest.sht_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.sht_dt IS '촬영일시(관제 video.date_created).';


--
-- Name: COLUMN ls_data_ingest.file_fmt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.file_fmt IS '파일형식(관제 video.type).';


--
-- Name: COLUMN ls_data_ingest.vdo_cdc; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.vdo_cdc IS '영상코덱(관제 video.format).';


--
-- Name: COLUMN ls_data_ingest.file_sz; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.file_sz IS '파일크기 — 관제 수신 바이트 수(관제 v2_video_assets.file_sz, bigint). 어노테이션 작성예시의 ''4800KB'' 표기는 export 직렬화 단계에서 생성한다(인입은 원본 단위 그대로 받는다).';


--
-- Name: COLUMN ls_data_ingest.lclgv_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.lclgv_nm IS '지방자치단체명(관제 video.location). 동은 포함하지 않는다. 지방자치단체코드(LCLGV_CD)와 서로 다른 값이며 서로 대체·통합하지 않는다. (V172 개명 — 구 RGN_NM. 표준용어 LCLGV_NM·도메인 명V100, 관제 datasets.lclgv_nm 과 일치)';


--
-- Name: COLUMN ls_data_ingest.vdo_len_sec; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.vdo_len_sec IS '영상길이(초). 관제 원본은 소수 2자리이나 등록 도메인 수N10 에 맞춰 정수 초로 수신한다. 밀리초 정밀도는 LS_DATA_RAW.VDO_LEN_MS 담당.';


--
-- Name: COLUMN ls_data_ingest.fps; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.fps IS '프레임재생속도(관제 video.fps).';


--
-- Name: COLUMN ls_data_ingest.frme_cnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.frme_cnt IS '프레임수(관제 video.frames). (V172 개명 — 구 FRM_CNT. FRM 은 표준에서 형식(Form)이라 프레임=FRME 로 정정)';


--
-- Name: COLUMN ls_data_ingest.asprt_rt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.asprt_rt IS '종횡비(관제 video.aspect_ratio). 예 4:3.';


--
-- Name: COLUMN ls_data_ingest.wdth; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.wdth IS '영상 너비(px, 관제 video.width).';


--
-- Name: COLUMN ls_data_ingest.vrtc; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.vrtc IS '영상 세로(px, 관제 video.height).';


--
-- Name: COLUMN ls_data_ingest.resl; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.resl IS '해상도 표기(관제 video.resolution). 어노테이션 resolution/pixel 두 등급 표기의 파생 원천.';


--
-- Name: COLUMN ls_data_ingest."bit"; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest."bit" IS '비트레이트(bps 단위 숫자, 관제 video.bit). 예 2050627. 색심도가 아니다 — 관제 실측값이 색심도 표기(''24bit'')가 아니라 bps 정수임이 확인됐다(2026-08-24 관제 재확인). PostgreSQL 에서 컬럼명으로는 예약어라 baseline 이 인용 생성했다.';


--
-- Name: COLUMN ls_data_ingest.pxl; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.pxl IS '화소 표기(관제 video.pixel). 예 4K.';


--
-- Name: COLUMN ls_data_ingest.wgs84_lat; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.wgs84_lat IS 'WGS84 위도(관제 video.coordinates 분해).';


--
-- Name: COLUMN ls_data_ingest.wgs84_lot; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.wgs84_lot IS 'WGS84 경도(관제 video.coordinates 분해).';


--
-- Name: COLUMN ls_data_ingest.cctv_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.cctv_nm IS 'CCTV명(관제 resource_cctvs 조인). 촬영 시점 값 고정.';


--
-- Name: COLUMN ls_data_ingest.cctv_hgt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.cctv_hgt IS 'CCTV 설치 높이(m). 촬영 시점 값 고정 — 카메라 교체 시 과거 영상 오염 방지.';


--
-- Name: COLUMN ls_data_ingest.main_surv_pan_ang; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.main_surv_pan_ang IS '주감시방향값(도, 관제 cctv_azimuth). 촬영 시점 값 고정 — 방위각 재설정 시 과거 영상 오염 방지.';


--
-- Name: COLUMN ls_data_ingest.evnt_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.evnt_id IS '이벤트 아이디(관제 video.event_id). 예 ABA_0001. 이벤트유형코드가 아니다.';


--
-- Name: COLUMN ls_data_ingest.evnt_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.evnt_nm IS '이벤트명(관제 video.event_name).';


--
-- Name: COLUMN ls_data_ingest.mntr_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.mntr_cn IS '관제일지 내용(관제 video.event_log).';


--
-- Name: COLUMN ls_data_ingest.lclgv_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.lclgv_cd IS '지방자치단체코드. LS_DATA_RAW.LCLGV_CD 의 원천이며 관제 완료통지 페이로드 lclgv_cd(required)가 이 값에서 나온다. 지방자치단체명(LCLGV_NM)과 서로 다른 값이다.';


--
-- Name: COLUMN ls_data_ingest.evnt_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.evnt_type_cd IS '이벤트유형코드(관제 수신). LS_DATA_RAW.EVNT_TYPE_CD 의 원천이며 마킹 진입 프리컨디션 값이다. EVNT_ID(식별자, 예 ABA_0001)와 서로 다른 값이다 — 대체·통합하지 않는다. ★이 컬럼이 유일한 조달처다: 관제가 채우지 않으면 null 로 적재되고 그 영상은 마킹 단계에서 차단된다(대용값을 넣지 않는다).';


--
-- Name: COLUMN ls_data_ingest.anony_incl_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.anony_incl_yn IS '원천 영상(비식별 처리 전)의 익명정보 포함여부(관제 수신, Y/N). 관제가 항상 송신하며 미지정 INSERT 는 DB DEFAULT ''N''(fail-closed)으로 채워진다(V170 — V166 의 "DEFAULT 없음" 서술 폐기). 저작도구가 사람 입력으로 관리하는 비식별 축(LS_DATA_RAW.ANONY_INCL_YN, V163)과 대상이 다른 별개 축이며 적재는 그 컬럼을 채우지 않는다. 파생영상은 원천 영상이 없으므로 이 값을 물려받지 않는다.';


--
-- Name: COLUMN ls_data_ingest.psdo_incl_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.psdo_incl_yn IS '원천 영상의 가명정보 포함여부(관제 수신, Y/N). 미지정 INSERT 는 DB DEFAULT ''N''(V170). 비식별 축(LS_DATA_RAW.PSDO_INCL_YN, V163)과 별개.';


--
-- Name: COLUMN ls_data_ingest.prvc_incl_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.prvc_incl_yn IS '원천 영상의 개인정보 포함여부(관제 수신, Y/N). 미지정 INSERT 는 DB DEFAULT ''Y''(fail-closed — 원천은 비식별 전이라 개인정보가 남아 있는 것이 기본 상태다. V170). 비식별 축(LS_DATA_RAW.PRVC_INCL_YN, V163)과 별개.';


--
-- Name: COLUMN ls_data_ingest.evnt_clsf_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.evnt_clsf_cd IS '이벤트분류코드(대분류, 관제 수신). LS_EVNT_TYPE.EVNT_CLSF_CD 의 원천이며 이벤트유형 제외 필터의 판정축이다. 코드에서 유도하지 않는다 — 관제가 안 보내면 NULL 이다. (V172 표준도메인 코드C2 정합)';


--
-- Name: COLUMN ls_data_ingest.evnt_ctgry_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.evnt_ctgry_cd IS '이벤트카테고리코드(관제 수신). 이벤트 3계층(대분류→카테고리→유형)의 중간 레벨이며 LS_EVNT_TYPE.EVNT_CTGRY_CD 의 원천이다. 관제가 안 보내면 NULL 이며 유도하지 않는다. (V172 표준도메인 코드C4 정합)';


--
-- Name: COLUMN ls_data_ingest.vrfc_evnt_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.vrfc_evnt_type_cd IS '검증이벤트유형코드(관제 수신). 외부 VLM 검증 API 요청의 event_type 조달처이며 허용값은 fire/fall/violence/flooding/car_accident/kidnapping 6종이다. 이벤트유형코드(EVNT_TYPE_CD, 예 EV01000101)와 서로 다른 값이다 — 대체·통합하거나 한쪽에서 유도하지 않는다. 관제 미송신 시 null 이며 서버가 보정하지 않는다(수신 원장). 허용값 검증은 DB CHECK 가 아니라 저작도구 쓰기 통로(400)와 소비 시점 fail-closed 재검증이 담당한다 — 관제 직접 INSERT 를 막지 않기 위함이다.';


--
-- Name: COLUMN ls_data_ingest.og_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_ingest.og_cd IS '기관코드(관제 resource_cctvs 조인). 촬영 시점 값 고정 목적의 의도된 중복 저장. 지방자치단체코드(LCLGV_CD)·지방자치단체명(LCLGV_NM)과 서로 다른 값이며 셋을 대체·통합하지 않는다. 관제 재요청으로 재추가(2026-08-24, 구 V185 제거분 복원).';


--
-- Name: ls_data_ingest_rcptn_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_ingest ALTER COLUMN rcptn_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_ingest_rcptn_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_issue; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_issue (
    data_issue_sn bigint NOT NULL,
    up_data_issue_sn bigint,
    data_raw_sn bigint NOT NULL,
    issue_rsn character varying(1000),
    reported_user_no character varying(50),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    issue_type_cd character varying(20) DEFAULT 'REJECTION'::character varying NOT NULL,
    issue_stts_cd character varying(20) DEFAULT 'OPEN'::character varying NOT NULL,
    src_sn bigint,
    ver bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_ls_data_issue_stts CHECK (((issue_stts_cd)::text = ANY ((ARRAY['OPEN'::character varying, 'ANSWERED'::character varying, 'RESOLVED'::character varying])::text[]))),
    CONSTRAINT ck_ls_data_issue_type CHECK (((issue_type_cd)::text = ANY ((ARRAY['REJECTION'::character varying, 'INQUIRY'::character varying])::text[])))
);


--
-- Name: ls_data_issue_data_issue_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_issue ALTER COLUMN data_issue_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_issue_data_issue_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_lbl; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_lbl (
    lbl_sn bigint NOT NULL,
    src_sn bigint NOT NULL,
    lbl_type_cd character varying(16) NOT NULL,
    lbl_nm character varying(80),
    point_cn text,
    trck_id character varying(30),
    reg_user_no character varying(100),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone,
    lbl_id bigint,
    lbl_src_cd character varying(20),
    mdl_nm character varying(100),
    mdl_ver character varying(50),
    conf_score numeric(6,5),
    auto_lbl_yn character(1)
);


--
-- Name: COLUMN ls_data_lbl.lbl_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl.lbl_nm IS '라벨명 — 이 라벨의 표시 이름. 마스터(LS_LABEL) 연결이 있으면 조회 시점에 마스터 이름이 우선하고 이 값은 보조다. ★NULL 을 허용하는 이유(V30): 포털 업로드 자산의 수동 라벨은 마스터를 참조하지 않는 자유 입력이라 이름 없이 도형만 그리는 것이 정상이다. 기본값을 지어 채우면 나중에 진짜 라벨명과 구분되지 않으므로 없다는 사실을 그대로 둔다. 내부 채널 생성 통로는 전부 이 값을 채우므로 관제 라벨에 NULL 이 새로 생기지는 않는다. @design ADR-058, ERD-028';


--
-- Name: COLUMN ls_data_lbl.reg_user_no; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl.reg_user_no IS '등록사용자번호 — 이 라벨을 만든 사용자. 내부 채널에서는 작업자이고, 포털 업로드 자산의 라벨에서는 그 라벨의 소유자이자 인가 판정의 키라 반드시 채워진다. ★자료형이 숫자가 아니라 문자인 이유(V29): 포털이 발급한 토큰의 주체 식별자를 담아야 한다. 숫자로 두면 파싱 실패로 조용히 null 이 되어 소유자 없는 라벨이 저장된다. 폭 100 은 공통표준도메인 번호V100 이며 ls_marking.reg_user_no(V28)와 같다. @design ADR-058, ERD-028';


--
-- Name: COLUMN ls_data_lbl.lbl_src_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl.lbl_src_cd IS '라벨출처코드. YOLO / SAM2 / INTERPOLATE / VLM. NULL = AI 가 만들지 않은 라벨(사람이 그린 것).';


--
-- Name: COLUMN ls_data_lbl.mdl_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl.mdl_nm IS '모델명. 자동 라벨을 만든 추론 모델. 적재 경로가 아직 값을 채우지 않아 전량 NULL 이다.';


--
-- Name: COLUMN ls_data_lbl.mdl_ver; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl.mdl_ver IS '모델버전. MDL_NM 과 같은 이유로 현재 전량 NULL 이다.';


--
-- Name: COLUMN ls_data_lbl.conf_score; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl.conf_score IS '신뢰도점수 0.0~1.0. 수동 라벨과 보간 이전 라벨은 NULL.';


--
-- Name: COLUMN ls_data_lbl.auto_lbl_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl.auto_lbl_yn IS '자동라벨여부 Y/N. NULL 은 "N" 이 아니라 <AI 정보가 없는 라벨>이라는 뜻이다 — DEFAULT 를 걸지 않는다. 응답의 N 치환은 조립 지점이 담당하며 이 컬럼을 오염시키지 않는다.';


--
-- Name: ls_data_lbl_attr_val; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_lbl_attr_val (
    atrb_vl_id bigint NOT NULL,
    lbl_sn bigint NOT NULL,
    atrb_id bigint NOT NULL,
    atrb_vl character varying(1000),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_data_lbl_attr_val_attr_val_id_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_lbl_attr_val ALTER COLUMN atrb_vl_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_lbl_attr_val_attr_val_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_lbl_hstry; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_lbl_hstry (
    lbl_hstry_sn bigint NOT NULL,
    src_sn bigint NOT NULL,
    reg_dt timestamp without time zone NOT NULL,
    reg_id character varying(30),
    add_cnt integer DEFAULT 0 NOT NULL,
    mdfcn_cnt integer DEFAULT 0 NOT NULL,
    del_cnt integer DEFAULT 0 NOT NULL,
    chg_dtl_cn text
);


--
-- Name: TABLE ls_data_lbl_hstry; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_data_lbl_hstry IS '라벨 저장 이벤트 이력 — 프레임 단위 1행(종류별 건수 + diff 페이로드)';


--
-- Name: COLUMN ls_data_lbl_hstry.src_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl_hstry.src_sn IS '저장 이벤트가 발생한 프레임 LS_DATA_SRC.SRC_SN';


--
-- Name: COLUMN ls_data_lbl_hstry.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl_hstry.reg_dt IS '저장 이벤트 기록 일시';


--
-- Name: COLUMN ls_data_lbl_hstry.reg_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl_hstry.reg_id IS '작업자 식별자(감사) — 토큰/PII 미저장, 신고 경로는 NULL';


--
-- Name: COLUMN ls_data_lbl_hstry.add_cnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl_hstry.add_cnt IS '이 저장 이벤트의 추가(ADDED) 라벨 건수';


--
-- Name: COLUMN ls_data_lbl_hstry.mdfcn_cnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl_hstry.mdfcn_cnt IS '이 저장 이벤트의 수정(UPDATED) 라벨 건수';


--
-- Name: COLUMN ls_data_lbl_hstry.del_cnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl_hstry.del_cnt IS '이 저장 이벤트의 삭제(DELETED) 라벨 건수';


--
-- Name: COLUMN ls_data_lbl_hstry.chg_dtl_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_lbl_hstry.chg_dtl_cn IS '변경 상세 diff — List<LabelChange> JSON 직렬화(TEXT)';


--
-- Name: ls_data_lbl_hstry_lbl_hstry_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_lbl_hstry ALTER COLUMN lbl_hstry_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_lbl_hstry_lbl_hstry_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_lbl_lbl_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_lbl ALTER COLUMN lbl_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_lbl_lbl_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_meta; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_meta (
    meta_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    meta_key character varying(64) NOT NULL,
    meta_vl character varying(2000),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone,
    idmp_key character varying(128),
    otsd_job_id character varying(200),
    rtry_nmtm integer DEFAULT 0 NOT NULL,
    dead_letter_at timestamp without time zone
);


--
-- Name: ls_data_meta_meta_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_meta ALTER COLUMN meta_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_meta_meta_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_meta_review; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_meta_review (
    data_meta_review_sn bigint NOT NULL,
    data_meta_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    data_src_sn bigint,
    meta_type_cd character varying(20) NOT NULL,
    src_sys_cd character varying(20),
    rvw_stts_cd character varying(20) NOT NULL,
    rvw_id character varying(30),
    rvw_dt timestamp without time zone,
    rjct_rsn character varying(4000),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_data_meta_review_data_meta_review_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_meta_review ALTER COLUMN data_meta_review_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_meta_review_data_meta_review_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_raw; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_raw (
    raw_sn bigint NOT NULL,
    vms_clip_id character varying(128) NOT NULL,
    vms_cctv_id character varying(64),
    evnt_type_cd character varying(20),
    lclgv_cd character varying(20),
    prvc_type_cd character varying(16) NOT NULL,
    prvc_yn character(1) DEFAULT 'N'::character varying NOT NULL,
    de_ident_yn character(1) DEFAULT 'N'::character varying NOT NULL,
    raw_file_path_nm character varying(500) NOT NULL,
    sht_dt timestamp without time zone,
    vdo_len_sec integer,
    data_stts_cd character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone,
    orgnl_raw_sn bigint,
    vdo_len_ms bigint,
    wthr_nm character varying(20),
    day_ngt_cd character varying(20),
    sesn_cd character varying(20),
    src_type character varying(20),
    aug_type_cd character varying(20),
    anony_incl_yn character(1),
    psdo_incl_yn character(1),
    prvc_incl_yn character(1),
    portal_user_no character varying(100)
);


--
-- Name: COLUMN ls_data_raw.vms_cctv_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_raw.vms_cctv_id IS 'VMS CCTV 아이디 — 인입(LS_DATA_INGEST.VMS_CCTV_ID) 복사값. NULL 허용(2026-08-12 관제 확정). 화면 표시명은 CCTV명 → 이 값 → 영상 #{RAW_SN} 순으로 폴백한다.';


--
-- Name: COLUMN ls_data_raw.wthr_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_raw.wthr_nm IS '촬영 날씨(작업자 수동입력). NULL=미입력(미상). 자동 파생 원천이 없어 값이 있으면 항상 수동값이다.';


--
-- Name: COLUMN ls_data_raw.day_ngt_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_raw.day_ngt_cd IS '촬영 시간대 코드 주간/야간(작업자 수동입력). NULL=미입력(미상). 동결·export·데이터마트 뷰는 NULL 을 그대로 싣는다 — 촬영일시 추정 안 함(E-ISSUE-42). 촬영일시 파생은 화면 프리필(조회 응답 source=DERIVED)에만 쓴다.';


--
-- Name: COLUMN ls_data_raw.sesn_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_raw.sesn_cd IS '촬영 계절 코드(작업자 수동입력). NULL=미입력(미상). 동결·export·데이터마트 뷰는 NULL 을 그대로 싣는다 — 촬영일시 추정 안 함(E-ISSUE-42). 촬영일시 파생은 화면 프리필(조회 응답 source=DERIVED)에만 쓴다.';


--
-- Name: COLUMN ls_data_raw.src_type; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_raw.src_type IS '출처유형(ORIGINAL/RELAY/USER_ULD/GENERATED/AUGMENTED). 경계축은 "관제가 만들었나 / 저작도구가 만들었나"이며 외부 위탁 여부가 아니다. 관제 인입분은 LS_DATA_INGEST.SRC_TYPE 복사. 백필 전 기존 행은 NULL.';


--
-- Name: COLUMN ls_data_raw.aug_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_raw.aug_type_cd IS '증강유형코드(WINTER/NIGHT/RAIN/RESL_1080P/RESL_720P/RESL_480P). 파생영상이 자기 종류를 직접 보유해 VMS_CLIP_ID 마커 역파싱을 대체한다. 원본 영상은 NULL.';


--
-- Name: COLUMN ls_data_raw.anony_incl_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_raw.anony_incl_yn IS '영상 익명정보 포함여부(검수자/작업자 수동입력). NULL=미입력(비식별 기본값 Y 프리필 대상).';


--
-- Name: COLUMN ls_data_raw.psdo_incl_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_raw.psdo_incl_yn IS '영상 가명정보 포함여부(검수자/작업자 수동입력). NULL=미입력(비식별 기본값 N 프리필 대상).';


--
-- Name: COLUMN ls_data_raw.prvc_incl_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_raw.prvc_incl_yn IS '영상 개인정보 포함여부(검수자/작업자 수동입력). NULL=미입력(비식별 기본값 N 프리필 대상).';


--
-- Name: COLUMN ls_data_raw.portal_user_no; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_raw.portal_user_no IS '포털사용자번호 — 포털 채널이 발급한 토큰의 주체. 이 영상이 포털 사용자 본인 업로드 자산일 때만 채워지고 관제 인입 영상에는 비어 있다. 포털 경로의 인가는 전적으로 이 값 기반이며(본인 자산만 조회·수정·내려받기), 보존기간 만료 자동 삭제가 대상을 고를 때 쓰는 판별자 셋(출처유형·이 컬럼·보존기간 경과) 중 하나다. 하나만 빠뜨리면 관제 영상이 함께 지워진다. @design ADR-058, ERD-028';


--
-- Name: ls_data_raw_raw_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_raw ALTER COLUMN raw_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_raw_raw_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_src; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_src (
    src_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    frm_no bigint NOT NULL,
    src_file_path_nm character varying(500),
    de_idntf_src_file_path_nm character varying(1000),
    sht_dt timestamp without time zone,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    upd_dt timestamp without time zone,
    vdo_frm_no bigint,
    frm_expln character varying(1000),
    anony_incl_yn character(1),
    psdo_incl_yn character(1),
    prvc_incl_yn character(1),
    lbl_ver bigint DEFAULT 0 NOT NULL,
    dscd_yn character(1) DEFAULT 'N'::bpchar NOT NULL
);


--
-- Name: COLUMN ls_data_src.vdo_frm_no; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_src.vdo_frm_no IS '실제 영상 내 디코더 0-base 프레임 위치. FRM_NO(추출순번)와 의미 구분 — 재비식별 재추출용';


--
-- Name: COLUMN ls_data_src.frm_expln; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_src.frm_expln IS '프레임 설명(작업자 수기, NIA image.description 조달원). nullable.';


--
-- Name: COLUMN ls_data_src.anony_incl_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_src.anony_incl_yn IS '프레임 익명정보 포함여부(작업자 수동입력). NULL=미입력.';


--
-- Name: COLUMN ls_data_src.psdo_incl_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_src.psdo_incl_yn IS '프레임 가명정보 포함여부(작업자 수동입력). NULL=미입력.';


--
-- Name: COLUMN ls_data_src.prvc_incl_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_src.prvc_incl_yn IS '프레임 개인정보 포함여부(작업자 수동입력). NULL=미입력.';


--
-- Name: COLUMN ls_data_src.lbl_ver; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_src.lbl_ver IS '라벨버전 - 프레임 라벨셋 변경 시마다 +1 (동시 저장 lost update 차단용, 0부터 시작)';


--
-- Name: COLUMN ls_data_src.dscd_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_data_src.dscd_yn IS '폐기여부 — 이 프레임을 학습데이터 산출물에서 제외할지에 대한 사람의 판정. Y=폐기(산출 제외) / N=사용(기본값). 행을 삭제하지 않고 표시만 한다(복원 가능). 행안부 공통표준용어 DSCD_YN + 공통표준도메인 여부C1(CHAR(1), Y/N).';


--
-- Name: ls_data_src_hstry; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_data_src_hstry (
    hstry_seq bigint NOT NULL,
    src_sn bigint NOT NULL,
    chg_type_cd character varying(16) NOT NULL,
    chg_user_no bigint,
    chg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_data_src_hstry_hstry_seq_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_src_hstry ALTER COLUMN hstry_seq ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_src_hstry_hstry_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_src_src_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_data_src ALTER COLUMN src_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_data_src_src_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_dataset_export; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_dataset_export (
    output_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    output_ver_no integer NOT NULL,
    output_path_nm character varying(500),
    output_stts_cd character varying(20) NOT NULL,
    frme_cnt integer,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    content_hash character varying(64),
    rty_nmtm integer DEFAULT 0 NOT NULL,
    rty_dt timestamp without time zone,
    data_etbl_cpct bigint
);


--
-- Name: COLUMN ls_dataset_export.output_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_dataset_export.output_sn IS '산출물일련번호(PK). (V173 개명 — 구 EXPORT_SN. EXPORT 는 표준 미등록이라 산출물=OUTPUT 표준단어로 정정)';


--
-- Name: COLUMN ls_dataset_export.output_ver_no; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_dataset_export.output_ver_no IS '산출물버전번호(영상별 누적 건수+1). UK(DATA_RAW_SN, OUTPUT_VER_NO)로 동시 승인 중복 채번을 차단한다. (V173 개명 — 구 EXPORT_VER_NO)';


--
-- Name: COLUMN ls_dataset_export.output_path_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_dataset_export.output_path_nm IS '산출물경로명 — <영상 루트>({dirname(원본)}/{rawSn})다. 버전 루트가 아니다: 관제가 한 경로 아래에서 v1·v2 를 모두 보고 골라야 비교·복구가 성립한다. (V173 개명 — 구 EXPORT_PATH_NM)';


--
-- Name: COLUMN ls_dataset_export.output_stts_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_dataset_export.output_stts_cd IS '산출물상태코드. PENDING → SUCCEEDED|PARTIAL|FAILED. (V173 개명 — 구 EXPORT_STTS_CD)';


--
-- Name: COLUMN ls_dataset_export.frme_cnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_dataset_export.frme_cnt IS '산출 프레임 수 — 실제 프레임 수이며 원본벌+비식별벌 합계가 아니다. 관제 datasets.img_nocs · dataset_versions.data_etbl_nocs 에 그대로 실리고 그 정의가 "추출·라벨링 프레임 수"이기 때문이다(2벌 쓰기 건수를 합산하면 2배로 부풀고, 1벌만 산출하는 파생영상과 값의 축이 갈린다). (V185 재지정 — V173 이 남긴 "원본벌+비식별벌 합계" 서술은 산정 방식 변경으로 사실과 달라졌다)';


--
-- Name: COLUMN ls_dataset_export.rty_nmtm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_dataset_export.rty_nmtm IS '재시도횟수 - 이 산출 행을 기준으로 회수 잡이 재산출을 트리거한 누적 횟수(산출 결과와 무관하게 증가)';


--
-- Name: COLUMN ls_dataset_export.rty_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_dataset_export.rty_dt IS '재시도일시 - 회수 잡이 마지막으로 재산출을 트리거(클레임)한 시각. 동시 노드 중복 클레임 차단 키';


--
-- Name: COLUMN ls_dataset_export.data_etbl_cpct; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_dataset_export.data_etbl_cpct IS '데이터구축용량 — 이 버전의 산출 폴더({영상루트}/v{n}) 총 바이트(관제 dataset_versions.data_etbl_cpct 공급). 산출 실패 시 NULL 이며 그때도 export 는 성공으로 종결한다. (V173 신설, @req R4)';


--
-- Name: ls_dataset_export_output_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_dataset_export ALTER COLUMN output_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_dataset_export_output_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_dataset_video_meta; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_dataset_video_meta (
    meta_snpsht_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    snpsht_hash character varying(64) NOT NULL,
    active_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    orgnl_raw_sn bigint,
    vms_clip_id character varying(128),
    vms_cctv_id character varying(64),
    raw_file_path_nm character varying(500),
    sht_dt timestamp without time zone,
    vdo_len_sec integer,
    lclgv_cd character varying(20),
    prvc_yn character(1),
    prvc_type_cd character varying(16),
    de_ident_yn character(1),
    ai_crt_yn character(1),
    evnt_type_cd character varying(20),
    cctv_nm character varying(255),
    wgs84_lat numeric(10,7),
    wgs84_lot numeric(10,7),
    sido_nm character varying(100),
    sgg_nm character varying(100),
    file_fmt character varying(32),
    evnt_nm character varying(255),
    vdo_cdc character varying(20),
    fps numeric,
    bit_rt bigint,
    asprt_rt numeric,
    resl character varying(32),
    vdo_wdth integer,
    vdo_hgt integer,
    file_sz bigint,
    day_ngt_cd character varying(8),
    sesn_cd character varying(20),
    wthr_nm character varying(32),
    rvw_cmpl_dt timestamp without time zone,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reg_id character varying(30),
    evnt_anno_cn jsonb
);


--
-- Name: COLUMN ls_dataset_video_meta.evnt_anno_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_dataset_video_meta.evnt_anno_cn IS '동결된 event_annotation payload 원문(jsonb). 승인 시점 APPROVED event_annotation 스냅샷, 미승인/부재 시 NULL';


--
-- Name: ls_dataset_video_meta_meta_snpsht_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_dataset_video_meta ALTER COLUMN meta_snpsht_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_dataset_video_meta_meta_snpsht_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_deident_proc_log; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_deident_proc_log (
    proc_log_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    req_id character varying(64),
    orgnl_file_path_nm character varying(1000) NOT NULL,
    de_idntf_file_path_nm character varying(1000),
    proc_stts_cd character varying(20) NOT NULL,
    req_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    rspns_dt timestamp without time zone,
    err_cd character varying(50),
    err_msg_cn character varying(4000),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone,
    otsd_job_id character varying(200),
    de_idntf_pjt_id bigint,
    de_idntf_datst_id bigint,
    poll_stts_cd character varying(20),
    poll_last_dt timestamp without time zone,
    poll_atmpt_cnt integer DEFAULT 0,
    req_knd_cd character varying(20),
    face_dtct_cnt numeric(10,0),
    noplt_dtct_cnt numeric(10,0),
    frme_cnt numeric(10,0),
    prcs_bgng_dt timestamp without time zone,
    prcs_end_dt timestamp without time zone,
    rpt_file_path_nm character varying(1000)
);


--
-- Name: COLUMN ls_deident_proc_log.face_dtct_cnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_deident_proc_log.face_dtct_cnt IS '얼굴 검출 수 — KPST GET /retrieve_report 의 dsStatus[].faceCount(전체 검출 - 번호판). NULL=리포트 미조회/조회 실패.';


--
-- Name: COLUMN ls_deident_proc_log.noplt_dtct_cnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_deident_proc_log.noplt_dtct_cnt IS '번호판 검출 수 — KPST GET /retrieve_report 의 dsStatus[].lpCount(license plate). NULL=리포트 미조회/조회 실패.';


--
-- Name: COLUMN ls_deident_proc_log.frme_cnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_deident_proc_log.frme_cnt IS '비식별 처리 대상 총 프레임 수 — KPST GET /retrieve_report 의 dsStatus[].totalFrame. NULL=리포트 미조회/조회 실패.';


--
-- Name: COLUMN ls_deident_proc_log.prcs_bgng_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_deident_proc_log.prcs_bgng_dt IS '외부 솔루션의 비식별 처리 시작 일시 — dsStatus[].startTime. 해석 불가 값("None" 등)이면 NULL.';


--
-- Name: COLUMN ls_deident_proc_log.prcs_end_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_deident_proc_log.prcs_end_dt IS '외부 솔루션의 비식별 처리 종료 일시 — dsStatus[].endTime. 해석 불가 값("None" 등)이면 NULL.';


--
-- Name: COLUMN ls_deident_proc_log.rpt_file_path_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_deident_proc_log.rpt_file_path_nm IS '리포트가 회신한 파일 경로 — dsStatus[].fileName. 실측 계약상 결과 파일명이 아니라 원본 입력파일의 절대경로다(비식별 산출물 경로는 DE_IDNTF_FILE_PATH_NM). 1000자 초과 시 절단 적재.';


--
-- Name: ls_deident_proc_log_proc_log_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_deident_proc_log ALTER COLUMN proc_log_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_deident_proc_log_proc_log_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_deident_report; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_deident_report (
    deident_report_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    reporter_no bigint,
    rsn character varying(1000),
    report_stts_cd character varying(16),
    dclr_dt timestamp without time zone,
    resolved_dt timestamp without time zone,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone,
    dclr_stp_cd character varying(20)
);


--
-- Name: COLUMN ls_deident_report.dclr_stp_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_deident_report.dclr_stp_cd IS '신고 단계 코드 — MARKING(마킹 화면 신고, 해소 후 마킹부터 재개) / LABELING(라벨링 화면 신고, 해소 후 프레임만 재추출). NULL=단계 미상(컬럼 신설 이전 레거시 행, 재개 이벤트 미발행).';


--
-- Name: ls_deident_report_deident_report_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_deident_report ALTER COLUMN deident_report_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_deident_report_deident_report_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_eblc_uld_job; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_eblc_uld_job (
    eblc_uld_job_sn bigint NOT NULL,
    orgnl_fldr_path_nm character varying(300) NOT NULL,
    job_stts_cd character varying(20) DEFAULT 'RUNNING'::character varying NOT NULL,
    trgt_nocs integer DEFAULT 0 NOT NULL,
    scs_nocs integer DEFAULT 0 NOT NULL,
    fail_nocs integer DEFAULT 0 NOT NULL,
    dmnd_cn text,
    bgng_dt timestamp without time zone,
    cmptn_dt timestamp without time zone,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone
);


--
-- Name: TABLE ls_eblc_uld_job; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_eblc_uld_job IS '마킹이 끝난 영상 묶음을 폴더째 받아 일괄로 올리는 작업. 요청은 곧바로 반환하고 실제 적재는 뒤에서 항목별로 진행하므로, 진행과 결과를 여기 담는다.';


--
-- Name: COLUMN ls_eblc_uld_job.eblc_uld_job_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job.eblc_uld_job_sn IS '일괄업로드작업일련번호. 자동 증가.';


--
-- Name: COLUMN ls_eblc_uld_job.orgnl_fldr_path_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job.orgnl_fldr_path_nm IS '훑은 폴더의 위치. 허용된 저장소 범위 안이어야 한다.';


--
-- Name: COLUMN ls_eblc_uld_job.job_stts_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job.job_stts_cd IS '작업상태코드. RUNNING(진행중) → COMPLETED(완료) | FAILED(실패) | CANCELED(취소). COMPLETED 는 모든 항목이 끝나고 실패가 없는 경우, FAILED 는 모든 항목이 끝났으나 실패가 남은 경우, CANCELED 는 사람이 중단시킨 경우다.';


--
-- Name: COLUMN ls_eblc_uld_job.trgt_nocs; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job.trgt_nocs IS '대상건수. 작업을 만들 때 정해지고 이후 바뀌지 않는다. 진행률의 분모다.';


--
-- Name: COLUMN ls_eblc_uld_job.scs_nocs; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job.scs_nocs IS '성공건수. 적재에 성공한 항목 수.';


--
-- Name: COLUMN ls_eblc_uld_job.fail_nocs; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job.fail_nocs IS '실패건수. 실패했거나 건너뛴 항목 수.';


--
-- Name: COLUMN ls_eblc_uld_job.dmnd_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job.dmnd_cn IS '요구내용. 마킹 문서에서 얻을 수 없어 사람이 지정한 값(이벤트 유형·지자체 코드·카메라 식별자·개인정보 유형·촬영일시)을 담는다. 항목마다 같은 값이 붙는다.';


--
-- Name: COLUMN ls_eblc_uld_job.bgng_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job.bgng_dt IS '시작일시. 첫 항목의 처리를 시작한 시각.';


--
-- Name: COLUMN ls_eblc_uld_job.cmptn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job.cmptn_dt IS '완료일시. 마지막 항목이 끝나 작업이 종결된 시각.';


--
-- Name: COLUMN ls_eblc_uld_job.reg_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job.reg_id IS '등록아이디. 이 작업을 실행한 사람.';


--
-- Name: COLUMN ls_eblc_uld_job.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job.reg_dt IS '등록일시.';


--
-- Name: COLUMN ls_eblc_uld_job.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job.mdfcn_dt IS '수정일시.';


--
-- Name: ls_eblc_uld_job_artcl; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_eblc_uld_job_artcl (
    eblc_uld_job_artcl_sn bigint NOT NULL,
    eblc_uld_job_sn bigint NOT NULL,
    mark_file_path_nm character varying(300) NOT NULL,
    vdo_file_path_nm character varying(300),
    artcl_stts_cd character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    raw_sn bigint,
    fail_rsn character varying(4000),
    rtry_nmtm integer DEFAULT 0 NOT NULL,
    bgng_dt timestamp without time zone,
    cmptn_dt timestamp without time zone,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone
);


--
-- Name: TABLE ls_eblc_uld_job_artcl; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_eblc_uld_job_artcl IS '일괄업로드작업의 항목. 마킹 문서 한 건이 항목 한 건이다. 적재는 항목마다 따로 트랜잭션을 가지므로 한 건이 실패해도 나머지가 이어진다.';


--
-- Name: COLUMN ls_eblc_uld_job_artcl.eblc_uld_job_artcl_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job_artcl.eblc_uld_job_artcl_sn IS '일괄업로드작업항목일련번호. 자동 증가.';


--
-- Name: COLUMN ls_eblc_uld_job_artcl.eblc_uld_job_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job_artcl.eblc_uld_job_sn IS '소속 일괄업로드작업일련번호.';


--
-- Name: COLUMN ls_eblc_uld_job_artcl.mark_file_path_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job_artcl.mark_file_path_nm IS '마킹 문서의 위치.';


--
-- Name: COLUMN ls_eblc_uld_job_artcl.vdo_file_path_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job_artcl.vdo_file_path_nm IS '짝지은 영상 파일의 위치. 짝을 찾지 못하면 비어 있다. 마킹 문서가 함께 담은 원래 경로는 다른 체계에서 만들어진 값이라 위치로 쓰지 않는다 — 짝짓기는 문서가 적어 둔 파일 이름으로 한다.';


--
-- Name: COLUMN ls_eblc_uld_job_artcl.artcl_stts_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job_artcl.artcl_stts_cd IS '항목상태코드. PENDING(대기) → PROCESSING(처리중) → SUCCESS(성공) | FAILED(실패) | SKIPPED(건너뜀). 건너뜀은 짝을 찾지 못했거나 적재할 수 없는 상태여서 처리하지 않은 것이며 실패와 구분한다.';


--
-- Name: COLUMN ls_eblc_uld_job_artcl.raw_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job_artcl.raw_sn IS '적재에 성공해 만들어진 영상의 일련번호. 성공하기 전에는 비어 있다.';


--
-- Name: COLUMN ls_eblc_uld_job_artcl.fail_rsn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job_artcl.fail_rsn IS '실패했거나 건너뛴 사유. 사람이 읽고 무엇을 고쳐야 하는지 알 수 있는 문장이며 내부 구조를 드러내지 않는다.';


--
-- Name: COLUMN ls_eblc_uld_job_artcl.rtry_nmtm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job_artcl.rtry_nmtm IS '재시도횟수. 처리 도중 서버가 다시 뜨면 처리 중이던 항목을 다시 집을 수 있게 되돌리는데, 정해진 횟수를 넘게 집힌 항목은 실패로 마감해 영원히 맴돌지 않게 한다.';


--
-- Name: COLUMN ls_eblc_uld_job_artcl.bgng_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job_artcl.bgng_dt IS '시작일시.';


--
-- Name: COLUMN ls_eblc_uld_job_artcl.cmptn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job_artcl.cmptn_dt IS '완료일시.';


--
-- Name: COLUMN ls_eblc_uld_job_artcl.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job_artcl.reg_dt IS '등록일시.';


--
-- Name: COLUMN ls_eblc_uld_job_artcl.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_eblc_uld_job_artcl.mdfcn_dt IS '수정일시.';


--
-- Name: ls_eblc_uld_job_artcl_eblc_uld_job_artcl_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_eblc_uld_job_artcl ALTER COLUMN eblc_uld_job_artcl_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_eblc_uld_job_artcl_eblc_uld_job_artcl_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_eblc_uld_job_eblc_uld_job_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_eblc_uld_job ALTER COLUMN eblc_uld_job_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_eblc_uld_job_eblc_uld_job_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_evnt_anno; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_evnt_anno (
    evnt_anno_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    anno_cn jsonb NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_evnt_anno_evnt_anno_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_evnt_anno ALTER COLUMN evnt_anno_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_evnt_anno_evnt_anno_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_evnt_anno_review; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_evnt_anno_review (
    rvw_sn bigint NOT NULL,
    evnt_anno_sn bigint NOT NULL,
    rvw_stts_cd character varying(20) NOT NULL,
    meta_type_cd character varying(20),
    rvw_id character varying(30),
    rvw_dt timestamp without time zone,
    rjct_rsn character varying(4000),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone,
    ver bigint DEFAULT 0 NOT NULL
);


--
-- Name: ls_evnt_anno_review_rvw_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_evnt_anno_review ALTER COLUMN rvw_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_evnt_anno_review_rvw_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_evnt_ctgry; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_evnt_ctgry (
    evnt_clsf_cd character varying(20) NOT NULL,
    evnt_ctgry_cd character varying(20) NOT NULL,
    evnt_ctgry_nm character varying(200),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_evnt_ctgry; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_evnt_ctgry IS '이벤트카테고리 마스터(저작도구 소유). 구 관제 매핑(MNG_EX_EVNT_TYPE_MAP CD_TYPE=''02'') 10건의 이관처이며, 유형에 고유 이름이 없을 때 표시명이 여기로 폴백한다. 어노테이션(export)에는 나가지 않지만 원래 3계층 구조를 보존하는 값이다.';


--
-- Name: COLUMN ls_evnt_ctgry.evnt_clsf_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_evnt_ctgry.evnt_clsf_cd IS '이벤트분류코드(대분류) — 복합 PK 상위.';


--
-- Name: COLUMN ls_evnt_ctgry.evnt_ctgry_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_evnt_ctgry.evnt_ctgry_cd IS '이벤트카테고리코드 — 복합 PK 하위.';


--
-- Name: COLUMN ls_evnt_ctgry.evnt_ctgry_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_evnt_ctgry.evnt_ctgry_nm IS '이벤트카테고리명 — 표시명 4단 폴백의 3순위.';


--
-- Name: COLUMN ls_evnt_ctgry.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_evnt_ctgry.reg_dt IS '등록일시.';


--
-- Name: ls_evnt_type; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_evnt_type (
    evnt_type_cd character varying(20) NOT NULL,
    evnt_nm character varying(200),
    optr_indct_nm character varying(200),
    evnt_clsf_cd character varying(20),
    evnt_ctgry_cd character varying(20),
    clct_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_evnt_type; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_evnt_type IS '이벤트유형 마스터(저작도구 소유). 관제 인입 소비 시점에 미등록 유형코드가 자동 등록되고, 관제 수신 칸(EVNT_NM)은 인입이 갱신한다. 운영자 칸(OPTR_INDCT_NM)은 관리 API 전용이라 관제가 덮어쓰지 않는다.';


--
-- Name: COLUMN ls_evnt_type.evnt_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_evnt_type.evnt_type_cd IS '이벤트유형코드(PK). LS_DATA_RAW.EVNT_TYPE_CD · export JSON event_id 와 같은 값이다.';


--
-- Name: COLUMN ls_evnt_type.evnt_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_evnt_type.evnt_nm IS '관제 수신 이벤트유형명. 관제 인입(LS_DATA_INGEST.EVNT_NM)이 값이 바뀔 때만 갱신한다. 관제 마스터에는 유형별 이름이 없었으므로 이관 시점에는 비어 있고, 관제가 보내기 시작하면 채워진다.';


--
-- Name: COLUMN ls_evnt_type.optr_indct_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_evnt_type.optr_indct_nm IS '운영자 표시명. 관리 API(PATCH /v1/manage/event-types) 전용이며 ★관제 인입은 절대 쓰지 않는다. 비우면 관제 수신명으로 자연 복귀한다(관제 원본 유실 없음).';


--
-- Name: COLUMN ls_evnt_type.evnt_clsf_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_evnt_type.evnt_clsf_cd IS '이벤트분류코드(대분류). 관제 인입값(LS_DATA_INGEST.EVNT_CLSF_CD)에서 온다. 제외 대분류 설정의 판정축이며 null 이면 제외되지 않는다(fail-open).';


--
-- Name: COLUMN ls_evnt_type.evnt_ctgry_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_evnt_type.evnt_ctgry_cd IS '이벤트카테고리코드 — 원래 3계층 구조(대분류→카테고리→유형)의 중간 레벨. LS_EVNT_CTGRY 조인 키이며, 유형명이 없을 때 표시명이 카테고리명으로 폴백하는 근거다.';


--
-- Name: COLUMN ls_evnt_type.clct_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_evnt_type.clct_yn IS '수집여부(Y/N). 필터 드롭다운 노출 여부. 자동등록 기본값은 Y 다(인입으로 실제 들어온 유형이므로). ★관제는 이 값을 보내지 않으므로 인입이 갱신하지 않는다 — 운영자가 숨긴 유형이 되살아나면 안 된다.';


--
-- Name: COLUMN ls_evnt_type.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_evnt_type.reg_dt IS '등록일시.';


--
-- Name: ls_issue_comment; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_issue_comment (
    cmnt_sn bigint NOT NULL,
    data_issue_sn bigint NOT NULL,
    author_no character varying(50) NOT NULL,
    author_role_cd character varying(20) NOT NULL,
    cmnt_cn character varying(4000) NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_ls_issue_comment_role CHECK (((author_role_cd)::text = ANY ((ARRAY['ADMIN'::character varying, 'WORKER'::character varying, 'REVIEWER'::character varying])::text[])))
);


--
-- Name: COLUMN ls_issue_comment.author_role_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_issue_comment.author_role_cd IS '작성자역할 — 작성 시점 역할이며 표시와 상태 전이 판정에 쓴다. 허용값은 관리자(ADMIN)·라벨링 작업자(WORKER)·검수자(REVIEWER) 셋이다. ★관리자가 들어 있는 이유(V27): 관리자는 검수자 권한을 계층으로 물려받아 이 창구에 그대로 들어오므로 작성자 역할로 관리자도 저장된다. 관리자가 실제로 쓰지 않는 값처럼 보인다고 이 목록에서 빼지 말 것 — 빼면 관리자가 댓글을 다는 순간 제약 위반으로 실패한다. 셋 밖의 값(포털 회원 등)은 계속 거부된다.';


--
-- Name: ls_issue_comment_issue_comment_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_issue_comment ALTER COLUMN cmnt_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_issue_comment_issue_comment_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_label; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_label (
    lbl_id bigint NOT NULL,
    lbl_nm character varying(80) NOT NULL,
    colr_vl character varying(7) NOT NULL,
    lbl_type_cd character varying(16) NOT NULL,
    sort_seq integer DEFAULT 0 NOT NULL,
    use_yn character(1) DEFAULT 'Y'::character varying NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone,
    dtct_type_cd character varying(20)
);


--
-- Name: COLUMN ls_label.dtct_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_label.dtct_type_cd IS 'AI(COCO) 검출 클래스 매핑 — COCO 80 클래스명 저장(예: person/car/bus). NULL=미매핑(표시하되 검출 제외).';


--
-- Name: ls_label_attr; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_label_attr (
    atrb_id bigint NOT NULL,
    lbl_id bigint NOT NULL,
    atrb_nm character varying(100) NOT NULL,
    input_type_cd character varying(16) NOT NULL,
    values_cn character varying(1000),
    dflt_vl character varying(255),
    mutable_yn character(1) DEFAULT 'Y'::character varying NOT NULL,
    sort_seq integer DEFAULT 0 NOT NULL,
    use_yn character(1) DEFAULT 'Y'::character varying NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_label_attr_attr_id_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_label_attr ALTER COLUMN atrb_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_label_attr_attr_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_label_label_id_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_label ALTER COLUMN lbl_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_label_label_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_label_preset; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_label_preset (
    preset_id bigint NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    evnt_type_cd character varying(20) NOT NULL
);


--
-- Name: TABLE ls_label_preset; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_label_preset IS '라벨 프리셋 — 이벤트유형 1건에 대한 오토라벨 대상 라벨 세트. 식별자는 EVNT_TYPE_CD(유니크)이며 사람이 읽는 이름은 이벤트유형 마스터의 표시명이 제공한다(프리셋 자체는 이름을 갖지 않는다).';


--
-- Name: COLUMN ls_label_preset.evnt_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_label_preset.evnt_type_cd IS '이벤트유형코드(유니크·필수). 프리셋의 유일한 식별 축이다. 영상의 이벤트 코드를 필터 키로 변환한 값과 대조해 오토라벨 대상 라벨을 정한다.';


--
-- Name: ls_label_preset_code; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_label_preset_code (
    cd_sn bigint NOT NULL,
    preset_id bigint NOT NULL,
    lbl_cd character varying(32),
    sort_seq integer DEFAULT 0 NOT NULL,
    lbl_id bigint
);


--
-- Name: ls_label_preset_code_code_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_label_preset_code ALTER COLUMN cd_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_label_preset_code_code_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_label_preset_preset_id_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_label_preset ALTER COLUMN preset_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_label_preset_preset_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_label_version; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_label_version (
    lbl_version_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    data_src_sn bigint,
    ver_no integer,
    save_reason_cd character varying(20),
    actvtn_yn character(1) DEFAULT 'Y'::character varying NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    lbl_payload text,
    version_hash character varying(64)
);


--
-- Name: COLUMN ls_label_version.ver_no; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_label_version.ver_no IS '영상 단위 산출 버전 번호 — LS_DATASET_EXPORT.OUTPUT_VER_NO 와 같은 번호이며 관제가 픽업하는 산출 폴더 v1·v2 와 일치한다. NULL=버전 번호를 알 수 없음. 구 의미(프레임별 승인 순번)는 폐기됐다 — 승인은 영상 단위인데 내용 무변경 프레임은 스냅샷이 생기지 않아 프레임마다 번호가 밀려 회차를 지목할 수 없었다(V181 에서 기존 값 전량 무효화).';


--
-- Name: ls_label_version_label_version_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_label_version ALTER COLUMN lbl_version_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_label_version_label_version_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_marking; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_marking (
    marking_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    mark_mode_cd character varying(16) NOT NULL,
    frme_intv_nocs integer,
    mark_cn text NOT NULL,
    stts_cd character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    reg_user_no character varying(100),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    fps double precision,
    vrfc_evnt_qstn_sn bigint,
    vrfc_evnt_type_cd character varying(20)
);


--
-- Name: COLUMN ls_marking.reg_user_no; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_marking.reg_user_no IS '등록사용자번호 — 마킹을 만든 사용자. 내부 채널에서는 작업자이고, 포털 채널에서는 그 마킹의 소유자이자 인가 판정의 키라 반드시 채워진다. ★자료형이 숫자가 아니라 문자인 이유(V28): 포털이 발급한 토큰의 주체 식별자를 담아야 한다. 폭 100 은 공통표준도메인 번호V100 을 따르며, 더 좁게 잡으면 서로 다른 사용자가 같은 값으로 잘려 인가가 조용히 어긋난다.';


--
-- Name: COLUMN ls_marking.vrfc_evnt_qstn_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_marking.vrfc_evnt_qstn_sn IS '검증이벤트질문일련번호 — 작업자가 그 영상의 검증 이벤트 유형에 등록된 질문 가운데 고른 값. 기본 선택은 그 유형의 첫 번째 질문이다. 값이 없거나 그 유형에 속하지 않는 질문이면 조달 시점에 첫 번째로 되돌린다(화면 입력을 신뢰하지 않는다). 마킹을 거치지 않는 경로는 언제나 첫 번째를 쓴다. 검증 이벤트 유형이 미수신이면 비어 있고, 그래도 위탁은 그대로 나간다. ★물리 FK 를 걸지 않는다 — 질문 목록이 전체 교체로 저장되므로 참조 무결성을 조달 판정기가 갖는다.';


--
-- Name: COLUMN ls_marking.vrfc_evnt_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_marking.vrfc_evnt_type_cd IS '검증이벤트유형코드 — 관제가 유형을 보내지 않은 영상에서 작업자가 마킹 화면에서 직접 고른 값. 관제 값이 있으면 화면이 선택을 노출하지 않으므로 이 칸은 쓰이지 않는다(조달 순서: 관제 인입 값 -> 이 칸 -> null). 선택 사항이라 비어 있을 수 있다.';


--
-- Name: ls_marking_marking_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_marking ALTER COLUMN marking_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_marking_marking_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_meta_repl_outbox; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_meta_repl_outbox (
    outbox_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    snpsht_hash character varying(64) NOT NULL,
    payload_cn text,
    stts_cd character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    rtry_nmtm integer DEFAULT 0 NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    prcs_dt timestamp without time zone
);


--
-- Name: COLUMN ls_meta_repl_outbox.payload_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_meta_repl_outbox.payload_cn IS '페이로드내용. 포털로 보낼 동결 메타 스냅샷 JSON. 로그·예외 메시지에 본문을 싣지 않는다.';


--
-- Name: COLUMN ls_meta_repl_outbox.stts_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_meta_repl_outbox.stts_cd IS '상태코드. PENDING → DONE/DEAD, 더 최신 스냅샷이 발행되면 SUPERSEDED.';


--
-- Name: COLUMN ls_meta_repl_outbox.rtry_nmtm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_meta_repl_outbox.rtry_nmtm IS '재시도횟수. 상한 초과 시 DEAD(사장큐)로 격리한다.';


--
-- Name: COLUMN ls_meta_repl_outbox.prcs_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_meta_repl_outbox.prcs_dt IS '처리일시. 처리는 PRCS 다 — PROC 는 프로세스라 뜻이 달라진다.';


--
-- Name: ls_meta_repl_outbox_outbox_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_meta_repl_outbox ALTER COLUMN outbox_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_meta_repl_outbox_outbox_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_mngr_pswd; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_mngr_pswd (
    mngr_pswd_sn bigint NOT NULL,
    pswd_hash character varying(100) NOT NULL,
    mdfr_id character varying(30),
    pswd_mdfcn_dt timestamp without time zone,
    CONSTRAINT ck_ls_mngr_pswd_single_row CHECK ((mngr_pswd_sn = 1))
);


--
-- Name: TABLE ls_mngr_pswd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_mngr_pswd IS '관리자비밀번호 — 관리자 유효창 발급에 쓰는 공유 자격의 해시. ★행은 최대 1개다(기본키 + 일련번호를 1로 고정하는 체크 제약). 둘이 되면 어느 것이 현재 자격인지 판정할 수 없고, 그 판정을 애플리케이션이 대신하기 시작하면 그것이 두 번째 진실원이 된다. 행이 없는 것이 정상 상태이며 그때는 배포 설정값으로 폴백한다 — 그래서 시드하지 않는다. ★세대·판수 컬럼을 두지 않는다: 자격 교체 시 기존 유효창 무효화는 서명이 현재 자격에 의존하게 해서 이루므로 상태를 늘리지 않는다(부재가 설계 결정이다).';


--
-- Name: COLUMN ls_mngr_pswd.mngr_pswd_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_mngr_pswd.mngr_pswd_sn IS '관리자비밀번호일련번호 — 값이 1로 고정된다. 기본키와 체크 제약이 함께 걸려 있어야 「행 최대 1개」가 성립한다(기본키만 있으면 1·2·3이 나란히 서고, 체크만 있으면 값이 전부 1인 행이 여러 개 들어간다).';


--
-- Name: COLUMN ls_mngr_pswd.pswd_hash; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_mngr_pswd.pswd_hash IS '비밀번호해시 — ★평문을 담지 않는다. 사업표준 도메인 번호V100.';


--
-- Name: COLUMN ls_mngr_pswd.mdfr_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_mngr_pswd.mdfr_id IS '수정자아이디 — 마지막으로 자격을 교체한 사람. 공유 자격이라 「누가 지금 아는가」는 알 수 없고, 「누가 마지막으로 바꿨는가」만 남는다.';


--
-- Name: COLUMN ls_mngr_pswd.pswd_mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_mngr_pswd.pswd_mdfcn_dt IS '비밀번호수정일시 — 마지막 교체 시각.';


--
-- Name: ls_mon_noti_acml; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_mon_noti_acml (
    noti_acml_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    stts_cd character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    export_rprcs_yn character(1) DEFAULT 'N'::bpchar NOT NULL,
    chg_dtl_cn text DEFAULT '{}'::text NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_mon_noti_acml; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_mon_noti_acml IS '관제 수정 통지 디바운스 누적 — 영상 1건의 변경을 윈도우로 모아 1회만 flush(2노드 정합).';


--
-- Name: COLUMN ls_mon_noti_acml.raw_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_mon_noti_acml.raw_sn IS '영상 단위 작업 식별자. 통지 단위가 영상 1건이므로 윈도우 키다.';


--
-- Name: COLUMN ls_mon_noti_acml.stts_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_mon_noti_acml.stts_cd IS 'PENDING(축적 중) / FLUSHING(한 노드가 클레임해 발송 중). 발송 완료 시 행 삭제.';


--
-- Name: COLUMN ls_mon_noti_acml.export_rprcs_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_mon_noti_acml.export_rprcs_yn IS 'export 폴더 전량 재생성 동반 여부(OR 누적). Y 면 재생성 후 통지, N 이면 즉시 통지.';


--
-- Name: COLUMN ls_mon_noti_acml.chg_dtl_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_mon_noti_acml.chg_dtl_cn IS '누적 변경 상세 JSON {"frames":{srcSn:[변경종류..]},"video":[변경종류..]}. 라벨/메타 본문·PII 미포함.';


--
-- Name: COLUMN ls_mon_noti_acml.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_mon_noti_acml.reg_dt IS '윈도우 열린 시각. 디바운스 만료 판정 기준.';


--
-- Name: COLUMN ls_mon_noti_acml.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_mon_noti_acml.mdfcn_dt IS '마지막 갱신 시각. FLUSHING 행에서는 클레임 임차 시작 시각(임차 만료 시 재클레임 기준).';


--
-- Name: ls_mon_noti_acml_noti_acml_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_mon_noti_acml ALTER COLUMN noti_acml_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_mon_noti_acml_noti_acml_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_notice; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_notice (
    notice_sn bigint NOT NULL,
    notice_title character varying(200) NOT NULL,
    notice_cn text NOT NULL,
    upend_fix_yn character(1) DEFAULT 'N'::character varying NOT NULL,
    pblcn_stts_cd character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    pblcn_dt timestamp without time zone,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_notice_attach; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_notice_attach (
    atch_file_sn bigint NOT NULL,
    notice_sn bigint NOT NULL,
    orgnl_file_nm character varying(300) NOT NULL,
    strg_file_nm character varying(300) NOT NULL,
    file_path character varying(1000) NOT NULL,
    file_sz bigint NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_notice_attach_attach_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_notice_attach ALTER COLUMN atch_file_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_notice_attach_attach_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_notice_notice_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_notice ALTER COLUMN notice_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_notice_notice_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_otsd_ctgry_mpng; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_otsd_ctgry_mpng (
    mpng_sn bigint NOT NULL,
    mpng_knd_cd character varying(20) NOT NULL,
    otsd_ctgry_cd character varying(20) NOT NULL,
    otsd_ctgry_nm character varying(200),
    lbl_id bigint,
    evnt_type_cd character varying(20),
    use_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: TABLE ls_otsd_ctgry_mpng; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_otsd_ctgry_mpng IS '외부카테고리대응 — 외부 산출물이 쓰는 분류 이름을 저작도구 라벨 체계·이벤트 유형에 잇는다. 대응은 종류 단위이며 개별 항목마다 정하지 않는다. 처음 보는 분류는 이름이 비슷한 후보를 제시하고 사람이 확인해 확정하며, 자동으로 확정하지 않는다 — 짐작으로 연결하면 다른 분류로 저장되고 저장된 뒤에는 어느 것이 짐작이었는지 구분할 수 없다. 대응이 정해지지 않은 분류가 하나라도 남으면 적재하지 않는다.';


--
-- Name: COLUMN ls_otsd_ctgry_mpng.mpng_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_ctgry_mpng.mpng_sn IS '대응일련번호 — 대응 한 건을 가리킨다.';


--
-- Name: COLUMN ls_otsd_ctgry_mpng.mpng_knd_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_ctgry_mpng.mpng_knd_cd IS '대응종류코드 — LABEL(라벨) / EVNT_TYPE(이벤트유형). 이 값이 어느 축인지 가르고 그 축의 참조 컬럼만 채운다(반대쪽은 NULL).';


--
-- Name: COLUMN ls_otsd_ctgry_mpng.otsd_ctgry_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_ctgry_mpng.otsd_ctgry_cd IS '외부카테고리코드 — 외부 산출물이 준 분류 식별 문자열 원문. 저작도구가 정한 코드가 아니라 외부 체계의 값이다. 폭은 표준 코드 도메인을 따르며 넘는 값은 입구에서 거부한다.';


--
-- Name: COLUMN ls_otsd_ctgry_mpng.otsd_ctgry_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_ctgry_mpng.otsd_ctgry_nm IS '외부카테고리명 — 외부 산출물이 준 표시 이름. 처음 보는 분류에 후보를 제시할 때 근거로 쓰고, 나중에 어떤 근거로 연결했는지 되짚는 데 쓴다.';


--
-- Name: COLUMN ls_otsd_ctgry_mpng.lbl_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_ctgry_mpng.lbl_id IS '라벨아이디 — 연결된 저작도구 라벨. 대응종류가 이벤트유형이면 비어 있다.';


--
-- Name: COLUMN ls_otsd_ctgry_mpng.evnt_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_ctgry_mpng.evnt_type_cd IS '이벤트유형코드 — 연결된 이벤트 유형. 대응종류가 라벨이면 비어 있다.';


--
-- Name: COLUMN ls_otsd_ctgry_mpng.use_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_ctgry_mpng.use_yn IS '사용여부 — 해제된 대응을 지우지 않고 표시로 남긴다(과거 이관이 어느 대응으로 적재됐는지 판독 가능하게).';


--
-- Name: COLUMN ls_otsd_ctgry_mpng.reg_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_ctgry_mpng.reg_id IS '등록자아이디';


--
-- Name: COLUMN ls_otsd_ctgry_mpng.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_ctgry_mpng.reg_dt IS '등록일시';


--
-- Name: COLUMN ls_otsd_ctgry_mpng.mdfr_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_ctgry_mpng.mdfr_id IS '수정자아이디';


--
-- Name: COLUMN ls_otsd_ctgry_mpng.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_ctgry_mpng.mdfcn_dt IS '수정일시';


--
-- Name: ls_otsd_ctgry_mpng_mpng_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_otsd_ctgry_mpng ALTER COLUMN mpng_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_otsd_ctgry_mpng_mpng_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_otsd_datst_trnsf_hstry; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_otsd_datst_trnsf_hstry (
    trnsf_sn bigint NOT NULL,
    orgnl_fldr_path_nm character varying(300) NOT NULL,
    orgnl_fldr_nm character varying(300),
    otsd_datst_id character varying(50),
    raw_sn bigint,
    trnsf_stts_cd character varying(20) NOT NULL,
    frme_cnt integer,
    lbl_cnt integer,
    fail_rsn character varying(1000),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_otsd_datst_trnsf_hstry; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_otsd_datst_trnsf_hstry IS '외부데이터셋이관이력 — 언제 누가 어떤 폴더를 가져와 몇 건이 들어왔는지의 감사 기록. 같은 산출물을 두 번 가져오려 할 때 거부의 근거가 된다. 영상이 지워져도 이력은 남는다(영상 참조는 SET NULL).';


--
-- Name: COLUMN ls_otsd_datst_trnsf_hstry.trnsf_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_datst_trnsf_hstry.trnsf_sn IS '이관일련번호 — 이관 한 건을 가리킨다.';


--
-- Name: COLUMN ls_otsd_datst_trnsf_hstry.orgnl_fldr_path_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_datst_trnsf_hstry.orgnl_fldr_path_nm IS '원본폴더경로명 — 가져온 산출물 폴더의 위치. 허용된 저장소 범위 밖이면 입구에서 거부한다.';


--
-- Name: COLUMN ls_otsd_datst_trnsf_hstry.orgnl_fldr_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_datst_trnsf_hstry.orgnl_fldr_nm IS '원본폴더명 — 폴더 식별자. 외부데이터셋아이디와 함께 같은 산출물인지 판정하는 축이다.';


--
-- Name: COLUMN ls_otsd_datst_trnsf_hstry.otsd_datst_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_datst_trnsf_hstry.otsd_datst_id IS '외부데이터셋아이디 — 산출물 문서가 밝힌 데이터셋 식별자.';


--
-- Name: COLUMN ls_otsd_datst_trnsf_hstry.raw_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_datst_trnsf_hstry.raw_sn IS '원시영상일련번호 — 이 이관으로 만들어진 영상. 영상이 지워지면 NULL 이 되고 이력 자체는 남는다.';


--
-- Name: COLUMN ls_otsd_datst_trnsf_hstry.trnsf_stts_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_datst_trnsf_hstry.trnsf_stts_cd IS '이관상태코드 — PROCESSING(진행중) / SUCCESS(성공) / FAILED(실패).';


--
-- Name: COLUMN ls_otsd_datst_trnsf_hstry.frme_cnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_datst_trnsf_hstry.frme_cnt IS '프레임건수 — 실제로 적재된 프레임 수. 문서가 선언한 수가 아니라 실제 파일 기준이다.';


--
-- Name: COLUMN ls_otsd_datst_trnsf_hstry.lbl_cnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_datst_trnsf_hstry.lbl_cnt IS '라벨건수 — 실제로 적재된 라벨 수.';


--
-- Name: COLUMN ls_otsd_datst_trnsf_hstry.fail_rsn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_datst_trnsf_hstry.fail_rsn IS '실패사유 — 이관이 실패한 경우의 사유.';


--
-- Name: COLUMN ls_otsd_datst_trnsf_hstry.reg_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_datst_trnsf_hstry.reg_id IS '등록자아이디';


--
-- Name: COLUMN ls_otsd_datst_trnsf_hstry.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_otsd_datst_trnsf_hstry.reg_dt IS '등록일시';


--
-- Name: ls_otsd_datst_trnsf_hstry_trnsf_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_otsd_datst_trnsf_hstry ALTER COLUMN trnsf_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_otsd_datst_trnsf_hstry_trnsf_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_output_ver_snpsh; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_output_ver_snpsh (
    output_ver_snpsh_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    data_src_sn bigint NOT NULL,
    output_ver_no integer NOT NULL,
    lbl_ver_sn bigint NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_output_ver_snpsh; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_output_ver_snpsh IS '산출 회차 ↔ 라벨 버전 스냅샷 매핑 — 「산출 회차 N 의 프레임 F 내용은 이 스냅샷이었다」. 「시작 버전 선택」(R6)이 되돌릴 스냅샷을 고르는 판정의 단일 원천이다. LS_LABEL_VERSION.VER_NO 는 조회·표시용으로 남되 판정 원천이 아니다 — 한 스냅샷이 여러 회차의 내용일 수 있어(내용 무변경 회차는 스냅샷이 생기지 않는다) 컬럼 하나로는 1:N 을 담을 수 없다.';


--
-- Name: COLUMN ls_output_ver_snpsh.output_ver_no; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_output_ver_snpsh.output_ver_no IS '산출 회차 번호 — LS_DATASET_EXPORT.OUTPUT_VER_NO 및 관제가 픽업하는 산출 폴더 v1·v2 와 같다.';


--
-- Name: COLUMN ls_output_ver_snpsh.lbl_ver_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_output_ver_snpsh.lbl_ver_sn IS '그 회차의 내용이 된 승인 스냅샷 PK(LS_LABEL_VERSION.LBL_VERSION_SN). 컬럼명은 표준 약어 VER 을 쓴다 — 참조 대상의 VERSION 표기는 선존 드리프트이며 미러하지 않는다.';


--
-- Name: ls_output_ver_snpsh_output_ver_snpsh_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_output_ver_snpsh ALTER COLUMN output_ver_snpsh_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_output_ver_snpsh_output_ver_snpsh_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_portal_tus_uld; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_portal_tus_uld (
    uld_id uuid NOT NULL,
    portal_user_no character varying(100) NOT NULL,
    uld_len bigint NOT NULL,
    uld_offset bigint NOT NULL,
    stts_cd character varying(16) NOT NULL,
    file_path_nm character varying(500) NOT NULL,
    orgnl_file_nm character varying(255),
    uld_sn bigint,
    expry_dt timestamp without time zone NOT NULL,
    ver bigint DEFAULT 0 NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_portal_uld; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_portal_uld (
    uld_sn bigint NOT NULL,
    portal_user_no character varying(100) NOT NULL,
    uld_type_cd character varying(16) NOT NULL,
    orgnl_file_nm character varying(255),
    file_path_nm character varying(500),
    file_sz bigint,
    mime_type_nm character varying(100),
    uld_stts_cd character varying(16) NOT NULL,
    vdo_len_sec double precision,
    fps double precision,
    frme_cnt integer,
    fail_rsn_cn text,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_portal_uld; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_portal_uld IS '포털 전용 업로드 원본(이미지/영상) — 데이터마트/원본과 무관한 포털 작업본.';


--
-- Name: COLUMN ls_portal_uld.uld_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_uld.uld_type_cd IS '업로드 유형: IMAGE | VIDEO.';


--
-- Name: COLUMN ls_portal_uld.uld_stts_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_uld.uld_stts_cd IS '업로드 상태: UPLOADED | PROCESSING | READY | FAILED.';


--
-- Name: COLUMN ls_portal_uld.frme_cnt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_uld.frme_cnt IS '영상 추출 프레임 수(이미지는 null).';


--
-- Name: COLUMN ls_portal_uld.fail_rsn_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_uld.fail_rsn_cn IS '처리 실패 사유(내용). 상태 FAILED 시 기록.';


--
-- Name: ls_portal_uld_frme_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

CREATE SEQUENCE klid_at.ls_portal_uld_frme_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ls_portal_uld_frme; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_portal_uld_frme (
    uld_frme_sn bigint DEFAULT nextval('klid_at.ls_portal_uld_frme_seq'::regclass) NOT NULL,
    uld_sn bigint NOT NULL,
    frme_no integer NOT NULL,
    file_path_nm character varying(500) NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_portal_uld_frme; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_portal_uld_frme IS '포털 업로드 프레임(이미지=1행, 영상=추출 프레임 N행). 업로드 삭제 시 연쇄 삭제.';


--
-- Name: COLUMN ls_portal_uld_frme.frme_no; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_uld_frme.frme_no IS '프레임 순번(영상 추출 순서, 이미지는 0).';


--
-- Name: ls_portal_uld_lbl; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_portal_uld_lbl (
    uld_lbl_sn bigint NOT NULL,
    portal_user_no character varying(100) NOT NULL,
    uld_sn bigint NOT NULL,
    uld_frme_sn bigint NOT NULL,
    lbl_type_cd character varying(16) NOT NULL,
    lbl_nm character varying(80),
    point_cn text,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_portal_uld_lbl; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_portal_uld_lbl IS '포털 업로드 프레임 위 수동 라벨(BBOX/POLYGON). 프레임 삭제 시 연쇄 삭제.';


--
-- Name: COLUMN ls_portal_uld_lbl.lbl_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_uld_lbl.lbl_type_cd IS '라벨 유형: BBOX | POLYGON.';


--
-- Name: COLUMN ls_portal_uld_lbl.point_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_uld_lbl.point_cn IS '좌표 JSON 내용(BBOX=[x,y,w,h] / POLYGON=[[x,y],...]).';


--
-- Name: ls_portal_uld_lbl_uld_lbl_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_portal_uld_lbl ALTER COLUMN uld_lbl_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_portal_uld_lbl_uld_lbl_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_portal_uld_uld_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_portal_uld ALTER COLUMN uld_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_portal_uld_uld_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_portal_user_evnt_anno; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_portal_user_evnt_anno (
    user_evnt_anno_sn bigint NOT NULL,
    portal_user_no character varying(100) NOT NULL,
    src_raw_sn bigint NOT NULL,
    anno_cn jsonb NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_portal_user_evnt_anno; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_portal_user_evnt_anno IS '포털사용자이벤트어노테이션 — 포털 사용자가 확인·수정·추가한 이벤트 어노테이션의 단방향 오버레이. 원본(LS_EVNT_ANNO)과 승인 시점 동결본을 수정하지 않으며 관제 통지·산출물 재생성을 일으키지 않는다. 영상 축이라 프레임 참조를 두지 않는다.';


--
-- Name: COLUMN ls_portal_user_evnt_anno.user_evnt_anno_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_evnt_anno.user_evnt_anno_sn IS '포털사용자이벤트어노테이션일련번호 — PK.';


--
-- Name: COLUMN ls_portal_user_evnt_anno.portal_user_no; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_evnt_anno.portal_user_no IS '포털사용자번호 — 포털 발급 토큰 sub 클레임. 본인 데이터만 조회/수정하도록 IDOR 차단 키.';


--
-- Name: COLUMN ls_portal_user_evnt_anno.src_raw_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_evnt_anno.src_raw_sn IS '원시데이터일련번호 — 대상 데이터마트 영상(LS_DATA_RAW).';


--
-- Name: COLUMN ls_portal_user_evnt_anno.anno_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_evnt_anno.anno_cn IS '어노테이션내용 — 내부 원장(LS_EVNT_ANNO.ANNO_CN)과 같은 구조체를 그대로 담는다. 키별로 펴지 않는다(산출 문서와 모양이 갈리면 재조립이 필요하고 중첩이 무너진다).';


--
-- Name: COLUMN ls_portal_user_evnt_anno.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_evnt_anno.reg_dt IS '등록일시 — 최초 저장 일시.';


--
-- Name: COLUMN ls_portal_user_evnt_anno.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_evnt_anno.mdfcn_dt IS '수정일시 — 마지막 저장 일시.';


--
-- Name: ls_portal_user_evnt_anno_user_evnt_anno_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_portal_user_evnt_anno ALTER COLUMN user_evnt_anno_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_portal_user_evnt_anno_user_evnt_anno_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_portal_user_label; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_portal_user_label (
    user_lbl_sn bigint NOT NULL,
    portal_user_no character varying(100) NOT NULL,
    src_raw_sn bigint NOT NULL,
    src_data_src_sn bigint NOT NULL,
    lbl_type_cd character varying(16) NOT NULL,
    lbl_nm character varying(80),
    point_cn text,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    lbl_id bigint,
    trck_id character varying(30)
);


--
-- Name: COLUMN ls_portal_user_label.lbl_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_label.lbl_id IS '라벨아이디 — 이 라벨이 가리키는 라벨 마스터. 산출 어노테이션의 분류 식별자 조달처. 외래키를 걸지 않으며 조회 시점 join 으로 해석한다(미매칭은 미연결). 값이 없으면 NULL.';


--
-- Name: COLUMN ls_portal_user_label.trck_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_label.trck_id IS '트랙아이디 — 산출 어노테이션의 트랙 식별자 조달처. 값이 없으면 NULL.';


--
-- Name: ls_portal_user_label_user_lbl_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_portal_user_label ALTER COLUMN user_lbl_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_portal_user_label_user_lbl_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_portal_user_meta; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_portal_user_meta (
    user_meta_sn bigint NOT NULL,
    portal_user_no character varying(100) NOT NULL,
    src_raw_sn bigint NOT NULL,
    src_data_src_sn bigint,
    meta_key character varying(64) NOT NULL,
    meta_vl character varying(2000),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_portal_user_meta; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_portal_user_meta IS '포털사용자메타 — 포털 사용자가 데이터마트 영상에서 확인·수정·추가한 메타 값의 단방향 오버레이. 촬영환경·프레임 설명·개인정보 판정·시계열 메타를 담으며 원본(LS_DATA_META)과 동결 스냅샷을 수정하지 않는다. 본인이 올린 자산은 이 표를 쓰지 않는다(가려야 할 남의 원본이 없다).';


--
-- Name: COLUMN ls_portal_user_meta.user_meta_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_meta.user_meta_sn IS '포털사용자메타일련번호 — PK.';


--
-- Name: COLUMN ls_portal_user_meta.portal_user_no; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_meta.portal_user_no IS '포털사용자번호 — 포털 발급 토큰 sub 클레임. 본인 데이터만 조회/수정하도록 IDOR 차단 키.';


--
-- Name: COLUMN ls_portal_user_meta.src_raw_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_meta.src_raw_sn IS '원시데이터일련번호 — 대상 데이터마트 영상(LS_DATA_RAW).';


--
-- Name: COLUMN ls_portal_user_meta.src_data_src_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_meta.src_data_src_sn IS '데이터원천일련번호 — 프레임 축 메타일 때 대상 프레임(LS_DATA_SRC). 영상 축 메타는 비운다. 지어내면 없는 프레임을 가리킨다.';


--
-- Name: COLUMN ls_portal_user_meta.meta_key; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_meta.meta_key IS '메타키 — 내부 원장(LS_DATA_META.META_KEY)과 같은 키 규격·같은 폭.';


--
-- Name: COLUMN ls_portal_user_meta.meta_vl; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_meta.meta_vl IS '메타값 — 내부 원장(LS_DATA_META.META_VL)과 같은 폭이라 넘치면 잘리는 지점도 같다.';


--
-- Name: COLUMN ls_portal_user_meta.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_meta.reg_dt IS '등록일시 — 최초 저장 일시.';


--
-- Name: COLUMN ls_portal_user_meta.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_portal_user_meta.mdfcn_dt IS '수정일시 — 마지막 저장 일시.';


--
-- Name: ls_portal_user_meta_user_meta_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_portal_user_meta ALTER COLUMN user_meta_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_portal_user_meta_user_meta_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_raw_data_status; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_raw_data_status (
    raw_data_id bigint NOT NULL,
    data_stts_cd character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    stp_cycl integer DEFAULT 0 NOT NULL,
    igi_cycl integer DEFAULT 0 NOT NULL,
    upd_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ver bigint DEFAULT 0 NOT NULL,
    revlt_yn character(1) DEFAULT 'N'::bpchar NOT NULL,
    de_idntf_cmptn_yn character(1) DEFAULT 'Y'::bpchar NOT NULL
);


--
-- Name: COLUMN ls_raw_data_status.revlt_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_raw_data_status.revlt_yn IS '재검토여부 — 검수 승인(APPROVED) 이후 라벨/메타가 수정되어 검수자의 재검토가 필요한가. Y=재검토 필요(재승인 대기) / N=재검토 불요(기본값). 행안부 공통표준용어 REVLT_YN + 공통표준도메인 여부C1(CHAR(1), Y/N).';


--
-- Name: COLUMN ls_raw_data_status.de_idntf_cmptn_yn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_raw_data_status.de_idntf_cmptn_yn IS '비식별화완료여부 — 값이 N 인 동안 그 영상의 검수 승인이 거부된다. 기본값이 Y 인 이유는 외부 산출물 이관 경로와 무관한 기존 영상이 이 값 때문에 승인이 막히면 안 되기 때문이며, 그 경로로 원본이라고 지정해 들어온 영상만 N 으로 시작한다. N 에서 Y 로 바뀌는 계기는 두 갈래다 — 가져올 때 영상 파일을 함께 준 원본이면 저작도구가 비식별 단계를 태우고 그 비식별이 성공으로 기록되면 Y 가 되고, 영상 파일이 없어 프레임만 가져온 경우에는 외부에서 비식별한 산출물을 받아 기록하는 별도 행위로 Y 가 된다. 그 기록 행위는 비식별 산출물이 실제로 존재하는지 확인한 뒤에만 성립한다. 이 값은 검수 승인 하나만 막는다 — 라벨 조회·프레임 이미지·영상 스트리밍·학습데이터 산출물 생성을 닫지 않으며, 그 통로들을 함께 닫는 것은 비식별 여부 값 가운데 누락 신고 상태로 별개 축이다.';


--
-- Name: ls_system_config; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_system_config (
    stng_key character varying(100) NOT NULL,
    stng_value character varying(4000),
    stng_type_cd character varying(20) NOT NULL,
    expln character varying(500),
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_task_altmnt; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_task_altmnt (
    assignment_id bigint NOT NULL,
    user_no bigint NOT NULL,
    raw_data_id bigint NOT NULL,
    task_type_cd character varying(20) NOT NULL,
    reg_user_no bigint NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ver bigint DEFAULT 0 NOT NULL
);


--
-- Name: ls_task_altmnt_assignment_id_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_task_altmnt ALTER COLUMN assignment_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_task_altmnt_assignment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_task_evnt_log; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_task_evnt_log (
    evnt_id bigint NOT NULL,
    raw_data_id bigint NOT NULL,
    evnt_type_cd character varying(20) NOT NULL,
    actor_user_no bigint NOT NULL,
    subject_user_no bigint,
    prev_user_no bigint,
    rsn character varying(500),
    ocrn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_task_evnt_log_evnt_id_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_task_evnt_log ALTER COLUMN evnt_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_task_evnt_log_evnt_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_tus_upload; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_tus_upload (
    uld_id uuid NOT NULL,
    user_no character varying(100) NOT NULL,
    uld_len bigint NOT NULL,
    uld_offset bigint DEFAULT 0 NOT NULL,
    stts_cd character varying(16) DEFAULT 'IN_PROGRESS'::character varying NOT NULL,
    file_path character varying(500) NOT NULL,
    file_nm character varying(255),
    vms_clip_id character varying(128),
    cctv_id character varying(64),
    evnt_type_cd character varying(20),
    lclgv_cd character varying(20),
    prvc_type_cd character varying(8),
    sht_dt timestamp without time zone,
    raw_sn bigint,
    expry_dt timestamp without time zone NOT NULL,
    ver bigint DEFAULT 0 NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: COLUMN ls_tus_upload.user_no; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_tus_upload.user_no IS '사용자번호 — 재개 업로드 세션의 소유자. 소유자만 진행 상태 조회·이어올리기·취소를 할 수 있다. ★폭이 100 인 이유(V29): 포털 채널 세션이 이 원장을 함께 쓰며 포털 토큰 주체 식별자를 담아야 한다. 좁히면 서로 다른 사용자가 같은 값으로 잘려 세션 인가가 조용히 어긋난다. 공통표준도메인 번호V100. @design ADR-058, ERD-028';


--
-- Name: ls_user_role; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_user_role (
    user_no bigint NOT NULL,
    role_cd character varying(32) NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    upd_dt timestamp without time zone,
    mdfr_id character varying(30)
);


--
-- Name: COLUMN ls_user_role.mdfr_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_user_role.mdfr_id IS '수정자아이디 — 이 사용자의 역할을 마지막으로 바꾼 사람(인계 토큰 subject). ★요청 바디가 아니라 인증 주체에서만 취한다(바디 값은 위조 가능하다). null 은 「이 컬럼이 생기기 전에 바뀐 행」이거나 「인증 주체를 알 수 없는 경로(운영 배치·시험 하네스)」를 뜻하며, 지어낸 값을 채우지 않는다. 이 컬럼이 남기는 것은 마지막 변경자 하나뿐이고 변경 이력 전체는 대상이 아니다(별개 결정).';


--
-- Name: ls_vrfc_evnt_qstn; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_vrfc_evnt_qstn (
    vrfc_evnt_qstn_sn bigint NOT NULL,
    vrfc_evnt_type_cd character varying(20) NOT NULL,
    sort_seq numeric(10,0) NOT NULL,
    qstn_cn character varying(4000) NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: TABLE ls_vrfc_evnt_qstn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_vrfc_evnt_qstn IS '검증이벤트질문 — 검증 이벤트 유형별 질문 문구. 한 유형에 여러 개를 둘 수 있고 순서가 곧 의미다(정렬순서 첫 번째가 기본 질문). 이 문구는 이벤트 어노테이션의 질문 칸을 채우는 조달원이며, 마킹에서 고른 값이 1순위이고 없거나 그 유형에 속하지 않으면 첫 번째로 되돌린다. 질문 본문은 외부 사업자 요청 바디와 로그에 실릴 값이므로 제어문자·개행을 입구에서 막는다.';


--
-- Name: COLUMN ls_vrfc_evnt_qstn.vrfc_evnt_qstn_sn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_qstn.vrfc_evnt_qstn_sn IS '검증이벤트질문일련번호 — 질문 한 건을 가리킨다. 마킹이 고른 질문을 이 값으로 보관한다.';


--
-- Name: COLUMN ls_vrfc_evnt_qstn.vrfc_evnt_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_qstn.vrfc_evnt_type_cd IS '검증이벤트유형코드 — 이 질문이 딸린 유형.';


--
-- Name: COLUMN ls_vrfc_evnt_qstn.sort_seq; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_qstn.sort_seq IS '정렬순서 — ★「첫 번째 질문」의 결정성 근거. (유형코드, 정렬순서) 유일 제약과 짝이며, 이것이 없으면 마킹을 거치지 않는 경로의 기본 질문이 실행마다 달라진다.';


--
-- Name: COLUMN ls_vrfc_evnt_qstn.qstn_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_qstn.qstn_cn IS '질문내용 — 그 이벤트가 있었는지와 근거를 묻는 문장. ⚠ 현행 위탁 요청에는 이 문장을 실을 자리가 없어, 첫 번째가 아닌 질문을 고르면 기록된 질문과 사업자가 실제로 쓴 질문이 달라진다(인지·수용한 위험).';


--
-- Name: COLUMN ls_vrfc_evnt_qstn.reg_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_qstn.reg_id IS '등록자아이디';


--
-- Name: COLUMN ls_vrfc_evnt_qstn.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_qstn.reg_dt IS '등록일시';


--
-- Name: COLUMN ls_vrfc_evnt_qstn.mdfr_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_qstn.mdfr_id IS '수정자아이디';


--
-- Name: COLUMN ls_vrfc_evnt_qstn.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_qstn.mdfcn_dt IS '수정일시';


--
-- Name: ls_vrfc_evnt_qstn_vrfc_evnt_qstn_sn_seq; Type: SEQUENCE; Schema: klid_at; Owner: -
--

ALTER TABLE klid_at.ls_vrfc_evnt_qstn ALTER COLUMN vrfc_evnt_qstn_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME klid_at.ls_vrfc_evnt_qstn_vrfc_evnt_qstn_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_vrfc_evnt_type; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_vrfc_evnt_type (
    vrfc_evnt_type_cd character varying(20) NOT NULL,
    vrfc_evnt_type_nm character varying(300) NOT NULL,
    vrfc_evnt_type_expln character varying(4000),
    sort_seq numeric(10,0) DEFAULT 0 NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: TABLE ls_vrfc_evnt_type; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_vrfc_evnt_type IS '검증이벤트유형 — 외부 시계열 분석에 위탁할 때 쓰는 이벤트 유형의 표시·시드 카탈로그. ★허용목록이 아니다: 위탁 가능 여부를 이 표로 게이팅하지 않으며, 여기에 없는 유형의 영상도 위탁은 그대로 나가고 질문 칸만 빈다. 사업자 열거값의 사본이 두 번째 진실원이 되어 정상 값을 우리가 먼저 막은 결함이 있었다. 관제 이벤트 코드 마스터와는 값 공간이 다른 별개 체계이므로 합치거나 참조로 잇지 않는다.';


--
-- Name: COLUMN ls_vrfc_evnt_type.vrfc_evnt_type_cd; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_type.vrfc_evnt_type_cd IS '검증이벤트유형코드 — 관제 인입 원장이 실어 보내는 검증 이벤트 유형과 같은 값 공간이다.';


--
-- Name: COLUMN ls_vrfc_evnt_type.vrfc_evnt_type_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_type.vrfc_evnt_type_nm IS '검증이벤트유형명 — 화면에서 사람이 읽는 이름.';


--
-- Name: COLUMN ls_vrfc_evnt_type.vrfc_evnt_type_expln; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_type.vrfc_evnt_type_expln IS '검증이벤트유형설명 — 그 유형이 어떤 상황을 가리키는지의 서술.';


--
-- Name: COLUMN ls_vrfc_evnt_type.sort_seq; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_type.sort_seq IS '정렬순서 — 화면 표시 순서. 질문의 「첫 번째」 판정과는 다른 축이다.';


--
-- Name: COLUMN ls_vrfc_evnt_type.reg_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_type.reg_id IS '등록자아이디';


--
-- Name: COLUMN ls_vrfc_evnt_type.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_type.reg_dt IS '등록일시';


--
-- Name: COLUMN ls_vrfc_evnt_type.mdfr_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_type.mdfr_id IS '수정자아이디';


--
-- Name: COLUMN ls_vrfc_evnt_type.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_vrfc_evnt_type.mdfcn_dt IS '수정일시';


--
-- Name: ls_webhook_idempotency; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_webhook_idempotency (
    idmp_key character varying(128) NOT NULL,
    chnl_cd character varying(32) NOT NULL,
    stts_cd character varying(16) NOT NULL,
    otsd_job_id character varying(200),
    aplcn_dt timestamp without time zone,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    raw_sn bigint,
    srvr_id character varying(20),
    qstn_cn character varying(4000)
);


--
-- Name: COLUMN ls_webhook_idempotency.srvr_id; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_webhook_idempotency.srvr_id IS 'AI서버아이디 — 이 위탁을 보낸 AI 서버(장비) 식별자. 외부 시계열 분석 서버가 장비 두 대로 이중화되면서 장비별 부하를 세고 결과 출처를 되짚을 자리가 필요해졌다. ★부하로 세는 것은 수락된(ACCEPTED) 행뿐이다 — 발급(ISSUED)은 벤더가 아직 받지 않아 그 장비의 부하가 0이고, 세면 제출이 몰린 장비를 과대평가해 다음 요청이 반대편으로 쏠린다. ★nullable 이라 이 컬럼 도입 전 행과 장비를 고르지 못한 위탁은 장비 미상이다 — 그래서 미결 회수 스윕의 조회·클레임 조건에 장비 축을 걸지 않는다. 걸면 미상 행과 죽은 장비의 몫이 영영 회수되지 않는데, 그 스윕이 무증상 영구 결손을 막는 유일한 경로다. 외래키를 걸지 않는 것도 의도다 — 이 원장은 감사 기록이라 장비 원장보다 오래 살아야 하고, 장비 행이 지워져도 어느 장비였는지는 남아야 한다.';


--
-- Name: COLUMN ls_webhook_idempotency.qstn_cn; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_webhook_idempotency.qstn_cn IS '질문내용 — 위탁 시점에 외부 분석 서버로 보낸 질문 문구 전문. 결과 수신 시 재조달하지 않고 이 값을 읽어 이벤트 어노테이션의 질문 칸을 채운다(질문 목록이 전체 교체로 저장되어 재조달하면 보낸 질문과 기록된 질문이 갈리기 때문). 묘사 축 위탁 행에는 값이 없다.';


--
-- Name: ls_whk_fail_nmtm; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_whk_fail_nmtm (
    call_ip_addr character varying(45) NOT NULL,
    bgng_dt timestamp without time zone NOT NULL,
    fail_nmtm integer DEFAULT 0 NOT NULL,
    expd_dt timestamp without time zone NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_whk_fail_nmtm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_whk_fail_nmtm IS '웹훅 인증 실패 횟수 — 노드 공유 rate limit 집계(분 단위 윈도우).';


--
-- Name: COLUMN ls_whk_fail_nmtm.call_ip_addr; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_whk_fail_nmtm.call_ip_addr IS '호출 IP 주소 — 신뢰 프록시 기반으로 산출된 클라이언트 IP.';


--
-- Name: COLUMN ls_whk_fail_nmtm.bgng_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_whk_fail_nmtm.bgng_dt IS '집계 윈도우 시작일시 — 분 단위로 절삭된 버킷 키.';


--
-- Name: COLUMN ls_whk_fail_nmtm.fail_nmtm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_whk_fail_nmtm.fail_nmtm IS '해당 윈도우의 누적 인증 실패 횟수.';


--
-- Name: COLUMN ls_whk_fail_nmtm.expd_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_whk_fail_nmtm.expd_dt IS '만료일시 — 경과 시 정리(중복 실행 무해한 조건부 DELETE).';


--
-- Name: COLUMN ls_whk_fail_nmtm.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_whk_fail_nmtm.reg_dt IS '등록일시.';


--
-- Name: COLUMN ls_whk_fail_nmtm.mdfcn_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_whk_fail_nmtm.mdfcn_dt IS '수정일시.';


--
-- Name: ls_whk_sign_use; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.ls_whk_sign_use (
    sign_hash character varying(64) NOT NULL,
    whk_path_nm character varying(200) NOT NULL,
    expd_dt timestamp without time zone NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_whk_sign_use; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON TABLE klid_at.ls_whk_sign_use IS '웹훅 서명 사용 원장 — 동일 서명 재전송(replay) 차단용 1회성 소비 기록.';


--
-- Name: COLUMN ls_whk_sign_use.sign_hash; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_whk_sign_use.sign_hash IS '서명 해시 — (경로, X-Timestamp, X-Signature) SHA-256 hex.';


--
-- Name: COLUMN ls_whk_sign_use.whk_path_nm; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_whk_sign_use.whk_path_nm IS '웹훅 경로명 — 운영 추적용.';


--
-- Name: COLUMN ls_whk_sign_use.expd_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_whk_sign_use.expd_dt IS '만료일시 — timestamp 허용창을 덮는 보존기간. 경과 시 정리.';


--
-- Name: COLUMN ls_whk_sign_use.reg_dt; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON COLUMN klid_at.ls_whk_sign_use.reg_dt IS '등록일시.';


--
-- Name: qrtz_blob_triggers; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.qrtz_blob_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    blob_data bytea
);


--
-- Name: qrtz_calendars; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.qrtz_calendars (
    sched_name character varying(120) NOT NULL,
    calendar_name character varying(200) NOT NULL,
    calendar bytea NOT NULL
);


--
-- Name: qrtz_cron_triggers; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.qrtz_cron_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    cron_expression character varying(120) NOT NULL,
    time_zone_id character varying(80)
);


--
-- Name: qrtz_fired_triggers; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.qrtz_fired_triggers (
    sched_name character varying(120) NOT NULL,
    entry_id character varying(140) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    instance_name character varying(200) NOT NULL,
    fired_time bigint NOT NULL,
    sched_time bigint NOT NULL,
    priority integer NOT NULL,
    state character varying(16) NOT NULL,
    job_name character varying(200),
    job_group character varying(200),
    is_nonconcurrent boolean,
    requests_recovery boolean
);


--
-- Name: qrtz_job_details; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.qrtz_job_details (
    sched_name character varying(120) NOT NULL,
    job_name character varying(200) NOT NULL,
    job_group character varying(200) NOT NULL,
    description character varying(250),
    job_class_name character varying(250) NOT NULL,
    is_durable boolean NOT NULL,
    is_nonconcurrent boolean NOT NULL,
    is_update_data boolean NOT NULL,
    requests_recovery boolean NOT NULL,
    job_data bytea
);


--
-- Name: qrtz_locks; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.qrtz_locks (
    sched_name character varying(120) NOT NULL,
    lock_name character varying(40) NOT NULL
);


--
-- Name: qrtz_paused_trigger_grps; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.qrtz_paused_trigger_grps (
    sched_name character varying(120) NOT NULL,
    trigger_group character varying(200) NOT NULL
);


--
-- Name: qrtz_scheduler_state; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.qrtz_scheduler_state (
    sched_name character varying(120) NOT NULL,
    instance_name character varying(200) NOT NULL,
    last_checkin_time bigint NOT NULL,
    checkin_interval bigint NOT NULL
);


--
-- Name: qrtz_simple_triggers; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.qrtz_simple_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    repeat_count bigint NOT NULL,
    repeat_interval bigint NOT NULL,
    times_triggered bigint NOT NULL
);


--
-- Name: qrtz_simprop_triggers; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.qrtz_simprop_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    str_prop_1 character varying(512),
    str_prop_2 character varying(512),
    str_prop_3 character varying(512),
    int_prop_1 integer,
    int_prop_2 integer,
    long_prop_1 bigint,
    long_prop_2 bigint,
    dec_prop_1 numeric(13,4),
    dec_prop_2 numeric(13,4),
    bool_prop_1 boolean,
    bool_prop_2 boolean
);


--
-- Name: qrtz_triggers; Type: TABLE; Schema: klid_at; Owner: -
--

CREATE TABLE klid_at.qrtz_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    job_name character varying(200) NOT NULL,
    job_group character varying(200) NOT NULL,
    description character varying(250),
    next_fire_time bigint,
    prev_fire_time bigint,
    priority integer,
    trigger_state character varying(16) NOT NULL,
    trigger_type character varying(8) NOT NULL,
    start_time bigint NOT NULL,
    end_time bigint,
    calendar_name character varying(200),
    misfire_instr smallint,
    job_data bytea
);


--
-- Name: v_completed_frame; Type: VIEW; Schema: klid_at; Owner: -
--

CREATE VIEW klid_at.v_completed_frame AS
 SELECT src_sn,
    raw_sn,
    frm_no AS frame_no,
    src_file_path_nm AS original_path,
    de_idntf_src_file_path_nm AS deidentified_path,
    sht_dt AS captured_at,
    reg_dt,
    upd_dt,
    frm_expln AS description
   FROM klid_at.ls_data_src src
  WHERE ((EXISTS ( SELECT 1
           FROM klid_at.ls_raw_data_status s
          WHERE ((s.raw_data_id = src.raw_sn) AND ((s.data_stts_cd)::text = 'APPROVED'::text)))) AND (NOT ((src_file_path_nm IS NOT NULL) AND (TRIM(BOTH FROM src_file_path_nm) <> ''::text) AND (de_idntf_src_file_path_nm IS NOT NULL) AND (TRIM(BOTH FROM de_idntf_src_file_path_nm) <> ''::text) AND ((de_idntf_src_file_path_nm)::text = (src_file_path_nm)::text))) AND (COALESCE(dscd_yn, 'N'::bpchar) <> 'Y'::bpchar));


--
-- Name: VIEW v_completed_frame; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON VIEW klid_at.v_completed_frame IS '검수완료 영상의 프레임 페어(원본/비식별) — 비식별 경로 불변식 게이트(V133), 폐기 프레임 제외(V182)';


--
-- Name: v_completed_label_change; Type: VIEW; Schema: klid_at; Owner: -
--

CREATE VIEW klid_at.v_completed_label_change AS
 SELECT h.lbl_hstry_sn,
    src.raw_sn,
    h.src_sn,
    h.add_cnt,
    h.mdfcn_cnt,
    h.del_cnt,
    h.reg_id,
    h.reg_dt
   FROM (klid_at.ls_data_lbl_hstry h
     JOIN klid_at.ls_data_src src ON ((src.src_sn = h.src_sn)))
  WHERE ((((COALESCE(h.add_cnt, 0) + COALESCE(h.mdfcn_cnt, 0)) + COALESCE(h.del_cnt, 0)) > 0) AND (COALESCE(src.dscd_yn, 'N'::bpchar) <> 'Y'::bpchar) AND (EXISTS ( SELECT 1
           FROM klid_at.ls_raw_data_status s
          WHERE ((s.raw_data_id = src.raw_sn) AND ((s.data_stts_cd)::text = 'APPROVED'::text)))));


--
-- Name: VIEW v_completed_label_change; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON VIEW klid_at.v_completed_label_change IS '검수완료 영상의 라벨 저장이벤트 변경점 — 종류별 건수만 노출(V137), 변경 0건 행 제외(V139), 폐기 프레임 제외(V182)';


--
-- Name: v_completed_meta; Type: VIEW; Schema: klid_at; Owner: -
--

CREATE VIEW klid_at.v_completed_meta AS
 SELECT meta.meta_sn,
    meta.raw_sn,
    meta.meta_key,
    meta.meta_vl,
    meta.otsd_job_id AS external_job_id,
    mrev.data_meta_review_sn,
    mrev.meta_type_cd,
    mrev.src_sys_cd,
    mrev.rvw_stts_cd,
    mrev.rvw_id,
    mrev.rvw_dt AS reviewed_at
   FROM (klid_at.ls_data_meta meta
     JOIN klid_at.ls_data_meta_review mrev ON ((mrev.data_meta_sn = meta.meta_sn)))
  WHERE (((mrev.rvw_stts_cd)::text = 'APPROVED'::text) AND ((meta.meta_key)::text !~~ 'video.%'::text) AND (EXISTS ( SELECT 1
           FROM klid_at.ls_raw_data_status s
          WHERE ((s.raw_data_id = meta.raw_sn) AND ((s.data_stts_cd)::text = 'APPROVED'::text)))));


--
-- Name: v_completed_video; Type: VIEW; Schema: klid_at; Owner: -
--

CREATE VIEW klid_at.v_completed_video AS
 SELECT m.raw_sn,
    m.orgnl_raw_sn,
    m.evnt_type_cd,
    i.evnt_clsf_cd,
    i.evnt_ctgry_cd,
    m.lclgv_cd,
    i.lclgv_nm,
    (
        CASE
            WHEN ((r.src_type)::text = ANY ((ARRAY['GENERATED'::character varying, 'AUGMENTED'::character varying])::text[])) THEN 'Y'::text
            ELSE 'N'::text
        END)::character(1) AS gen_ai_yn,
    (((m.evnt_nm)::text || ' 데이터셋 구축'::text))::character varying(200) AS datst_nm,
    (((m.evnt_nm)::text || ' 데이터셋 구축'::text))::character varying(4000) AS datst_expln,
    m.vdo_len_sec,
    COALESCE(e.frme_cnt, 0) AS frme_cnt,
    m.rvw_cmpl_dt AS rvw_cmptn_dt,
    (
        CASE
            WHEN (COALESCE(e.frme_cnt, 0) > 0) THEN 'Y'::text
            ELSE 'N'::text
        END)::character(1) AS img_yn,
    (
        CASE
            WHEN (d.de_idntf_file_path_nm IS NOT NULL) THEN 'Y'::text
            ELSE 'N'::text
        END)::character(1) AS vdo_yn,
    (COALESCE(NULLIF(btrim((r.anony_incl_yn)::text), ''::text), 'Y'::text))::character(1) AS anony_incl_yn,
    (COALESCE(NULLIF(btrim((r.psdo_incl_yn)::text), ''::text), 'N'::text))::character(1) AS psdo_incl_yn,
    (COALESCE(NULLIF(btrim((r.prvc_incl_yn)::text), ''::text), 'N'::text))::character(1) AS prvc_incl_yn,
        CASE
            WHEN (r.orgnl_raw_sn IS NULL) THEN i.anony_incl_yn
            ELSE NULL::bpchar
        END AS src_anony_incl_yn,
        CASE
            WHEN (r.orgnl_raw_sn IS NULL) THEN i.psdo_incl_yn
            ELSE NULL::bpchar
        END AS src_psdo_incl_yn,
        CASE
            WHEN (r.orgnl_raw_sn IS NULL) THEN i.prvc_incl_yn
            ELSE NULL::bpchar
        END AS src_prvc_incl_yn,
    to_char(m.rvw_cmpl_dt, 'YYYY'::text) AS data_etbl_yr,
    e.data_etbl_cpct,
    (l.lbl_type)::character varying(256) AS lbl_type,
    'NIA-COCO-JSON'::character varying(256) AS lbl_fmt,
    e.output_path_nm,
    e.output_stts_cd,
    d.de_idntf_file_path_nm,
    m.raw_file_path_nm AS orgnl_vdo_path_nm,
    m.de_ident_yn AS de_idntf_yn,
    t.thmb_file_path_nm
   FROM (((((((klid_at.ls_dataset_video_meta m
     JOIN klid_at.ls_data_raw r ON ((r.raw_sn = m.raw_sn)))
     JOIN klid_at.ls_raw_data_status s ON ((s.raw_data_id = m.raw_sn)))
     LEFT JOIN LATERAL ( SELECT ex.output_path_nm,
            ex.output_stts_cd,
            ex.frme_cnt,
            ex.data_etbl_cpct
           FROM klid_at.ls_dataset_export ex
          WHERE ((ex.data_raw_sn = m.raw_sn) AND ((ex.output_stts_cd)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'PARTIAL'::character varying])::text[])))
          ORDER BY ex.output_ver_no DESC
         LIMIT 1) e ON (true))
     LEFT JOIN LATERAL ( SELECT pl.de_idntf_file_path_nm
           FROM klid_at.ls_deident_proc_log pl
          WHERE ((pl.data_raw_sn = m.raw_sn) AND ((pl.proc_stts_cd)::text = 'SUCCEEDED'::text) AND (pl.de_idntf_file_path_nm IS NOT NULL))
          ORDER BY pl.req_dt DESC, pl.proc_log_sn DESC
         LIMIT 1) d ON (true))
     LEFT JOIN LATERAL ( SELECT ig.evnt_clsf_cd,
            ig.evnt_ctgry_cd,
            ig.lclgv_nm,
            ig.anony_incl_yn,
            ig.psdo_incl_yn,
            ig.prvc_incl_yn
           FROM klid_at.ls_data_ingest ig
          WHERE (ig.raw_sn = COALESCE(r.orgnl_raw_sn, r.raw_sn))
          ORDER BY ig.rcptn_sn DESC
         LIMIT 1) i ON (true))
     LEFT JOIN LATERAL ( SELECT string_agg(DISTINCT (lb.lbl_type_cd)::text, ','::text ORDER BY (lb.lbl_type_cd)::text) AS lbl_type
           FROM (klid_at.ls_data_lbl lb
             JOIN klid_at.ls_data_src sc ON ((sc.src_sn = lb.src_sn)))
          WHERE (sc.raw_sn = m.raw_sn)) l ON (true))
     LEFT JOIN LATERAL ( SELECT fr.de_idntf_src_file_path_nm AS thmb_file_path_nm
           FROM klid_at.ls_data_src fr
          WHERE ((fr.raw_sn = m.raw_sn) AND (fr.de_idntf_src_file_path_nm IS NOT NULL) AND (btrim((fr.de_idntf_src_file_path_nm)::text) <> ''::text) AND (NOT ((fr.src_file_path_nm IS NOT NULL) AND (btrim((fr.src_file_path_nm)::text) <> ''::text) AND (btrim((fr.de_idntf_src_file_path_nm)::text) <> ''::text) AND ((fr.de_idntf_src_file_path_nm)::text = (fr.src_file_path_nm)::text))) AND (COALESCE(fr.dscd_yn, 'N'::bpchar) <> 'Y'::bpchar))
          ORDER BY fr.frm_no
         LIMIT 1) t ON (true))
  WHERE ((m.active_yn = 'Y'::bpchar) AND ((s.data_stts_cd)::text = 'APPROVED'::text));


--
-- Name: VIEW v_completed_video; Type: COMMENT; Schema: klid_at; Owner: -
--

COMMENT ON VIEW klid_at.v_completed_video IS '데이터마트 적재용 — 검수 승인(APPROVED) 영상 1건 = 1 row, 31컬럼(규격서 §5-1). 관제가 datasets·dataset_versions 를 채우고 산출 폴더·비식별 영상을 픽업하는 계약면이다. 비식별 누락 신고 구간(DE_IDNTF_YN=''F'')에도 행을 감추거나 경로를 비우지 않는다(확정 정책) — 관제가 DE_IDNTF_YN 으로 자체 판단한다. THMB_FILE_PATH_NM 은 저작도구 비식별 첫 프레임의 절대경로이며 관제 인입값 pass-through 가 아니다(V17). 선택 규칙은 FRM_NO 최소 · 폐기 아님 · 비식별 경로가 비어있지 않음 · 비식별 경로가 원본 경로와 같지 않음(V_COMPLETED_FRAME 게이트와 동치)이며, 만족하는 프레임이 없으면 NULL 이고 원본 프레임 경로로 폴백하지 않는다.';


--
-- Name: ls_acnt_user ls_acnt_user_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_acnt_user
    ADD CONSTRAINT ls_acnt_user_pkey PRIMARY KEY (user_no);


--
-- Name: ls_ai_srvr_altmnt ls_ai_srvr_altmnt_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_ai_srvr_altmnt
    ADD CONSTRAINT ls_ai_srvr_altmnt_pkey PRIMARY KEY (altmnt_sn);


--
-- Name: ls_ai_srvr ls_ai_srvr_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_ai_srvr
    ADD CONSTRAINT ls_ai_srvr_pkey PRIMARY KEY (srvr_id);


--
-- Name: ls_ai_srvr_usg ls_ai_srvr_usg_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_ai_srvr_usg
    ADD CONSTRAINT ls_ai_srvr_usg_pkey PRIMARY KEY (srvr_id, usg_type_cd);


--
-- Name: ls_auth_work_lock ls_auth_work_lock_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_auth_work_lock
    ADD CONSTRAINT ls_auth_work_lock_pkey PRIMARY KEY (work_lock_sn);


--
-- Name: ls_bat_rty_wtng ls_bat_rty_wtng_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_bat_rty_wtng
    ADD CONSTRAINT ls_bat_rty_wtng_pkey PRIMARY KEY (bat_rty_sn);


--
-- Name: ls_batch_proc_log ls_batch_proc_log_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_batch_proc_log
    ADD CONSTRAINT ls_batch_proc_log_pkey PRIMARY KEY (batch_proc_log_sn);


--
-- Name: ls_clip_schedule_que ls_clip_schedule_que_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_clip_schedule_que
    ADD CONSTRAINT ls_clip_schedule_que_pkey PRIMARY KEY (que_sn);


--
-- Name: ls_control_notify_fallback ls_control_notify_fallback_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_control_notify_fallback
    ADD CONSTRAINT ls_control_notify_fallback_pkey PRIMARY KEY (queue_sn);


--
-- Name: ls_data_aug_dscd ls_data_aug_dscd_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug_dscd
    ADD CONSTRAINT ls_data_aug_dscd_pkey PRIMARY KEY (data_aug_dscd_sn);


--
-- Name: ls_data_aug_job_file ls_data_aug_job_file_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug_job_file
    ADD CONSTRAINT ls_data_aug_job_file_pkey PRIMARY KEY (aug_job_file_sn);


--
-- Name: ls_data_aug_job ls_data_aug_job_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug_job
    ADD CONSTRAINT ls_data_aug_job_pkey PRIMARY KEY (aug_job_sn);


--
-- Name: ls_data_aug_lbl_map ls_data_aug_lbl_map_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug_lbl_map
    ADD CONSTRAINT ls_data_aug_lbl_map_pkey PRIMARY KEY (data_aug_lbl_map_sn);


--
-- Name: ls_data_aug ls_data_aug_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug
    ADD CONSTRAINT ls_data_aug_pkey PRIMARY KEY (data_aug_sn);


--
-- Name: ls_data_aug_rvw ls_data_aug_rvw_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug_rvw
    ADD CONSTRAINT ls_data_aug_rvw_pkey PRIMARY KEY (data_aug_rvw_sn);


--
-- Name: ls_data_issue ls_data_issue_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_issue
    ADD CONSTRAINT ls_data_issue_pkey PRIMARY KEY (data_issue_sn);


--
-- Name: ls_data_lbl_attr_val ls_data_lbl_attr_val_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_lbl_attr_val
    ADD CONSTRAINT ls_data_lbl_attr_val_pkey PRIMARY KEY (atrb_vl_id);


--
-- Name: ls_data_lbl_hstry ls_data_lbl_hstry_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_lbl_hstry
    ADD CONSTRAINT ls_data_lbl_hstry_pkey PRIMARY KEY (lbl_hstry_sn);


--
-- Name: ls_data_lbl ls_data_lbl_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_lbl
    ADD CONSTRAINT ls_data_lbl_pkey PRIMARY KEY (lbl_sn);


--
-- Name: ls_data_meta ls_data_meta_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_meta
    ADD CONSTRAINT ls_data_meta_pkey PRIMARY KEY (meta_sn);


--
-- Name: ls_data_meta_review ls_data_meta_review_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_meta_review
    ADD CONSTRAINT ls_data_meta_review_pkey PRIMARY KEY (data_meta_review_sn);


--
-- Name: ls_data_raw ls_data_raw_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_raw
    ADD CONSTRAINT ls_data_raw_pkey PRIMARY KEY (raw_sn);


--
-- Name: ls_data_src_hstry ls_data_src_hstry_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_src_hstry
    ADD CONSTRAINT ls_data_src_hstry_pkey PRIMARY KEY (hstry_seq);


--
-- Name: ls_data_src ls_data_src_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_src
    ADD CONSTRAINT ls_data_src_pkey PRIMARY KEY (src_sn);


--
-- Name: ls_dataset_export ls_dataset_export_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_dataset_export
    ADD CONSTRAINT ls_dataset_export_pkey PRIMARY KEY (output_sn);


--
-- Name: ls_dataset_video_meta ls_dataset_video_meta_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_dataset_video_meta
    ADD CONSTRAINT ls_dataset_video_meta_pkey PRIMARY KEY (meta_snpsht_sn);


--
-- Name: ls_deident_proc_log ls_deident_proc_log_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_deident_proc_log
    ADD CONSTRAINT ls_deident_proc_log_pkey PRIMARY KEY (proc_log_sn);


--
-- Name: ls_deident_report ls_deident_report_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_deident_report
    ADD CONSTRAINT ls_deident_report_pkey PRIMARY KEY (deident_report_sn);


--
-- Name: ls_eblc_uld_job_artcl ls_eblc_uld_job_artcl_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_eblc_uld_job_artcl
    ADD CONSTRAINT ls_eblc_uld_job_artcl_pkey PRIMARY KEY (eblc_uld_job_artcl_sn);


--
-- Name: ls_eblc_uld_job ls_eblc_uld_job_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_eblc_uld_job
    ADD CONSTRAINT ls_eblc_uld_job_pkey PRIMARY KEY (eblc_uld_job_sn);


--
-- Name: ls_evnt_anno ls_evnt_anno_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_evnt_anno
    ADD CONSTRAINT ls_evnt_anno_pkey PRIMARY KEY (evnt_anno_sn);


--
-- Name: ls_evnt_anno_review ls_evnt_anno_review_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_evnt_anno_review
    ADD CONSTRAINT ls_evnt_anno_review_pkey PRIMARY KEY (rvw_sn);


--
-- Name: ls_evnt_ctgry ls_evnt_ctgry_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_evnt_ctgry
    ADD CONSTRAINT ls_evnt_ctgry_pkey PRIMARY KEY (evnt_clsf_cd, evnt_ctgry_cd);


--
-- Name: ls_evnt_type ls_evnt_type_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_evnt_type
    ADD CONSTRAINT ls_evnt_type_pkey PRIMARY KEY (evnt_type_cd);


--
-- Name: ls_issue_comment ls_issue_comment_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_issue_comment
    ADD CONSTRAINT ls_issue_comment_pkey PRIMARY KEY (cmnt_sn);


--
-- Name: ls_label_attr ls_label_attr_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label_attr
    ADD CONSTRAINT ls_label_attr_pkey PRIMARY KEY (atrb_id);


--
-- Name: ls_label ls_label_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label
    ADD CONSTRAINT ls_label_pkey PRIMARY KEY (lbl_id);


--
-- Name: ls_label_preset_code ls_label_preset_code_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label_preset_code
    ADD CONSTRAINT ls_label_preset_code_pkey PRIMARY KEY (cd_sn);


--
-- Name: ls_label_preset ls_label_preset_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label_preset
    ADD CONSTRAINT ls_label_preset_pkey PRIMARY KEY (preset_id);


--
-- Name: ls_label_version ls_label_version_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label_version
    ADD CONSTRAINT ls_label_version_pkey PRIMARY KEY (lbl_version_sn);


--
-- Name: ls_marking ls_marking_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_marking
    ADD CONSTRAINT ls_marking_pkey PRIMARY KEY (marking_sn);


--
-- Name: ls_meta_repl_outbox ls_meta_repl_outbox_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_meta_repl_outbox
    ADD CONSTRAINT ls_meta_repl_outbox_pkey PRIMARY KEY (outbox_sn);


--
-- Name: ls_mngr_pswd ls_mngr_pswd_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_mngr_pswd
    ADD CONSTRAINT ls_mngr_pswd_pkey PRIMARY KEY (mngr_pswd_sn);


--
-- Name: ls_mon_noti_acml ls_mon_noti_acml_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_mon_noti_acml
    ADD CONSTRAINT ls_mon_noti_acml_pkey PRIMARY KEY (noti_acml_sn);


--
-- Name: ls_notice_attach ls_notice_attach_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_notice_attach
    ADD CONSTRAINT ls_notice_attach_pkey PRIMARY KEY (atch_file_sn);


--
-- Name: ls_notice ls_notice_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_notice
    ADD CONSTRAINT ls_notice_pkey PRIMARY KEY (notice_sn);


--
-- Name: ls_otsd_ctgry_mpng ls_otsd_ctgry_mpng_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_otsd_ctgry_mpng
    ADD CONSTRAINT ls_otsd_ctgry_mpng_pkey PRIMARY KEY (mpng_sn);


--
-- Name: ls_otsd_datst_trnsf_hstry ls_otsd_datst_trnsf_hstry_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_otsd_datst_trnsf_hstry
    ADD CONSTRAINT ls_otsd_datst_trnsf_hstry_pkey PRIMARY KEY (trnsf_sn);


--
-- Name: ls_output_ver_snpsh ls_output_ver_snpsh_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_output_ver_snpsh
    ADD CONSTRAINT ls_output_ver_snpsh_pkey PRIMARY KEY (output_ver_snpsh_sn);


--
-- Name: ls_portal_tus_uld ls_portal_tus_uld_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_tus_uld
    ADD CONSTRAINT ls_portal_tus_uld_pkey PRIMARY KEY (uld_id);


--
-- Name: ls_portal_uld_frme ls_portal_uld_frme_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_uld_frme
    ADD CONSTRAINT ls_portal_uld_frme_pkey PRIMARY KEY (uld_frme_sn);


--
-- Name: ls_portal_uld_lbl ls_portal_uld_lbl_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_uld_lbl
    ADD CONSTRAINT ls_portal_uld_lbl_pkey PRIMARY KEY (uld_lbl_sn);


--
-- Name: ls_portal_uld ls_portal_uld_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_uld
    ADD CONSTRAINT ls_portal_uld_pkey PRIMARY KEY (uld_sn);


--
-- Name: ls_portal_user_evnt_anno ls_portal_user_evnt_anno_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_user_evnt_anno
    ADD CONSTRAINT ls_portal_user_evnt_anno_pkey PRIMARY KEY (user_evnt_anno_sn);


--
-- Name: ls_portal_user_label ls_portal_user_label_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_user_label
    ADD CONSTRAINT ls_portal_user_label_pkey PRIMARY KEY (user_lbl_sn);


--
-- Name: ls_portal_user_meta ls_portal_user_meta_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_user_meta
    ADD CONSTRAINT ls_portal_user_meta_pkey PRIMARY KEY (user_meta_sn);


--
-- Name: ls_raw_data_status ls_raw_data_status_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_raw_data_status
    ADD CONSTRAINT ls_raw_data_status_pkey PRIMARY KEY (raw_data_id);


--
-- Name: ls_system_config ls_system_config_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_system_config
    ADD CONSTRAINT ls_system_config_pkey PRIMARY KEY (stng_key);


--
-- Name: ls_task_altmnt ls_task_altmnt_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_task_altmnt
    ADD CONSTRAINT ls_task_altmnt_pkey PRIMARY KEY (assignment_id);


--
-- Name: ls_task_evnt_log ls_task_evnt_log_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_task_evnt_log
    ADD CONSTRAINT ls_task_evnt_log_pkey PRIMARY KEY (evnt_id);


--
-- Name: ls_tus_upload ls_tus_upload_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_tus_upload
    ADD CONSTRAINT ls_tus_upload_pkey PRIMARY KEY (uld_id);


--
-- Name: ls_user_role ls_user_role_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_user_role
    ADD CONSTRAINT ls_user_role_pkey PRIMARY KEY (user_no);


--
-- Name: ls_vrfc_evnt_qstn ls_vrfc_evnt_qstn_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_vrfc_evnt_qstn
    ADD CONSTRAINT ls_vrfc_evnt_qstn_pkey PRIMARY KEY (vrfc_evnt_qstn_sn);


--
-- Name: ls_vrfc_evnt_type ls_vrfc_evnt_type_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_vrfc_evnt_type
    ADD CONSTRAINT ls_vrfc_evnt_type_pkey PRIMARY KEY (vrfc_evnt_type_cd);


--
-- Name: ls_webhook_idempotency ls_webhook_idempotency_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_webhook_idempotency
    ADD CONSTRAINT ls_webhook_idempotency_pkey PRIMARY KEY (idmp_key);


--
-- Name: ls_authrt_grant_atmpt pk_ls_authrt_grant_atmpt; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_authrt_grant_atmpt
    ADD CONSTRAINT pk_ls_authrt_grant_atmpt PRIMARY KEY (atmpt_se_cd, atmpt_idntfr, bgng_dt);


--
-- Name: ls_data_ingest pk_ls_data_ingest; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_ingest
    ADD CONSTRAINT pk_ls_data_ingest PRIMARY KEY (rcptn_sn);


--
-- Name: ls_whk_fail_nmtm pk_ls_whk_fail_nmtm; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_whk_fail_nmtm
    ADD CONSTRAINT pk_ls_whk_fail_nmtm PRIMARY KEY (call_ip_addr, bgng_dt);


--
-- Name: ls_whk_sign_use pk_ls_whk_sign_use; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_whk_sign_use
    ADD CONSTRAINT pk_ls_whk_sign_use PRIMARY KEY (sign_hash);


--
-- Name: qrtz_blob_triggers qrtz_blob_triggers_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_blob_triggers
    ADD CONSTRAINT qrtz_blob_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_calendars qrtz_calendars_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_calendars
    ADD CONSTRAINT qrtz_calendars_pkey PRIMARY KEY (sched_name, calendar_name);


--
-- Name: qrtz_cron_triggers qrtz_cron_triggers_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_cron_triggers
    ADD CONSTRAINT qrtz_cron_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_fired_triggers qrtz_fired_triggers_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_fired_triggers
    ADD CONSTRAINT qrtz_fired_triggers_pkey PRIMARY KEY (sched_name, entry_id);


--
-- Name: qrtz_job_details qrtz_job_details_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_job_details
    ADD CONSTRAINT qrtz_job_details_pkey PRIMARY KEY (sched_name, job_name, job_group);


--
-- Name: qrtz_locks qrtz_locks_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_locks
    ADD CONSTRAINT qrtz_locks_pkey PRIMARY KEY (sched_name, lock_name);


--
-- Name: qrtz_paused_trigger_grps qrtz_paused_trigger_grps_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_paused_trigger_grps
    ADD CONSTRAINT qrtz_paused_trigger_grps_pkey PRIMARY KEY (sched_name, trigger_group);


--
-- Name: qrtz_scheduler_state qrtz_scheduler_state_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_scheduler_state
    ADD CONSTRAINT qrtz_scheduler_state_pkey PRIMARY KEY (sched_name, instance_name);


--
-- Name: qrtz_simple_triggers qrtz_simple_triggers_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_simple_triggers
    ADD CONSTRAINT qrtz_simple_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_simprop_triggers qrtz_simprop_triggers_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_simprop_triggers
    ADD CONSTRAINT qrtz_simprop_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_triggers qrtz_triggers_pkey; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_triggers
    ADD CONSTRAINT qrtz_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: ls_data_aug uk_aug_external_job_id; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug
    ADD CONSTRAINT uk_aug_external_job_id UNIQUE (otsd_job_id);


--
-- Name: ls_data_aug uk_aug_idempotency_key; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug
    ADD CONSTRAINT uk_aug_idempotency_key UNIQUE (idmp_key);


--
-- Name: ls_bat_rty_wtng uk_lbrw_raw_sn; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_bat_rty_wtng
    ADD CONSTRAINT uk_lbrw_raw_sn UNIQUE (raw_sn);


--
-- Name: ls_control_notify_fallback uk_lcnf_idempotency; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_control_notify_fallback
    ADD CONSTRAINT uk_lcnf_idempotency UNIQUE (idmp_key);


--
-- Name: ls_data_aug_job uk_ldaj_idmp_key; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug_job
    ADD CONSTRAINT uk_ldaj_idmp_key UNIQUE (idmp_key);


--
-- Name: ls_data_aug_job_file uk_ldajf_job_file_seq; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug_job_file
    ADD CONSTRAINT uk_ldajf_job_file_seq UNIQUE (aug_job_sn, file_seq);


--
-- Name: ls_ai_srvr_altmnt uk_ls_ai_srvr_altmnt_raw_sn; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_ai_srvr_altmnt
    ADD CONSTRAINT uk_ls_ai_srvr_altmnt_raw_sn UNIQUE (raw_sn);


--
-- Name: ls_auth_work_lock uk_ls_auth_work_lock_id; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_auth_work_lock
    ADD CONSTRAINT uk_ls_auth_work_lock_id UNIQUE (lck_id);


--
-- Name: ls_data_ingest uk_ls_data_ingest_clip; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_ingest
    ADD CONSTRAINT uk_ls_data_ingest_clip UNIQUE (vms_clip_id);


--
-- Name: ls_data_lbl_attr_val uk_ls_data_lbl_attr_val; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_lbl_attr_val
    ADD CONSTRAINT uk_ls_data_lbl_attr_val UNIQUE (lbl_sn, atrb_id);


--
-- Name: ls_data_meta uk_ls_data_meta_raw_key; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_meta
    ADD CONSTRAINT uk_ls_data_meta_raw_key UNIQUE (raw_sn, meta_key);


--
-- Name: ls_data_raw uk_ls_data_raw_vms_clip; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_raw
    ADD CONSTRAINT uk_ls_data_raw_vms_clip UNIQUE (vms_clip_id);


--
-- Name: ls_data_src uk_ls_data_src_raw_frame; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_src
    ADD CONSTRAINT uk_ls_data_src_raw_frame UNIQUE (raw_sn, frm_no);


--
-- Name: ls_dataset_export uk_ls_dataset_export; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_dataset_export
    ADD CONSTRAINT uk_ls_dataset_export UNIQUE (data_raw_sn, output_ver_no);


--
-- Name: ls_dataset_video_meta uk_ls_dataset_video_meta; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_dataset_video_meta
    ADD CONSTRAINT uk_ls_dataset_video_meta UNIQUE (raw_sn, snpsht_hash);


--
-- Name: ls_evnt_anno uk_ls_evnt_anno_raw; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_evnt_anno
    ADD CONSTRAINT uk_ls_evnt_anno_raw UNIQUE (raw_sn);


--
-- Name: ls_label_attr uk_ls_label_attr_name; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label_attr
    ADD CONSTRAINT uk_ls_label_attr_name UNIQUE (lbl_id, atrb_nm);


--
-- Name: ls_label_preset_code uk_ls_label_preset_code; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label_preset_code
    ADD CONSTRAINT uk_ls_label_preset_code UNIQUE (preset_id, lbl_cd);


--
-- Name: ls_label_preset uk_ls_label_preset_evnt; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label_preset
    ADD CONSTRAINT uk_ls_label_preset_evnt UNIQUE (evnt_type_cd);


--
-- Name: ls_label_version uk_ls_label_version_src_hash; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label_version
    ADD CONSTRAINT uk_ls_label_version_src_hash UNIQUE (data_src_sn, version_hash);


--
-- Name: ls_output_ver_snpsh uk_ls_output_ver_snpsh; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_output_ver_snpsh
    ADD CONSTRAINT uk_ls_output_ver_snpsh UNIQUE (data_raw_sn, data_src_sn, output_ver_no);


--
-- Name: ls_portal_uld_frme uk_ls_portal_uld_frme; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_uld_frme
    ADD CONSTRAINT uk_ls_portal_uld_frme UNIQUE (uld_sn, frme_no);


--
-- Name: ls_task_altmnt uk_ls_task_altmnt; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_task_altmnt
    ADD CONSTRAINT uk_ls_task_altmnt UNIQUE (raw_data_id, user_no, task_type_cd);


--
-- Name: ls_data_meta uk_meta_external_job_id; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_meta
    ADD CONSTRAINT uk_meta_external_job_id UNIQUE (otsd_job_id);


--
-- Name: ls_data_meta uk_meta_idempotency_key; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_meta
    ADD CONSTRAINT uk_meta_idempotency_key UNIQUE (idmp_key);


--
-- Name: ls_data_meta_review uq_ls_data_meta_review_meta_type; Type: CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_meta_review
    ADD CONSTRAINT uq_ls_data_meta_review_meta_type UNIQUE (data_meta_sn, meta_type_cd);


--
-- Name: idx_lbrw_status; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_lbrw_status ON klid_at.ls_bat_rty_wtng USING btree (stts_cd, rty_prnmnt_dt);


--
-- Name: idx_lcnf_status; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_lcnf_status ON klid_at.ls_control_notify_fallback USING btree (stts_cd, next_rtry_dt);


--
-- Name: idx_ldaj_aug_seq; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ldaj_aug_seq ON klid_at.ls_data_aug_job USING btree (data_aug_sn, job_seq);


--
-- Name: idx_ldaj_otsd_job_id; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ldaj_otsd_job_id ON klid_at.ls_data_aug_job USING btree (otsd_job_id);


--
-- Name: idx_ldajf_src_sn; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ldajf_src_sn ON klid_at.ls_data_aug_job_file USING btree (src_sn);


--
-- Name: idx_ldlh_src; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ldlh_src ON klid_at.ls_data_lbl_hstry USING btree (src_sn, reg_dt DESC);


--
-- Name: idx_ldr_orgnl; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ldr_orgnl ON klid_at.ls_data_raw USING btree (orgnl_raw_sn);


--
-- Name: idx_ldr_portal_user_reg; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ldr_portal_user_reg ON klid_at.ls_data_raw USING btree (portal_user_no, reg_dt) WHERE (portal_user_no IS NOT NULL);


--
-- Name: idx_leuj_stts; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_leuj_stts ON klid_at.ls_eblc_uld_job USING btree (job_stts_cd);


--
-- Name: idx_leuja_job; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_leuja_job ON klid_at.ls_eblc_uld_job_artcl USING btree (eblc_uld_job_sn);


--
-- Name: idx_leuja_stts; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_leuja_stts ON klid_at.ls_eblc_uld_job_artcl USING btree (artcl_stts_cd, eblc_uld_job_artcl_sn);


--
-- Name: idx_lm_raw; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_lm_raw ON klid_at.ls_marking USING btree (raw_sn);


--
-- Name: idx_lmna_stts_mdfcn; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_lmna_stts_mdfcn ON klid_at.ls_mon_noti_acml USING btree (stts_cd, mdfcn_dt);


--
-- Name: idx_lmna_stts_reg; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_lmna_stts_reg ON klid_at.ls_mon_noti_acml USING btree (stts_cd, reg_dt);


--
-- Name: idx_lnt_pub; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_lnt_pub ON klid_at.ls_notice USING btree (pblcn_stts_cd, upend_fix_yn, reg_dt DESC);


--
-- Name: idx_lnta_notice; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_lnta_notice ON klid_at.ls_notice_attach USING btree (notice_sn);


--
-- Name: idx_lptu_expry; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_lptu_expry ON klid_at.ls_portal_tus_uld USING btree (expry_dt) WHERE ((stts_cd)::text = 'IN_PROGRESS'::text);


--
-- Name: idx_lptu_user_stts; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_lptu_user_stts ON klid_at.ls_portal_tus_uld USING btree (portal_user_no) WHERE ((stts_cd)::text = 'IN_PROGRESS'::text);


--
-- Name: idx_lpul_src; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_lpul_src ON klid_at.ls_portal_user_label USING btree (portal_user_no, src_data_src_sn);


--
-- Name: idx_lpul_user; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_lpul_user ON klid_at.ls_portal_user_label USING btree (portal_user_no, src_raw_sn);


--
-- Name: idx_lpum_user; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_lpum_user ON klid_at.ls_portal_user_meta USING btree (portal_user_no, src_raw_sn);


--
-- Name: idx_ls_auth_work_lock_stts; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_auth_work_lock_stts ON klid_at.ls_auth_work_lock USING btree (lck_stts_cd, expry_dt);


--
-- Name: idx_ls_auth_work_lock_target; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_auth_work_lock_target ON klid_at.ls_auth_work_lock USING btree (lck_target_cd, data_raw_sn, data_src_sn);


--
-- Name: idx_ls_authrt_grant_atmpt_expd; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_authrt_grant_atmpt_expd ON klid_at.ls_authrt_grant_atmpt USING btree (expd_dt);


--
-- Name: idx_ls_batch_proc_log_job; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_batch_proc_log_job ON klid_at.ls_batch_proc_log USING btree (job_id);


--
-- Name: idx_ls_batch_proc_log_raw; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_batch_proc_log_raw ON klid_at.ls_batch_proc_log USING btree (data_raw_sn);


--
-- Name: idx_ls_batch_proc_log_src; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_batch_proc_log_src ON klid_at.ls_batch_proc_log USING btree (data_src_sn);


--
-- Name: idx_ls_batch_proc_log_step_stts; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_batch_proc_log_step_stts ON klid_at.ls_batch_proc_log USING btree (proc_step_cd, proc_stts_cd);


--
-- Name: idx_ls_data_aug_lbl_map_aug; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_aug_lbl_map_aug ON klid_at.ls_data_aug_lbl_map USING btree (data_aug_sn);


--
-- Name: idx_ls_data_aug_lbl_map_lbl; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_aug_lbl_map_lbl ON klid_at.ls_data_aug_lbl_map USING btree (data_lbl_sn);


--
-- Name: idx_ls_data_aug_lbl_map_orgnl; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_aug_lbl_map_orgnl ON klid_at.ls_data_aug_lbl_map USING btree (orgnl_data_lbl_sn);


--
-- Name: idx_ls_data_aug_rvw_aug; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_aug_rvw_aug ON klid_at.ls_data_aug_rvw USING btree (data_aug_sn);


--
-- Name: idx_ls_data_aug_rvw_stts; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_aug_rvw_stts ON klid_at.ls_data_aug_rvw USING btree (rvw_stts_cd);


--
-- Name: idx_ls_data_aug_rvw_target; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_aug_rvw_target ON klid_at.ls_data_aug_rvw USING btree (data_raw_sn, data_src_sn);


--
-- Name: idx_ls_data_aug_src; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_aug_src ON klid_at.ls_data_aug USING btree (src_sn, aug_type_cd);


--
-- Name: idx_ls_data_aug_src_regdt; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_aug_src_regdt ON klid_at.ls_data_aug USING btree (src_sn, reg_dt);


--
-- Name: idx_ls_data_aug_stts; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_aug_stts ON klid_at.ls_data_aug USING btree (aug_proc_stts_cd, reg_dt);


--
-- Name: idx_ls_data_lbl_attr_val_lbl; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_lbl_attr_val_lbl ON klid_at.ls_data_lbl_attr_val USING btree (lbl_sn);


--
-- Name: idx_ls_data_lbl_label_id; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_lbl_label_id ON klid_at.ls_data_lbl USING btree (lbl_id);


--
-- Name: idx_ls_data_meta_review_meta; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_meta_review_meta ON klid_at.ls_data_meta_review USING btree (data_meta_sn);


--
-- Name: idx_ls_data_meta_review_stts; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_meta_review_stts ON klid_at.ls_data_meta_review USING btree (meta_type_cd, rvw_stts_cd);


--
-- Name: idx_ls_data_meta_review_target; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_data_meta_review_target ON klid_at.ls_data_meta_review USING btree (data_raw_sn, data_src_sn);


--
-- Name: idx_ls_dataset_video_meta_raw_active; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_dataset_video_meta_raw_active ON klid_at.ls_dataset_video_meta USING btree (raw_sn, active_yn);


--
-- Name: idx_ls_dataset_video_meta_rvw_cmpl; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_dataset_video_meta_rvw_cmpl ON klid_at.ls_dataset_video_meta USING btree (rvw_cmpl_dt);


--
-- Name: idx_ls_deident_proc_log_raw; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_deident_proc_log_raw ON klid_at.ls_deident_proc_log USING btree (data_raw_sn);


--
-- Name: idx_ls_deident_proc_log_raw_log; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_deident_proc_log_raw_log ON klid_at.ls_deident_proc_log USING btree (data_raw_sn, proc_log_sn);


--
-- Name: idx_ls_deident_proc_log_stts; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_deident_proc_log_stts ON klid_at.ls_deident_proc_log USING btree (proc_stts_cd);


--
-- Name: idx_ls_deident_report_raw; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_deident_report_raw ON klid_at.ls_deident_report USING btree (data_raw_sn);


--
-- Name: idx_ls_deident_report_report; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_deident_report_report ON klid_at.ls_deident_report USING btree (report_stts_cd, dclr_dt);


--
-- Name: idx_ls_evnt_anno_review_anno; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_evnt_anno_review_anno ON klid_at.ls_evnt_anno_review USING btree (evnt_anno_sn);


--
-- Name: idx_ls_evnt_anno_review_stts; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_evnt_anno_review_stts ON klid_at.ls_evnt_anno_review USING btree (meta_type_cd, rvw_stts_cd);


--
-- Name: idx_ls_label_attr; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_label_attr ON klid_at.ls_label_attr USING btree (lbl_id, use_yn, sort_seq);


--
-- Name: idx_ls_label_preset_code_label; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_label_preset_code_label ON klid_at.ls_label_preset_code USING btree (lbl_id);


--
-- Name: idx_ls_label_preset_code_preset; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_label_preset_code_preset ON klid_at.ls_label_preset_code USING btree (preset_id);


--
-- Name: idx_ls_label_use; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_label_use ON klid_at.ls_label USING btree (use_yn, sort_seq);


--
-- Name: idx_ls_label_version_target; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_label_version_target ON klid_at.ls_label_version USING btree (data_raw_sn, data_src_sn, actvtn_yn);


--
-- Name: idx_ls_meta_repl_outbox_status; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_meta_repl_outbox_status ON klid_at.ls_meta_repl_outbox USING btree (stts_cd, reg_dt);


--
-- Name: idx_ls_output_ver_snpsh_lbl_ver; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_output_ver_snpsh_lbl_ver ON klid_at.ls_output_ver_snpsh USING btree (lbl_ver_sn);


--
-- Name: idx_ls_portal_uld_lbl_frme; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_portal_uld_lbl_frme ON klid_at.ls_portal_uld_lbl USING btree (uld_frme_sn, portal_user_no);


--
-- Name: idx_ls_portal_uld_lbl_user_uld; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_portal_uld_lbl_user_uld ON klid_at.ls_portal_uld_lbl USING btree (portal_user_no, uld_sn);


--
-- Name: idx_ls_portal_uld_stts_mdfcn; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_portal_uld_stts_mdfcn ON klid_at.ls_portal_uld USING btree (uld_stts_cd, mdfcn_dt);


--
-- Name: idx_ls_portal_uld_user_reg; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_portal_uld_user_reg ON klid_at.ls_portal_uld USING btree (portal_user_no, reg_dt);


--
-- Name: idx_ls_webhook_idempotency_ch_state; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_webhook_idempotency_ch_state ON klid_at.ls_webhook_idempotency USING btree (chnl_cd, stts_cd);


--
-- Name: idx_ls_webhook_idempotency_ext_job; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_webhook_idempotency_ext_job ON klid_at.ls_webhook_idempotency USING btree (otsd_job_id);


--
-- Name: idx_ls_webhook_idempotency_srvr_state; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_webhook_idempotency_srvr_state ON klid_at.ls_webhook_idempotency USING btree (srvr_id, stts_cd);


--
-- Name: idx_ls_whk_fail_nmtm_expd; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_whk_fail_nmtm_expd ON klid_at.ls_whk_fail_nmtm USING btree (expd_dt);


--
-- Name: idx_ls_whk_sign_use_expd; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ls_whk_sign_use_expd ON klid_at.ls_whk_sign_use USING btree (expd_dt);


--
-- Name: idx_ltu_expires; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ltu_expires ON klid_at.ls_tus_upload USING btree (stts_cd, expry_dt);


--
-- Name: idx_ltu_user_status; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX idx_ltu_user_status ON klid_at.ls_tus_upload USING btree (user_no, stts_cd);


--
-- Name: ix_ldpl_poll_stts; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ldpl_poll_stts ON klid_at.ls_deident_proc_log USING btree (poll_stts_cd);


--
-- Name: ix_ls_ai_srvr_altmnt_srvr_id; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_ai_srvr_altmnt_srvr_id ON klid_at.ls_ai_srvr_altmnt USING btree (srvr_id);


--
-- Name: ix_ls_clip_schedule_que_raw; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_clip_schedule_que_raw ON klid_at.ls_clip_schedule_que USING btree (raw_sn);


--
-- Name: ix_ls_clip_schedule_que_status; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_clip_schedule_que_status ON klid_at.ls_clip_schedule_que USING btree (stts_cd, job_type_cd);


--
-- Name: ix_ls_data_aug_dscd_file_rty; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_aug_dscd_file_rty ON klid_at.ls_data_aug_dscd USING btree (del_dt) WHERE ((del_dt IS NOT NULL) AND (file_del_dt IS NULL) AND (file_del_fail_dt IS NULL));


--
-- Name: ix_ls_data_aug_dscd_lookup; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_aug_dscd_lookup ON klid_at.ls_data_aug_dscd USING btree (data_aug_sn, data_aug_dscd_sn DESC);


--
-- Name: ix_ls_data_aug_dscd_new_raw; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_aug_dscd_new_raw ON klid_at.ls_data_aug_dscd USING btree (new_raw_sn);


--
-- Name: ix_ls_data_aug_dscd_sweep; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_aug_dscd_sweep ON klid_at.ls_data_aug_dscd USING btree (dscd_dt) WHERE ((rstr_dt IS NULL) AND (del_dt IS NULL));


--
-- Name: ix_ls_data_aug_new_raw_sn; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_aug_new_raw_sn ON klid_at.ls_data_aug USING btree (new_raw_sn) WHERE (new_raw_sn IS NOT NULL);


--
-- Name: ix_ls_data_ingest_poll; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_ingest_poll ON klid_at.ls_data_ingest USING btree (rcptn_dt, rcptn_sn) INCLUDE (nxtm_rtry_dt) WHERE ((prcs_stts_cd)::text = 'PENDING'::text);


--
-- Name: ix_ls_data_ingest_raw_sn; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_ingest_raw_sn ON klid_at.ls_data_ingest USING btree (raw_sn, rcptn_sn DESC);


--
-- Name: ix_ls_data_issue_reporter; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_issue_reporter ON klid_at.ls_data_issue USING btree (reported_user_no);


--
-- Name: ix_ls_data_issue_src; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_issue_src ON klid_at.ls_data_issue USING btree (src_sn) WHERE (src_sn IS NOT NULL);


--
-- Name: ix_ls_data_issue_up; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_issue_up ON klid_at.ls_data_issue USING btree (up_data_issue_sn);


--
-- Name: ix_ls_data_issue_video; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_issue_video ON klid_at.ls_data_issue USING btree (data_raw_sn);


--
-- Name: ix_ls_data_lbl_lbl_src_cd; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_lbl_lbl_src_cd ON klid_at.ls_data_lbl USING btree (lbl_src_cd);


--
-- Name: ix_ls_data_lbl_src; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_lbl_src ON klid_at.ls_data_lbl USING btree (src_sn);


--
-- Name: ix_ls_data_lbl_trck_id; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_lbl_trck_id ON klid_at.ls_data_lbl USING btree (trck_id);


--
-- Name: ix_ls_data_meta_raw; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_meta_raw ON klid_at.ls_data_meta USING btree (raw_sn);


--
-- Name: ix_ls_data_raw_cctv; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_raw_cctv ON klid_at.ls_data_raw USING btree (vms_cctv_id);


--
-- Name: ix_ls_data_raw_stts; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_raw_stts ON klid_at.ls_data_raw USING btree (data_stts_cd);


--
-- Name: ix_ls_data_src_hstry_src; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_src_hstry_src ON klid_at.ls_data_src_hstry USING btree (src_sn);


--
-- Name: ix_ls_data_src_raw; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_data_src_raw ON klid_at.ls_data_src USING btree (raw_sn);


--
-- Name: ix_ls_issue_comment_author; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_issue_comment_author ON klid_at.ls_issue_comment USING btree (author_no);


--
-- Name: ix_ls_issue_comment_issue; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_issue_comment_issue ON klid_at.ls_issue_comment USING btree (data_issue_sn);


--
-- Name: ix_ls_otsd_datst_trnsf_hstry_fldr; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_otsd_datst_trnsf_hstry_fldr ON klid_at.ls_otsd_datst_trnsf_hstry USING btree (orgnl_fldr_nm, otsd_datst_id);


--
-- Name: ix_ls_otsd_datst_trnsf_hstry_raw; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_otsd_datst_trnsf_hstry_raw ON klid_at.ls_otsd_datst_trnsf_hstry USING btree (raw_sn);


--
-- Name: ix_ls_raw_data_status_stts_upd; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_raw_data_status_stts_upd ON klid_at.ls_raw_data_status USING btree (data_stts_cd, upd_dt DESC);


--
-- Name: ix_ls_task_altmnt_raw; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_task_altmnt_raw ON klid_at.ls_task_altmnt USING btree (raw_data_id);


--
-- Name: ix_ls_task_altmnt_user; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_task_altmnt_user ON klid_at.ls_task_altmnt USING btree (user_no, task_type_cd);


--
-- Name: ix_ls_task_evnt_log_actor; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_task_evnt_log_actor ON klid_at.ls_task_evnt_log USING btree (actor_user_no);


--
-- Name: ix_ls_task_evnt_log_raw; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE INDEX ix_ls_task_evnt_log_raw ON klid_at.ls_task_evnt_log USING btree (raw_data_id, ocrn_dt);


--
-- Name: uk_leuja_job_mark; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_leuja_job_mark ON klid_at.ls_eblc_uld_job_artcl USING btree (eblc_uld_job_sn, mark_file_path_nm);


--
-- Name: uk_lmna_raw_pending; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_lmna_raw_pending ON klid_at.ls_mon_noti_acml USING btree (raw_sn) WHERE ((stts_cd)::text = 'PENDING'::text);


--
-- Name: uk_lpuea_raw; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_lpuea_raw ON klid_at.ls_portal_user_evnt_anno USING btree (portal_user_no, src_raw_sn);


--
-- Name: uk_lpum_key; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_lpum_key ON klid_at.ls_portal_user_meta USING btree (portal_user_no, src_raw_sn, src_data_src_sn, meta_key) WHERE (src_data_src_sn IS NOT NULL);


--
-- Name: uk_lpum_key_video; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_lpum_key_video ON klid_at.ls_portal_user_meta USING btree (portal_user_no, src_raw_sn, meta_key) WHERE (src_data_src_sn IS NULL);


--
-- Name: uk_ls_acnt_user_user_id; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_ls_acnt_user_user_id ON klid_at.ls_acnt_user USING btree (user_id) WHERE (user_id IS NOT NULL);


--
-- Name: uk_ls_data_aug_dscd_actvtn; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_ls_data_aug_dscd_actvtn ON klid_at.ls_data_aug_dscd USING btree (data_aug_sn) WHERE ((rstr_dt IS NULL) AND (del_dt IS NULL));


--
-- Name: uk_ls_data_aug_resl; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_ls_data_aug_resl ON klid_at.ls_data_aug USING btree (src_sn, aug_type_cd) WHERE ((aug_type_cd)::text ~~ 'RESL\_%'::text);


--
-- Name: uk_ls_dataset_video_meta_raw_active; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_ls_dataset_video_meta_raw_active ON klid_at.ls_dataset_video_meta USING btree (raw_sn) WHERE (active_yn = 'Y'::bpchar);


--
-- Name: uk_ls_deident_proc_log_ext_job; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_ls_deident_proc_log_ext_job ON klid_at.ls_deident_proc_log USING btree (otsd_job_id);


--
-- Name: uk_ls_label_dtct_type; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_ls_label_dtct_type ON klid_at.ls_label USING btree (dtct_type_cd) WHERE ((use_yn = 'Y'::bpchar) AND (dtct_type_cd IS NOT NULL));


--
-- Name: uk_ls_label_nm_ci; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_ls_label_nm_ci ON klid_at.ls_label USING btree (lower(TRIM(BOTH FROM lbl_nm))) WHERE (use_yn = 'Y'::bpchar);


--
-- Name: uk_ls_label_preset_code_lblid; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_ls_label_preset_code_lblid ON klid_at.ls_label_preset_code USING btree (preset_id, lbl_id) WHERE (lbl_id IS NOT NULL);


--
-- Name: uk_ls_marking_raw_actvtn; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_ls_marking_raw_actvtn ON klid_at.ls_marking USING btree (raw_sn) WHERE ((stts_cd)::text = ANY ((ARRAY['PENDING'::character varying, 'VLM_REQUESTED'::character varying])::text[]));


--
-- Name: uk_ls_otsd_ctgry_mpng; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_ls_otsd_ctgry_mpng ON klid_at.ls_otsd_ctgry_mpng USING btree (mpng_knd_cd, otsd_ctgry_cd);


--
-- Name: uk_ls_vrfc_evnt_qstn_type_sort; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX uk_ls_vrfc_evnt_qstn_type_sort ON klid_at.ls_vrfc_evnt_qstn USING btree (vrfc_evnt_type_cd, sort_seq);


--
-- Name: ux_ls_auth_work_lock_raw_active; Type: INDEX; Schema: klid_at; Owner: -
--

CREATE UNIQUE INDEX ux_ls_auth_work_lock_raw_active ON klid_at.ls_auth_work_lock USING btree (data_raw_sn) WHERE (((lck_target_cd)::text = 'RAW'::text) AND ((lck_stts_cd)::text = 'LOCKED'::text));


--
-- Name: ls_data_aug_job fk_ldaj_data_aug; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug_job
    ADD CONSTRAINT fk_ldaj_data_aug FOREIGN KEY (data_aug_sn) REFERENCES klid_at.ls_data_aug(data_aug_sn) ON DELETE CASCADE;


--
-- Name: ls_data_aug_job_file fk_ldajf_aug_job; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug_job_file
    ADD CONSTRAINT fk_ldajf_aug_job FOREIGN KEY (aug_job_sn) REFERENCES klid_at.ls_data_aug_job(aug_job_sn) ON DELETE CASCADE;


--
-- Name: ls_eblc_uld_job_artcl fk_leuja_job; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_eblc_uld_job_artcl
    ADD CONSTRAINT fk_leuja_job FOREIGN KEY (eblc_uld_job_sn) REFERENCES klid_at.ls_eblc_uld_job(eblc_uld_job_sn) ON DELETE CASCADE;


--
-- Name: ls_eblc_uld_job_artcl fk_leuja_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_eblc_uld_job_artcl
    ADD CONSTRAINT fk_leuja_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE SET NULL;


--
-- Name: ls_notice_attach fk_lnta_notice; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_notice_attach
    ADD CONSTRAINT fk_lnta_notice FOREIGN KEY (notice_sn) REFERENCES klid_at.ls_notice(notice_sn) ON DELETE CASCADE;


--
-- Name: ls_ai_srvr_usg fk_ls_ai_srvr_usg_srvr; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_ai_srvr_usg
    ADD CONSTRAINT fk_ls_ai_srvr_usg_srvr FOREIGN KEY (srvr_id) REFERENCES klid_at.ls_ai_srvr(srvr_id) ON DELETE CASCADE;


--
-- Name: ls_auth_work_lock fk_ls_auth_work_lock_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_auth_work_lock
    ADD CONSTRAINT fk_ls_auth_work_lock_raw FOREIGN KEY (data_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_bat_rty_wtng fk_ls_bat_rty_wtng_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_bat_rty_wtng
    ADD CONSTRAINT fk_ls_bat_rty_wtng_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_batch_proc_log fk_ls_batch_proc_log_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_batch_proc_log
    ADD CONSTRAINT fk_ls_batch_proc_log_raw FOREIGN KEY (data_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_clip_schedule_que fk_ls_clip_schedule_que_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_clip_schedule_que
    ADD CONSTRAINT fk_ls_clip_schedule_que_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_control_notify_fallback fk_ls_control_notify_fallback_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_control_notify_fallback
    ADD CONSTRAINT fk_ls_control_notify_fallback_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_data_aug_rvw fk_ls_data_aug_rvw_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_aug_rvw
    ADD CONSTRAINT fk_ls_data_aug_rvw_raw FOREIGN KEY (data_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_data_issue fk_ls_data_issue_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_issue
    ADD CONSTRAINT fk_ls_data_issue_raw FOREIGN KEY (data_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_data_lbl_attr_val fk_ls_data_lbl_attr_attr; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_lbl_attr_val
    ADD CONSTRAINT fk_ls_data_lbl_attr_attr FOREIGN KEY (atrb_id) REFERENCES klid_at.ls_label_attr(atrb_id);


--
-- Name: ls_data_lbl_attr_val fk_ls_data_lbl_attr_lbl; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_lbl_attr_val
    ADD CONSTRAINT fk_ls_data_lbl_attr_lbl FOREIGN KEY (lbl_sn) REFERENCES klid_at.ls_data_lbl(lbl_sn);


--
-- Name: ls_data_lbl fk_ls_data_lbl_label; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_lbl
    ADD CONSTRAINT fk_ls_data_lbl_label FOREIGN KEY (lbl_id) REFERENCES klid_at.ls_label(lbl_id);


--
-- Name: ls_data_meta fk_ls_data_meta_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_meta
    ADD CONSTRAINT fk_ls_data_meta_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_data_meta_review fk_ls_data_meta_review_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_meta_review
    ADD CONSTRAINT fk_ls_data_meta_review_raw FOREIGN KEY (data_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_data_src fk_ls_data_src_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_data_src
    ADD CONSTRAINT fk_ls_data_src_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_dataset_export fk_ls_dataset_export_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_dataset_export
    ADD CONSTRAINT fk_ls_dataset_export_raw FOREIGN KEY (data_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_dataset_video_meta fk_ls_dataset_video_meta_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_dataset_video_meta
    ADD CONSTRAINT fk_ls_dataset_video_meta_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_deident_proc_log fk_ls_deident_proc_log_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_deident_proc_log
    ADD CONSTRAINT fk_ls_deident_proc_log_raw FOREIGN KEY (data_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_deident_report fk_ls_deident_report_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_deident_report
    ADD CONSTRAINT fk_ls_deident_report_raw FOREIGN KEY (data_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_evnt_anno fk_ls_evnt_anno_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_evnt_anno
    ADD CONSTRAINT fk_ls_evnt_anno_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_evnt_anno_review fk_ls_evnt_anno_review_anno; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_evnt_anno_review
    ADD CONSTRAINT fk_ls_evnt_anno_review_anno FOREIGN KEY (evnt_anno_sn) REFERENCES klid_at.ls_evnt_anno(evnt_anno_sn);


--
-- Name: ls_issue_comment fk_ls_issue_comment_issue; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_issue_comment
    ADD CONSTRAINT fk_ls_issue_comment_issue FOREIGN KEY (data_issue_sn) REFERENCES klid_at.ls_data_issue(data_issue_sn) ON DELETE RESTRICT;


--
-- Name: ls_label_attr fk_ls_label_attr_label; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label_attr
    ADD CONSTRAINT fk_ls_label_attr_label FOREIGN KEY (lbl_id) REFERENCES klid_at.ls_label(lbl_id);


--
-- Name: ls_label_preset_code fk_ls_label_preset_code_label; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label_preset_code
    ADD CONSTRAINT fk_ls_label_preset_code_label FOREIGN KEY (lbl_id) REFERENCES klid_at.ls_label(lbl_id);


--
-- Name: ls_label_preset_code fk_ls_label_preset_code_preset; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label_preset_code
    ADD CONSTRAINT fk_ls_label_preset_code_preset FOREIGN KEY (preset_id) REFERENCES klid_at.ls_label_preset(preset_id) ON DELETE CASCADE;


--
-- Name: ls_label_version fk_ls_label_version_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_label_version
    ADD CONSTRAINT fk_ls_label_version_raw FOREIGN KEY (data_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_marking fk_ls_marking_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_marking
    ADD CONSTRAINT fk_ls_marking_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_meta_repl_outbox fk_ls_meta_repl_outbox_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_meta_repl_outbox
    ADD CONSTRAINT fk_ls_meta_repl_outbox_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_mon_noti_acml fk_ls_mon_noti_acml_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_mon_noti_acml
    ADD CONSTRAINT fk_ls_mon_noti_acml_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_otsd_ctgry_mpng fk_ls_otsd_ctgry_mpng_evnt_type; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_otsd_ctgry_mpng
    ADD CONSTRAINT fk_ls_otsd_ctgry_mpng_evnt_type FOREIGN KEY (evnt_type_cd) REFERENCES klid_at.ls_evnt_type(evnt_type_cd) ON DELETE RESTRICT;


--
-- Name: ls_otsd_ctgry_mpng fk_ls_otsd_ctgry_mpng_lbl; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_otsd_ctgry_mpng
    ADD CONSTRAINT fk_ls_otsd_ctgry_mpng_lbl FOREIGN KEY (lbl_id) REFERENCES klid_at.ls_label(lbl_id) ON DELETE RESTRICT;


--
-- Name: ls_otsd_datst_trnsf_hstry fk_ls_otsd_datst_trnsf_hstry_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_otsd_datst_trnsf_hstry
    ADD CONSTRAINT fk_ls_otsd_datst_trnsf_hstry_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE SET NULL;


--
-- Name: ls_output_ver_snpsh fk_ls_output_ver_snpsh_lbl_ver; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_output_ver_snpsh
    ADD CONSTRAINT fk_ls_output_ver_snpsh_lbl_ver FOREIGN KEY (lbl_ver_sn) REFERENCES klid_at.ls_label_version(lbl_version_sn) ON DELETE CASCADE;


--
-- Name: ls_output_ver_snpsh fk_ls_output_ver_snpsh_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_output_ver_snpsh
    ADD CONSTRAINT fk_ls_output_ver_snpsh_raw FOREIGN KEY (data_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_portal_uld_frme fk_ls_portal_uld_frme_uld; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_uld_frme
    ADD CONSTRAINT fk_ls_portal_uld_frme_uld FOREIGN KEY (uld_sn) REFERENCES klid_at.ls_portal_uld(uld_sn) ON DELETE CASCADE;


--
-- Name: ls_portal_uld_lbl fk_ls_portal_uld_lbl_frme; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_uld_lbl
    ADD CONSTRAINT fk_ls_portal_uld_lbl_frme FOREIGN KEY (uld_frme_sn) REFERENCES klid_at.ls_portal_uld_frme(uld_frme_sn) ON DELETE CASCADE;


--
-- Name: ls_portal_uld_lbl fk_ls_portal_uld_lbl_uld; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_uld_lbl
    ADD CONSTRAINT fk_ls_portal_uld_lbl_uld FOREIGN KEY (uld_sn) REFERENCES klid_at.ls_portal_uld(uld_sn) ON DELETE CASCADE;


--
-- Name: ls_portal_user_evnt_anno fk_ls_portal_user_evnt_anno_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_user_evnt_anno
    ADD CONSTRAINT fk_ls_portal_user_evnt_anno_raw FOREIGN KEY (src_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_portal_user_label fk_ls_portal_user_label_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_user_label
    ADD CONSTRAINT fk_ls_portal_user_label_raw FOREIGN KEY (src_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_portal_user_meta fk_ls_portal_user_meta_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_portal_user_meta
    ADD CONSTRAINT fk_ls_portal_user_meta_raw FOREIGN KEY (src_raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_raw_data_status fk_ls_raw_data_status_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_raw_data_status
    ADD CONSTRAINT fk_ls_raw_data_status_raw FOREIGN KEY (raw_data_id) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_task_altmnt fk_ls_task_altmnt_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_task_altmnt
    ADD CONSTRAINT fk_ls_task_altmnt_raw FOREIGN KEY (raw_data_id) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_task_evnt_log fk_ls_task_evnt_log_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_task_evnt_log
    ADD CONSTRAINT fk_ls_task_evnt_log_raw FOREIGN KEY (raw_data_id) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_tus_upload fk_ls_tus_upload_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_tus_upload
    ADD CONSTRAINT fk_ls_tus_upload_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE SET NULL;


--
-- Name: ls_vrfc_evnt_qstn fk_ls_vrfc_evnt_qstn_type; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_vrfc_evnt_qstn
    ADD CONSTRAINT fk_ls_vrfc_evnt_qstn_type FOREIGN KEY (vrfc_evnt_type_cd) REFERENCES klid_at.ls_vrfc_evnt_type(vrfc_evnt_type_cd) ON DELETE RESTRICT;


--
-- Name: ls_webhook_idempotency fk_ls_webhook_idempotency_raw; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.ls_webhook_idempotency
    ADD CONSTRAINT fk_ls_webhook_idempotency_raw FOREIGN KEY (raw_sn) REFERENCES klid_at.ls_data_raw(raw_sn) ON DELETE SET NULL;


--
-- Name: qrtz_blob_triggers fk_qrtz_blob_triggers; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_blob_triggers
    ADD CONSTRAINT fk_qrtz_blob_triggers FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES klid_at.qrtz_triggers(sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_cron_triggers fk_qrtz_cron_triggers; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_cron_triggers
    ADD CONSTRAINT fk_qrtz_cron_triggers FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES klid_at.qrtz_triggers(sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_simple_triggers fk_qrtz_simple_triggers; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_simple_triggers
    ADD CONSTRAINT fk_qrtz_simple_triggers FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES klid_at.qrtz_triggers(sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_simprop_triggers fk_qrtz_simprop_triggers; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_simprop_triggers
    ADD CONSTRAINT fk_qrtz_simprop_triggers FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES klid_at.qrtz_triggers(sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_triggers fk_qrtz_triggers_job_details; Type: FK CONSTRAINT; Schema: klid_at; Owner: -
--

ALTER TABLE ONLY klid_at.qrtz_triggers
    ADD CONSTRAINT fk_qrtz_triggers_job_details FOREIGN KEY (sched_name, job_name, job_group) REFERENCES klid_at.qrtz_job_details(sched_name, job_name, job_group);


--
-- PostgreSQL database dump complete
--

\unrestrict shNvhYfPJZSvv7G3dq1sTTp65l0cW4ZgevLaGCssUXmO9GpHtIiwFeVlPFsClFp

