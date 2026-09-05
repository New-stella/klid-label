package kr.co.cudo.authoring.support;

import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * 같은 스프링 컨텍스트를 쓰는 시험 클래스들을 <b>연속으로 실행</b>시키는 클래스 정렬기.
 *
 * <h3>왜 필요한가 — 캐시가 작은 게 문제가 아니라 지역성이 없는 게 문제다</h3>
 * <p>Spring 은 테스트 컨텍스트를 캐시에 살려 두는데(기본 상한 32), 이 저장소는 서로 다른 컨텍스트가
 * <b>74종</b>이라 32칸을 두고 다툰다. 게다가 실행 순서가 컨텍스트와 <b>무관</b>해서, 같은 컨텍스트를
 * 쓰는 클래스들이 실행 순서상 흩어진 채 서로를 밀어낸다 — 한 번 만든 컨텍스트를 버렸다가 나중에
 * 다시 만든다.
 *
 * <p>클래스를 컨텍스트 시그니처로 묶어 연속 실행하면 <b>컨텍스트마다 정확히 한 번만 생성</b>된다.
 * 그러면 총 생성 횟수가 <b>캐시 크기와 무관하게 최소치(= 컨텍스트 종류 수)</b>가 되고, 캐시 상한을
 * 힙에 맞게 낮춰도 재생성 비용이 늘지 않는다. 즉 이 정렬기가 있어야
 * {@code spring.test.context.cache.maxSize} 를 낮추는 것이 안전해진다.
 *
 * <p>이 저장소는 특히 효과가 크다 — 시험 클래스 309개 중 <b>223개가 단 2개 컨텍스트</b>를 쓴다.
 * 그 두 덩어리만 뭉쳐도 대부분의 실행 구간에서 캐시 미스가 사라진다.
 *
 * <h3>시그니처는 근사치여도 된다</h3>
 * <p>진짜 캐시 키는 Spring 의 {@code MergedContextConfiguration} 이다. 여기서는 어노테이션에서
 * 읽을 수 있는 축만 모아 근사한다 — <b>틀려도 정확성 문제가 아니라 캐시 미스 한 번</b>이기 때문이다.
 * 반대로 시그니처가 <b>과하게 잘게</b> 갈리는 것도 손해가 아니다(같은 컨텍스트끼리 두 덩어리로
 * 나뉠 뿐 순서는 여전히 뭉쳐 있다).
 *
 * <p>{@code @DynamicPropertySource} 를 선언한 클래스는 <b>클래스명 자체를 시그니처에 넣는다</b> —
 * {@code DynamicPropertiesContextCustomizer} 의 동등성이 {@code Set<Method>} 라 선언 클래스가 다르면
 * 프로퍼티 값이 같아도 컨텍스트가 갈리기 때문이다(그 클래스들은 어차피 각자 컨텍스트를 갖는다).
 *
 * <h3>⚠ 실행 순서를 바꾸는 변경이다</h3>
 * <p>시험이 <b>실행 순서에 의존</b>하고 있었다면 이 정렬기가 그것을 드러낸다. 이 저장소에는 조합
 * 실행에서는 통과하던 정리 코드가 전체 실행에서 37건을 깨뜨린 이력이 있다. 그런 실패가 나면
 * <b>이 정렬기를 되돌릴 것이 아니라 그 순서 의존을 고쳐야 한다</b> — 순서 의존은 이 정렬기가 없어도
 * 언젠가 다른 계기로 터진다.
 *
 * <p>스프링을 쓰지 않는 시험(순수 Mockito·정적 스캔 가드 등)은 시그니처가 비어 한 덩어리로 모인다.
 * 그것들은 컨텍스트를 만들지 않으므로 어디에 모이든 무관하다.
 */
public class TestContextGroupingClassOrderer implements ClassOrderer {

    /** 컨텍스트를 가르는 어노테이션 — 인자 전체를 문자열로 떠서 시그니처에 넣는다. */
    private static final List<String> CONTEXT_ANNOTATIONS = List.of(
            "org.springframework.boot.test.context.SpringBootTest",
            "org.springframework.test.context.ActiveProfiles",
            "org.springframework.test.context.TestPropertySource",
            "org.springframework.test.context.ContextConfiguration",
            "org.springframework.context.annotation.Import");

    /** {@code @AutoConfigureMockMvc} 등 — 컨텍스트를 가르므로 전부 시그니처에 넣는다. */
    private static final String AUTOCONFIGURE_PACKAGE = "org.springframework.boot.test.autoconfigure.";

    private static final String MOCK_BEAN = "org.springframework.boot.test.mock.mockito.MockBean";
    private static final String SPY_BEAN = "org.springframework.boot.test.mock.mockito.SpyBean";
    private static final String DYNAMIC_PROPERTY_SOURCE =
            "org.springframework.test.context.DynamicPropertySource";

    @Override
    public void orderClasses(ClassOrdererContext context) {
        context.getClassDescriptors().sort(
                Comparator.comparing(TestContextGroupingClassOrderer::signatureOfDescriptor)
                        // 시그니처가 같으면 이름순 — 실행 순서를 결정적으로 유지한다.
                        .thenComparing(ClassDescriptor::getDisplayName));
    }

    private static String signatureOfDescriptor(ClassDescriptor descriptor) {
        return signatureOf(descriptor.getTestClass());
    }

    /** {@code @MockBean}/{@code @SpyBean} 필드의 타입 집합(정렬) — 조합이 다르면 컨텍스트가 갈린다. */
    private static String mockedTypes(Class<?> testClass) {
        TreeSet<String> types = new TreeSet<>();
        for (Class<?> current = testClass; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    String type = annotation.annotationType().getName();
                    if (MOCK_BEAN.equals(type) || SPY_BEAN.equals(type)) {
                        types.add(annotation.annotationType().getSimpleName()
                                + ":" + field.getType().getName());
                    }
                }
            }
        }
        return String.join(",", types);
    }

    private static boolean hasDynamicPropertySource(Class<?> testClass) {
        return Arrays.stream(testClass.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getAnnotations()))
                .map(annotation -> annotation.annotationType().getName())
                .anyMatch(DYNAMIC_PROPERTY_SOURCE::equals);
    }

    /**
     * 같은 값이면 같은 컨텍스트를 쓸 가능성이 높은 근사 시그니처 — <b>판정 단일 지점</b>.
     *
     * <p>어노테이션 {@code toString()} 이 모든 속성값을 담으므로 축을 일일이 열거하지 않는다.
     * 새 축이 생겨도 자동으로 반영된다.
     */
    static String signatureOf(Class<?> testClass) {
        // ⚠ getAnnotations() 는 <선언 순서>로 준다. 정렬하지 않으면 같은 컨텍스트인데
        //   어노테이션을 적은 순서만 다른 두 클래스가 서로 다른 덩어리로 갈린다.
        TreeSet<String> contextAnnotations = new TreeSet<>();
        for (Annotation annotation : testClass.getAnnotations()) {
            String type = annotation.annotationType().getName();
            if (CONTEXT_ANNOTATIONS.contains(type) || type.startsWith(AUTOCONFIGURE_PACKAGE)) {
                contextAnnotations.add(annotation.toString());
            }
        }
        StringBuilder signature = new StringBuilder();
        signature.append(String.join("|", contextAnnotations)).append('|');
        signature.append(mockedTypes(testClass)).append('|');
        if (hasDynamicPropertySource(testClass)) {
            // Set<Method> 동등성 — 선언 클래스가 다르면 무조건 별개 컨텍스트다.
            signature.append("DYN:").append(testClass.getName());
        }
        return signature.toString();
    }
}
