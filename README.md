# Returnloop

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
| 포트 | `3306` |
| 데이터베이스 | `returnloop` |
| 사용자 | `returnloop` |
| 비밀번호 | `returnloop_local` |
| root 비밀번호 | `root_local` |

위 계정과 비밀번호는 로컬 개발 전용입니다. `local` 프로필은 Hibernate의
`ddl-auto=update`로 엔티티에 맞게 스키마를 갱신합니다.

### 포트 및 계정 변경

```bash
cp .env.example .env
# .env의 필요한 값을 수정합니다. 포트 충돌 시 MYSQL_PORT=3307 등으로 변경합니다.
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
