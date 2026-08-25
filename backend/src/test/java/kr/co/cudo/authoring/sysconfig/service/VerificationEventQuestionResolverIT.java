package kr.co.cudo.authoring.sysconfig.service;

import kr.co.cudo.authoring.sysconfig.dto.VerificationEventQuestionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ★ 질문 칸 <b>조달 판정기</b> 실동작 검증 (Testcontainers PostgreSQL). [design: ERD-033]
 *
 * <p>여기서 지키는 불변:
 * <ul>
 *   <li><b>첫 번째의 결정성</b> — 「그 유형의 첫 번째 질문」이 삽입 순서·조회 순서와 <b>무관하게</b>
 *       정렬순서 최선두 1건으로 고정된다. 이것이 흔들리면 마킹을 거치지 않는 경로가 채우는 기본값이
 *       실행마다 달라진다.</li>
 *   <li><b>타 유형 선택값 교정</b> — 마킹이 보관한 선택값이 그 유형에 속하지 않으면 첫 번째로
 *       되돌린다. 화면 입력을 그대로 신뢰하지 않는다(그 컬럼엔 물리 FK 가 없고, 질문 목록은 전체
 *       교체로 저장되어 가리키던 행이 사라지는 것이 정상 동선이다).</li>
 *   <li><b>없으면 예외가 아니라 비어 있음</b> — 유형이 카탈로그에 없거나 질문이 0건이어도 예외를
 *       던지지 않는다. 이 카탈로그는 <b>허용목록이 아니며</b>, 여기서 막으면 위탁·마킹이 막힌다.</li>
 * </ul>
 *
 * <p>시드 7종을 건드리지 않도록 {@code itq} 접두 유형만 만들고 테스트 트랜잭션 롤백으로 정리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class VerificationEventQuestionResolverIT {

    private static final String TYPE_A = "itq_alpha";
    private static final String TYPE_B = "itq_beta";
    private static final String TYPE_EMPTY = "itq_empty";
    private static final String TYPE_UNKNOWN = "itq_absent";

    @Autowired
    private VerificationEventQuestionResolver resolver;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        seedType(TYPE_A, "알파", 101);
        seedType(TYPE_B, "베타", 102);
        seedType(TYPE_EMPTY, "질문없음", 103);
    }

    private void seedType(String code, String name, int sortSeq) {
        jdbc.update("""
                INSERT INTO ls_vrfc_evnt_type (vrfc_evnt_type_cd, vrfc_evnt_type_nm, sort_seq)
                VALUES (?, ?, ?)
                """, code, name, sortSeq);
    }

    /** 질문 1건 삽입 후 PK 반환 — 삽입 순서와 정렬순서를 <b>일부러 어긋나게</b> 줄 수 있게 분리했다. */
    private long seedQuestion(String typeCd, int sortSeq, String text) {
        jdbc.update("""
                INSERT INTO ls_vrfc_evnt_qstn (vrfc_evnt_type_cd, sort_seq, qstn_cn)
                VALUES (?, ?, ?)
                """, typeCd, sortSeq, text);
        return jdbc.queryForObject("""
                SELECT vrfc_evnt_qstn_sn FROM ls_vrfc_evnt_qstn
                 WHERE vrfc_evnt_type_cd = ? AND sort_seq = ?
                """, Long.class, typeCd, sortSeq);
    }

    @Test
    @DisplayName("첫_번째_질문은_정렬순서_최선두이며_삽입_순서와_무관하다")
    void 첫_번째_질문은_정렬순서_최선두이며_삽입_순서와_무관하다() {
        // given — 물리 삽입 순서를 정렬순서와 반대로 준다(조회 순서에 기대면 여기서 깨진다)
        seedQuestion(TYPE_A, 3, "세 번째");
        seedQuestion(TYPE_A, 1, "첫 번째");
        seedQuestion(TYPE_A, 2, "두 번째");

        // when
        Optional<VerificationEventQuestionResponse> first = resolver.firstQuestion(TYPE_A);

        // then
        assertThat(first).isPresent();
        assertThat(first.get().sortSeq()).isEqualTo(1);
        assertThat(first.get().qstnCn()).isEqualTo("첫 번째");
    }

    @Test
    @DisplayName("첫_번째_질문은_여러_번_조회해도_같은_값이다")
    void 첫_번째_질문은_여러_번_조회해도_같은_값이다() {
        seedQuestion(TYPE_A, 5, "다섯");
        seedQuestion(TYPE_A, 2, "둘");
        seedQuestion(TYPE_A, 9, "아홉");

        Long firstSn = resolver.firstQuestion(TYPE_A).orElseThrow().vrfcEvntQstnSn();
        for (int i = 0; i < 5; i++) {
            assertThat(resolver.firstQuestion(TYPE_A).orElseThrow().vrfcEvntQstnSn())
                    .as("조회를 반복해도 첫 번째는 흔들리지 않는다")
                    .isEqualTo(firstSn);
        }
    }

    @Test
    @DisplayName("첫_번째_질문을_지우면_다음_순서가_첫_번째로_재계산된다")
    void 첫_번째_질문을_지우면_다음_순서가_첫_번째로_재계산된다() {
        long removed = seedQuestion(TYPE_A, 1, "지울 것");
        seedQuestion(TYPE_A, 2, "남을 것");

        jdbc.update("DELETE FROM ls_vrfc_evnt_qstn WHERE vrfc_evnt_qstn_sn = ?", removed);

        VerificationEventQuestionResponse first = resolver.firstQuestion(TYPE_A).orElseThrow();
        assertThat(first.qstnCn()).isEqualTo("남을 것");
        assertThat(first.sortSeq()).isEqualTo(2);
    }

    @Test
    @DisplayName("마킹이_고른_질문이_그_유형에_속하면_그대로_쓴다")
    void 마킹이_고른_질문이_그_유형에_속하면_그대로_쓴다() {
        seedQuestion(TYPE_A, 1, "첫 번째");
        long selected = seedQuestion(TYPE_A, 2, "작업자가 고른 것");

        VerificationEventQuestionResponse resolved = resolver.resolve(selected, TYPE_A).orElseThrow();

        assertThat(resolved.vrfcEvntQstnSn()).isEqualTo(selected);
        assertThat(resolved.qstnCn()).isEqualTo("작업자가 고른 것");
    }

    @Test
    @DisplayName("다른_유형의_질문을_고르면_그_유형의_첫_번째로_되돌린다")
    void 다른_유형의_질문을_고르면_그_유형의_첫_번째로_되돌린다() {
        seedQuestion(TYPE_A, 1, "알파 첫 번째");
        long otherTypeQuestion = seedQuestion(TYPE_B, 1, "베타 질문");

        VerificationEventQuestionResponse resolved =
                resolver.resolve(otherTypeQuestion, TYPE_A).orElseThrow();

        assertThat(resolved.qstnCn())
                .as("소속이 어긋난 선택값을 그대로 쓰면 다른 유형의 질문이 어노테이션에 실린다")
                .isEqualTo("알파 첫 번째");
    }

    @Test
    @DisplayName("사라진_질문번호를_고르면_그_유형의_첫_번째로_되돌린다")
    void 사라진_질문번호를_고르면_그_유형의_첫_번째로_되돌린다() {
        long removed = seedQuestion(TYPE_A, 9, "곧 사라질 것");
        seedQuestion(TYPE_A, 1, "알파 첫 번째");
        jdbc.update("DELETE FROM ls_vrfc_evnt_qstn WHERE vrfc_evnt_qstn_sn = ?", removed);

        VerificationEventQuestionResponse resolved = resolver.resolve(removed, TYPE_A).orElseThrow();

        assertThat(resolved.qstnCn()).isEqualTo("알파 첫 번째");
    }

    @Test
    @DisplayName("선택값이_없으면_그_유형의_첫_번째를_쓴다")
    void 선택값이_없으면_그_유형의_첫_번째를_쓴다() {
        seedQuestion(TYPE_A, 1, "알파 첫 번째");

        assertThat(resolver.resolve(null, TYPE_A).orElseThrow().qstnCn()).isEqualTo("알파 첫 번째");
        assertThat(resolver.resolveQuestionText(null, TYPE_A)).contains("알파 첫 번째");
    }

    @Test
    @DisplayName("등록된_질문이_0건이면_예외_없이_비어_있음을_돌려준다")
    void 등록된_질문이_0건이면_예외_없이_비어_있음을_돌려준다() {
        assertThatCode(() -> {
            assertThat(resolver.firstQuestion(TYPE_EMPTY)).isEmpty();
            assertThat(resolver.resolve(null, TYPE_EMPTY)).isEmpty();
            assertThat(resolver.resolveQuestionText(null, TYPE_EMPTY)).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("카탈로그에_없는_유형도_예외_없이_비어_있음이다_허용목록이_아니다")
    void 카탈로그에_없는_유형도_예외_없이_비어_있음이다_허용목록이_아니다() {
        // 이 표는 허용목록이 아니다 — 목록 밖 유형의 영상도 위탁은 그대로 나가고 질문 칸만 빈다.
        // 여기서 예외를 던지면 그 정책이 뒤집혀 위탁이 막힌다.
        assertThatCode(() -> {
            assertThat(resolver.firstQuestion(TYPE_UNKNOWN)).isEmpty();
            assertThat(resolver.resolve(null, TYPE_UNKNOWN)).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("유형이_미지정이면_선택값이_있어도_비어_있음이다")
    void 유형이_미지정이면_선택값이_있어도_비어_있음이다() {
        long question = seedQuestion(TYPE_A, 1, "알파 첫 번째");

        // 유형을 모르면 소속을 판정할 축이 없다 — 선택값만 믿고 내보내면 다른 유형의 질문이 실린다.
        assertThat(resolver.resolve(question, null)).isEmpty();
        assertThat(resolver.resolve(question, "   ")).isEmpty();
        assertThat(resolver.firstQuestion(null)).isEmpty();
    }

    @Test
    @DisplayName("유형_표기가_달라도_정규화되어_같은_질문이_조달된다")
    void 유형_표기가_달라도_정규화되어_같은_질문이_조달된다() {
        seedQuestion(TYPE_A, 1, "알파 첫 번째");

        // 관제 인입이 대문자·공백을 섞어 보낸 값도 같은 값 공간이다 — 정규화 규칙을 복제하지 않고
        // 인입과 같은 함수를 재사용하기 때문에 여기서 어긋나지 않는다.
        assertThat(resolver.firstQuestion("  ITQ_ALPHA  ").orElseThrow().qstnCn())
                .isEqualTo("알파 첫 번째");
    }
}
