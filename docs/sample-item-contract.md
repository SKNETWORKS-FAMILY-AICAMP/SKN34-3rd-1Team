# SampleItem 준비 API 계약

SampleItem은 GovBiz의 Frontend·Core API 계층 연결을 보여 주는 최소 예제입니다. 실제 업무
도메인을 추가할 때는 이
계약을 그대로 확장하기보다, 필요한 상태와 필드를 새로 정의하세요.

Frontend의 React Hook 예제와 Redux Toolkit 예제는 모두 이 endpoint를 사용합니다. 두 화면은 상태
수명만 다르며 요청·응답 계약과 Domain 변환 규칙은 동일합니다.

## Endpoint

```text
POST /api/v1/sample-items/prepare
Content-Type: application/json
```

## 요청

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `item.name` | string | 예 | 공백이 아닌 최대 100자 이름 |
| `item.category` | `BASIC` \| `EXTENDED` \| null | 아니오 | 예제 분류 |
| `item.note` | string \| null | 아니오 | 최대 500자 메모 |

```json
{
  "item": {
    "name": "Example item",
    "category": "BASIC",
    "note": "Shows a typed vertical slice."
  }
}
```

## 성공 응답

```json
{
  "phase": "READY_FOR_PROCESSING",
  "item": {
    "name": "Example item",
    "category": "BASIC",
    "note": "Shows a typed vertical slice."
  },
  "processing": {
    "status": "NOT_STARTED"
  }
}
```

성공했다고 실제 처리, 저장, 비동기 작업이 시작된 것은 아닙니다. 이 상태는 입력이 검증되었고 다음
단계가 사용할 수 있음을 보여 주는 예제입니다.

## 오류

잘못된 JSON 타입, 필수 이름 누락, 공백 이름, 알 수 없는 enum은 `400`과 다음 형식의
`application/problem+json`을 반환합니다.

```json
{
  "type": "urn:govbiz:problem:request-validation-failed",
  "title": "Request Validation Failed",
  "status": 400,
  "detail": "One or more request fields are invalid.",
  "instance": "/api/v1/sample-items/prepare",
  "code": "REQUEST_VALIDATION_FAILED",
  "errors": [{ "field": "item.name", "code": "INVALID_VALUE" }]
}
```

Frontend는 Zod로 성공 응답을 검증하고, Core API는 Bean Validation과 JSON 역직렬화 설정으로 요청을
검증합니다.
