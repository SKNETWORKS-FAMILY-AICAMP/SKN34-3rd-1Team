# 지원사업 후보 검색 회귀 평가

이 자료는 **실제 기업마당 공고가 아닌 수작업 가상 공고 40개와 질문 30개**다. 최신순 20개 제한 때문에 오래된 관련 공고가 누락되는 문제를 재현하고, 같은 자료에서 후보 검색 방식을 비교하는 데 사용한다. 실제 사용자의 추천 정확도, 신청 자격, 운영 데이터 성능을 입증하는 자료가 아니다.

## 실행

프로젝트 루트에서 Python 표준 라이브러리만으로 실행한다. 외부 API 호출, 유료 모델 호출, DB 쓰기를 하지 않는다.

```bash
python3 evaluation/support-program-search/evaluate.py
python3 evaluation/support-program-search/evaluate.py --split dev
python3 -m unittest discover -s evaluation/support-program-search -p 'test_*.py'
```

출력은 JSON이다. `semantic: null`은 수동으로 저장한 의미 검색 후보가 없다는 뜻이고, `capture: null`은
실제 Core 검색 흐름을 캡처한 결과가 없다는 뜻이다. 어느 쪽도 0점이나 검증 성공을 뜻하지 않는다.

## 데이터와 비교 규칙

- `fixture.json`의 `name`, `docs`: `id`, 검색에 사용할 `text`, 고정된 `sortTimestamp`를 가진 공고 40개. 모든 공고는 노출·접수 조건을 만족한다고 가정한다. 날짜와 자격 필터는 이 평가의 범위 밖이다. 기존 의미 검색 비교에서 `dataType`이 없으면 결과에는 `unspecified`로 표시한다.
- `cases`: `id`, `query`, `relevantIds`, `split`을 가진 질문 30개. 관련 공고가 있는 질문 26개, 관련 공고가 없는 질문 3개, 추가 정보가 필요해 관련성을 확정하지 않은 질문 1개다.
- `relevantIds: []`는 이 고정 자료에서 정답이 없다고 라벨링한 경우다. `null`은 미라벨 상태이며 모든 점수 계산에서 제외한다. Q30은 중복 수혜 규정이 공고에 없어서 자격 관련 정답을 임의로 붙이지 않았다.
- 오래된 20개에는 개발비·설비비 등의 지원사업이, 최신 20개에는 유사 주제의 교육·행사 등과 일부 관련 사업이 있다. 목적에 맞는 오래된 공고를 검색하는지 확인하는 **의도적인 난례 구성**이다. 실제 공고의 연령·주제 분포를 대표하지 않는다.
- 질문 Q01~Q20은 개발용(`dev`), Q21~Q30은 검증용(`heldout`)으로 구분했다. 검증용 질문으로 임계값이나 검색 설정을 조정하면 더 이상 미사용 검증 자료가 아니다. 같은 공고나 주제가 양쪽에 있으므로 독립적인 일반화 성능을 입증하는 분리도 아니다. 실제 사용자 질문은 별도로 확보해야 한다.

기준 검색 방식은 다음과 같다.

1. `latest`: 모든 질문에 `sortTimestamp` 내림차순, ID 오름차순의 첫 K개를 반환한다. 과거 구현의 검색어와 무관한 후보 제한을 재현한다. 현재 운영 검색 경로는 전체 허용 공고를 대상으로 Qdrant 후보 검색을 사용한다.
2. `keyword`: NFC 정규화·소문자 처리 후 영문/숫자/한글 토큰의 **정확한 일치 개수**로 정렬한다. 0개 일치는 제외하고, 동점은 최신순과 ID 순으로 처리한다. 형태소 분석, 동의어 확장, 부정문 이해가 없는 단순 비교 기준이며 운영 fallback이 아니다.
3. `semantic`: 외부에서 실제 의미 검색을 실행해 저장한 후보 ID 목록을 읽는다. 스크립트가 모델 결과를 만들거나 라벨에서 정답을 생성하지 않는다.

기본 K는 20이다. `macroRecallAtK`는 답이 있는 질문별로 `상위 K개에 포함된 관련 공고 수 / 전체 관련 공고 수`를 계산한 뒤 평균한다. 정답이 여러 개인 Q28에서는 두 공고 모두 찾아야 1이다. 답이 있는 질문이 없으면 `null`로 표시한다.

수동 `semantic` 비교는 후보 단계의 `macroRecallAtK`와 `noMatchFalsePositiveRate`를 계산한다.
`--capture`는 실제 검색 흐름을 두 단계로 나눠 계산한다.

- `candidate`: Qdrant 후보 최대 20개의 `macroRecallAtK`, `noMatchFalsePositiveRate`
- `final`: AI 최종 추천 최대 5개의 `macroRecallAt5`, `mrrAt5`, `noMatchFalsePositiveRate`

`MRR@5`는 첫 관련 공고가 앞에 있을수록 높다. 이진 관련성 라벨만 있으므로 nDCG나 지원대상·지역
자격 판단 정확도는 계산하지 않는다. 응답 시간·비용도 이 도구의 지표가 아니다.

무결과 질문은 Recall 분모에 섞지 않는다. `noMatchFalsePositiveRate`는 정답이 없는 질문 중 후보를 1개 이상 반환한 비율이다. 이 수치는 **후보 검색 단계**의 무결과 처리만 측정한다. 후보 검색이 넓게 찾고 이후 순위화에서 제외하는 설계라면, 최종 추천의 오추천 비율을 별도 측정해야 한다. 이 소규모 자료만으로 점수 임계값을 결정하면 안 된다.

`perQuery`에서 누락된 정답과 반환 ID를 확인할 수 있다. 후보 20개와 최종 5개는 서로 다른 단계이므로
하나의 Recall 수치로 합치지 않는다.

이 가상 자료에서 최신 20개 기준의 Recall@20은 약 0.077, 단순 키워드 기준은 1.0이다. 40개 중 최대 20개를 고르는 작은 자료이므로 키워드 기준도 모든 관련 공고를 포함할 수 있다. 이는 벡터 검색의 우월성을 입증하지 않으며, Recall이 높아도 불필요한 후보가 함께 포함되거나 최종 추천 순서가 나쁠 수 있다. 의미 검색 결과와 더 현실적인 자료를 확보하기 전에는 개선 수치를 주장하지 않는다.

## 기존 의미 검색 후보 파일 비교

가상 공고만 평가할 때는 이 공고 40개만 넣은 **평가 전용 Qdrant 컬렉션/환경**에서 질문을 실행한다.
운영 기업마당 색인에 `SYNTH_*` 공고를 넣거나 운영 공고와 섞어 비교하지 않는다.

원문은 `docs[].text` 그대로 사용하고, 반환 후보를 공고 ID로 바꿔 JSON 파일에 저장한다. 입력 구조는 다음과 같다. 아래는 형식 예시일 뿐 실제 모델 결과가 아니며, 전체 실행에는 선택한 분할의 모든 라벨된 질문 결과가 필요하다.

```json
{
  "Q01": ["SYNTH_AI_SEOUL", "SYNTH_AI_EDUCATION"],
  "Q19": []
}
```

```bash
python3 evaluation/support-program-search/evaluate.py --semantic-results /path/to/captured-candidate-ids.json
```

질문 키가 빠진 것은 실행하지 않은 경우로 취급해 오류를 낸다. 실제 검색을 실행한 결과가 비어 있을 때만 `[]`를 쓴다. 알 수 없는 공고 ID·질문 ID·중복 반환 ID도 오류다. `--split dev`에서는 개발용 질문 결과만 있어도 된다. 외부 결과 파일과 함께 모델, 임베딩 차원, 검색 설정, 실행 일시, 데이터 버전(`synthetic-support-program-retrieval-v1`)을 기록해야 재현할 수 있다.

## 실제 검색 흐름 캡처

`evaluation-capture` Spring profile은 공개 HTTP endpoint를 추가하지 않는다. 이미 동기화되어 있는 MySQL
공고를 읽어, 각 질문에 대해 실제 `검색 → Qdrant 후보 선정 → AI 최종 추천` 흐름을 한 번 실행하고 결과를
하나의 JSON 파일로 저장한다. profile 자체가 웹 서버와 두 동기화 스케줄러를 끄므로, 캡처 도중 새 스냅샷이
공개되는 일을 이 프로세스가 만들지 않는다. 별도로 실행 중인 Core API가 카탈로그를 갱신할 수는 있으나,
질문 하나라도 실패하거나 카탈로그 지문이 질문 사이에 바뀌면 기존 결과 파일을 교체하지 않는다.

이 실행은 연결된 AI Service를 통해 실제 OpenAI 임베딩·점수화를 호출할 수 있어 비용이 발생할 수 있다.
CI나 기본 Compose 검증에서는 실행하지 않는다.

질문 묶음은 [query-set.example.json](query-set.example.json)과 같은 구조를 사용한다. `name`은 라벨 fixture의
`name`과 같아야 하며, 모든 `id`·`query`·`split`은 라벨 fixture의 `cases`와 같은 순서·내용으로 넣는다.
이 세 값과 fixture의 `name`에는 앞뒤 공백을 넣지 않는다. 공백이 있으면 실행한 검색어와 평가 fixture의
식별자가 달라질 수 있어 오류로 처리한다.

```json
{
  "schemaVersion": "support-program-search-query-set-v1",
  "name": "bizinfo-20260905-v1",
  "queries": [
    {"id": "Q01", "query": "서울 소재 AI 제품 개발비 지원사업", "split": "dev"}
  ]
}
```

Core API JAR를 만든 뒤, 실제 MySQL과 AI Service에 연결되는 환경에서 다음처럼 실행한다. 출력 경로는
입력 경로와 달라야 한다.

```bash
cd backend/core-api
./gradlew bootJar

SPRING_PROFILES_ACTIVE=evaluation-capture \
APP_SUPPORT_PROGRAM_SEARCH_CAPTURE_QUERY_SET_PATH=/absolute/path/queries.json \
APP_SUPPORT_PROGRAM_SEARCH_CAPTURE_OUTPUT_PATH=/absolute/path/capture.json \
java -jar build/libs/govbiz-core-api-0.0.1-SNAPSHOT.jar
```

캡처에는 질문 묶음 지문, 실행 시각, 접수 중 필터 여부, 현재·적격 공고 수, 적격 공고의 ID·내용 해시 지문,
후보 최대 20개와 최종 최대 5개의 제공처 포함 ID가 들어간다. 지문은 동일 공고 스냅샷에서 얻은 결과인지
확인하기 위한 값이며 원문이나 비밀정보를 저장하지 않는다.

## 실제 데이터 평가 준비

가상 자료의 통과를 제품 정확도 개선으로 보고하지 않는다. 실제 평가에는 특정 시점의 공고를 고정하고,
대표 사용자 질문에 사람이 관련·제외 공고를 라벨링한 fixture가 필요하다. 캡처 호환 fixture는 후보·최종
ID만 일부 담는 파일이 아니라 **캡처 시점의 전체 적격 공고 카탈로그**를 담아야 한다. 그래야 같은 ID가
다른 내용으로 갱신된 경우에도 평가를 거부할 수 있다.

- `dataType`은 예를 들어 `real_labeled_catalog_snapshot`처럼 자료 성격을 명시한다.
- `catalog`의 `presentProgramCount`, `eligibleProgramCount`, `eligibleCatalogFingerprint`은 capture 파일의
  `catalog`과 정확히 같아야 한다.
- `docs` 수는 `eligibleProgramCount`와 같아야 한다. 각 `docs[].id`와 `relevantIds`는 제공처를 포함한
  `BIZINFO:PBLN_...` 형식이고, 각 행은 Core의 `SupportProgramIndexDocumentMapper`가 만든 검색 문서의
  `contentHash`(소문자 SHA-256)를 포함해야 한다.
- 평가기는 `id:contentHash`를 ID 순서로 정렬해 만든 지문도 다시 계산한다. 즉 다른 날의 capture, 공고가
  누락된 fixture, 바뀐 내용 해시는 모두 오류가 된다.

기존 가상 fixture의 `SYNTH_*` ID는 기존 `--semantic-results` 비교 전용으로 유지한다.

```json
{
  "name": "bizinfo-20260905-v1",
  "dataType": "real_labeled_catalog_snapshot",
  "catalog": {
    "presentProgramCount": 1,
    "eligibleProgramCount": 1,
    "eligibleCatalogFingerprint": "c9c42090db18b14983851609533122a9492295f879178eaa43c296e2dad1ee56"
  },
  "docs": [
    {
      "id": "BIZINFO:PBLN_123",
      "contentHash": "ea87cd3309e776728d1cf1e5352de3f5256134133df956bbdebb38d38ed18a56",
      "text": "제목: 예시 공고\n기관: 예시 기관\n지원대상: 중소기업\n분야: AI\n지역: 서울\n신청기간: 상시 접수\n내용: 고정 시점의 평가용 공고 원문",
      "sortTimestamp": "20260905100000"
    }
  ],
  "cases": [
    {
      "id": "Q01",
      "query": "서울 소재 AI 제품 개발비 지원사업",
      "relevantIds": ["BIZINFO:PBLN_123"],
      "split": "dev"
    }
  ]
}
```

사람이 라벨링한 fixture와 캡처 결과가 준비되면 다음처럼 후보·최종 추천을 분리해 평가한다.

```bash
python3 evaluation/support-program-search/evaluate.py \
  --fixture /absolute/path/labeled-fixture.json \
  --capture /absolute/path/capture.json \
  --split heldout
```

질문 키가 빠진 것은 실행하지 않은 경우로 취급해 오류를 낸다. 실제 검색 결과가 비었을 때만 `[]`를 쓴다.
알 수 없는 공고 ID, 중복 ID, 후보에 없던 최종 추천, 카탈로그 지문 형식 오류도 모두 오류다. `relevantIds: null`
은 미라벨이므로 점수에서 제외하며, 신청 가능 여부가 문서에서 확인되지 않으면 임의로 정답을 붙이지 않는다.
개발용과 미사용 검증용은 분리하고, 원문 저장·이용 범위는 실제 제공처 조건을 따른다.
