# 오픈소스·외부 자료 고지 (괜차나 · Gwenchana)

앱에 들어간 외부 코드·글꼴·모델과 라이선스. 원문은 `app/src/main/assets/licenses/` 에 있다.

| 이름 | 용도 | 라이선스 | 출처 |
|---|---|---|---|
| Asta Sans (42dot) | 앱 글꼴 (Pleos 디자인 가이드 지정) | SIL Open Font License 1.1 | https://github.com/42dot/Asta-Sans , Google Fonts `ofl/astasans` |
| three.js 0.186.1 (`three.core.js`, `three.module.js`, `OrbitControls.js`, `GLTFLoader.js`, `BufferGeometryUtils.js`, `SkeletonUtils.js`) | 사고 장면 3D 재구성 | MIT | https://github.com/mrdoob/three.js (npm `three`) |
| MediaPipe Tasks Vision 1.2.0 | 블랙박스 프레임 차량 인식 실행 | Apache License 2.0 | https://github.com/google-ai-edge/mediapipe (Maven `com.google.mediapipe:tasks-vision`) |
| EfficientDet-Lite0 (TensorFlow) | 차량 인식 모델 (COCO 학습) | Apache License 2.0 (모델 메타데이터·Kaggle 표기 확인) | Kaggle `tensorflow/efficientdet` tfLite `lite0-detection-metadata` v1 |
| Kenney Mini Characters 1.0 (character-male-a → person.glb) | 사고 3D의 사람 모델 | CC0 1.0 | https://kenney.nl/assets/mini-characters |
| AndroidX (core, activity, compose, webkit) | 앱 기반 | Apache License 2.0 | https://developer.android.com/jetpack/androidx |
| Pleos SDK (Vehicle, TextToSpeech, SpeechToText, FusedLocation, NaviHelper) | 차량 데이터·음성 | Pleos Playground 약관 | https://document.pleos.ai |

- 3D 상대 차는 직접 만든 모델(`design/car/build_other_cars.py`), 사람은 Kenney CC0 모델, 모델을 못 읽으면 상자 조합 차로 그린다. 에뮬레이터·차량(Pleos DrivingView)의 3D 모델은 쓰지 않았다(재배포 권리 없음).
- BeamNG.drive(BeamNG GmbH)는 개발 시험 환경으로 쓴 정식 구매 게임이다. 앱·저장소에 게임 파일을 넣지 않았고, 제휴 관계가 없다. `game/beamng/mod`는 직접 작성한 모드.
- Ultralytics YOLO 등 AGPL 라이선스 모델·코드는 쓰지 않았다.
- 안내 화면의 사람·장면 그림과 영상은 AI로 생성했다(Google Gemini, Flow).
- COCO 데이터셋(모델 학습 데이터) 주석은 CC BY 4.0.
