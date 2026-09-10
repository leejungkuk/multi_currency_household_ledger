package archfixture;

import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * {@code ArchitectureTest} 의 UUID 바인딩 규칙이 실제로 위반을 잡는지 확인하는 <b>음성 픽스처</b>. 규칙이 조용히 죽어도
 * 실 컨트롤러 클래스 5개·매핑 메서드 26개는 전부 정상이라 아무것도 실패하지 않으므로, 위반 하나를 일부러 만들어 둔다.
 *
 * <p>베이스 패키지({@code com.self.multi_currency_household_ledger}) <b>밖</b>에 두는 것이 핵심이다 — 안에 두면
 * {@code @SpringBootTest} 와 {@code :module-api:generateApiSnapshot} 이 이 클래스를 빈으로 등록해
 * {@code api-contract/openapi.json} 에 {@code /archfixture} 가 새어 나간다.
 *
 * <p>규칙은 클래스 어노테이션과 무관하게 {@code @RequestMapping} 메타 어노테이션이 붙은 메서드를 검사한다.
 * {@code @GetMapping} 으로 그 메타 어노테이션 경로를 고정한다 — 직접 어노테이션만 보면 이 우회가 통과한다.
 */
@Controller
public class UnboundUuidControllerFixture {

    /** 어노테이션 없는 {@code UUID} 는 쿼리스트링에서 그대로 바인딩돼 즉시 IDOR 이다. */
    @GetMapping("/archfixture/unbound")
    @ResponseBody
    public Object unbound(UUID memberId) {
        return memberId;
    }
}
