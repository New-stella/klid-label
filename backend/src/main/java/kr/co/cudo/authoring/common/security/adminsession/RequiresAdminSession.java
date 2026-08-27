package kr.co.cudo.authoring.common.security.adminsession;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 창구는 <b>관리자 단기 유효창</b>을 요구한다 — 역할 인가 위에 얹히는 추가 조건. [@design ADR-046]
 *
 * <h3>언제 쓰나 (그리고 언제 쓰면 안 되나)</h3>
 * <p>붙이면 그 창구로 들어오는 <b>모든 요청</b>이 유효창을 요구받는다. 그래서 요구가
 * <b>창구 전체에 걸릴 때만</b> 쓴다 — 예컨대 사용자 역할 변경·업로드 시작·관리자 자격 교체처럼
 * 그 엔드포인트에 들어오는 어떤 요청도 예외가 없는 경우다.
 *
 * <p>⚠ <b>요구가 요청 내용에 따라 갈리는 창구에는 붙이지 않는다.</b> 시스템 설정 저장
 * ({@code PUT /v1/manage/configs/{key}})이 그 예다 — 연동 주소 키에만 유효창이 필요하고
 * 배치·추론·정밀도·비식별 키는 검수자 권한만으로 저장된다. 거기에 이 애노테이션을 붙이면
 * <b>요구가 없는 키까지 함께 막혀</b> 운영자가 늘 쓰던 설정 변경이 잠긴다(이 라운드가 피하려는
 * 최악의 회귀다). 그런 창구는 {@link AdminSessionGate#require(String, String)} 를 직접 부르고,
 * 「어느 요청에 요구가 걸리는가」의 판정은 그 도메인이 계속 소유한다.
 *
 * <h3>판정을 복제하지 않는다</h3>
 * <p>이 애노테이션은 <b>표식일 뿐</b>이고 실제 검증은 {@link AdminSessionGate} 한 곳이 한다.
 * 그 게이트도 스스로 판정하지 않고 토큰 검증 소유자에게 위임한다.
 */
@Documented
@Inherited
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAdminSession {
}
