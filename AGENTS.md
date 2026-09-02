# Codex 구현 지침

## 적용 범위

이 파일은 백엔드 구현 규칙을 서비스별로 구분한다. `AI Service 구현 규칙`은
`backend/ai-service/**`에, `Core API 구조 및 명명 규칙`은 `backend/core-api/**`에 적용한다.

## AI Service 구현 규칙

### 단순성 우선

- 사용자가 요청한 현재 기능만 구현한다. 미래 요구를 추측해 구조를 추가하지 않는다.
- 가장 단순하게 현재 요구와 테스트를 만족하는 구현을 우선한다.
- 기본적으로 구체 클래스와 직접적인 함수 호출을 사용한다.
- 하나의 기능을 불필요하게 여러 계층, 클래스 또는 파일로 분리하지 않는다.
- 새 클래스나 파일은 현재 책임을 한 문장으로 설명할 수 있을 때만 추가한다.
- "나중에 필요할 수 있다"는 이유만으로 코드를 추가하지 않는다.

### 추상화 제한

- 사용자가 명시적으로 요청하지 않는 한 새로운 `Protocol`, ABC, port, provider abstraction,
  registry, base class, 범용 factory를 만들지 않는다.
- 다음 조건 중 하나도 충족하지 않으면 추상화를 추가하지 않는다.
  1. 현재 production 구현체가 2개 이상이다.
  2. 현재 production 코드에서 같은 로직이 실제로 반복된다.
  3. 외부 시스템 경계를 격리해야 하는 명확한 장애 또는 보안 이유가 있다.
  4. 사용자가 해당 추상화를 명시적으로 요청했다.
- 테스트 편의를 이유로 production 추상화를 추가하지 않는다. 테스트에서는 `monkeypatch`,
  `AsyncMock` 또는 구체 클래스의 테스트 대역을 우선한다.
- 공통 코드는 실제 production 사용처가 2개 이상 생겼을 때만 추출한다. 단, 사용자가 공통화를
  명시적으로 요청한 경우에는 바로 적용한다.

### 현재 제품 정책

- OpenAI 사용은 필수다. 다른 LLM provider 선택 기능이나 규칙 기반 fallback을 추가하지 않는다.
- 장애를 정상 응답으로 숨기는 fallback을 만들지 않는다. 장애는 명시적인 오류로 반환한다.
- 여러 Agent를 추가하더라도 Agent별 역할이 실제로 나뉘기 전에는 오케스트레이터, handoff,
  graph 또는 범용 Agent 프레임워크 계층을 추가하지 않는다.

### 변경 범위와 검증

- 요청 범위 밖의 패턴 통일, 파일 재배치 또는 미래 대비 리팩터링을 하지 않는다.
- 새로운 production 의존성, 외부 서비스 또는 실행 계층을 추가하기 전에 사용자에게 알린다.
- 구현 후 실제 호출 흐름을 `HTTP API → Service → Agent → OpenAI → Response` 형식으로 설명한다.
- `backend/ai-service` 변경 후에는 AI Service 전체 테스트를 실행한다.

## Core API 구조 및 명명 규칙

### 기능 중심 배치

- Kotlin 기본 패키지는 `ai.govbiz.core`이며 실제 디렉터리와 `package` 선언을 항상 일치시킨다.
- 업무 코드는 기능 디렉터리 안에서 `controller → service → facade → client` 흐름을 기본으로 하고,
  프레임워크와 무관한 업무 모델은 `domain`에 둔다.
- `supportprogram`은 실제 지원사업 기능, `_health`와 `_health_ai_service`는 상태 확인 기능,
  `_sampleitem`은 계층 학습 예제, `_common`은 둘 이상의 기능이 실제로 공유하는 코드다.
- 공개 HTTP 계약은 해당 기능의 `controller/dto`, 외부 시스템 계약은 `client/dto`, 검증된 내부
  실행 결과는 `service/dto`, 프레임워크와 무관한 업무 모델은 `domain`에 둔다.
- 외부 DTO를 내부 모델로 변환하는 Mapper는 해당 외부 시스템의 `client/mapper`에 둔다.
- 외부 시스템에서만 사용하는 예외는 해당 시스템의 `client/exception`에 둔다.
- DTO, 예외, 설정을 프로젝트 전체의 중앙 폴더에 모으지 않고 그 계약을 소유하는 기능 가까이에 둔다.

### 역할별 이름

- 공개 HTTP 진입점은 `Controller`, 업무 흐름은 `Service`, 외부 HTTP 통신은 `Client`로 끝낸다.
- 하위 Client 호출·응답 검증·도메인 변환을 하나의 진입점으로 감추는 객체는 `Facade`로 끝낸다.
- 외부 DTO 변환은 `Mapper`, Spring 구성은 `Config`, 환경설정 값은 `Properties`로 끝낸다.
- 전송 객체는 경계에 맞춰 `Request`, `Response`, `Payload`, `Result`를 사용한다. 필드가 같다는
  이유만으로 서로 다른 경계의 타입을 합치지 않는다.
- 이름은 수행 역할을 드러내야 하며 `Support`, `Util`, `Common`처럼 의미가 모호한 접미사를
  새로 만들지 않는다.

### Helper 규칙

- 다른 코드의 반복 작업을 보조하는 파일과 `object`·클래스 이름은 `Helper`로 끝낸다.
- 둘 이상의 기능이 실제로 함께 사용하는 Helper는 `_common/helper`에 둔다.
- 특정 기능이나 외부 시스템만 사용하는 Helper는 해당 기능의 `helper` 디렉터리에 둔다.
  예: `supportprogram/client/bizinfo/helper`.
- Helper 함수 이름은 `executeHttpCall`, `buildRestClient`, `decode`처럼 동작을 나타내며
  함수 이름에 `Helper`를 반복하지 않는다.
- `Controller`, `Service`, `Facade`, `Client`, `Mapper`처럼 더 정확한 역할명이 있으면 Helper로
  분류하지 않는다.
- 실제 사용처가 하나뿐이고 호출부 안에 두는 편이 더 명확한 작은 함수는 불필요하게 별도
  Helper 파일로 추출하지 않는다.

### 의존 방향과 변경 원칙

- 기본 의존 방향은 `Controller → Service → Facade → Client`로 고정한다.
- Controller는 Facade나 Client를 직접 호출하지 않고 사용자 유스케이스를 담당하는 Service만 호출한다.
- Service는 필요한 Repository를 직접 사용하며, 외부 하위 시스템의 호출·검증·변환이
  복잡할 때만 Facade를 사용한다. 단순 Client 호출을 한 줄 전달하는 Facade는 만들지 않는다.
- Facade는 상위 Service를 다시 호출하지 않는다. `Service ↔ Facade` 순환 의존성은 금지한다.
- Facade가 필요 없는 단순 외부 호출은 Service가 Client를 직접 사용할 수 있다.
- 외부 시스템의 원본 JSON과 예외를 공개 API에 그대로 노출하지 않는다. Client 경계에서 DTO와
  안정적인 내부 예외로 변환한다.
- 새로운 공통 추상화는 production 사용처가 둘 이상이거나 외부 시스템 장애·보안 경계를
  격리해야 할 때만 추가한다.
- 구조나 이름을 변경하면 `backend/core-api/README.md`와 `docs/architecture.md`도 함께 갱신한다.

### 검증

- 테스트 패키지는 production 패키지 구조를 따라 배치한다.
- Core API 변경 후에는 JDK 21 환경에서 `./gradlew clean test --no-daemon`을 실행한다.
- 파일 이동 후에는 이전 package·import·문서 경로가 남지 않았는지 `rg`로 확인하고
  `git diff --check`를 통과시킨다.
