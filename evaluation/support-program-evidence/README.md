# 공고별 RAG 검수와 고정 근거 답변 평가

[문서 목록](../../docs/README.md) · [구현 현황](../../docs/implementation-status.md)

## 현재 상태: 5단계 진행 중

2026-09-06 기준, 기존 RAG 코드 검수·회귀 테스트 보강과 **고정 근거 답변 단계의 부분 평가**를 진행했습니다.
승인된 실제 OpenAI 12회 호출에서 유효 답변 11개를 얻었습니다. 초기 검증 실패 1건과 미실행 E12가 남아 있습니다.
[실제 실행·AI 의미 검토 기록](runs/README.md)에 실패와 표본 한계를 함께 보존합니다.
새 RAG를 만들거나 기존 production 구조·프롬프트를 변경한 작업은 아닙니다.

| 구분 | 확인한 내용 | 아직 확인하지 않은 내용 |
|---|---|---|
| 기존 코드 검수 | 공식 URL·공고 식별자·리다이렉트·HTML 추출, 캐시·본문 해시, 현재 청크만 검색/인용, UI 오류·취소 처리 | 모든 실제 기업마당 HTML 변형에서의 수집 성공 |
| Core 회귀 테스트 | 원문 갱신 실패 시 후속 호출 차단, 잘못된 검색·인용 응답 거부, 검색된 최대 5개만 답변에 전달 | 모델 답변의 의미 정확성 |
| AI 기존 테스트 | 출력 계약, Agent 요청 설정·시간 제한, 근거 청크 검색/색인 | 다양한 실데이터에서의 모델 품질 |
| 새 평가 도구·실제 답변 | 고정 질문·근거·기대 상태/인용, 실행 기록 재계산, API 호출 안전장치와 가상 자료 일부 실제 답변 | 실제 원문 수집 → 청킹 → Qdrant 검색을 포함한 전체 RAG 품질 |

코드 검수·회귀 테스트에서 재현 가능한 production 결함은 발견하지 못했습니다. 다만 실제 모델 호출에서
답변 검증 실패 1건을 관측했고 원인은 아직 확정하지 못했습니다. 무결함이나 운영 품질 보장은 아닙니다.
Core 기존 두 테스트 파일에 19개 실행 사례를 추가했고, JDK 21·실제 MySQL 8.4 Testcontainers 포함
전체 340개가 통과했습니다. AI Service 기존 전체 192개와 새 평가 도구 37개 테스트도 통과했습니다.

관련 테스트: [Facade](../../backend/core-api/src/test/kotlin/ai/govbiz/core/supportprogram/facade/AiSupportProgramEvidenceFacadeTest.kt),
[Service](../../backend/core-api/src/test/kotlin/ai/govbiz/core/supportprogram/service/evidence/SupportProgramEvidenceServiceTest.kt),
[AI 근거 기능](../../backend/ai-service/tests/support_program_evidence), [평가 도구](test_evaluate.py).

## 자료와 해석 범위

[fixture.json](fixture.json)은 **AI가 작성한 가상 공고 3개·10개 청크·12개 질문**입니다.
실제 기업마당 공고나 사람 검토 정답으로 소개하면 안 됩니다.

- 신청 대상 2개, 제출 서류 3개, 없는 정보 3개, 다른 공고 정보 혼입 2개, 지시문 공격 2개
- 기대 상태: `ANSWERED` 7개 / `INSUFFICIENT_EVIDENCE` 5개
- 별첨에만 서류가 있고 별첨 본문은 없는 경우, 미기재 금액·기간을 지어내면 안 되는 경우 포함
- 지시문 공격은 기초적인 사례이며 광범위한 공격 내성 평가가 아님

각 질문에는 선택한 공고의 고정 청크만 전달합니다. 다른 공고 혼입 사례는 질문에 섞인 다른 공고의 정보를
선택한 공고의 조건으로 받아들이는지 확인합니다. 검색기가 다른 공고를 반환하는 상황은 이 도구의 평가 대상이 아닙니다.
`referenceFacts`·`forbiddenClaims`는 답변의 의미를 검토할 때 쓰는 참조입니다. 단어가 등장하는지만으로
정오를 채점하면 부정문과 긍정문을 혼동하므로 자동 문자열 점수에 사용하지 않습니다.

| 출력 | 뜻과 제한 |
|---|---|
| `measured` / `completed` | 모델 실행 기록 유무 / 선택한 사례 모두 오류 없이 응답했는지. 품질 합격 여부가 아님 |
| `caseCount` / `fixtureCaseCount` | 이번 선택 사례 수 / 전체 고정 질문 수. 일부 질문만 실행한 결과를 전체로 오인하지 않도록 분리 |
| `statusAccuracy` | 기대 `ANSWERED`·`INSUFFICIENT_EVIDENCE` 상태 일치 비율 |
| `referenceCitationRecall` | 답변 가능한 질문에서 기대 청크를 인용에 포함한 비율의 평균. 불필요한 인용을 벌점주지 않으므로 전체 청크를 인용해도 1.0일 수 있음 |
| `semanticFaithfulness` | 항상 `null`. 위 두 지표가 높아도 답변 내용의 사실성은 입증되지 않음 |
| `semanticReviewRequired` | 의미 검토가 별도로 필요함. 사람 검토를 강제하는 필드가 아님 |

구조 검증과 의미 평가를 구분하고 일반·경계·공격 사례를 고정하는 방식은
[OpenAI의 평가 안내](https://developers.openai.com/api/docs/guides/evaluation-best-practices)를 참고했습니다.
이번 참조는 AI 작성 가상 자료이고 별도 사람 검증이 없어 실제 공고의 일반화 성능을 주장할 수 없습니다.

## 실행 방법

저장소 루트에서 실행합니다. AI Service 의존성을 먼저 설치해야 합니다
([설치 안내](../../backend/ai-service/README.md)). 서버·MySQL·Qdrant·Excel은 필요 없습니다.

```bash
# 입력 검증만: API 키 불필요, 파일 쓰기 없음, 품질 지표는 null
backend/ai-service/.venv/bin/python evaluation/support-program-evidence/evaluate.py

# 테스트: 실제 Agent/SDK 경로도 HTTP 스텁으로만 검증하며 외부 호출 없음
backend/ai-service/.venv/bin/python -m pytest evaluation/support-program-evidence/test_evaluate.py
```

실제 모델 평가를 선택한 경우에만 아래 명령을 실행합니다. `OPENAI_API_KEY`는 기존 보안 환경변수 주입
방식으로 설정하며, 이 도구는 `.env`를 자동으로 읽지 않습니다. 키를 명령행 인자로 넣지 마세요.

```bash
# 유료: 최대 12회 답변 생성. 기존 폴더가 아닌 새 경로를 지정
backend/ai-service/.venv/bin/python evaluation/support-program-evidence/evaluate.py \
  --execute --output-dir work/evidence-evaluation-v1

# 저장된 결과 재계산: API 호출 없음
backend/ai-service/.venv/bin/python evaluation/support-program-evidence/evaluate.py \
  --capture work/evidence-evaluation-v1/capture.json
```

- OpenAI 공식 API에만 전송하며 기본 모델·타임아웃과 기존 답변 Service·Agent를 사용합니다.
- 순차 실행, SDK 재시도 0회, 질문당 Agent 1턴, 첫 오류 즉시 중단입니다. 임베딩 호출은 없습니다.
- `--case-id E01`로 특정 사례만 진단할 수 있습니다. 여러 사례는 fixture 순서대로 옵션을 반복합니다.
  이미 호출한 실패 사례도 비용·실패 집계에서 제외하지 마세요.
- 모델은 현재 AI Service 기본값을 사용합니다. 실제 값·프롬프트/도구/fixture/요청 해시를 캡처에 기록합니다.
- 매 질문 후 `capture.json`을 저장하며 오류 메시지 원문·키·인증 헤더는 저장하지 않습니다.
  계약 실패 진단용 생성 답변 텍스트(`outputTexts`)와 하위 예외 종류(`causeType`)는 기록합니다.
  API 응답에서 제공한 토큰 사용량과 질문별 지연을 기록하며, 사용량 미제공은 0이 아니라 `null`입니다.
- 끝나면 `report.json`을 저장합니다. 미완료 실행은 종료 코드 1이며 부분 결과로 전체 점수를 내지 않습니다.
- 재계산 시 fixture·요청 해시·질문 순서·인용·완료 여부를 검사합니다. 해시는 파일 일치를 확인하는 것이며,
  캡처가 실제 API에서 생성됐음을 암호학적으로 증명하지는 않습니다.
- `work/`는 기존 임시 출력 제외 경로입니다. 도구·질문·참조·문서는 모두 Git 공유 대상입니다.
  실제 실행 기록을 팀에 공유할 때는 민감정보를 확인한 뒤 별도 버전 폴더에 보존해야 합니다.

## 실제 호출 흐름과 다음 작업

기존 사용자 기능은 **HTTP API → Service → 원문 수집/Repository → AI Facade → AI HTTP API → Service → Agent → OpenAI → Response**입니다.
그 과정에서 근거 청크를 별도 Qdrant 컬렉션에 색인·검색하며 Core가 최종 인용문을 원래 청크에서 구성합니다.
자세한 내용은 [기존 호출 흐름](../../docs/architecture.md)을 따릅니다.

이 평가 도구는 **고정 fixture → 기존 AI 답변 Service → Agent → OpenAI → 캡처/보고서**입니다.
HTTP API·원문 수집·DB·청킹·임베딩·Qdrant를 생략하므로 `scope=fixed-answer-context-only`로 기록합니다.

5단계 전체 완료를 판단하려면 다음을 구분해서 끝내야 합니다.

1. 미실행 질문을 확인하고 초기 검증 실패를 추적합니다. 답변·기대 사실·금지 주장을 대조합니다.
   AI-only 검토를 선택하면 검토 모델·출처·한계를 명시하며 사람 검토로 위장하지 않습니다.
2. 실제 공고 HTML을 고정한 사례에서 수집 → 청킹 → 검색 → 답변 → 인용이 연결되는지도 확인합니다.
   위의 가상 고정 근거 테스트를 이 전체 경로 평가로 대체하지 않습니다.
3. 확인한 오류만 수정하고 같은 입력으로 다시 비교해 보고서를 남깁니다.

첨부파일·PDF/OCR 확장과 새로운 제공처 추가는 이번 검수에 포함하지 않습니다.
