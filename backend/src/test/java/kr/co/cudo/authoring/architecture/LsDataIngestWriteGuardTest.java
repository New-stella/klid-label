package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.video.repository.InternalUploadIngestWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인입 테이블({@code LS_DATA_INGEST}) 쓰기 통로 가드 — 프로덕션 소스 스캔 (DEV_FIX F6).
 *
 * <p>행을 만드는 주체는 원칙적으로 <b>관제</b>다. 저작도구는 읽고 자기 운영 컬럼의 상태만 바꾼다.
 * 유일한 예외가 내부 REVIEWER 업로드(우리가 정당한 origin 인 흐름)이고, 그 통로는
 * {@code InternalUploadIngestWriter} <b>하나</b>여야 한다. 통로가 늘면 그 자체가 관제 소유 29컬럼의
 * 일반 쓰기 경로가 된다(CWE-915).
 *
 * <p><b>왜 여기(architecture 패키지)인가</b> — 프로덕션 소스 트리를 스캔하는 구조 가드는 엔티티
 * 단위 테스트가 아니라 아키텍처 가드다({@code MngAcctWriteGuardTest} 관례). 엔티티 자체의 계약
 * (setter·INSERT 팩토리 부재)은 {@code LsDataIngestTest} 가 계속 담당한다.
 *
 * <p><b>주석 스트리핑 필수</b> — 정규식을 원문에 그대로 걸면 Javadoc 이 테이블명을 언급하기만 해도
 * 위반으로 오탐된다({@code InternalUploadIngestWriter} 클래스 주석이 이미 이 문구를 갖고 있다).
 * {@code MngAcctWriteGuardTest} 와 동일하게 블록·라인 주석을 제거한 뒤 매칭한다.
 */
class LsDataIngestWriteGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    /** 블록 주석(/* ... *\/) 제거용. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** 라인 주석(// ...) 제거용. */
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    /**
     * 인입 테이블 <b>참조 표기</b> — 스키마 한정과 인용 식별자를 함께 덮는다.
     *
     * <h3>★스키마 한정({@code klid_at.})을 빠뜨리면 가드가 통째로 무력화된다 (DEV_FIX 2차 — 되돌리지 말 것)</h3>
     * <p>구 패턴은 {@code UPDATE\s+LS_DATA_INGEST} 였다. 그래서 아래 한 문을 writer 에 얹는 것만으로
     * <b>7개 가드가 전부 통과</b>했다(적대검증 mutation 실증 — BUILD SUCCESSFUL):
     * <pre>
     *   UPDATE klid_at.LS_DATA_INGEST SET CCTV_NM = ?, RAW_FILE_PATH_NM = ? WHERE RCPTN_SN = ?
     * </pre>
     * 스키마 한정만 뺀 같은 문은 3건 FAILED 였다 — 즉 접두사 하나가 미탐/탐지를 갈랐다.
     *
     * <p><b>가상의 우회가 아니다</b> — 이 저장소의 마이그레이션 {@code V162}·{@code V165}·{@code V97}
     * 이 이미 {@code klid_at.} 표기를 쓴다. 게다가 그 mutation 이 SET 하던 {@code RAW_FILE_PATH_NM} 은
     * 호출 측 신뢰 경계 판정({@code InternalUploadPathResolver#isUploadAreaPath})이 <b>읽는 바로 그
     * 컬럼</b>이라, 판별자를 스스로 오염시키는 경로가 열린다(CWE-915).
     *
     * <p>인용 식별자({@code "LS_DATA_INGEST"})도 선택적 따옴표로 함께 집는다 — PostgreSQL 은 인용하면
     * 대문자 식별자를 그대로 쓰므로 표기가 갈릴 수 있고, 이 writer 자신이
     * {@code GENERATED_KEY_COLUMNS = {"rcptn_sn"}} 으로 폴딩 규칙을 이미 의식하고 있다.
     *
     * <p>끝에 <b>식별자 경계</b>({@code (?![A-Za-z0-9_$"])})를 둔다 — 없으면
     * {@code LS_DATA_INGEST_ARCHIVE} 같은 <b>다른 테이블</b>까지 인입 통로로 계수해 개수 단언
     * ({@link #ALLOWED_UPDATE_STATEMENTS})이 통째로 흔들린다(구 패턴의 잠재 위양성).
     */
    private static final String INGEST_TABLE_REF =
            "(?:\"?[A-Za-z_][A-Za-z0-9_$]*\"?\\s*\\.\\s*)?\"?LS_DATA_INGEST\"?(?![A-Za-z0-9_$\"])";

    private static final Pattern INSERT_LS_DATA_INGEST =
            Pattern.compile("INSERT\\s+INTO\\s+" + INGEST_TABLE_REF, Pattern.CASE_INSENSITIVE);

    private static final Pattern UPDATE_LS_DATA_INGEST =
            Pattern.compile("UPDATE\\s+" + INGEST_TABLE_REF, Pattern.CASE_INSENSITIVE);

    /** 인입 행 INSERT 를 보유해도 되는 <b>유일한</b> 파일. */
    private static final String ALLOWED_WRITER = "InternalUploadIngestWriter.java";

    /**
     * 인입 행 UPDATE 를 보유해도 되는 파일과 <b>그 파일이 보유해도 되는 UPDATE 문 개수</b>.
     *
     * <ul>
     *   <li>{@code LsDataIngestRepository} — 저작도구 <b>운영 컬럼</b>만 바꾸는 조건부 UPDATE 7건
     *       (클레임 · 미도착 복귀 · 좀비 회수 · 재큐 단건 · 재큐 일괄 · 도착 통지 · 취소 종결).
     *       관제 수신 29컬럼은 건드리지 않는다.</li>
     *   <li>{@code InternalUploadIngestWriter} — <b>2건</b>: ①내부 업로드 행 <b>되살리기</b>(DEV_FIX M1)
     *       ②완료 시점 측정 기술메타 <b>back-fill</b>(Phase 2). 관제 수신 컬럼을 갱신하는 통로는 이 둘
     *       뿐이며, 각각의 술어({@code FAILED}/{@code PENDING} + {@code RAW_SN IS NULL})와 호출 측 경로
     *       판정("우리가 만든 행인가")으로 관제 행에 닿지 못하게 막는다.</li>
     * </ul>
     *
     * <p>⚠ <b>이 숫자만 올려 통과시키지 말 것.</b> 개수는 "통로가 늘었다"는 신호일 뿐이고, 각 문이
     * <b>무엇을 어떤 술어로</b> 갱신하는지는 {@link #controlOwnedColumnsAreWrittenOnlyByReviveAndBackfill}
     * 이 문 단위로 따로 고정한다.
     *
     * <p><b>왜 파일이 아니라 문(statement) 단위인가 (DEV_FIX 2차 [C])</b> — 구 가드는 "UPDATE 를
     * 보유한 <b>파일명</b>"만 대조했다. 그래서 <b>이미 allowlist 된 파일 안에</b> 무제한 술어
     * ({@code WHERE RCPTN_SN = :rcptnSn} 뿐)로 관제 소유 컬럼을 갱신하는 문을 추가하면 <b>그대로
     * 통과</b>했다(적대검증 mutation 실증 — BUILD SUCCESSFUL). 새 통로를 만들 가장 자연스러운 자리가
     * 바로 그 파일이므로, 통로 <b>수</b>와 각 문의 <b>술어·대상 컬럼</b>까지 고정한다.
     */
    private static final Map<String, Integer> ALLOWED_UPDATE_STATEMENTS =
            Map.of("LsDataIngestRepository.java", 7, ALLOWED_WRITER, 2);

    /**
     * 관제 소유(수신) 컬럼 중 <b>우리 통로가 실제로 쓰는 것</b> — 이 컬럼을 {@code SET} 하는 문은
     * 되살리기 <b>하나</b>뿐이어야 한다.
     *
     * <p>{@code VMS_CLIP_ID} 는 되살리기 SET 에도 없다(UK 이자 조회 키). 목록에 포함해 두면 그것을
     * 갱신하는 문이 새로 생길 때도 잡힌다.
     *
     * <p>★ <b>writer 가 쓰는 컬럼을 추가할 때는 이 목록도 같은 커밋에서 갱신한다</b> — 빠뜨리면
     * 그 컬럼만 가드 밖이 되어, 무제한 술어로 그 컬럼을 갱신하는 새 통로가 생겨도
     * {@link #controlOwnedColumnsAreWrittenOnlyByReviveAndBackfill} 이 잡지 못한다(CWE-915).
     * {@code VRFC_EVNT_TYPE_CD}(V176 — 검증이벤트유형, 외부 VLM verify 의 {@code event_type})가
     * 그 이유로 여기 있다.
     *
     * <p>★ {@code OG_CD}(기관코드)는 V185 에서 제거됐다가 <b>V16 에서 복원</b>됐다(2026-08-24 관제
     * 재확인 "실보유"). {@code LCLGV_CD}·{@code LCLGV_NM} 과 서로 다른 값이다. {@code THMB_FILE_PATH_NM}
     * (썸네일파일경로명)도 V16 신설분이다. <b>지금 writer 는 두 컬럼을 쓰지 않지만 목록에는 넣는다</b> —
     * 이 목록의 존재 이유가 "쓰기 시작하면 잡는" 것이라, 쓰기 시작한 뒤에 넣기로 미루면 그 통로가
     * 열리는 바로 그 커밋에서 가드가 침묵한다(위 CWE-915 경고와 같은 취지). 관제가 채우는 값이므로
     * 우리 쓰기 경로가 건드리면 안 되는 축이라는 점도 이미 확정돼 있다.
     */
    private static final List<String> CONTROL_OWNED_COLUMNS = List.of(
            "VMS_CLIP_ID", "VMS_CCTV_ID", "VDO_FILE_NM", "RAW_FILE_PATH_NM", "SRC_TYPE", "SHT_DT",
            "FILE_FMT", "VDO_CDC", "FILE_SZ", "LCLGV_NM", "VDO_LEN_SEC", "FPS", "FRME_CNT", "ASPRT_RT",
            "WDTH", "VRTC", "RESL", "BIT", "PXL", "WGS84_LAT", "WGS84_LOT", "CCTV_NM",
            "CCTV_HGT", "MAIN_SURV_PAN_ANG", "EVNT_ID", "EVNT_NM", "MNTR_CN", "LCLGV_CD",
            "VRFC_EVNT_TYPE_CD", "OG_CD", "THMB_FILE_PATH_NM");

    /** 되살리기·back-fill UPDATE 를 호출해도 되는 <b>유일한</b> 파일 — Java 측 신뢰 경계 판정이 여기 있다. */
    private static final String ALLOWED_REVIVE_CALLER = "TusUploadService.java";

    private static final Pattern REVIVE_CALL = Pattern.compile("\\.reviveForUpload\\s*\\(");
    private static final Pattern BACKFILL_CALL = Pattern.compile("\\.backfillMeasuredMeta\\s*\\(");

    /**
     * back-fill 이 채우면 <b>안 되는</b> 컬럼 (R3) — 표기 규약이 없어 무엇을 넣든 지어낸 값이 된다.
     *
     * <p>아래 {@link #BACKFILL_ALLOWED_COLUMNS} allowlist 가 이들을 이미 배제하지만 <b>지우지
     * 않는다</b> — 요구 R3 를 이름으로 드러내는 문서적 가치가 있고 이중으로 걸어도 비용이 없다.
     *
     * <p>{@code VRFC_EVNT_TYPE_CD}(V176)도 여기 있다 — back-fill 은 <b>측정 기술메타 전용</b>이고
     * 검증이벤트유형은 ffprobe 로 측정할 수 있는 값이 아니다. SET 절에 들어가면 사람이 고른 값을
     * 측정 경로가 덮는 통로가 된다.
     */
    private static final List<String> BACKFILL_FORBIDDEN_COLUMNS =
            List.of("PXL", "BIT", "VRFC_EVNT_TYPE_CD");

    /**
     * back-fill 이 SET 해도 되는 <b>전부</b> — 측정으로 채우는 기술메타 8컬럼 (R2).
     *
     * <p><b>denylist 가 아니라 allowlist 인 이유</b> — 금지 목록(PXL·BIT)만 두면 <b>목록에 없는</b>
     * 관제 소유 컬럼({@code MNTR_CN}·{@code CCTV_NM}·심지어 신뢰 판별자 {@code RAW_FILE_PATH_NM})을
     * SET 절에 얹어도 통과한다(CWE-915). 특히 {@code RAW_FILE_PATH_NM} 은 호출 측 신뢰 경계 판정
     * ({@code TusUploadService#backfillIngestMeta} → {@code isUploadAreaPath})이 <b>읽는 바로 그
     * 컬럼</b>이라, back-fill 이 그것을 쓸 수 있으면 판별자를 스스로 오염시키는 구조가 열린다.
     */
    private static final List<String> BACKFILL_ALLOWED_COLUMNS = List.of(
            "VDO_LEN_SEC", "FPS", "VDO_CDC", "WDTH", "VRTC", "RESL", "FRME_CNT", "ASPRT_RT");

    /**
     * SET 절의 개별 대입 — {@code COLUMN = <RHS 의 첫 인자까지>}.
     *
     * <h3>★ 대문자 폴딩({@code toUpperCase})은 <b>반드시 유지</b>해야 한다 (DEV_FIX — 되돌리지 말 것)</h3>
     * <p>이 패턴은 {@link UpdateStatement#setAssignments()} 에서 <b>대문자로 접은 SET 절</b>에
     * 적용된다. 폴딩을 벗기면 {@code SET cctv_nm = ?} · {@code SET Cctv_Nm = ?} 같은 소문자·혼합
     * 표기가 <b>통째로 미탐</b>되어, 이미 allowlist 된 파일 안에 관제 소유 컬럼을 소문자로 얹는 것만으로
     * 모든 가드를 통과한다(적대검증 mutation 실증). 소문자는 억지 가정이 아니다 — PostgreSQL 은 무인용
     * 식별자를 소문자로 폴딩하고, 이 흐름의 writer 자신이
     * {@code InternalUploadIngestWriter.GENERATED_KEY_COLUMNS = {"rcptn_sn"}} 으로 소문자 물리명을
     * 인정하고 있다. 인용 식별자({@code "CCTV_NM"})도 선택적 따옴표로 함께 집는다.
     *
     * <h3>왜 대입 대상과 값을 <b>나누는가</b> (실제 이유)</h3>
     * <p>RHS 를 검사하기 위해서다 — {@code COALESCE(?, COL)} 처럼 <b>인자가 기존 값을 덮는</b> 형태를
     * 잡으려면 값 표현식을 손에 쥐어야 한다({@link #controlOwnedColumnsAreWrittenOnlyByReviveAndBackfill}
     * 단언 ③). 구 주석이 적은 "값 표현식 안의 동명 참조를 대입으로 오인하지 않게"는 <b>사실이 아니었다</b> —
     * 값 안의 동명 참조 뒤에는 {@code =} 가 아니라 {@code ,}/{@code )} 가 오므로 구 구현(대문자 폴딩 후
     * {@code \bCOLUMN\b\s*=} 매칭)도 애초에 오탐하지 않았다.
     *
     * <p>RHS 는 <b>첫 쉼표까지</b>만 끊는다 — {@code COALESCE(NULLIF(BTRIM(FPS), ''), ?)} 는
     * {@code COALESCE(NULLIF(BTRIM(FPS)} 가 되어 <b>선두가 COALESCE 인가</b>와 <b>첫 인자가 무엇인가</b>를
     * 둘 다 판정할 수 있다. 대입 대상 식별자만 {@code =} 앞에 오므로({@code COALESCE} 내부에는 {@code =}
     * 가 없다) 이 패턴은 SET 절의 <b>모든</b> 대입을 빠짐없이 집는다.
     */
    private static final Pattern SET_ASSIGNMENT =
            Pattern.compile("\\b\"?([A-Z][A-Z0-9_]*)\"?\\s*=\\s*([^,]+)");

    /** 중첩 함수 호출의 선두({@code COALESCE(} · {@code NULLIF(} · {@code BTRIM(})—첫 인자 추출용. */
    private static final Pattern LEADING_FUNCTION_CALL = Pattern.compile("^[A-Z][A-Z0-9_]*\\(");

    @Test
    @DisplayName("LS_DATA_INGEST_INSERT_통로는_InternalUploadIngestWriter_하나뿐이다")
    void ingestInsertHasSingleWriter() {
        // given — 프로덕션 main 소스 전체(주석 제거)
        List<JavaSource> sources = loadMainSources();

        // when — INSERT INTO LS_DATA_INGEST 를 <코드로> 보유한 파일
        List<String> insertSites = sources.stream()
                .filter(s -> INSERT_LS_DATA_INGEST.matcher(s.content()).find())
                .map(JavaSource::path)
                .sorted()
                .toList();

        // then — 통로는 하나이고 그 하나는 내부 업로드 writer 다
        assertThat(insertSites)
                .as("인입 INSERT 통로가 늘면 관제 소유 29컬럼의 일반 쓰기 경로가 된다. 발견: %s", insertSites)
                .hasSize(1);
        assertThat(Paths.get(insertSites.get(0)).getFileName().toString())
                .isEqualTo(ALLOWED_WRITER);
    }

    @Test
    @DisplayName("LS_DATA_INGEST_UPDATE_통로는_파일도_문_개수도_고정이다")
    void ingestUpdateHasBoundedWriters() {
        // 되살리기(M1)가 관제 수신 컬럼을 갱신하는 통로를 열었으므로, 그 통로가 <더 늘지 않는지>도
        // 함께 고정한다. 늘면 관제 소유 값의 일반 쓰기 경로가 된다(CWE-915).
        Map<String, Integer> perFile = new TreeMap<>();
        for (UpdateStatement stmt : loadUpdateStatements()) {
            perFile.merge(stmt.fileName(), 1, Integer::sum);
        }

        assertThat(perFile.keySet())
                .as("인입 UPDATE 를 보유한 파일. 발견: %s", perFile)
                .containsExactlyInAnyOrderElementsOf(ALLOWED_UPDATE_STATEMENTS.keySet());
        assertThat(perFile)
                .as("★파일 단위가 아니라 <문 개수>를 고정한다 — allowlist 된 파일 안에 새 통로를"
                        + " 추가하는 것이 가장 자연스러운 우회다. 발견: %s", perFile)
                .containsExactlyInAnyOrderEntriesOf(ALLOWED_UPDATE_STATEMENTS);
    }

    @Test
    @DisplayName("모든_LS_DATA_INGEST_UPDATE는_처리상태_술어를_가진다 — 무제한_술어_금지")
    void everyIngestUpdateIsStateGuarded() {
        // 상태 술어가 없는 UPDATE 는 <어떤 상태의 행이든> 갱신한다 — 처리 중인 행을 뺏고, 종결된
        // 행을 되살리며, 관제가 방금 넣은 행도 덮는다(CWE-362). 조건부 UPDATE 가 이 테이블의
        // 유일한 원자성 근거이므로 술어 존재 자체를 구조로 고정한다.
        for (UpdateStatement stmt : loadUpdateStatements()) {
            assertThat(stmt.hasWhere())
                    .as("%s: WHERE 없는 전체 갱신은 금지다%n%s", stmt.fileName(), stmt.sql())
                    .isTrue();
            assertThat(stmt.wherePart())
                    .as("%s: 상태 술어(PRCS_STTS_CD) 없는 UPDATE 는 처리 중·종결 행을 구분하지"
                            + " 못한다%n%s", stmt.fileName(), stmt.sql())
                    .containsIgnoringCase("PRCS_STTS_CD");
        }
    }

    /**
     * 관제 소유 컬럼 SET 통로 <b>2건</b>을 각각 술어로 식별해 개별 검증한다 (Phase 2 확장 — 구 이름
     * {@code controlOwnedColumnsAreWrittenOnlyByRevive}).
     *
     * <p><b>왜 "아무거나 2건"으로 세면 안 되는가</b> — 개수만 맞추면 back-fill 자리에 무제한 술어의
     * 새 통로가 들어와도 통과한다(이 파일이 이미 겪은 mutation 우회의 재발). 각 문을 술어
     * ({@code FAILED} vs {@code PENDING})로 <b>지목</b>해 그 문의 SET 절·WHERE 절을 따로 단언한다.
     */
    @Test
    @DisplayName("관제_수신컬럼_SET_통로는_되살리기와_backfill_둘뿐이고_각_문의_술어와_SET절이_고정이다")
    void controlOwnedColumnsAreWrittenOnlyByReviveAndBackfill() {
        List<UpdateStatement> controlWriters = loadUpdateStatements().stream()
                .filter(LsDataIngestWriteGuardTest::setsControlOwnedColumn)
                .toList();

        // 통로는 <되살리기 + back-fill> 2건뿐이다. 리포지토리에 관제 컬럼 SET 문을 추가하면 여기서 죽는다.
        assertThat(controlWriters)
                .as("관제 소유 컬럼을 갱신하는 UPDATE 문. 발견: %s",
                        controlWriters.stream().map(UpdateStatement::fileName).toList())
                .hasSize(2);
        assertThat(controlWriters).allSatisfy(stmt ->
                assertThat(stmt.fileName())
                        .as("관제 컬럼 SET 은 내부 업로드 writer 밖으로 나갈 수 없다")
                        .isEqualTo(ALLOWED_WRITER));

        // --- ① 되살리기 — 종결된 행만(FAILED) + 한 번도 적재된 적 없는 행만(RAW_SN IS NULL)
        UpdateStatement revive = statementGuardedBy(controlWriters, "FAILED");
        assertThat(revive.normalizedWhere())
                .as("적재된 영상의 인입 근거를 다른 업로드가 덮으면 역추적이 끊긴다")
                .containsIgnoringCase("RAW_SN IS NULL");

        // --- ② back-fill — 미처리 행만(PENDING) + 적재 전(RAW_SN IS NULL)
        UpdateStatement backfill = statementGuardedBy(controlWriters, "PENDING");
        assertThat(backfill.normalizedWhere())
                .as("이미 적재된 행의 인입 근거를 사후 변조하지 않는다")
                .containsIgnoringCase("RAW_SN IS NULL");

        // --- ③ (R3) back-fill 은 PXL·BIT 를 채우지 않는다 — 표기 규약이 없어 지어낸 값이 된다.
        for (String forbidden : BACKFILL_FORBIDDEN_COLUMNS) {
            assertThat(setsColumn(backfill, forbidden))
                    .as("back-fill SET 절에 %s 가 있으면 안 된다(R3)%n%s", forbidden, backfill.sql())
                    .isFalse();
        }

        // --- ④ (R4) back-fill SET 의 <모든> 컬럼이 COALESCE 로 감싸여 있고, 그 <첫 인자>가
        //   컬럼 자신이다. 선두 검사만으로는 부족하다 — `COALESCE(?, COL)` 은 여전히 COALESCE 로
        //   시작하지만 <인자가 기존 값을 덮어> R4 를 정확히 반대로 뒤집는다.
        Map<String, String> assignments = backfill.setAssignments();
        assertThat(assignments)
                .as("back-fill 이 채우는 컬럼이 사라졌다%n%s", backfill.sql())
                .isNotEmpty();
        assertThat(assignments).allSatisfy((column, rhs) -> {
            assertThat(rhs)
                    .as("%s 가 COALESCE 로 감싸이지 않으면 사용자 입력값을 덮어쓴다(R4)%n%s",
                            column, backfill.sql())
                    .startsWithIgnoringCase("COALESCE(");
            assertThat(firstCoalesceArgument(rhs))
                    .as("COALESCE(?, %1$s) 는 인자가 기존 값을 <덮는다> — R4 의 정반대다."
                            + " 컬럼 자신(%1$s)이 첫 인자여야 한다%n%2$s", column, backfill.sql())
                    .isEqualTo(column);
        });

        // --- ⑤ (R2/R3) SET 대상은 allowlist 8컬럼이 <전부>다.
        //   denylist(PXL·BIT)만으로는 목록에 없는 관제 컬럼(MNTR_CN·RAW_FILE_PATH_NM 등)이 새로
        //   얹혀도 통과한다. allowlist 는 R3 를 자동으로 포함하며, 위 ③ 은 문서적 이중 방어로 남긴다.
        assertThat(assignments.keySet())
                .as("back-fill 이 SET 하는 컬럼은 측정 기술메타 8종뿐이어야 한다%n%s", backfill.sql())
                .containsExactlyInAnyOrderElementsOf(BACKFILL_ALLOWED_COLUMNS);
    }

    /**
     * {@code COALESCE(...} RHS 의 <b>첫 인자</b>를 뽑는다 — 중첩 함수 호출은 벗겨낸다.
     *
     * <p>RHS 는 이미 첫 쉼표까지 잘려 있으므로({@link #SET_ASSIGNMENT}) 선두의 함수 호출
     * ({@code COALESCE(} · {@code NULLIF(} · {@code BTRIM(})을 반복해서 벗기고, 그렇게 <b>연</b>
     * 괄호 수만큼만 뒤에서 닫는 괄호를 걷어내면 첫 인자만 남는다.
     * <ul>
     *   <li>{@code COALESCE(VDO_LEN_SEC} → {@code VDO_LEN_SEC} (정상)</li>
     *   <li>{@code COALESCE(NULLIF(BTRIM(FPS)} → {@code FPS} (정상 — <b>중첩 형태를 깨뜨리지 않는다</b>.
     *       {@code BTRIM} 의 닫는 괄호가 첫 쉼표보다 앞에 있어 잘린 조각에 남는다)</li>
     *   <li>{@code COALESCE(?} → {@code ?} (R4 반전 — 여기서 죽는다)</li>
     *   <li>{@code COALESCE(NULLIF(?} → {@code ?} (중첩으로 위장해도 죽는다)</li>
     * </ul>
     * 공백은 먼저 제거해 {@code COALESCE( FPS} 같은 정렬 차이에 흔들리지 않게 한다.
     */
    private static String firstCoalesceArgument(String rhs) {
        String remaining = rhs.replaceAll("\\s+", "");
        int opened = 0;
        for (Matcher call = LEADING_FUNCTION_CALL.matcher(remaining); call.lookingAt();
             call = LEADING_FUNCTION_CALL.matcher(remaining)) {
            remaining = remaining.substring(call.end());
            opened++;
        }
        while (opened > 0 && remaining.endsWith(")")) {
            remaining = remaining.substring(0, remaining.length() - 1);
            opened--;
        }
        return remaining;
    }

    @Test
    @DisplayName("되살리기_호출부는_TusUploadService_하나뿐이다 — 신뢰경계_판정이_거기에만_있다")
    void reviveHasSingleCaller() {
        // SQL 술어(FAILED + RAW_SN IS NULL)는 <관제가 넣은 실패 행에도 맞는다>. 우리 행과 관제 행을
        // 구분하는 것은 오직 Java 측 경로 판정(InternalUploadPathResolver#isUploadAreaPath)이고
        // 그것은 TusUploadService 안에 있다. 호출부가 늘면 그 판정 없이 관제 행을 되살리는 통로가
        // 생긴다(CWE-915 — 신뢰 경계를 넘는 쓰기).
        assertThat(callersOf(REVIVE_CALL))
                .as("되살리기 호출부. 발견: %s", callersOf(REVIVE_CALL))
                .containsExactly(ALLOWED_REVIVE_CALLER);
    }

    @Test
    @DisplayName("backfill_호출부도_TusUploadService_하나뿐이다 — 관제행_보호_판정이_거기에만_있다")
    void backfillHasSingleCaller() {
        // 되살리기와 <같은 이유>다: back-fill 의 SQL 술어(PENDING + RAW_SN IS NULL)는 관제가 방금
        // 넣은 미처리 행에도 그대로 맞는다. 우리 행인지 가르는 유일한 판정이
        // InternalUploadPathResolver#isUploadAreaPath 이고 그 호출은 TusUploadService 에만 있다.
        // 호출부가 늘면 그 판정 없이 관제 수신 원장을 갱신하는 통로가 생긴다(CWE-915).
        assertThat(callersOf(BACKFILL_CALL))
                .as("back-fill 호출부. 발견: %s", callersOf(BACKFILL_CALL))
                .containsExactly(ALLOWED_REVIVE_CALLER);
    }

    @Test
    @DisplayName("스키마한정_klid_at_과_인용식별자_표기의_DML도_통로로_집힌다 — 접두사_하나로_가드가_풀리지_않는다")
    void schemaQualifiedAndQuotedTableReferencesAreDetected() {
        // ★적대검증 mutation 실증 — 구 패턴(UPDATE\s+LS_DATA_INGEST)은 아래 첫 문을 놓쳐 7개 가드가
        //   전부 통과했다. 이 저장소의 V162·V165·V97 이 이미 klid_at. 표기를 쓰므로 가상의 우회가 아니다.
        List<String> mustMatchUpdate = List.of(
                "UPDATE klid_at.LS_DATA_INGEST SET CCTV_NM = ? WHERE RCPTN_SN = ?",
                "UPDATE klid_at . LS_DATA_INGEST SET CCTV_NM = ?",
                "UPDATE \"klid_at\".\"LS_DATA_INGEST\" SET CCTV_NM = ?",
                "UPDATE \"LS_DATA_INGEST\" SET CCTV_NM = ?",
                "update klid_at.ls_data_ingest set cctv_nm = ?",
                "UPDATE   LS_DATA_INGEST SET CCTV_NM = ?");
        for (String sql : mustMatchUpdate) {
            assertThat(UPDATE_LS_DATA_INGEST.matcher(sql).find())
                    .as("이 표기를 놓치면 그 문은 모든 가드 밖이다: %s", sql)
                    .isTrue();
        }

        List<String> mustMatchInsert = List.of(
                "INSERT INTO klid_at.LS_DATA_INGEST (PRCS_STTS_CD) VALUES (?)",
                "INSERT INTO \"klid_at\".\"LS_DATA_INGEST\" (PRCS_STTS_CD) VALUES (?)",
                "insert into ls_data_ingest (prcs_stts_cd) values (?)");
        for (String sql : mustMatchInsert) {
            assertThat(INSERT_LS_DATA_INGEST.matcher(sql).find())
                    .as("이 표기를 놓치면 그 INSERT 는 통로 계수 밖이다: %s", sql)
                    .isTrue();
        }

        // 위양성 확인 — 다른 테이블·다른 접두 이름까지 끌어오면 안 된다(무관한 파일이 통로로 계수된다).
        List<String> mustNotMatch = List.of(
                "UPDATE LS_DATA_RAW SET DATA_STTS_CD = ?",
                "UPDATE klid_at.LS_DATA_INGEST_ARCHIVE SET X = ?",
                "INSERT INTO LS_DATA_INGEST_LOG (X) VALUES (?)");
        for (String sql : mustNotMatch) {
            assertThat(UPDATE_LS_DATA_INGEST.matcher(sql).find()
                    || INSERT_LS_DATA_INGEST.matcher(sql).find())
                    .as("무관한 테이블을 통로로 계수하면 개수 단언이 통째로 흔들린다: %s", sql)
                    .isFalse();
        }
    }

    /**
     * 인입 DML 은 <b>고정 텍스트블록 리터럴</b>로만 존재한다 — 테이블명을 상수로 뽑는 것부터 금지한다.
     *
     * <h3>왜 필요한가 (적대검증 mutation 실증)</h3>
     * <p>위 정규식들은 소스에 적힌 <b>완성된 SQL 문자열</b>을 본다. 그래서 아래처럼 <b>평범한
     * 리팩토링</b>으로 테이블명을 상수로 뽑는 순간 모든 가드가 통과했다(BUILD SUCCESSFUL):
     * <pre>
     *   private static final String TBL = "LS_DATA_INGEST";
     *   private static final String SNEAKY =
     *           "UPDATE " + TBL + " SET CCTV_NM = ?, RAW_FILE_PATH_NM = ? WHERE RCPTN_SN = ?";
     * </pre>
     * 정규식 가드는 <b>조립을 금지해야만</b> 성립한다. 두 writer 의 SQL 은 전부 텍스트블록 고정
     * 리터럴이므로 그 형태 자체를 불변식으로 못 박는다(SQL 조립 표면 부재 = CWE-89 심층방어이기도 하다).
     *
     * <h3>오탐 관리</h3>
     * <p>스캔 대상은 {@code src/main/java} 뿐이고(테스트 소스는 보지 않는다) 주석·텍스트블록은 제거한
     * 뒤 <b>일반 문자열 리터럴</b>만 본다. 도입 시점에 전체 main 소스 스캔으로 <b>0건</b>임을 확인했다.
     */
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\".*?\"\"\"", Pattern.DOTALL);

    /** 일반(단일 행) 문자열 리터럴 — 텍스트블록을 제거한 뒤에 적용한다. */
    private static final Pattern STRING_LITERAL =
            Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    /** 리터럴 <b>전체</b>가 인입 테이블 이름인 경우(스키마 한정·인용 포함) — 조립용 상수 추출. */
    private static final Pattern TABLE_NAME_LITERAL =
            Pattern.compile("^(?:\\w+\\s*\\.\\s*)?\"?LS_DATA_INGEST\"?$", Pattern.CASE_INSENSITIVE);

    /**
     * JPA 매핑({@code @Table(name = "LS_DATA_INGEST")})은 조립이 아니다 — 유일한 정당 사용처.
     *
     * <p>파일 전체를 allowlist 하지 않고 <b>이 표기 위치만</b> 예외로 둔다. 엔티티 파일이라도 SQL 을
     * 조립하기 시작하면 잡혀야 한다.
     */
    private static final Pattern JPA_TABLE_MAPPING =
            Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*$");

    /**
     * DML 키워드만 담고 <b>뒤에 구분 공백</b>이 붙은 리터럴 = 조립 조각({@code "UPDATE " + TBL}).
     *
     * <p>trailing 공백을 요구하는 이유는 오탐 회피다 — 값 상수({@code INPUT_SELECT = "SELECT"} ·
     * {@code "DELETE"}(HTTP 메서드) · {@code "MERGE"}(작업락 종류))는 공백을 달지 않는다. 실제로 공백
     * 없는 형태로 걸면 무관한 파일 3건이 오탐된다(도입 시 실측).
     */
    private static final Pattern DML_FRAGMENT_LITERAL = Pattern.compile(
            "^\\s*(UPDATE|INSERT\\s+INTO|DELETE\\s+FROM|MERGE\\s+INTO)\\s+$", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("인입_테이블명을_문자열_상수로_뽑지_않는다 — 뽑는_순간_정규식_가드가_전부_무력화된다")
    void ingestTableNameIsNeverExtractedIntoAStringConstant() {
        List<String> violations = new ArrayList<>();
        for (JavaSource source : loadMainSources()) {
            String scanned = TEXT_BLOCK.matcher(source.content()).replaceAll(" \"\" ");
            Matcher literal = STRING_LITERAL.matcher(scanned);
            while (literal.find()) {
                String value = unquote(literal.group()).trim();
                if (!TABLE_NAME_LITERAL.matcher(value).matches()) {
                    continue;
                }
                String before = scanned.substring(Math.max(0, literal.start() - 60), literal.start());
                if (JPA_TABLE_MAPPING.matcher(before).find()) {
                    continue; // @Table(name = "LS_DATA_INGEST") — 매핑이지 조립이 아니다
                }
                violations.add(source.path() + " → \"" + value + "\"");
            }
        }
        assertThat(violations)
                .as("테이블명을 상수로 뽑으면 `\"UPDATE \" + TBL` 조립이 가능해지고, 그 순간 이 파일의"
                        + " 모든 정규식 가드가 미탐이 된다(mutation 실증). 인입 DML 은 고정 텍스트블록"
                        + " 리터럴로만 둘 것. 발견: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("SQL을_DML키워드_조각으로_조립하지_않는다 — 조립_표면이_없어야_정규식_가드가_성립한다")
    void sqlIsNeverAssembledFromDmlKeywordFragments() {
        List<String> violations = new ArrayList<>();
        for (JavaSource source : loadMainSources()) {
            String scanned = TEXT_BLOCK.matcher(source.content()).replaceAll(" \"\" ");
            Matcher literal = STRING_LITERAL.matcher(scanned);
            while (literal.find()) {
                String value = unquote(literal.group());
                if (DML_FRAGMENT_LITERAL.matcher(value).matches()) {
                    violations.add(source.path() + " → \"" + value + "\"");
                }
            }
        }
        assertThat(violations)
                .as("DML 키워드만 담은 조각 리터럴은 곧 SQL 조립이다 — 완성 SQL 을 보는 정규식 가드가"
                        + " 통째로 미탐이 되고 SQL 조립 표면(CWE-89)도 생긴다. 발견: %s", violations)
                .isEmpty();
    }

    /**
     * 인입 writer 의 <b>모든</b> 트랜잭션 진입점은 {@link Propagation#REQUIRES_NEW} 다.
     *
     * <h3>왜 이 가드가 필요한가 (적대검증 실증 — 지우지 말 것)</h3>
     * <p>{@code backfillMeasuredMeta} 의 propagation 을 {@code REQUIRED} 로 바꿔도 <b>죽는 테스트가
     * 0건</b>이었다(Guard·단위·IT 전량 BUILD SUCCESSFUL). 그런데 그 상태에서 back-fill SQL 이 실패하면
     * 실제 피해가 재현된다:
     * <pre>
     *   ERROR: current transaction is aborted, commands ignored until end of transaction block
     *     → UPDATE LS_TUS_UPLOAD ... SET STTS_CD='COMPLETED' 실패
     *     → 다 올라온 업로드가 완료 실패(500)
     * </pre>
     * PostgreSQL 은 문 하나가 실패하면 트랜잭션 <b>전체</b>를 abort 시키므로, 호출부가 예외를 삼켜도
     * 커넥션은 이미 오염돼 뒤따르는 완료 전이·도착 통지가 모조리 실패한다. 즉 "부가 기능이 본 흐름을
     * 죽이지 않는다"는 이 흐름의 <b>핵심 방어</b>가 값 하나에 걸려 있는데 회귀 가드가 없었다.
     *
     * <p>같은 이유가 {@code insertPending}(UK 위반이 세션 생성 트랜잭션을 오염) ·
     * {@code reviveForUpload}(같은 UK 축)에도 <b>그대로</b> 성립하므로 셋을 함께 고정한다.
     *
     * <p>메서드 이름 집합까지 단언하는 이유 — 값만 보면 새로 추가된 {@code @Transactional} 통로가
     * {@code REQUIRED} 로 들어와도(또는 애노테이션이 통째로 빠져도) 조용히 통과한다.
     */
    @Test
    @DisplayName("인입_writer의_트랜잭션_통로는_전부_REQUIRES_NEW다 — REQUIRED면_실패가_호출자_트랜잭션을_abort시킨다")
    void ingestWriterMethodsAlwaysRunInTheirOwnTransaction() {
        Map<String, Propagation> declared = new TreeMap<>();
        for (Method method : InternalUploadIngestWriter.class.getDeclaredMethods()) {
            Transactional tx = method.getAnnotation(Transactional.class);
            if (tx != null) {
                declared.put(method.getName(), tx.propagation());
            }
        }

        assertThat(declared.keySet())
                .as("인입 writer 의 @Transactional 진입점 목록이 바뀌었다(추가/삭제). 새 통로라면 이"
                        + " 목록과 아래 REQUIRES_NEW 단언을 함께 검토할 것. 발견: %s", declared)
                .containsExactlyInAnyOrderElementsOf(TX_ISOLATED_WRITER_METHODS);
        assertThat(declared).allSatisfy((name, propagation) ->
                assertThat(propagation)
                        .as("%s 가 REQUIRED 면 이 문의 실패가 호출자 트랜잭션을 통째로 abort 시켜"
                                + " <다 올라온 업로드가 완료 실패(500)>한다(실증됨)", name)
                        .isEqualTo(Propagation.REQUIRES_NEW));
    }

    /** {@link InternalUploadIngestWriter} 의 {@code @Transactional} 진입점 — 전부 독립 트랜잭션이어야 한다. */
    private static final List<String> TX_ISOLATED_WRITER_METHODS =
            List.of("insertPending", "reviveForUpload", "backfillMeasuredMeta");

    /** 리터럴 양끝 따옴표 제거 + 이스케이프된 따옴표 복원(값 비교용). */
    private static String unquote(String literal) {
        return literal.substring(1, literal.length() - 1).replace("\\\"", "\"");
    }

    @Test
    @DisplayName("주석에_INSERT_INTO_LS_DATA_INGEST_를_언급해도_위반으로_오탐하지_않는다")
    void commentMentionIsNotAViolation() {
        // given — Javadoc 이 SQL 문구를 그대로 인용한 가상의 소스
        String source = """
                /**
                 * 이 클래스는 더 이상 INSERT INTO LS_DATA_INGEST 를 하지 않는다.
                 */
                class Sample {
                    // 구 구현: INSERT INTO LS_DATA_INGEST (...) VALUES (...)
                    void noop() { }
                }
                """;

        // when/then — 주석 제거 후에는 매칭되지 않는다
        assertThat(INSERT_LS_DATA_INGEST.matcher(stripComments(source)).find()).isFalse();
        // (원문 그대로면 오탐한다 — 스트리핑이 필요한 이유)
        assertThat(INSERT_LS_DATA_INGEST.matcher(source).find()).isTrue();
    }

    // --- helpers ---

    /**
     * 프로덕션 소스에서 {@code UPDATE LS_DATA_INGEST} <b>문 단위</b>로 잘라낸다.
     *
     * <p>문 끝은 텍스트 블록 종료({@code """}) 또는 문장 종료({@code ;}) 중 <b>먼저 오는 쪽</b>이다 —
     * 어느 표기든 한 문을 넘어 다음 문의 술어를 끌어오지 않게 한다(끌어오면 술어 검사가 위양성 통과).
     */
    private List<UpdateStatement> loadUpdateStatements() {
        List<UpdateStatement> statements = new ArrayList<>();
        for (JavaSource source : loadMainSources()) {
            String fileName = Paths.get(source.path()).getFileName().toString();
            Matcher matcher = UPDATE_LS_DATA_INGEST.matcher(source.content());
            while (matcher.find()) {
                statements.add(new UpdateStatement(fileName,
                        cutStatement(source.content(), matcher.start())));
            }
        }
        return statements;
    }

    private static String cutStatement(String content, int start) {
        int end = content.length();
        for (String terminator : new String[]{"\"\"\"", ";"}) {
            int at = content.indexOf(terminator, start);
            if (at >= 0) {
                end = Math.min(end, at);
            }
        }
        return content.substring(start, end);
    }

    private static boolean setsControlOwnedColumn(UpdateStatement stmt) {
        return CONTROL_OWNED_COLUMNS.stream().anyMatch(column -> setsColumn(stmt, column));
    }

    /** 그 문의 <b>SET 절</b>이 해당 컬럼을 대입 대상으로 갖는가(값 표현식 안의 동명 참조는 제외). */
    private static boolean setsColumn(UpdateStatement stmt, String column) {
        return stmt.setAssignments().containsKey(column.toUpperCase(java.util.Locale.ROOT));
    }

    /** WHERE 절이 {@code PRCS_STTS_CD = '<state>'} 인 문 <b>하나</b>를 지목한다(없거나 여럿이면 실패). */
    private static UpdateStatement statementGuardedBy(List<UpdateStatement> statements, String state) {
        String predicate = "PRCS_STTS_CD = '" + state + "'";
        List<UpdateStatement> matched = statements.stream()
                .filter(s -> s.normalizedWhere().toUpperCase(java.util.Locale.ROOT)
                        .contains(predicate))
                .toList();
        assertThat(matched)
                .as("술어 [%s] 로 식별되는 UPDATE 문이 정확히 1건이어야 한다. 발견: %d 건", predicate,
                        matched.size())
                .hasSize(1);
        return matched.get(0);
    }

    /** 해당 호출 패턴을 보유한 프로덕션 파일명(선언 파일 자신은 제외). */
    private List<String> callersOf(Pattern call) {
        return loadMainSources().stream()
                .filter(s -> call.matcher(s.content()).find())
                .map(s -> Paths.get(s.path()).getFileName().toString())
                .filter(name -> !ALLOWED_WRITER.equals(name))
                .sorted()
                .toList();
    }

    /** 잘라낸 UPDATE 문 1건 — SET 절과 WHERE 절을 나눠 본다. */
    private record UpdateStatement(String fileName, String sql) {

        private static final Pattern WHERE = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

        boolean hasWhere() {
            return WHERE.matcher(sql).find();
        }

        /** {@code SET} 이후 <b>첫 WHERE</b> 앞까지(= 바깥 SET 절). */
        String setPart() {
            Matcher where = WHERE.matcher(sql);
            return where.find() ? sql.substring(0, where.start()) : sql;
        }

        /** 첫 {@code WHERE} 이후 전부(서브쿼리 술어 포함 — 술어 존재 판정용). */
        String wherePart() {
            Matcher where = WHERE.matcher(sql);
            return where.find() ? sql.substring(where.start()) : "";
        }

        /** WHERE 절의 공백을 1칸으로 접은 형태 — 줄바꿈·정렬에 흔들리지 않는 술어 판정용. */
        String normalizedWhere() {
            return wherePart().replaceAll("\\s+", " ");
        }

        /**
         * SET 절의 대입 목록 — {@code 대문자 컬럼명 → RHS(첫 쉼표까지)}.
         *
         * <p>★매칭 대상은 <b>대문자로 접은</b> SET 절이다 — 소문자·혼합 표기 컬럼을 놓치지 않기 위한
         * 필수 장치이며 절대 벗기지 말 것({@link #SET_ASSIGNMENT} Javadoc 참조).
         *
         * <p>{@code UPDATE LS_DATA_INGEST} 헤더 뒤부터 스캔하므로 테이블명은 잡히지 않는다
         * (헤더에는 {@code =} 가 없다).
         */
        Map<String, String> setAssignments() {
            Map<String, String> assignments = new TreeMap<>();
            Matcher m = SET_ASSIGNMENT.matcher(setPart().toUpperCase(java.util.Locale.ROOT));
            while (m.find()) {
                assignments.put(m.group(1), m.group(2).trim());
            }
            return assignments;
        }
    }

    private List<JavaSource> loadMainSources() {
        assertThat(Files.isDirectory(MAIN_SRC))
                .as("스캔 대상 디렉토리(%s)가 존재해야 한다 (테스트 작업 디렉토리=backend 모듈 루트)",
                        MAIN_SRC.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.walk(MAIN_SRC)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(LsDataIngestWriteGuardTest::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("main 소스 스캔 실패", e);
        }
    }

    private static JavaSource read(Path path) {
        try {
            return new JavaSource(path.toString(),
                    stripComments(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }

    private static String stripComments(String source) {
        String noBlock = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return LINE_COMMENT.matcher(noBlock).replaceAll(" ");
    }

    private record JavaSource(String path, String content) {
    }
}
