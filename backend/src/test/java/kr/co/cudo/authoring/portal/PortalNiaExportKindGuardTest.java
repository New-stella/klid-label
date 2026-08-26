package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.ExportKind;
import kr.co.cudo.authoring.dataset.export.SourcePrivacyMeta;
import kr.co.cudo.authoring.dataset.export.json.CategoryMapper;
import kr.co.cudo.authoring.dataset.export.json.LabelToAnnotationMapper;
import kr.co.cudo.authoring.dataset.export.json.NiaAnnotationDoc;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder;
import kr.co.cudo.authoring.dataset.export.json.VideoMetaMapper;
import kr.co.cudo.authoring.portal.service.PortalNiaDocumentFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포털 산출물의 <b>산출 종류 고정</b>(비식별) 회귀 가드. @design API-203, AC-034
 *
 * <h3>이 가드가 막는 것</h3>
 * <p>공유 빌더는 산출 종류를 자유 파라미터로 받는다. 포털이 {@link ExportKind#ORIGINAL} 을 넘기면
 * 어노테이션 문서의 {@code dataset.src_path}·{@code video.filename} 에 <b>원본(비식별 이전) 영상의
 * 절대경로와 파일명</b>이 실려 나간다. 파일을 담지 않아도 경로만으로 원본 위치가 외부 채널에
 * 노출되므로 AC-034(원본 폴백 금지)를 정면으로 위반한다(CWE-359).
 *
 * <h3>세 축을 함께 고정한다 — 하나만으로는 새는 길이 남는다</h3>
 * <ol>
 *   <li><b>값</b> — 고정 상수가 비식별인가</li>
 *   <li><b>표면</b> — 포털 패키지 어디에도 원본 종류 참조가 없고, 공유 빌더를 <b>타입으로</b> 언급하는
 *       파일이 고정 진입점 하나뿐인가(누가 새 호출을 만들어도 못 넘긴다)</li>
 *   <li><b>동작</b> — 실제 문서에 원본 경로가 실리지 않는가</li>
 * </ol>
 * ①만 두면 다른 파일에서 빌더를 직접 부르는 우회가 열리고, ②만 두면 상수가 바뀌는 경로를 못 잡는다.
 */
class PortalNiaExportKindGuardTest {

    private static final Path PORTAL_MAIN =
            Paths.get("src/main/java/kr/co/cudo/authoring/portal");

    private static final String ORIGINAL_VIDEO_PATH = "/nas/raw/100/original-BEFORE-MASKING.mp4";
    private static final String DEID_VIDEO_PATH = "/nas/deid/100/deidentified.mp4";

    /** 문서 조립의 <b>유일한</b> 진입점. 이 파일 밖에서 공유 빌더를 언급하면 그 자체가 위반이다. */
    private static final String FIXED_ENTRY_POINT = "service/PortalNiaDocumentFactory.java";

    /** 판정을 필드명이 아니라 <b>타입</b>으로 한다 — 다른 이름의 같은 타입 필드로 우회되지 않는다. */
    private static final String SHARED_BUILDER_TYPE = "NiaJsonBuilder";

    /**
     * 원본 산출종류 <b>참조</b>만 잡는 어절 패턴. {@code ExportKind.ORIGINAL} 과 static import 뒤의 맨
     * 이름을 함께 잡고, 앞이 {@code #} 인 javadoc 링크 표기는 설명이므로 제외한다.
     */
    private static final Pattern ORIGINAL_KIND_TOKEN = Pattern.compile("(?<![\\w#])ORIGINAL\\b");

    // ======================== ① 값 ========================

    @Test
    @DisplayName("포털_산출_종류_상수는_비식별이다")
    void portalExportKindIsDeidentified() {
        assertThat(PortalNiaDocumentFactory.PORTAL_EXPORT_KIND).isEqualTo(ExportKind.DEIDENTIFIED);
    }

    // ======================== ② 표면 ========================

    @Test
    @DisplayName("스캔은_포털_소스를_실제로_읽는다")
    void scanActuallySeesPortalSources() throws IOException {
        // ⚠ 아래 두 검사는 «못 본 것» 과 «없는 것» 을 구분하지 못한다 — 경로가 틀리거나 작업
        //   디렉터리가 바뀌면 전건 0 이 나오고 그 0 이 «전부 통과» 로 보고된다(이 저장소가 반복해서
        //   뚫린 형태). 스캔이 눈을 뜨고 있는지를 먼저 못박는다.
        assertThat(scan(line -> line.startsWith("package ")))
                .as("포털 main 소스를 한 건도 읽지 못했다면 이 스캔 자체가 눈이 먼 것이다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("포털_패키지_어디에도_원본_산출종류_참조가_없다")
    void portalSourcesNeverReferenceOriginalKind() throws IOException {
        // ⚠ {@code ExportKind.ORIGINAL} 이라는 <문자열>만 찾으면 static import 뒤의 맨 이름
        //   ({@code build(..., ORIGINAL)})을 놓친다. 어절 단위로 넓히되 javadoc 링크 표기
        //   ({@code ExportKind#ORIGINAL})는 «참조» 가 아니라 «설명» 이므로 제외한다.
        List<String> offenders = scan(line -> ORIGINAL_KIND_TOKEN.matcher(line).find());

        assertThat(offenders)
                .as("포털은 비식별본만 서빙한다 — 원본 산출종류를 참조하는 순간 문서 경로 필드로 원본이 샌다")
                .isEmpty();
    }

    @Test
    @DisplayName("공유_빌더를_언급하는_포털_소스는_고정_진입점_한_곳뿐이다")
    void sharedBuilderIsReferencedOnlyByTheFixedEntryPoint() throws IOException {
        // ⚠ 구 술어는 <필드명 문자열>({@code niaJsonBuilder.build(})을 찾았다. 같은 타입 필드를 다른
        //   이름으로 두면 그대로 통과해 <산출 종류를 직접 고르는 새 호출>이 열린다. 판정을 <타입 참조>
        //   로 올려 이름과 무관하게 잡는다 — 자바에서 그 빈을 쓰려면 어딘가에서 타입을 적어야 한다.
        List<String> references = scan(line -> line.contains(SHARED_BUILDER_TYPE));

        // ⚠ allMatch 는 빈 목록에서 공허참이라 <검사가 아무것도 못 본 상태>와 구분되지 않는다.
        //   참조가 실제로 있음을 먼저 못박은 뒤에 그 위치를 판정한다.
        assertThat(references)
                .as("공유 빌더 참조를 한 건도 찾지 못했다면 이 스캔 자체가 눈이 먼 것이다")
                .isNotEmpty();
        // 진입점이 늘어나면 그 새 호출은 산출 종류를 스스로 고르게 되고, 위 ① 상수가 무력해진다.
        assertThat(references)
                .as("문서 조립은 PortalNiaDocumentFactory 한 곳을 거쳐야 한다")
                .allMatch(entry -> entry.startsWith(FIXED_ENTRY_POINT));
    }

    // ======================== ③ 동작 ========================

    @Test
    @DisplayName("어노테이션_문서에_원본_영상_경로와_파일명이_실리지_않는다")
    void documentNeverCarriesOriginalVideoPath() throws IOException {
        NiaAnnotationDoc doc = buildPortalDoc(DEID_VIDEO_PATH);
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(doc);

        assertThat(json).doesNotContain(ORIGINAL_VIDEO_PATH);
        assertThat(json).doesNotContain("original-BEFORE-MASKING");
        assertThat(doc.dataset().srcPath()).isEqualTo(DEID_VIDEO_PATH);
        assertThat(doc.video().filename()).isEqualTo("deidentified.mp4");
    }

    @Test
    @DisplayName("비식별_영상_경로가_없으면_그_자리를_비우고_원본으로_대체하지_않는다")
    void missingDeidVideoPathLeavesTheSlotEmpty() {
        NiaAnnotationDoc doc = buildPortalDoc(null);

        // AC-034 — 비식별 영상이 없으면 그 자리를 비운다(원본 경로를 대신 싣지 않는다).
        assertThat(doc.dataset().srcPath()).isNull();
        assertThat(doc.video().filename()).isNull();
    }

    // ======================== fixtures ========================

    private static NiaAnnotationDoc buildPortalDoc(String deidVideoPath) {
        ObjectMapper mapper = new ObjectMapper();
        NiaJsonBuilder builder = new NiaJsonBuilder(
                new LabelToAnnotationMapper(mapper), new VideoMetaMapper(), new CategoryMapper());
        PortalNiaDocumentFactory factory = new PortalNiaDocumentFactory(builder);

        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(100L)
                .rawFilePathNm(ORIGINAL_VIDEO_PATH)
                .evntNm("화재")
                .build();
        LsDataSrc frame = LsDataSrc.create(100L, 0L, "/nas/raw/frames/0000.jpg", LocalDateTime.now());
        setField(frame, "srcSn", 70001L);

        return factory.build(
                builder.prepareContext(meta, null, List.of(), null, deidVideoPath,
                        SourcePrivacyMeta.NONE, null, null),
                frame, List.of());
    }

    /** 포털 main 소스 전건에서 조건에 걸리는 줄을 {@code 상대경로:번호} 로 모은다. */
    private static List<String> scan(java.util.function.Predicate<String> predicate) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PORTAL_MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (predicate.test(lines.get(i))) {
                        hits.add(PORTAL_MAIN.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }
        return hits;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException ignore) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
