# ReturnLoop

환불 처리의 동시성, 외부 PG 응답 유실, 장애 복구와 정합성을 검증하는 개인 백엔드 프로젝트입니다.
실제 운영 서비스가 아닌 재현 가능한 실험 환경을 목표로 합니다.

## 현재 구현 및 검증 범위

- Flyway로 Payment / Refund 스키마 및 DB 제약 관리
- Payment / Refund 엔티티 및 Repository 구현
- 환불 생성 API, 기존 Refund 반환, 중복 충돌 후 별도 트랜잭션 조회 구현
- 2026-11-17 전체 테스트 52건 통과. MySQL 8.4에서 서비스 요청 100건을 워커 10개로 처리했고, 별도 테스트에서 두 생성 트랜잭션의 UNIQUE 충돌과 복구를 확인했습니다.
- PG 호출과 실제 금전 처리, HTTP 부하 및 다중 인스턴스 동작은 E01 검증 범위 밖입니다.

## 환불 생성 API

`POST /payments/{paymentId}/refund`는 요청 본문 없이 호출합니다. 최초 생성은
`201 Created`, 같은 결제의 기존 환불 반환은 `200 OK`입니다. 응답에는
`id`, `paymentId`, `amount`, `status`가 들어 있습니다. 최초 환불은
`PENDING`으로 저장되며 결제는 `PAID`를 유지합니다.

| HTTP 상태 | 오류 코드 |
| --- | --- |
| 404 | `PAYMENT_NOT_FOUND` |
| 409 | `PAYMENT_REFUND_STATE_INCONSISTENT` |
| 500 | `INTERNAL_SERVER_ERROR` |

## 설계와 Engineering Evidence

- [핵심 설계와 선택 이유](docs/DESIGN.md): 합의된 원칙, 대안 및 구현 단계별 보장 범위
- [스키마 및 DB 제약 Evidence](docs/evidence/E01-01-schema.md): 실행 조건, 관측 결과와 미검증 항목
- [엔티티 및 Repository Evidence](docs/evidence/E01-02-entities.md): 스키마 매핑, 저장·재조회 및 지연 로딩 검증
- [환불 Service Evidence](docs/evidence/E01-03-service.md): 실제 커밋·기존 환불 반환과 중복 오류 분기 검증

설계 방향과 실제 구현·검증을 구분하여 기록합니다. 세부 작업 메모와 논의 초안은 공개 문서에서 제외합니다.

## 테스트

Docker가 실행 중인 환경에서 다음 명령을 실행합니다.

```bash
./gradlew clean test
```

Testcontainers가 테스트 전용 MySQL 8.4 컨테이너를 생성하고 종료 시 제거합니다.
접속 정보는 `@ServiceConnection`으로 자동 설정하며 임의의 호스트 포트를 사용합니다.
개발용 Compose MySQL 실행이나 `local` 프로필, `.env` 설정은 필요하지 않습니다.
CI에서도 Java 21과 Docker 데몬 접근 권한이 필요하며, 최초 실행 시 컨테이너 이미지를
다운로드할 수 있어야 합니다.

## 로컬 개발

Java 21과 Docker Compose v2가 필요합니다. 프로젝트 루트에서 실행합니다.

```bash
docker compose up -d --wait
./gradlew bootRun --args='--spring.profiles.active=local'
```

MySQL 8.4를 사용하며 기본 접속 정보는 다음과 같습니다.

| 항목 | 값 |
| --- | --- |
| 호스트 | `127.0.0.1` |
| 포트 | `3307` |
| 데이터베이스 | `returnloop` |
| 사용자 | `returnloop` |
| 비밀번호 | `returnloop_local` |
| root 비밀번호 | `root_local` |

위 계정과 비밀번호는 로컬 개발 전용입니다. 스키마는 Flyway가
`src/main/resources/db/migration`의 버전별 SQL로 관리합니다.
Hibernate는 공통 `ddl-auto=validate` 설정으로 엔티티와 스키마의 호환성을 검사합니다.
적용된 마이그레이션은 수정하지 않고 이후 변경은 새 버전 SQL로 추가합니다.
기존 테이블이 있지만 Flyway 이력이 없는 DB는 자동 baseline 또는 초기화하지 않습니다.
이 경우 기존 스키마와 데이터를 확인한 후 별도 적용 절차를 결정해야 합니다.

### 포트 및 계정 변경

```bash
cp .env.example .env
# .env의 필요한 값을 수정합니다. 포트 충돌 시 MYSQL_PORT=3308 등으로 변경합니다.
set -a
source .env
set +a
docker compose up -d --wait
./gradlew bootRun --args='--spring.profiles.active=local'
```

Compose는 `.env`를 자동으로 읽지만 Spring Boot는 읽지 않으므로,
변경한 값은 위처럼 셸에 내보내거나 IDE 실행 환경 변수에 등록해야 합니다.
`.env`는 Git 추적 대상에서 제외됩니다.

### 상태 확인 및 종료

```bash
docker compose ps
docker compose logs -f mysql
docker compose exec mysql mysql -u returnloop -p returnloop
docker compose down
```

`down` 후에도 데이터는 볼륨에 보존됩니다. 데이터베이스와 계정 초기화 설정은
빈 볼륨으로 처음 시작할 때만 적용됩니다. 모든 로컬 데이터를 삭제하고 다시
초기화하려면 다음 명령을 실행합니다.

```bash
docker compose down -v
docker compose up -d --wait
```
