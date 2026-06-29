package kr.co.cudo.authoring.eventtype;

import kr.co.cudo.authoring.eventtype.repository.MngExEvntTypeMapRepository;
import kr.co.cudo.authoring.eventtype.repository.MngExEvntTypeRepository;
import kr.co.cudo.authoring.video.entity.MngExEvntType;
import kr.co.cudo.authoring.video.entity.MngExEvntTypeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제 이벤트 타입 마스터(MNG_EX_EVNT_TYPE / MNG_EX_EVNT_TYPE_MAP) 엔티티·스키마 정합 통합 테스트.
 *
 * <p>관제 실제 코드 체계(EV01000101 등)를 진실원으로 READ 연동하는 전환의 1단계 — 엔티티/stub 이
 * 실제 관제 스키마와 일치하여 {@code ddl-auto=validate} 를 통과하고, dev-seed 로 적재된 실데이터를
 * JPA 로 조회할 수 있음을 실 PostgreSQL(Testcontainer) 위에서 고정한다.
 *
 * <p>컨테이너/시드는 {@code @ActiveProfiles("local")} 의 {@code DevSeedRunner}(CommandLineRunner)가
 * Flyway 마이그레이션(V71 stub 정렬) 이후 controlDataSource 에 멱등 적재한다. MNG_* 는 관제 소유
 * READ 전용이므로 엔티티는 {@code @Immutable} 이며 어떤 쓰기도 하지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "authoring.dev.seed.enabled=true")
class MngExEvntTypeRepositoryIT {

    /** dev-seed 가 적재하는 수집대상(CLCT_YN='Y') 14종 — EV08000101(ignore) 제외. */
    private static final List<String> COLLECTED_CODES = List.of(
            "EV01000101", "EV01000102", "EV01000103", "EV01000201",
            "EV02000101", "EV02000102", "EV02000201", "EV02000501",
            "EV03000101", "EV03000102", "EV03000103",
            "EV05000101", "EV05000201", "EV05000701");

    @Autowired
    private MngExEvntTypeRepository evntTypeRepository;

    @Autowired
    private MngExEvntTypeMapRepository evntTypeMapRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Test
    @DisplayName("관제_이벤트타입_엔티티가_실제컬럼으로_매핑되어_조회된다")
    void entityMapsRealColumns() {
        // given — dev-seed 가 적재한 실제 관제 코드 EV02000201(쓰러짐 카테고리)
        // when
        MngExEvntType type = evntTypeRepository.findById("EV02000201").orElseThrow();

        // then — 실제 스키마 컬럼(EVNT_CLS_CD/EVNT_CTGRY_CD/CLCT_YN)이 정확히 매핑되어 채워진다
        assertThat(type.getEvntClsCd()).isEqualTo("02");
        assertThat(type.getEvntCtgryCd()).isEqualTo("0002");
        assertThat(type.getClctYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("수집대상_CLCT_YN_Y_만_조회된다 — 14종_포함_비수집_EV07000201_미포함")
    void findByClctYnReturnsOnlyCollected() {
        // when — 수집대상만 파생 쿼리로 조회
        List<MngExEvntType> collected = evntTypeRepository.findByClctYn("Y");

        // then — 14종 수집 코드 모두 포함, 비수집(EV07000201, CLCT_YN='N')은 미포함, 전부 'Y'
        List<String> codes = collected.stream().map(MngExEvntType::getEvntTypeCd).toList();
        assertThat(codes).containsAll(COLLECTED_CODES);
        assertThat(codes).doesNotContain("EV07000201");
        assertThat(collected).allSatisfy(t -> assertThat(t.getClctYn()).isEqualTo("Y"));

        // 비수집 코드는 마스터에는 존재(폴백 테스트용)
        assertThat(evntTypeRepository.findById("EV07000201")).isPresent();
    }

    @Test
    @DisplayName("카테고리_라벨_MAP이_CD_TYPE_02로_조회된다 — 쓰러짐_등_카테고리명_포함")
    void findByCdTypeReturnsCategoryLabels() {
        // when — CD_TYPE='02' (카테고리명행)
        List<MngExEvntTypeMap> categories = evntTypeMapRepository.findByCdType("02");

        // then — 카테고리 한글명(라벨 소스)이 조회된다
        List<String> labels = categories.stream().map(MngExEvntTypeMap::getEvntNm).toList();
        assertThat(labels).contains("쓰러짐", "화재", "교통사고", "흉기소지");
        // CD_TYPE='02' 행만 반환
        assertThat(categories).allSatisfy(m -> assertThat(m.getCdType()).isEqualTo("02"));
    }

    @Test
    @DisplayName("EV02000201의_카테고리_라벨이_쓰러짐으로_조인된다")
    void categoryLabelJoinsToFallen() {
        // given — 이벤트 코드로 (EVNT_CLS_CD, EVNT_CTGRY_CD) 도출
        MngExEvntType type = evntTypeRepository.findById("EV02000201").orElseThrow();

        // when — MAP 의 CD_TYPE='02' 카테고리명행을 (cls, ctgry) 로 조회
        MngExEvntTypeMap category = evntTypeMapRepository
                .findCategoryLabel(type.getEvntClsCd(), type.getEvntCtgryCd())
                .orElseThrow();

        // then — 카테고리 한글 라벨 = 쓰러짐
        assertThat(category.getEvntNm()).isEqualTo("쓰러짐");
    }

    @Test
    @DisplayName("같은_클래스_카테고리에_상세행이_섞여도_카테고리명행만_1건_조회된다")
    void categoryLabelReturnsSingleRowEvenWithDetailRows() {
        // given — 동일 (CD_TYPE='02', cls='02', ctgry='0002') 에 DTL_EVNT/EVNT_TYPE_CD 가 채워진
        // 상세행을 추가 적재한다. 3컬럼만 거는 파생 쿼리였다면 카테고리명행 + 상세행이 함께 잡혀
        // IncorrectResultSizeDataAccessException 이 났을 상황(멱등 — ON CONFLICT DO NOTHING).
        JdbcTemplate jdbc = new JdbcTemplate(controlDataSource);
        jdbc.update(
                "INSERT INTO MNG_EX_EVNT_TYPE_MAP "
                        + "(CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD, EVNT_NM, USE_YN) "
                        + "VALUES ('02', '02', '0002', '01', 'EV02000201', '쓰러짐(상세)', 'Y') "
                        + "ON CONFLICT (CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD) DO NOTHING");

        // when — 상세행이 섞인 상태에서 카테고리 라벨 조회
        MngExEvntTypeMap category = evntTypeMapRepository
                .findCategoryLabel("02", "0002")
                .orElseThrow();

        // then — 예외 없이 카테고리명행(DTL_EVNT/EVNT_TYPE_CD='') 단건만 반환된다
        assertThat(category.getEvntNm()).isEqualTo("쓰러짐");
        assertThat(category.getDtlEvnt()).isEmpty();
        assertThat(category.getEvntTypeCd()).isEmpty();
    }
}
