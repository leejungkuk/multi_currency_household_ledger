package archfixture;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 컨트롤러가 아닌 부모에 선언된 매핑도 검사하는지 확인하는 음성 픽스처. */
public class InheritedUnboundUuidControllerFixture {

    public static class Parent {

        @GetMapping("/archfixture/inherited-unbound")
        public Object unbound(UUID memberId) {
            return memberId;
        }
    }

    @RestController
    public static class Child extends Parent {}
}
