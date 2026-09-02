# GovBiz Core API

Spring Boot 기반의 GovBiz 브라우저 공개 API입니다. React의 업무 요청을 받고, 공공데이터포털의
기업마당 공고와 내부 AI Service 호출 결과를 안정적인 공개 HTTP 계약으로 변환합니다.

## 현재 API

| Endpoint | 용도 |
|---|---|
| `GET /api/v1/health` | Core API 자체 상태 |
| `GET /api/v1/health/ai-service` | Core API를 통한 AI Service 상태 |
| `GET /api/v1/support-programs/search?query=수출&acceptingOnly=true` | 기업마당 실제 공고 검색 |
| `POST /api/v1/sample-items/prepare` | 예제 수직 슬라이스의 입력 검증과 준비 상태 반환 |

지원사업 검색의 요청·응답 필드, 날짜·상태 의미와 오류 코드는
[지원사업 검색·추천 HTTP 계약](../../docs/support-program-search-contract.md)을 참고하세요. 검색 결과는
LLM 총점 순으로 최대 5개를 반환합니다.

`SampleItem`의 정확한 요청·응답은 [SampleItem 계약](../../docs/sample-item-contract.md)을
참고하세요.

현재 SampleItem은 실제 지원사업 검색과 무관한 계층 연결 구조 예제입니다.

## 구조

```text
supportprogram/
├── controller/             # 지원사업 공개 HTTP API
│   └── dto/                # 지원사업 공개 요청·응답 계약
├── service/
│   ├── search/             # 검색 흐름과 검색 오류
│   └── dto/                # 검증된 검색 실행 결과
├── facade/                 # 후보 조회·AI 점수화 Facade 계약과 구현
│   ├── config/             # Facade 전용 설정
│   └── exception/          # 상위 Service에 전달하는 Facade 실패 계약
├── domain/                 # 지원사업·정규화된 검색 후보 모델과 상태
└── client/
    ├── ai/                 # AI 점수화 Client
    │   └── dto/            # AI 내부 요청·응답 계약
    └── bizinfo/            # 기업마당 HTTP와 외부 응답 해석
        ├── config/         # 기업마당 Client 설정·속성
        ├── dto/            # 기업마당 응답 계약
        ├── exception/      # 기업마당 전용 Client 오류 계약
        ├── helper/         # 기업마당 전용 HTTP·JSON 변환 헬퍼
        └── mapper/         # 기업마당 DTO를 검색 후보로 변환
_sampleitem/
├── controller/             # SampleItem 공개 API
│   └── dto/                # SampleItem 공개 요청·응답 계약
├── service/                # SampleItem 준비 흐름
└── domain/                 # SampleItem 모델과 상태
_health/
└── controller/             # Core API 자체 Health API
    └── dto/                # Core API Health 공개 응답 계약
_health_ai_service/
├── client/                 # AI Service 내부 Health HTTP 호출
│   └── dto/                # AI Service Health 응답 계약
├── controller/             # AI Service Health 공개 API
│   └── dto/                # AI Service Health 공개 응답 계약
└── service/                # AI Service Health 응답 검증
    └── dto/                # 검증된 Health 실행 결과
_common/
├── ai_config/              # 두 AI Client가 공유하는 주소·timeout·RestClient 설정
├── config/                 # 전체 API의 CORS·JSON 정책
├── exception/              # 공통 AI 호출 예외와 ProblemDetail 예외 처리
└── helper/                 # 공통 RestClient 생성·검증과 HTTP 예외 분류 헬퍼
```

Kotlin 기본 패키지는 `ai.govbiz.core`이고 Gradle 프로젝트명은 `govbiz-core-api`입니다.

### 파일 배치 규칙

전송 객체와 모델은 이름에 `Request`, `Response`, `Payload`가 들어가는지만 보고 프로젝트 전체의
한 DTO 폴더에 모으지 않습니다. 그 객체를 만들고 해석하며 책임지는 코드 가까이에 둡니다.

```text
공개 HTTP Request/Response → controller/dto
외부 API Request/Payload  → client/dto
검증된 실행 Result        → service/dto
업무 모델                 → domain
```

| 구분 | 배치 위치 | 현재 예시 |
|---|---|---|
| 브라우저가 Core API에 보내거나 받는 공개 HTTP 계약 | 해당 기능의 `controller/dto` | `SampleItemPreparationRequest`, `SupportProgramSearchResponse` |
| Core가 AI Service·기업마당처럼 다른 시스템과 주고받는 계약 | 해당 외부 시스템의 `client/dto` | `AiSupportProgramRankingRequest`, `BizInfoProgramPayload` |
| 외부 응답 검증과 업무 처리를 마친 애플리케이션 실행 결과 | 해당 기능의 `service/dto` | `AiServiceHealthResult`, `SupportProgramSearchResult` |
| 프레임워크·HTTP 형식과 무관한 업무 개념과 불변식 | 해당 기능의 `domain` | `SampleItem`, `SupportProgram`, `CatalogSupportProgram` |

두 곳에서 같은 필드가 보인다는 이유만으로 계약을 합치거나 `_common`으로 옮기지 않습니다. 실제로
둘 이상의 기능이 같은 의미와 변경 이유를 공유할 때만 공통화를 검토합니다.

### 파일·타입 이름과 Helper 배치 규칙

파일과 타입 이름은 맡은 역할을 접미사로 드러냅니다. 같은 역할은 같은 접미사를 사용하고,
`Support`, `Util`, `Common`처럼 구체적인 동작을 알기 어려운 이름은 사용하지 않습니다.

| 역할 | 접미사 | 현재 예시 |
|---|---|---|
| 공개 HTTP 진입점 | `Controller` | `SupportProgramController` |
| 애플리케이션 흐름·업무 처리 | `Service` | `SupportProgramSearchService` |
| 하위 기능의 호출·검증·변환을 단일 진입점으로 제공 | `Facade` | `BizInfoSupportProgramCatalogFacade`, `AiSupportProgramRankingFacade` |
| 외부 시스템 HTTP 통신 | `Client` | `BizInfoClient`, `HttpAiSupportProgramRankingClient` |
| 경계별 실패 분류 | `Exception` | `BizInfoClientException`, `AiServiceCallException` |
| 외부 DTO를 내부 모델로 변환 | `Mapper` | `BizInfoProgramMapper` |
| Spring Bean 구성 | `Config` | `BizInfoClientConfig` |
| 환경설정 값 | `Properties` | `BizInfoClientProperties` |
| 공개·외부·내부 전송 객체 | `Request`, `Response`, `Payload`, `Result` | `SupportProgramSearchResponse`, `BizInfoProgramPayload` |
| 다른 코드의 반복 작업을 보조 | `Helper` | `HttpCallHelper`, `BizInfoPageDecoderHelper` |

### Facade 배치 규칙

이 프로젝트에서 Facade는 Controller 위에 두는 상위 진입점이 아니라, Service가 복잡한 하위 시스템을
간단하게 사용하도록 감싸는 내부 경계입니다. 기본 의존 방향은 다음과 같이 고정합니다.

```text
Controller → Service → Facade → Client → 외부 시스템
```

- Controller는 Facade나 Client를 직접 호출하지 않고 사용자 유스케이스를 담당하는 Service만 호출합니다.
- Service는 Repository를 직접 사용할 수 있습니다. 외부 하위 시스템의 요청 생성, Client 호출,
  응답 검증, 도메인 변환이 함께 필요할 때 Facade를 사용합니다.
- Facade는 자신을 호출한 상위 Service를 다시 호출하지 않습니다. `Service ↔ Facade` 순환 의존성은
  허용하지 않습니다.
- 단순히 Client 메서드를 한 줄 전달하는 경우에는 Facade를 만들지 않고 Service가 Client를 직접
  사용합니다.

현재 검색 흐름에서 `BizInfoSupportProgramCatalogFacade`는 기업마당 조회·Facade 실패 변환·검색 후보 정규화를
`load` 하나로 감추고, 검색 Service가 그 실패를 공개 검색 오류로 변환합니다. `AiSupportProgramRankingFacade`는 AI 요청 생성·점수화 Client 호출·응답의 공고
ID·점수·순서 검증과 도메인 변환을 `rank` 하나로 감춥니다. 둘 다 단순 전달 객체가 아니라 이 규칙에
맞는 하위 시스템 Facade입니다.

Helper는 사용 범위에 따라 배치합니다.

```text
둘 이상의 기능이 함께 사용 → _common/helper
특정 외부 시스템만 사용   → 해당 client/helper
특정 기능만 사용           → 해당 기능/helper
```

Helper 파일과 `object`·클래스 이름은 `Helper`로 끝냅니다. Helper 안의 함수는
`executeHttpCall`, `buildRestClient`, `decode`처럼 수행 동작을 동사로 표현하며 함수 이름에
`Helper`를 반복해서 붙이지 않습니다. 역할이 이미 `Controller`, `Service`, `Facade`, `Client`, `Mapper`로
명확한 코드는 Helper 디렉터리로 옮기지 않습니다. 공통 사용처가 하나뿐인 코드를
미리 `_common/helper`로 올리지 않습니다.

### AI Health의 Payload·Result·Response를 분리하는 이유

AI Service Health 흐름에는 현재 `status`, `service`라는 동일한 필드를 가진 객체가 세 개 있습니다.
필드 모양이 같더라도 각 객체가 소유하는 경계와 신뢰 수준이 다르기 때문에 의도적으로 분리합니다.

```text
AI Service의 신뢰하지 않는 JSON
  → AiServiceHealthPayload       # client가 역직렬화
  → AiServiceHealthService 검증  # status·service exact 확인
  → AiServiceHealthResult        # 검증된 내부 실행 결과
  → AiServiceHealthController    # 공개 계약으로 변환
  → AiServiceHealthResponse      # 브라우저에 반환하는 JSON
```

| 타입 | 소유 위치 | 의미와 변경 이유 |
|---|---|---|
| `AiServiceHealthPayload` | `_health_ai_service/client/dto` | 외부 AI Service JSON을 그대로 받는 신뢰하지 않는 입력입니다. 누락·`null`·잘못된 값을 검증할 수 있도록 필드가 nullable입니다. |
| `AiServiceHealthResult` | `_health_ai_service/service/dto` | Client 응답이 `status=up`, `service=govbiz-ai-service` 계약을 통과한 뒤 만들어지는 non-null 내부 결과입니다. 향후 확인 시각이나 지연시간 같은 내부 정보가 추가될 수 있습니다. |
| `AiServiceHealthResponse` | `_health_ai_service/controller/dto` | Core API가 브라우저에 보장하는 공개 HTTP 응답입니다. 내부 결과가 확장되어도 공개할 필드만 선택하여 API 형식을 유지합니다. |

현재 `Result`와 `Response`의 필드는 같아서 Controller 변환이 단순합니다.

```kotlin
val result = aiServiceHealthService.getHealth()
return AiServiceHealthResponse(result.status, result.service)
```

하지만 나중에 내부 결과에 `latencyMs`, `checkedAt`을 추가하더라도 공개 응답에 자동으로 노출되지
않습니다. 반대로 공개 JSON의 이름이나 구성을 바꾸더라도 Service 결과를 함께 바꿀 필요가 없습니다.
또한 Service가 Controller의 응답 타입에 의존하지 않으므로 의존 방향도 `controller → service → client`로
유지됩니다. 따라서 이 분리는 코드 중복을 위한 것이 아니라 외부 입력, 검증된 내부 결과, 공개 응답의
서로 다른 계약을 독립적으로 변경하기 위한 경계입니다.

지원사업 검색 관련 코드는 `supportprogram` 기능 디렉터리에서 함께 관리합니다. `service/search`는 검색 흐름과 검색 오류를 소유합니다. `facade`는 `SupportProgramCatalogFacade`·`SupportProgramRankingFacade` 계약과 그 구현을 함께 관리하고, 하위 `config`에는 Facade 전용 설정을, `exception`에는 상위 Service에 전달하는 안정적인 Facade 실패 계약을 둡니다. `BizInfoSupportProgramCatalogFacade`는 기업마당 조회·오류 변환·검색 후보 정규화를 `load` 하나로 감추고, `AiSupportProgramRankingFacade`는 AI 요청 생성·호출·응답 검증·도메인 변환을 `rank` 하나로 감춥니다. 모든 전송 객체를 프로젝트 전체의 한 DTO 폴더에 모으지 않습니다. 브라우저 공개 응답은 `supportprogram/controller/dto`, AI Service 요청·응답은 `supportprogram/client/ai/dto`, 기업마당 응답은 `supportprogram/client/bizinfo/dto`, 검증된 검색 결과는 `supportprogram/service/dto`가 각각 소유합니다. `BizInfoClient`는 인증키·pagination·공공데이터포털 HTTP 전송을 담당하고, `BizInfoPageDecoderHelper`가 허용된 JSON 구조만 DTO로 변환합니다. `client/bizinfo/mapper`의 `BizInfoProgramMapper`는 그 DTO를 검색 후보로 정규화합니다. Client 설정과 속성은 `client/bizinfo/config`, 전용 실패 계약은 `client/bizinfo/exception`, 전용 보조 코드는 `client/bizinfo/helper`에서 관리하고, 접수 상태 계산용 서울 기준 시계는 `supportprogram/facade/config`에 둡니다. Kotlin 단어 사전과 고정 관련도 가중치는 사용하지 않습니다.

계층 연결 예제인 SampleItem도 `_sampleitem/controller → service → domain`으로 독립되어 있으며 공개 요청·응답 형식은 `_sampleitem/controller/dto`가 소유합니다.

Core API 프로세스 자체의 생존 상태는 `_health/controller`, 공개 응답 계약은 `_health/controller/dto`가 담당합니다. AI Service 연결 상태를 확인하는 `_health_ai_service` 기능과는 별개입니다. `_health_ai_service/client/AiServiceHealthClient`는 내부 Health API만 호출하고, 공개 Health 응답은 `_health_ai_service/controller/dto`, 지원사업 점수화 호출은 `supportprogram/client/ai/HttpAiSupportProgramRankingClient`가 담당합니다.

둘 이상의 기능이 실제로 함께 사용하는 코드만 `_common`에 둡니다. 앞의 밑줄은 IDE의 알파벳 정렬에서 공통 코드를 기능보다 위에 표시하려는 프로젝트 규칙입니다. `_common/ai_config`는 AI Service 주소·timeout과 공용 `RestClient` 설정만 담당합니다. `_common/helper`는 범용 `RestClient` 생성·설정 검증과 AI·기업마당 Client가 함께 사용하는 연결 실패·timeout·Spring 응답 해석 실패 분류를 담당합니다. 각 외부 시스템은 이 공통 분류를 자기 예외 계약으로 변환합니다. `_common/exception`은 공통 AI 호출 예외와 공개 ProblemDetail 변환을 담당합니다. 반면 HTTP 204·503·504가 각 기능에서 무엇을 뜻하는지는 각 Client가 판단합니다. `_common/config`는 전체 API의 JSON·CORS 정책을 담당합니다.

## 외부 HTTP 호출의 공통 처리와 기능별 처리

AI Health·지원사업 점수화·기업마당 수집은 서로 다른 API입니다. 하지만 Spring이 표현하는 연결
실패·timeout·JSON 역직렬화 실패는 같으므로 그 분류만 `executeHttpCall`로 공통화합니다.

```text
AiServiceHealthClient ─────────────┐
HttpAiSupportProgramRankingClient ─┼─ executeHttpCall { 각 Client의 HTTP 호출 }
BizInfoClient ─────────────────────┘    ├─ 연결 실패 공통 분류
                                       ├─ 네트워크 timeout 공통 분류
                                       └─ Spring 응답 해석 실패 공통 분류
```

공통 함수는
[`HttpCallHelper.kt`](src/main/kotlin/ai/govbiz/core/_common/helper/HttpCallHelper.kt)에 있습니다. 각
Client가 전달한 코드 블록을 `try` 안에서 실행하고, 실패 종류에 맞는 예외 생성 함수를 호출합니다.

```kotlin
fun <T> executeHttpCall(
    onTimeout: (Throwable) -> RuntimeException,
    onUnavailable: (Throwable) -> RuntimeException,
    onUpstreamError: (RestClientResponseException) -> RuntimeException,
    onInvalidResponse: (RestClientException) -> RuntimeException,
    block: () -> T,
): T =
    try {
        block()
    } catch (exception: ResourceAccessException) {
        // timeout인지 연결 불가인지 공통 판별
    } catch (exception: RestClientResponseException) {
        // 처리되지 않은 HTTP 응답 오류
    } catch (exception: RestClientException) {
        // JSON 역직렬화 같은 RestClient 오류
    }
```

`executeAiServiceCall`은 이 공통 함수를 AI용 `AiServiceCallException`과 연결합니다. 기업마당은
`executeBizInfoHttpCall`을 통해 같은 공통 함수를 `BizInfoClientException`과 연결합니다. 기업마당에서
AI용 helper를 그대로 사용하면 공개 오류가 `AI_SERVICE_*`로 잘못 표시되므로, 공통화 대상은 Spring
예외 판별까지만입니다.

| 공통 판별 | AI 호출 결과 | 기업마당 호출 결과 |
|---|---|---|
| 연결 불가 | `AiServiceFailure.UNAVAILABLE` | `BizInfoClientException.Failure.UNAVAILABLE` |
| timeout | `AiServiceFailure.TIMEOUT` | `BizInfoClientException.Failure.TIMEOUT` |
| HTTP 응답 오류 | `AiServiceFailure.UPSTREAM_ERROR` | `BizInfoClientException.Failure.UPSTREAM_ERROR` |
| 역직렬화 오류 | `AiServiceFailure.INVALID_RESPONSE` | `BizInfoClientException.Failure.INVALID_RESPONSE` |

Health Client는 다음처럼 이 공통 함수 안에서 자기 HTTP 요청을 실행합니다.

```kotlin
fun getHealth(): AiServiceHealthPayload =
    executeAiServiceCall {
        restClient.get()
            .uri("/internal/v1/health")
            .retrieve()
            .onStatus(/* Health 응답 상태 해석 */)
            .toEntity(AiServiceHealthPayload::class.java)
    }
```

여기서 `executeAiServiceCall`은 연결·timeout·JSON 해석 오류를 처리하고, `onStatus`는 실제 HTTP
응답을 받은 뒤 그 상태 코드의 의미를 판단합니다. 숫자 대신 Spring 상수를 사용합니다.

| Spring 표현 | 실제 HTTP 상태 |
|---|---:|
| `HttpStatus.OK.value()` | 200 |
| `HttpStatus.NO_CONTENT.value()` | 204 |
| `HttpStatus.REQUEST_TIMEOUT.value()` | 408 |
| `HttpStatus.SERVICE_UNAVAILABLE.value()` | 503 |
| `HttpStatus.GATEWAY_TIMEOUT.value()` | 504 |

현재 상태 해석은 다음과 같습니다.

| 내부 API | 204 | 503 | 408·504 | 그 밖의 200이 아닌 상태 |
|---|---|---|---|---|
| Health | `INVALID_RESPONSE` | `UNAVAILABLE` | `UPSTREAM_ERROR` | `UPSTREAM_ERROR` |
| 지원사업 점수화 | `INVALID_RESPONSE` | `UNAVAILABLE` | `TIMEOUT` | `UPSTREAM_ERROR` |

204와 503은 현재 두 Client에서 같은 오류로 바뀌지만, 각 내부 API의 상태 계약을 코드에서 바로
확인할 수 있도록 `onStatus` 안에 명시적으로 둡니다. 공통화된 것은 그 뒤에 사용하는
`AiServiceCallException`의 네 가지 분류와 공개 ProblemDetail 변환입니다. 따라서 흐름은 다음과
같습니다.

```text
HTTP 상태를 기능별 Client가 해석
  → UPSTREAM_ERROR | INVALID_RESPONSE | UNAVAILABLE | TIMEOUT
  → ApiExceptionHandler가 공개 502 | 502 | 503 | 504 ProblemDetail로 변환
```

HTTP 200을 받았더라도 body가 없으면 각 Client가 `INVALID_RESPONSE`를 발생시킵니다. 연결조차 되지
않거나 소켓 timeout이 발생하면 HTTP 상태가 없으므로 `onStatus`가 아니라 공통
`executeAiServiceCall`이 처리합니다.

## 실행

JDK 21이 필요합니다.

```bash
./gradlew bootRun
```

기본 주소는 `http://127.0.0.1:8080`입니다. 실제 공고 검색에는 공공데이터포털에서 발급한 개인
서비스키가 필요합니다. Encoding 또는 Decoding 키를 `DATA_GO_KR_SERVICE_KEY`에 넣을 수 있으며,
Core API가 외부 요청 전에 정확히 한 번 인코딩합니다. 키를 `application.properties`, Git 또는
Frontend 환경변수에 기록하지 마세요.

| 환경변수 | 기본값 | 용도 |
|---|---|---|
| `DATA_GO_KR_SERVICE_KEY` | 빈 값 | 공공데이터포털 인증키. 비어 있으면 검색만 503 |
| `BIZINFO_API_BASE_URL` | `https://apis.data.go.kr` | 공고 API origin |
| `BIZINFO_API_CONNECT_TIMEOUT` | `2s` | 공고 API 연결 제한시간 |
| `BIZINFO_API_READ_TIMEOUT` | `10s` | 공고 API 응답 제한시간 |
| `AI_SERVICE_BASE_URL` | `http://127.0.0.1:8000` | 내부 AI Service 주소 |
| `AI_SERVICE_CONNECT_TIMEOUT` | `1s` | AI Service 연결 제한시간 |
| `AI_SERVICE_READ_TIMEOUT` | `12s` | AI Service 응답 제한시간(LLM 전체 제한 10초 + 내부 응답 여유) |
| `APP_CORS_ALLOWED_ORIGIN` | `http://localhost:5173` | 허용할 Web origin |

Compose 실행은 저장소 루트 `.env`의 `DATA_GO_KR_SERVICE_KEY`를 Core API에, `OPENAI_API_KEY`를 AI
Service에만 전달합니다. 네이티브 실행에서는 각 프로세스 환경변수를 직접 설정해야 합니다.

## 지원사업 검색 동작

Core API는 현재 검색 요청마다 공공데이터포털을 조회하며 메모리 캐시를 사용하지 않습니다. 외부
호출 실패는 즉시 안전한 공개 오류로 반환합니다. 검색은 서울 시간 기준으로 접수상태를 계산하고,
접수 필터 적용 후 최신 후보 최대 20개를 AI Service에 보냅니다. LLM이 버전된 100점 기준으로 모든
후보를 점수화하며 Core는 결과를 방어적으로 검증합니다.
현재 최신 20개 제한은 벡터 검색 전의 임시 후보 선택입니다.
다음 단계에서는 정기 수집과 DB 검색으로 외부 호출을 사용자 요청 경로에서 제거합니다.

| 상황 | HTTP | code |
|---|---:|---|
| 인증키 미설정 | 503 | `SUPPORT_PROGRAM_SOURCE_NOT_CONFIGURED` |
| 외부 API 실패 응답 | 502 | `SUPPORT_PROGRAM_SOURCE_ERROR` |
| 잘못된 외부 JSON·필드 구조 | 502 | `SUPPORT_PROGRAM_INVALID_RESPONSE` |
| 연결 불가 | 503 | `SUPPORT_PROGRAM_SOURCE_UNAVAILABLE` |
| 연결·읽기 시간 초과 | 504 | `SUPPORT_PROGRAM_SOURCE_TIMEOUT` |

외부 URL, 인증키와 라이브러리 예외는 공개 응답에 노출하지 않습니다.

## AI Service 오류 계약

| 상황 | HTTP | code |
|---|---:|---|
| AI Service/OpenAI 실패 응답 | 502 | `AI_SERVICE_UPSTREAM_ERROR` |
| 잘못된 Content-Type·JSON·응답 body | 502 | `AI_SERVICE_INVALID_RESPONSE` |
| 연결 불가 | 503 | `AI_SERVICE_UNAVAILABLE` |
| 연결·읽기 시간 초과 | 504 | `AI_SERVICE_TIMEOUT` |

내부 URL과 라이브러리 예외는 공개 응답에 노출하지 않습니다.

## 검증

```bash
./gradlew clean build --no-daemon
```
