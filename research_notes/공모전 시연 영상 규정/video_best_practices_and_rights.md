# 공모전 시연 영상: 구성 모범사례와 제3자 권리 위험 (괜차나)

조사일 2026-11-12. 한계: 웹 검색 요약 위주이고 Devpost·Google Solution Challenge·YC 원문은 직접 열지 못했다. 공식 규정과 의견을 구분해 표시했다. 현대 Pleos 공모전의 영상 규정 자체는 이번 조사 범위 밖이라 확인하지 못했다(접수 요강을 따로 볼 것).

## 1. 해커톤/앱 공모전 심사자가 권하는 시연 영상 구성

### Takeaway
출처 대부분이 "문제 짧게(15~20초) → 실제 작동하는 앱 시연이 본론 → 짧은 마무리"를 권하고, 긴 인트로와 기능 나열을 실수로 꼽는다. "화자가 카메라에 나와 배경을 길게 말하는 오프닝"은 공식 규정이 아니라 선택 사항이며, 쓴다면 짧아야 한다는 것이 근거 방향이다.

### Cited Findings
- (의견) 해커톤 팁: 문제 15~20초 뒤 핵심 기능 3~5개를 1~2분 보여 주는 구조가 흔히 인용된다 — [Reskilll 해커톤 데모 팁](https://blogs.reskilll.com/hackathon-demo-presentation-tips-pitch-3-minutes-win-2026/)
- (의견) 길고 장황한 소개 대신 프로젝트 자체를 먼저 보여 주라. 영상이 늘어지면 심사자가 흥미를 잃는다. 평가 기준을 놓치면 탈락 위험 — [hackathon.com 팁](https://tips.hackathon.com/article/creating-the-best-demo-video-for-a-hackathon-what-to-know)
- (의견) RevenueCat Shipaton 가이드: 심사자는 3분만 볼 의무가 있으니 훅과 증거는 처음 60~90초에 둔다. 0:00~0:20은 "누구의 어떤 불편"(Setup+Need), 알아볼 만한 한두 순간으로 시작. 목표 기기에서 앱이 실제 도는 것을 보여 줘야 한다(그 대회 제출 요건). "증거 먼저, 다듬기는 나중". 나레이션은 화면 설명이 아니라 사용자가 얻는 결과에 맞춘다. 흔한 실수: 기능 목록 나열, 목표 기기 화면 없음, 무허가 음악·상표·제3자 자산, 공개 안 된 링크 — [RevenueCat](https://www.revenuecat.com/blog/engineering/how-to-win-shipaton-part-4-pitching-your-app)
- (의견) 심사자는 제작 품질보다 해법과 작동 여부를 본다. 자막은 이해를 돕는다. 업로드가 몇 분~몇 시간 걸릴 수 있으니 일찍 올려라 — 위 해커톤 검색 요약(출처: [Reskilll](https://blogs.reskilll.com/hackathon-demo-presentation-tips-pitch-3-minutes-win-2026/), [Devpost 제출 글](https://dev.to/jacklyn/how-to-make-your-devpost-submissions-not-suck-37i0)). 자막 문장은 검색 요약에서 가져온 것이라 원문 대조는 못 했다.
- (공식 규정, 해당 대회 한정) Google Solution Challenge: 데모 영상은 120초를 넘을 수 없고 넘으면 앞 120초만 평가. 심사 포인트는 사용자가 앱과 어떻게 상호작용하는지, 작동하는 앱 — [Google 공식 FAQ(검색 요약)](https://developers.google.com/community/dsc-solution-challenge/faq). 연도별로 2분/3분 표기가 엇갈려(2025 슬라이드는 3분) 해당 연도 규정 확인 필요 — [2025 슬라이드](https://www.slideshare.net/slideshow/solution-challenge-introduction-and-steps/275151554)
- 한국 공모전 영상 길이 예: 서울대 공대 홍보영상 30~60초 세로형, AI 숏폼 공모전 1분 이내 세로형. 해상도는 FHD 16:9 권장, 1280x720 이상 mp4 등 공고마다 다름. 심사 기준은 주제 적합성·차별성·참신성·흥미 — [서울대 공고 PDF](https://mse.snu.ac.kr/wp-content/uploads/2026/05/제15회-공과대학-창의설계축전-홍보-동영상-공모전-개최안발송.pdf), [올콘 AI 숏폼](https://www.all-con.co.kr/view/contest/536772?device=pc)

### Inferences
- 제안 구조(발표자 정면 오프닝 → 시연 → 마무리)는 허용되나, 오프닝은 15~20초(최대 30초)로 줄이고, "배경 문제"는 말보다 사고 직후 시나리오 장면(충격 → "다치셨나요?")으로 보여 주는 편이 근거와 맞다. 팀 비전은 마무리 10초로 옮기는 안을 권한다. 총 길이는 2~3분 이내(요강 우선)에 시연 비중 60% 이상이 안전선(추정).
- 1차는 서류+영상 심사라 영상이 첫인상이다. 거짓 완료 금지 규칙에 따라, 시연에는 실제 작동하는 흐름만 넣고 BeamNG가 만든 데이터임을 자막으로 밝힌다.
- 훅 15초 안에 "차가 제3의 탑승자처럼 사실만 기록한다"를 한 문장으로.

### Gaps
- Devpost 공식 영상 가이드, YC 데모 조언, 삼성/LG 공모전 영상 가이드 원문은 검색에서 못 찾았다(2차 요약뿐). "토킹헤드 길이 대 시연 길이" 비율을 정량으로 제시한 출처는 찾지 못했다.
- 자막/음질 실수는 일반 조언 수준의 출처만 있고 수치 근거는 없다.

## 2. 한국 공모전 관행: 얼굴 노출, 블라인드 심사

### Takeaway
얼굴 노출 여부나 블라인드 관행을 일반화한 자료는 찾지 못했다. 공고마다 다르므로 현대 공모전 요강·접수양식을 직접 확인해야 한다. 프로젝트 규칙(CLAUDE.md 7번)상 실명·학교명 비노출이 이미 필요하다.

### Cited Findings
- 서울대 공고는 초상권 등 권리 문제 책임이 제출자에게 있다고 명시 — [서울대 공고 PDF](https://mse.snu.ac.kr/wp-content/uploads/2026/05/제15회-공과대학-창의설계축전-홍보-동영상-공모전-개최안발송.pdf)
- 이 저장소 내부 근거: 접수양식은 실명 기재 금지 조항이 있고 공개 저장소에 실명을 올리지 않는 규칙 — /home/user/gwenchana/CLAUDE.md (규칙 7, 접수양식 항목)

### Inferences
- 얼굴을 쓰면 실명·소속 자막, 학교 로고·명찰, 방 배경 노출을 피하고 출연 동의를 받아 둔다. 안전한 대안은 얼굴 없이 목소리+자막 또는 손/화면 위주(추정).

### Gaps
- Pleos 앱마켓 공모전이 영상에 얼굴을 허용하는지, 블라인드 여부: 미확인. 운영국(appdevchallenge@hyundai-partners.com) 문의가 정확하나 이 작업에서는 연락하지 않음.

## 3. 권리 위험

### Takeaway
가장 큰 위험은 BeamNG 영상의 상업 이용(수상·사업화 포함) 해석이다. 그 외 음악, 현대·Pleos 표기, AI 생성 이미지 표기를 점검해야 한다.

### Cited Findings
- (공식) BeamNG EULA §4.2: 서면 승인 없이 소프트웨어나 일부를 "어떤 상업적 목적으로도" 이용 금지. EULA는 영상·스크린샷·녹화를 직접 다루지 않고, §3.3은 소프트웨어를 다른 저작물에 통합하는 것을 금지(모딩 가이드 예외). 별도 라이선스는 §3.6에서 licensing@beamng.gmbh로 문의 — [BeamNG EULA](https://beamng.com/game/support/policies/eula/)
- (공식 일부) 크리에이터 프로그램은 서드파티 콘텐츠(모드·클립·음악)를 적절히 크레딧하라고 요구. 단 프로그램 회원용 규정이고 영상 수익화 규정은 아님 — [BeamNG Creator Program](https://www.beamng.com/game/community/creator-program)
- (비공식) 팬 채널 페이지는 BeamNG 제작사가 게임 소유자의 유튜브 업로드·수익화를 허용한다고 소개하나, 공식 FAQ 원문은 열지 못했다. 포럼 답변은 "직접 녹화·편집하고 BeamNG를 출처로 밝히면 된다" 수준(커뮤니티 의견) — [CollabPals](https://www.collabpals.com/@beamng-m1t), [BeamNG 포럼](https://www.beamng.com/threads/beamng-inaugural-cinematic-clip-contest.71920)
- (공식) 음악: YouTube 오디오 라이브러리 일반 라이선스 트랙은 유튜브 영상용이라는 2차 출처가 다수이고, CC-BY 트랙은 출처 표기로 다른 곳에서도 가능하다고 한다. 공식 도움말 원문은 확인 못 함. Creator Music은 유튜브 업로드 영상에만 사용 가능(공식) — [YouTube 도움말(Creator Music)](https://support.google.com/youtube/answer/11610609?hl=en), [정리글](https://licenseorg.com/guide/music-audio/youtube-audio-library)
- (의견) 영상에서 무허가 음악, 상표, 제3자 자산이 흔한 실수 — [RevenueCat](https://www.revenuecat.com/blog/engineering/how-to-win-shipaton-part-4-pitching-your-app)
- 최근 한국 공모전은 AI 사용 표기나 제작 증빙(프롬프트 등)을 요구하는 경우가 늘고 있다(공고별) — [올콘/브런치 검색 요약](https://brunch.co.kr/@designerkang/56)
- 프로젝트 내부 규칙: 현대·Pleos 로고 사용 금지, 이름 근처 Hyundai/Pleos/Gleo 단어 금지(권리 침해 시 수상 취소 조항), AI 일러스트 규칙 미확인, 그림의 차가 아이오닉 5와 유사 — /home/user/gwenchana/CLAUDE.md

### Inferences
- BeamNG: 공모전 접수·평가는 영리 목적이 아닌 "시연"이라 해석 여지는 있으나, 수상·사업화 단계는 §4.2 위반 소지. EULA에 영상 예외가 명시돼 있지 않아 "허용"이라 단정 불가. 대책: (1) 영상은 직접 녹화, 게임 화면은 최소화하거나 "시험 데이터 생성용 게임"이라 자막 표기, (2) 게임 속 실제 브랜드 차량(모드 포함) 노출 주의, (3) 수상·사업화 전 licensing 문의(CLAUDE.md 다음 할 일 10번과 일치).
- 에뮬레이터 UI: Pleos 에뮬레이터 화면에 Pleos/현대 브랜딩이 있으면 로고 규칙과 충돌 가능 → 크롭하거나 요강이 허용하는지 확인(미확인).
- 음악은 CC0나 본인이 만든 음원, 또는 라이선스를 캡처로 남긴 곡만. 음악 없이 나레이션+효과음도 안전.
- AI 일러스트가 영상에 나오면 "AI 생성 이미지 포함" 자막/설명란 표기를 권장.

### Gaps
- BeamNG 공식 FAQ의 영상 수익화 문구, 콘텐츠 가이드라인 원문: 못 열었다. 공모전 제출에 대한 BeamNG 입장: 없음. YouTube 오디오 라이브러리 약관 원문: 못 열었다(2차 출처만).
- 현대 Pleos 공모전의 저작권·초상권·AI 표기 조항: 이번에 확인 못 함.

## 4. 숏폼(9:16) 모범사례

### Takeaway
9:16 세로, 첫 2~3초 훅, 15~60초가 흔한 권장이나 출처는 대부분 마케팅 블로그라 의견 수준이다. 공모전 숏폼은 요강의 길이·해상도가 우선.

### Cited Findings
- 유튜브 쇼츠 최대 길이 3분(2024-10 이후 업로드). 구 60초 한도 정보는 오래됨 — [Hootsuite](https://blog.hootsuite.com/youtube-shorts/)
- 9:16 표준, 훅은 첫 2~3초, 권장 길이 15~60초(주로 20~45초). 완주율이 길이보다 중요하다는 주장은 벤더 주장이라 미검증 — [JoinBrands](https://joinbrands.com/blog/youtube-shorts-best-practices/), [Opus](https://www.opus.pro/blog/ideal-youtube-shorts-length-format-retention)
- 한국 공모전 숏폼 예: 1분 이내 세로 — [올콘](https://www.all-con.co.kr/view/contest/536772?device=pc)

### Inferences
- 괜차나 숏폼: 0~3초 충돌 장면과 "다치셨나요?" 화면, 30~45초에 안내 → 기록서 PDF까지. 가로 에뮬레이터 화면은 세로 프레임에 맞춰 확대/자막 크게.

### Gaps
- 공모전 숏폼 전용 공식 모범사례와 8번 숏폼 요건(접수양식 8번): 미확인.
