# GovBiz Docker Compose

Docker Compose는 React 개발 서버, Core API, AI Service, 원본 카탈로그용 MySQL과 의미 검색용
Qdrant를 함께 실행하는 로컬 개발 구성입니다. 운영 배포·TLS·인증 구성은 포함하지 않습니다.
전체 기술 선택과 데이터 흐름은 [프로젝트 기술 문서](../docs/technology.md)를 참고하세요.

```text
Browser (127.0.0.1:5173)
  → Vite web container
      → /api proxy
          → core-api:8080
              ├→ mysql:3306 (사용자 검색 카탈로그)
              ├→ https://apis.data.go.kr (백그라운드 동기화)
              └→ ai-service:8000
                    ├→ qdrant:6333 (현재 공고의 벡터 색인)
                    └→ https://api.openai.com (텍스트 임베딩·후보 점수화)
```

## 주소 규칙

| 호출 주체 | 사용하는 주소 | 이유 |
|---|---|---|
| 브라우저의 React | `/api/...` | Vite 프록시가 같은 Origin 요청을 Core API로 중계 |
| web 컨테이너 | `http://core-api:8080` | Compose 내부 DNS |
| Core API 컨테이너 | `http://ai-service:8000` | Compose 내부 DNS |
| Core API 컨테이너 | `jdbc:mysql://mysql:3306/govbiz` | 사용자 검색용 지원사업 카탈로그 MySQL |
| Core API 컨테이너 | `https://apis.data.go.kr` | 백그라운드 동기화 전용 실제 기업마당 공고 upstream |
| AI Service 컨테이너 | `https://api.openai.com/v1` | 공고·질의 임베딩 및 후보 점수화 |
| AI Service 컨테이너 | `http://qdrant:6333` | 공고 임베딩 저장·의미 검색 |
| Host 터미널 | `http://127.0.0.1:8080` | Host에 공개된 Core API 포트 |
| Host의 DB 도구 | `127.0.0.1:3306` | loopback으로만 공개한 MySQL 포트 |
| Host 터미널 | `http://127.0.0.1:6333` | loopback으로만 공개한 개발용 Qdrant API |

`core-api`, `ai-service`, `mysql`, `qdrant`는 컨테이너 네트워크 안에서만 해석되는 이름입니다. 브라우저
JavaScript가 `http://core-api:8080`을 직접 호출하면 실패합니다.

## 실행

Docker Engine과 Compose v2가 필요합니다. 저장소 루트에서 `.env.example`을 `.env`로 복사하고,
공공데이터포털에서 발급한 일반 인증키와 필수 OpenAI 키를 넣습니다.
Encoding 또는 Decoding 키를 사용할 수 있으며 Core API가 호출 전에 정규화합니다. `.env`는 Git에서
제외되며 각 키는 필요한 컨테이너에만 전달됩니다.

```dotenv
DATA_GO_KR_SERVICE_KEY=발급받은_인증키
OPENAI_API_KEY=발급받은_OpenAI_API_키
```

| 환경변수 | 기본값 | 용도 |
|---|---|---|
| `BIZINFO_API_BASE_URL` | `https://apis.data.go.kr` | 백그라운드 동기화가 사용하는 공고 API origin. 로컬 스텁 검증 외에는 변경하지 않음 |
| `BIZINFO_API_CONNECT_TIMEOUT` | `2s` | 동기화 외부 API 연결 제한시간 |
| `BIZINFO_API_READ_TIMEOUT` | `10s` | 동기화 외부 API 응답 제한시간 |
| `BIZINFO_SYNC_ENABLED` | `true` | `false`이면 기업마당 공고 자동 동기화를 실행하지 않음 |
| `BIZINFO_SYNC_INITIAL_DELAY` | `PT0S` | 앱 시작 시 스케줄러의 첫 동기화까지의 ISO-8601 기간. 기본값은 즉시 실행 |
| `BIZINFO_SYNC_FIXED_DELAY` | `PT6H` | 이전 동기화가 끝난 뒤 다음 동기화까지의 ISO-8601 기간 |
| `OPENAI_API_KEY` | 없음(필수) | AI Service만 사용하는 OpenAI 인증키 |
| `OPENAI_MODEL` | `gpt-5.6-luna` | 현재 Compose에 설정된 Agent의 Structured Output 모델 |
| `LLM_MODEL_TIMEOUT_SECONDS` | `25.0` | OpenAI 모델 호출 한 번의 제한시간(초) |
| `LLM_RUN_TIMEOUT_SECONDS` | `30.0` | Agent의 `Runner.run` 실행 제한시간(초) |
| `AI_SERVICE_READ_TIMEOUT` | `35s` | Core API의 AI Health·점수화·원문 근거 답변 읽기 제한시간 |
| `AI_SEMANTIC_SEARCH_READ_TIMEOUT` | `30s` | Core API의 색인·의미 검색 요청 제한시간 |
| `OPENAI_EMBEDDING_MODEL` | `text-embedding-3-small` | 공고·질의 임베딩 모델 |
| `OPENAI_EMBEDDING_DIMENSIONS` | `1536` | 임베딩 차원 수. 모델·차원이 바뀌면 별도 컬렉션을 사용 |
| `EMBEDDING_TIMEOUT_SECONDS` | `15` | OpenAI 임베딩 호출 제한시간(초) |
| `QDRANT_TIMEOUT_SECONDS` | `5` | Qdrant 요청 제한시간(초) |
| `QDRANT_HOST_PORT` | `6333` | Host loopback에 연결할 Qdrant 포트 |
| `SUPPORT_PROGRAM_INDEX_ENABLED` | `true` | MySQL 현재 공고의 누락 벡터 정기 복구 여부. 기업마당 동기화의 사전 색인은 중지하지 않음 |
| `SUPPORT_PROGRAM_INDEX_INITIAL_DELAY` | `PT0S` | 앱 시작 시 스케줄러의 첫 벡터 복구까지의 기간 |
| `SUPPORT_PROGRAM_INDEX_FIXED_DELAY` | `PT1M` | 이전 복구 완료 뒤 다음 실행까지의 기간 |
| `APP_CORS_ALLOWED_ORIGIN` | `http://127.0.0.1:5173` | Compose에서 Core API가 허용할 브라우저 origin |
| `MYSQL_DATABASE` | `govbiz` | MySQL 초기 데이터베이스 이름 |
| `MYSQL_USER` | `govbiz` | Core API의 MySQL 사용자 |
| `MYSQL_PASSWORD` | `govbiz-local` | Core API의 MySQL 비밀번호. 공유 환경에서는 secret으로 교체 |
| `MYSQL_ROOT_PASSWORD` | `govbiz-root-local` | MySQL 초기 root 비밀번호. 공유 환경에서는 secret으로 교체 |
| `MYSQL_HOST_PORT` | `3306` | Host loopback에 연결할 MySQL 포트 |

OpenAI는 공고 임베딩과 후보 점수화의 필수 의존성입니다. 키가 없으면 Compose 설정과 AI Service
시작이 실패하고, 실행 중 AI 호출이 실패하면 Core API가 오류 종류에 따라 502·503·504로
전달합니다. 모델 `25s`·전체 Agent 실행 `30s`·Core API 읽기 `35s` 순서로 제한시간을 유지합니다.
모델·Agent 설정은 추천 점수화와 원문 근거 답변이 공유합니다. 검색 화면은 순차적인 의미 검색과
점수화의 Core 읽기 제한 `30s + 35s`에 여유를 둔 `70s` 후 요청을 취소하고 재시도를 허용합니다.
기존 `.env`나 서버 환경변수에 예전 제한시간을 지정했다면 기본값보다 우선하므로 직접 갱신해야 합니다.

공고 색인에도 OpenAI 임베딩 비용이 발생합니다. 신규·변경된 검색용 텍스트만 임베딩하며, 동일 내용은
Qdrant에 저장된 벡터를 재사용합니다. 의미 검색·색인 API의 전체 제한시간은 최대 25초이며 Core의
전용 읽기 제한시간 `30s`보다 짧게 유지합니다. 개발용 Qdrant는 인증 없이 loopback에만 공개됩니다.
외부에 배포할 때는 네트워크 접근 제한과 인증을 별도로 구성해야 합니다.

현재 Compose의 Qdrant 이미지는 `1.17.1`로 고정되어 있습니다. 버전 변경 시에는 데이터가 있는
상태로 중지·재시작한 뒤 검색이 복구되는지도 확인합니다.

Core API는 기본 설정에서 앱 시작 시 초기 지연 `PT0S`로 기업마당 공고 동기화를 실행하고, 동기화 완료 시점부터
6시간 뒤에 다시 실행합니다. 전체 페이지 수집·검증과 새 공고의 벡터 색인이 성공한 뒤 MySQL
카탈로그를 한 transaction으로 갱신합니다. 외부 호출·색인 실패 시 이전 MySQL 카탈로그를 유지하고,
더 최신 동기화가 시작되었다면 오래된 실행 결과는 공개하지 않습니다. 사용자 검색은 MySQL을 읽고
기업마당 API를 직접 호출하지 않습니다. 로컬에서 자동 동기화를 끄려면 `.env`에 `BIZINFO_SYNC_ENABLED=false`를
설정합니다. 이 경우 기존 카탈로그는 검색할 수 있지만 새 공고는 갱신되지 않습니다.

별도 벡터 복구 스케줄러는 MySQL의 현재 공고를 기본 1분 주기로 확인하고 누락된 벡터를 채웁니다.
새 동기화 데이터는 벡터 준비 후 MySQL에 공개되므로, 통상적인 공고 변경 때문에 미완성 색인이
검색에 노출되지는 않습니다. Qdrant 데이터 유실이나 기존 DB의 색인 미완료로 검색 대상 공고의 벡터가
부족하면 자연어 검색은 503을 반환합니다. 최초 카탈로그가 아직 비어 있으면 검색 결과도 빈 목록입니다.
빈 검색어의 최신 목록과 상세 조회는 Qdrant·OpenAI 없이 MySQL에서 반환합니다.

현재 작업은 오래된 벡터를 자동 삭제하지 않습니다. 검색할 때 현재 공고 식별자·내용 해시와 일치하는
벡터만 선택하며, 삭제 없는 복구로 겹치는 동기화 실행이 서로의 벡터를 지우는 일을 방지합니다.
오래된 벡터의 안전한 정리는 후속 과제입니다. `SUPPORT_PROGRAM_INDEX_ENABLED=false`는 정기 복구만
중지하며 기업마당 동기화의 사전 색인이나 자연어 검색의 Qdrant 의존성을 없애지 않습니다.

저장소 루트에서 실행합니다.

```bash
docker compose --env-file .env --file infrastructure/compose.yaml up --build
```

| 주소 | 용도 |
|---|---|
| `http://127.0.0.1:5173` | React Vite 개발 서버 |
| `http://127.0.0.1:5173/api/v1/health` | Vite 프록시를 거친 Core API Health |
| `http://127.0.0.1:5173/api/v1/support-programs/search?query=%EC%88%98%EC%B6%9C&acceptingOnly=true` | Vite 프록시를 거친 실제 공고 검색 |
| `http://127.0.0.1:5173/api/v1/sample-items/prepare` | Vite 프록시를 거친 SampleItem 준비 API (`POST`, JSON 본문 필요) |
| `http://127.0.0.1:5173/api/v1/health/ai-service` | Core API를 거친 AI Service Health |

AI Service의 `/internal/v1/support-program-rankings/rank`와 `/internal/v1/support-program-index/*`는
Compose 네트워크 내부에서 Core API만 호출합니다. Host나 브라우저에 AI Service 포트를 공개하지 않습니다.

### 중지와 데이터 초기화

일반 중지는 named volume의 MySQL·Qdrant 데이터를 유지합니다.

```bash
docker compose --env-file .env --file infrastructure/compose.yaml down --remove-orphans
```

로컬 데이터를 의도적으로 초기화할 때만 다음 명령을 사용합니다. `mysql-data`, `qdrant-data`,
`web-node-modules` volume을 삭제하므로 필요한 데이터는 먼저 백업해야 합니다. 삭제한 카탈로그와
색인은 다시 수집·구축해야 하며, 실제 OpenAI를 쓰는 색인 재구축에는 비용이 발생합니다.

```bash
docker compose --env-file .env --file infrastructure/compose.yaml down --volumes --remove-orphans
```

## 통합 smoke

Docker Engine·Compose v2·Bash·curl이 필요합니다. 다음 스크립트는 별도 Compose 프로젝트
`govbiz-verify`를 사용해 이미지를 빌드하고 다음을 확인합니다. 첫 실행의 이미지·의존 패키지
다운로드에는 네트워크가 필요합니다.

```bash
./infrastructure/scripts/verify-compose.sh
```

검증 스크립트는 `verification` profile의 `bizinfo-stub`과 `openai-stub`을 사용합니다. MySQL·Qdrant는
실제 서버이고, 외부 공고·임베딩·점수화 응답만 고정된 가상 자료입니다. 공고 27개 중 최신 20개 밖에
있는 AI 공고가 `서울 AI` 검색으로 선택되는지 확인합니다. 이는 서비스 연결과 후보 누락 수정의 검증이지,
실제 OpenAI 모델의 검색 품질 측정이 아닙니다.

스텁 주소와 더미 인증키를 강제하므로 개인 키를 외부로 전송하거나 실제 OpenAI 비용을 발생시키지 않습니다.
기업마당 스텁은 디코딩된 키도 확인합니다. 기업마당 동기화와 색인은 `PT2S` 주기로 실행합니다.
검증용 MySQL·Qdrant는 기본 Host 포트 `13306`·`16333`을 사용하며 각각
`VERIFY_COMPOSE_MYSQL_HOST_PORT`·`VERIFY_COMPOSE_QDRANT_HOST_PORT`로 변경할 수 있습니다.

1. Vite Web 응답이 200인지 확인합니다.
2. Vite 프록시를 거친 Core API Health가 200인지 확인합니다.
3. 동기화된 공고 행이 MySQL에 존재하는지 확인한 뒤 로컬 스텁을 중지하고, 빈 검색어 GET이
   Web → Core API → MySQL 카탈로그를 거쳐 이를 반환하는지 확인합니다. 이 검색 요청은 로컬 스텁을
   직접 호출하지 않으며, 더미 OpenAI 키도 외부로 보내지 않습니다.
4. 자연어 검색이 Web → Core → MySQL → AI Service → Qdrant → 점수화를 거쳐 오래된 관련 공고를 반환하는지 확인합니다.
5. Qdrant를 중지하면 자연어 검색은 503, 빈 검색어 목록은 200인지 확인하고, 재시작 뒤 검색 복구를 확인합니다.
6. SampleItem 준비 POST가 200과 `READY_FOR_PROCESSING`을 반환하는지 확인합니다.
7. Core API를 통한 AI Service Health가 200인지 확인합니다.
8. AI Service를 중지했을 때 Core Health는 200, AI Health와 자연어 검색은 503(연결 불가) 또는 504(시간 초과)인지 확인합니다.
9. AI Service 재시작 후 Core API 재시작 없이 Health와 자연어 검색이 복구되는지 확인합니다.

기본적으로 5173과 8080을 사용하므로, 같은 포트를 쓰는 다른 Compose 프로젝트는 중지한 뒤 실행하세요.
스크립트는 종료 시 검증용 컨테이너와 volume을 삭제합니다. 조사 목적으로 유지하려면
`VERIFY_COMPOSE_KEEP_RUNNING=true`로 실행합니다. `VERIFY_COMPOSE_PROJECT_NAME`을 변경할 경우 기존
개발·운영 프로젝트 이름을 사용하지 마세요. 해당 이름의 스택도 중지·삭제 대상이 됩니다.
