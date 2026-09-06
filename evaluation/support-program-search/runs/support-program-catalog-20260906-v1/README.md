# 함께 이어받는 검색 평가 자료

이 디렉터리는 Git으로 공유하는 고정 평가 자료다. 같은 저장소를 받은 개발자는 이전 작업자의 PC·브라우저·
Codex 대화에 접속하지 않고도 원본 판정과 현재 진행 상태를 확인할 수 있다. **API 키, Excel, DB는 자료 검증에
필요하지 않다.** 저장된 판정을 검증·재사용하는 작업이며 AI를 다시 호출하는 명령이 아니다.

## 현재 상태와 다음 담당자의 작업

| 항목 | 현재 상태 |
| --- | --- |
| 고정 기준일 / 공고 | 2026-09-06 / 접수 중 공고 1,422건 |
| 질문 / 검토 풀 | 16개 / 질문·공고 조합 321건 |
| AI 판정 | 최초 1,605건 + 미확정 42건에 대한 추가 210건 완료 |
| 합의 / 미확정 | 279건 / 42건 → 추가 검토 후 303건 / 18건 |
| 기본 방식 | AI-only. 기존 사람 판정 2건은 별도 보존 |
| 평가 가능 질문 | 4개 → 8개. 관련 공고가 있는 질문은 Q01·Q02·Q06·Q14 |
| 실제 검색 캡처·Recall·MRR | 실제 API 실행은 Q01 추천 검증 오류로 중단. 성공한 capture·품질 점수 없음 |

현재 선택은 `review-v2/selected-ai-recheck-v1/`이다. 기존 합의 279건을 보존하고, 기존 미확정 42건 전부를
새 Luna 작업 5개가 한 차례 독립 검토했다. 같은 4/5 합의 기준으로 24건을 추가 확정하고 18건은 미확정으로 남겼다.
추가 검토는 여기서 종료한다. DB·색인 일치를 확인하고 실제 API 실행까지 진행했으나 추천 검증 오류로 중단됐다.
후보 수 누락은 schema를 보강했으며 남은 총점·자격 점수 모순을 먼저 해결해야 한다.
근거와 API 없는 재현은 [실제 실행 기록](actual-capture-v1/README.md)에 있다.
새 후보가 기존 검토 풀 밖에 있으면 별도 판정이 필요하다. 절차와 한계는
[추가 검토 보고서](review-v2/codex-ai-recheck-v1/recheck-report.md),
[최초 보고서](review-v2/codex-ai-v1/labeling-report.md), [실행 기록](run-manifest.md)에 남긴다.

## 1. 저장소를 받은 뒤 검증

아래 명령은 **저장소 루트에서 Python 3.11 이상**으로 실행한다. 별도 Python 패키지를 설치하지 않는다.

```bash
python3 -B evaluation/support-program-search/review/verify-shared-run.py \
  --run-dir evaluation/support-program-search/runs/support-program-catalog-20260906-v1 --with-recheck
```

고정 공고·질문·풀을 검증하고, 다섯 원본 판정을 임시 디렉터리에 다시 수집한 결과와 공유된 `ai-review.json`을
비교한다. 추가 210개 판정도 재수집하고 원인 감사의 출처·근거를 확인한다. 기존 세 모드와 새 AI-only·혼합
모드를 다시 계산해 공유된 JSON·CSV와 비교한다. 원본 파일은 덮어쓰지 않는다. 옵션을 빼면 최초 버전만 검증한다.
검증 성공은 **자료와 계산의 재현성**을 뜻하며 AI 판정의 정확성이나 실제 검색 성능을 보증하지 않는다.

전체 오프라인 테스트도 실행할 수 있다. CI는 같은 검증을 실행하며 외부 모델을 호출하지 않는다.

```bash
python3 -B -m unittest discover -s evaluation/support-program-search -p 'test_*.py'
python3 -B -m unittest discover -s evaluation/support-program-search/review -p 'test_*.py'
```

## 2. 엑셀 없이 화면 열기

공유하는 원본은 JSON·CSV이고, HTML은 필요할 때 다시 생성한다. 아래 명령은 AI 판정을 다시 실행하지 않는다.

```bash
RUN=evaluation/support-program-search/runs/support-program-catalog-20260906-v1
POOL="$RUN/review-v2"

python3 -B evaluation/support-program-search/review/select-review-mode.py \
  --fixture "$RUN/fixture-unlabeled.json" --query-set "$RUN/query-set.json" \
  --review-pool "$POOL/review-pool.csv" --pool-manifest "$POOL/review-pool-manifest.json" \
  --mode ai-only --ai-review "$POOL/codex-ai-v1/ai-review.json" \
  --ai-recheck "$POOL/codex-ai-recheck-v1/ai-recheck.json" \
  --conversation-judgments "$POOL/conversation-judgments.json" \
  --output-dir "$POOL/local-ai-only"
```

생성된 `review-v2/local-ai-only/review.html`을 브라우저로 연다. 출력 디렉터리가 이미 있으면 새 이름을 쓴다.
혼합 모드는 `--mode hybrid`와 새 출력 경로를 사용한다. 사람 모드는 `--mode human`으로 바꾸고
`--ai-review`와 `--ai-recheck`를 모두 생략한다. 기존 공유된 `selected-*-v1`은 비교 기준이므로 덮어쓰지 않는다.

화면의 입력란은 **사람 입력 전용**이다. AI 판정이 완료돼 있어도 사람 입력이 빈칸인 것은 정상이다.
최신 AI-only 결과는 `selected-ai-recheck-v1/selection.json`·`reviewed.csv`와 추가 검토 보고서에서 확인한다.

## 3. 여러 사람이 작업할 때

1. Git 이슈에서 담당 질문 ID와 작업 종류를 정하고 각자 브랜치에서 작업한다. 기존 판정 1,605건을 다시 만들지 않는다.
2. `fixture-unlabeled.json`, 질문, 원본 판정과 정책은 고정한다. 새 판정·기준 변경은 새 버전 디렉터리에 남기고
   변경 사유와 원본 참조를 기록한다. 점수를 높이려고 미확정을 부적합 처리하거나 합의 기준을 낮추지 않는다.
3. 사람 검토를 선택한 경우 화면의 **결과 파일 저장**으로 JSON을 내보낸다. 브라우저 자동 저장은 해당 PC에만
   남으며 Git과 동기화되지 않는다. 다른 기기에서는 **저장한 결과 불러오기**로 이어받는다.
4. 공유할 새 JSON은 기존 파일을 덮어쓰지 않고 담당자·버전을 구분한 경로에 저장한다. `runs/.gitignore`의
   허용 목록에 해당 파일만 추가하고, 민감정보와 출처를 확인한 뒤 코드와 함께 커밋·푸시/PR한다.
5. 같은 질문·공고의 판단이 충돌하면 Git의 마지막 수정 내용을 자동 정답으로 삼지 않는다. 담당자가 근거를
   확인해 새 버전으로 정리한다. AI가 만든 판정을 사람이 검토한 것처럼 표시하지 않는다.

현재 사람 검토는 필수가 아니다. AI-only에서 남은 18건은 미확정으로 보존한다. 혼합 모드를 선택하면
미확정 18건과 합의 표본 38건, 총 56건의 사람 확인이 필요하다. 기존 사람 모드는 319건 대기 상태를 유지한다.
`assignments.json`의 agentId는 과거 실행의 출처 기록이지 다른 PC에서 재접속해야 하는 계정이나 실행 요구사항이 아니다.

## 공유 파일과 제외 파일

- 포함: 전체 고정 공고·질문·풀 설정, 현재 321행 풀과 출처, 사람 판정 원본 2건, AI 입력·정책·실행 배정,
  다섯 판정 원본·수집 결과, 세 모드의 JSON·CSV, 추가 검토 210건·원인 감사·새 선택 결과, 보고서·실행 기록.
- 추가 포함: 실제 검색 실패 기록, 관측된 토큰 사용량, 실패 모델 출력 2개와 오프라인 재현 절차.
  이 진단 출력은 정답 라벨이나 성공한 검색 결과가 아니다.
- 거부된 judge-1 초안은 감사 기록용으로만 포함한다. 검증기는 명시한 최종 `judge-1.jsonl`~`judge-5.jsonl`만
  수집한다. 거부된 초안으로 판정을 대체하지 않는다.
- 제외: 중간 체크포인트, 이전 323행 풀, 실행 폴더에 복사했던 도구, 생성 HTML·XLSX·PNG, OS 파일, 임시 출력.
  원본과 재사용 도구로 생성할 수 있으며 현재 판정의 이어받기에 필요하지 않다. 로컬 파일을 삭제한 것은 아니다.
- 비밀정보·환경설정은 포함하지 않는다. 새 실행은 기본적으로 제외하고 필요한 자료만 검토 후 허용한다.

공고 요약·공식 URL·공개 문의처가 포함된 고정 평가 자료다. 원문 출처를 유지한다. 이 자료를 별도 공개 데이터셋으로
재배포하거나 저장소의 공개 범위를 확대할 때는 제공처 이용 조건과 개인정보 포함 여부를 별도로 확인한다.
파일 해시의 재현성을 위해 JSON·JSONL·CSV는 `.gitattributes`로 자동 줄바꿈 변환을 막는다. 자동 포맷도 적용하지 않는다.

## 실제 검색 측정은 별도

이 스냅샷을 Git으로 공유했다고 MySQL과 Qdrant가 자동으로 같은 상태가 되지는 않는다. 실제 검색 캡처는
동일 기준일·카탈로그 내용 해시가 맞는 DB·색인과 실행 설정을 따로 준비해야 한다. 현재 DB 내용이 달라졌다면
다른 스냅샷의 결과를 여기에 합치지 않는다. 기존 스냅샷 복원 없이 최신 DB 결과로 점수를 만들 수 없다.

실제 캡처는 운영 AI Service를 통해 OpenAI를 호출할 수 있으므로 비용·실행 범위를 확인한 뒤 별도로 실행한다.
저장된 Luna 판정으로 검색 결과를 대신 만들지 않는다. [실제 검색 캡처 절차](../../README.md#실제-검색-흐름-캡처)를 따른다.
