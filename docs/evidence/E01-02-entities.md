# E01-02 — 엔티티 및 Repository 검증

## 문제와 선택

2026-11-16. V1 스키마를 변경하지 않고 Java 엔티티에서 동일한 데이터를 저장·조회할 수
있는지 검증한다. 이 단위는 서비스, API 및 동시성 처리까지 포함하지 않는다.

- Payment / Refund의 식별자 및 금액은 Long, ID 생성은 IDENTITY 사용
- 상태는 EnumType.STRING 및 JdbcTypeCode(VARCHAR)로 V1의 VARCHAR와 명시적으로 매핑
- Refund에서 Payment를 단방향 OneToOne / LAZY로 참조하고 cascade 저장·삭제는 지정하지 않음
- Refund 생성자가 원본 금액을 복사하고 PENDING, UUID 멱등 키를 설정
- 금액·결제 참조·멱등 키에 setter를 제공하지 않고 updatable=false 적용

Hibernate는 MySQL에서 문자열 enum의 기본 SQL 타입으로 네이티브 ENUM을 사용할 수 있어
기존 VARCHAR 스키마에 맞는 JDBC 타입을 지정했다.
근거: [Hibernate 공식 안내](https://docs.hibernate.org/stable/core/introduction/html_single/).

## 실행 및 관측

```bash
./gradlew clean test
git diff --check
```

- Testcontainers mysql:8.4, local 프로필 없이 공통 ddl-auto=validate 사용
- 각 테스트 클래스의 별도 MySQL 컨테이너에 Flyway V1 적용
- 최종 실행 BUILD SUCCESSFUL: 기존 DB 제약 15건 + 신규 8건 = 23건, 실패 0건
- 애플리케이션 시작 및 스키마 검증 통과
- 저장 후 flush / clear하여 1차 캐시 대신 DB에서 엔티티를 다시 읽고 값 확인
- Payment의 PAID, Refund의 PENDING이 실제 DB에 문자열로 저장됨을 JDBC 조회로 확인
- 27,000원 결제의 Refund에 같은 금액 저장 및 생성한 UUID 키의 재조회 후 보존 확인
- PersistenceUnitUtil로 결제 참조가 최초 미로딩이고 금액 접근 후 로딩됨을 확인
- Refund가 없는 Payment 조회 시 Optional.empty 확인
- 0 / 음수 Payment 생성과 null Payment를 전달한 Refund 생성 거부 확인
- JPA 경유 중복 환불 INSERT에서 uk_refund_payment_id 위반 확인
- 테스트 및 보조 메서드는 한국어, main의 식별자와 예외 메시지는 영어로 작성

## 관계 매핑 검토 후 변경

초기 구현은 ManyToOne과 DB UNIQUE를 함께 사용했다. DB는 Payment당 Refund 하나를
보장했지만 Java 매핑은 여러 Refund가 같은 Payment를 참조하는 관계를 표현했다.
업무 규칙과 코드의 관계 표현을 일치시키기 위해 검토·합의 후 단방향 OneToOne으로 변경했다.
Payment 역방향 필드나 V1 스키마 변경은 추가하지 않았다.

변경 후 ./gradlew test 재실행 결과 23건 통과. 기존 저장·조회, 지연 로딩,
중복 환불 거부 테스트도 통과했다. 지연 로딩 확인은 현재 Hibernate / MySQL 환경의 관측 결과다.

원본 결과는 build/test-results/test의 TEST-*.xml 및 build/reports/tests/test/index.html에 생성된다.
해당 디렉터리는 Git에 포함하지 않으며 clean 실행 시 삭제될 수 있다.

## 한계

- 테스트는 트랜잭션 내 저장·재조회를 확인하고 종료 시 롤백한다. 독립 트랜잭션 간 가시성이나 커밋 경쟁을 검증한 것이 아님
- JPA 무결성 예외 발생 후 해당 테스트는 추가 DB 작업을 하지 않고 종료한다. 별도 트랜잭션의 기존 Refund 반환은 서비스 단계에서 구현할 예정
- 금액 복사는 생성 경로에서 검증했다. 임의 SQL에 의한 금액 변경·불일치까지 DB가 방지하지는 않음
- Payment 상태에 따른 생성 허용, 중복 요청 응답, 상태 전이 및 동시성 검증은 후속 서비스 단계
- 직접 DB 조작으로 모든 상태 전이 조합을 재현한 것은 아님
- 개발용 Compose DB 및 데이터는 변경하지 않음
