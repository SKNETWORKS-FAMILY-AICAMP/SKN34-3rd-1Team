# GovBiz Docker Compose

Docker Compose는 React, Core API, AI Service와 지원사업 카탈로그용 MySQL을 한 번에 실행하고 실제 서비스 경계를 검증합니다.

```text
Browser (127.0.0.1:5173)
  → Vite web container
      → /api proxy
          → core-api:8080
              ├→ mysql:3306 (사용자 검색 카탈로그)
              ├→ https://apis.data.go.kr (백그라운드 동기화)
              └→ ai-service:8000
                    └→ https://api.openai.com (LLM 활성 시)
```

## 주소 규칙

| 호출 주체 | 사용하는 주소 | 이유 |
|---|---|---|
| 브라우저의 React | `/api/...` | Vite 프록시가 같은 Origin 요청을 Core API로 중계 |
| web 컨테이너 | `http://core-api:8080` | Compose 내부 DNS |
| Core API 컨테이너 | `http://ai-service:8000` | Compose 내부 DNS |
| Core API 컨테이너 | `jdbc:mysql://mysql:3306/govbiz` | 사용자 검색용 지원사업 카탈로그 MySQL |
| Core API 컨테이너 | `https://apis.data.go.kr` | 백그라운드 동기화 전용 실제 기업마당 공고 upstream |
| AI Service 컨테이너 | `https://api.openai.com` | 공고 후보 점수화 typed agent |
| Host 터미널 | `http://127.0.0.1:8080` | Host에 공개된 Core API 포트 |
| Host의 DB 도구 | `127.0.0.1:3306` | loopback으로만 공개한 MySQL 포트 |

`core-api`, `ai-service`, `mysql`은 컨테이너 네트워크 안에서만 해석되는 이름입니다. 브라우저
JavaScript가 `http://core-api:8080`을 직접 호출하면 실패합니다.

## 실행

저장소 루트의 `.env`에 공공데이터포털에서 발급한 일반 인증키와 필수 OpenAI 키를 넣고 실행합니다.
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
| `BIZINFO_SYNC_INITIAL_DELAY` | `PT0S` | 앱 준비 뒤 첫 동기화까지의 ISO-8601 기간. 기본값은 즉시 실행 |
| `BIZINFO_SYNC_FIXED_DELAY` | `PT6H` | 이전 동기화가 끝난 뒤 다음 동기화까지의 ISO-8601 기간 |
| `OPENAI_API_KEY` | 없음(필수) | AI Service만 사용하는 OpenAI 인증키 |
| `OPENAI_MODEL` | [`gpt-5.6-luna`](https://developers.openai.com/api/docs/models/gpt-5.6-luna) | Agent의 Structured Output 모델 |
| `LLM_MODEL_TIMEOUT_SECONDS` | `8.0` | OpenAI 모델 호출 한 번의 제한시간(초) |
| `LLM_RUN_TIMEOUT_SECONDS` | `10.0` | 후보 점수 검증을 포함한 전체 agent run 제한시간(초) |
| `AI_SERVICE_READ_TIMEOUT` | `12s` | Core API의 AI Service 읽기 제한시간 |
| `APP_CORS_ALLOWED_ORIGIN` | `http://127.0.0.1:5173` | Compose에서 Core API가 허용할 브라우저 origin |
| `MYSQL_DATABASE` | `govbiz` | MySQL 초기 데이터베이스 이름 |
| `MYSQL_USER` | `govbiz` | Core API의 MySQL 사용자 |
| `MYSQL_PASSWORD` | `govbiz-local` | Core API의 MySQL 비밀번호. 공유 환경에서는 secret으로 교체 |
| `MYSQL_ROOT_PASSWORD` | `govbiz-root-local` | MySQL 초기 root 비밀번호. 공유 환경에서는 secret으로 교체 |
| `MYSQL_HOST_PORT` | `3306` | Host loopback에 연결할 MySQL 포트 |

OpenAI는 공식 공고 후보 점수화의 필수 의존성입니다. 키가 없으면 Compose 설정과 AI Service 시작이
실패하고, 실행 중 OpenAI 평가가 실패하면 Core API가 안전한 502·503·504로 전달합니다. Kotlin의
단어 사전이나 고정 가중치로 성공을 가장하지 않습니다. Core API 읽기 제한시간 `12s`는 전체 agent
run 제한시간 `10s`보다 길게 유지합니다.

Core API는 기본 설정에서 앱 준비 뒤 기업마당 공고 동기화를 한 번 실행하고, 동기화 완료 시점부터
6시간 뒤에 다시 실행합니다. 동기화 실패는 Core API를 중지시키지 않으며 이전 MySQL 카탈로그를
유지한 채 다음 실행을 기다립니다. 사용자 검색은 이 MySQL 카탈로그를 읽고, 기업마당 API를 직접
호출하지 않습니다. 로컬에서 자동 동기화를 끄려면 `.env`에 `BIZINFO_SYNC_ENABLED=false`를
설정합니다. 이 경우 기존 카탈로그는 검색할 수 있지만 새 공고는 갱신되지 않습니다.

저장소 루트에서 실행합니다.

```bash
docker compose --env-file .env --file infrastructure/compose.yaml up --build
```

| 주소 | 용도 |
|---|---|
| `http://127.0.0.1:5173` | React Vite 개발 서버 |
| `http://127.0.0.1:5173/api/v1/health` | Vite 프록시를 거친 Core API Health |
| `http://127.0.0.1:5173/api/v1/support-programs/search?query=%EC%88%98%EC%B6%9C&acceptingOnly=true` | Vite 프록시를 거친 실제 공고 검색 |
| `http://127.0.0.1:5173/api/v1/sample-items/prepare` | Vite 프록시를 거친 SampleItem 준비 API |
| `http://127.0.0.1:5173/api/v1/health/ai-service` | Core API를 거친 AI Service Health |

`POST http://ai-service:8000/internal/v1/support-program-rankings/rank`는 Compose 네트워크 내부에서 Core API만
호출합니다. Host나 브라우저에 포트를 공개하지 않습니다.

중지와 정리:

```bash
docker compose --file infrastructure/compose.yaml down --volumes --remove-orphans
```

`mysql-data` volume도 함께 삭제되므로 위 명령은 로컬 지원사업 카탈로그를 초기화합니다. 데이터를
유지하려면 `--volumes`를 빼고 중지하세요.

## 통합 smoke

다음 스크립트는 별도 Compose 프로젝트를 사용해 이미지를 빌드하고 다음을 확인합니다.

```bash
./infrastructure/scripts/verify-compose.sh
```

검증 스크립트는 `verification` profile의 `bizinfo-stub`을 사용하고, `BIZINFO_API_BASE_URL`과
`DATA_GO_KR_SERVICE_KEY`를 각각 스텁 주소와 percent-encoded dummy key로 강제합니다. 시작 동기화가
스텁의 고정 공고를 MySQL에 저장한 뒤 스텁을 중지하고, 빈 검색어가 여전히 이 카탈로그를 읽는지
확인합니다. 검증은 자동 동기화를 켜고 `PT2S` 재시도 주기를 강제하므로 개발자 `.env`의 동기화 설정에
영향받지 않습니다. 스텁은 디코딩된 키를 확인하므로 Encoding 키가 외부 요청에서 정확히 한 번만
인코딩되는 경로도 검증합니다. 따라서 루트 `.env`의 개인 키를 사용하거나 외부로 보내지 않고,
공공데이터포털의 네트워크 상태나 응답 변경에도 영향받지 않습니다. 검증에서는 실제 OpenAI에 전송하지
않는 더미 키와 LLM 2.5초/Core 3초 제한을 사용합니다. AI Service 중지 중 자연어 검색이 503으로
실패하는 필수 의존성 계약도 확인합니다. 일반 실행은 `.env`의 실제 OpenAI 설정을 사용합니다. 검증용
MySQL은 기본적으로 Host의 `13306` 포트를 사용하므로 로컬 `3306`과 충돌하지 않으며, 필요하면
`VERIFY_COMPOSE_MYSQL_HOST_PORT`로 바꿀 수 있습니다.

1. Vite Web 응답이 200인지 확인합니다.
2. Vite 프록시를 거친 Core API Health가 200인지 확인합니다.
3. 동기화된 공고 행이 MySQL에 존재하는지 확인한 뒤 로컬 스텁을 중지하고, 빈 검색어 GET이
   Web → Core API → MySQL 카탈로그를 거쳐 이를 반환하는지 확인합니다. 이 검색 요청은 로컬 스텁을
   직접 호출하지 않으며, 더미 OpenAI 키도 외부로 보내지 않습니다.
4. SampleItem 준비 POST가 200과 `READY_FOR_PROCESSING`을 반환하는지 확인합니다.
5. Core API를 통한 AI Service Health가 200인지 확인합니다.
6. AI Service를 중지했을 때 Core Health는 200, AI Health와 자연어 검색은 503인지 확인합니다.
7. AI Service 재시작 후 Core API 재시작 없이 Health가 복구되는지 확인합니다.

기본적으로 5173과 8080을 사용하므로, 같은 포트를 쓰는 다른 Compose 프로젝트는 중지한 뒤 실행하세요.
