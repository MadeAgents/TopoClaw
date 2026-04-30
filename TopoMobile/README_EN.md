# TopoMobile

TopoMobile is the Android client in the project, hosting the mobile UI, chat interaction, and related capability integrations.

## Project Role

- Primary Android application
- Works with `TopoDesktop` and `customer_service` to form the end-to-end pipeline
- Codebase organized as a standard Kotlin + Android project

## Quick Start

- Main source code is under `app/`
- Open the `TopoMobile/` directory in Android Studio to build and run
- Ensure the local Android SDK and Gradle environment are available before running

## Directory Overview

- `app/` — main application source
- `scripts/` — utility scripts (originally documented in `scripts/README.md`, now merged here)

## Development Tips

- Prefer debugging, log inspection, and packaging through Android Studio
- After changing key interactions, run a basic regression (startup, main chat flow, core page navigation)
- When working with input method or pinyin dictionary capabilities, follow the `scripts` workflow below to update data

## APK Capabilities (TopoMobile)

Current capabilities organized by module:

### GUI Capabilities (Highlight)

TopoMobile's core strength is its task-execution and human-agent collaboration GUI system, emphasizing "perceivable, guidable, replayable, diagnosable":

- **Multi-layer overlay system**: Layered visual feedback for in-progress, paused, result, reasoning, and highlight-border states
- **Trajectory visualization pipeline**: Supports recording, overlay display, navigation hints, and event detail inspection for review and debugging
- **Task-state interaction components**: Task indicators, menus, and guided views reduce cognitive load during execution
- **Onboarding & tutorial UI**: Built-in accessibility guides, floating-ball guides, physical-wake guides, and tutorial overlays
- **Remote control & permission prompts**: Explicit UI prompts and flow handling for critical permissions, remote control, and install confirmations
- **Screenshot & visual aids**: Regular screenshots, chat screenshots, long screenshots, and full-screen image viewer work together for a "see → analyze → act" loop
- **Voice & input integration**: Voice input, recording feedback, and enhanced input methods combined with UI interactions for complex scenarios

This GUI system serves not only everyday chat but also smart task execution, trajectory collection, and device-cloud collaboration.

### 1) Chat & Social

- Chat sessions: send/receive messages, conversation list, chat details, multiple message formats
- Contacts: friend management, profile display, direct messaging
- Groups: group creation, member management, in-group messages, and group assistant capabilities

### 2) Assistant & Task Execution UX

- Assistant entry points: introduction, capability access, guided pages
- Task prompt system: in-progress, paused, result-state overlay prompts
- Reasoning & next-step hints: visual feedback during task execution

### 3) Trajectory Collection & Replay

- Trajectory recording: captures actions/events during task execution
- Trajectory collection: records real user-screen interactions to efficiently build trajectories
- Trajectory visualization: displays execution paths and interaction info via trajectory overlays and navigation overlays
- Cloud collaboration: supports trajectory data sync with cloud services

### 4) Wake, Permissions & Runtime Stability

- Physical wake: onboarding and integration flow for physical-wake triggers
- Accessibility: accessibility service integration, permission guides, and auxiliary data capabilities
- Floating ball / overlay: quick triggers, status hints, and interaction feedback
- Notification keep-alive (Companion mode): persistent notification and foreground service for improved runtime stability
- Remote control & install confirmation guides: UI prompts for remote control permissions and install confirmations

### 5) Media & Input Enhancement

- Screenshots: regular, chat, and long-screenshot support
- Voice: voice input assistance, recording management, voice feedback
- Input method enhancement: built-in IME service to work around non-editable input fields
- Full-screen image viewer: pinch-to-zoom and immersive viewing

### 6) Other Engineering Capabilities

- Network requests and version update prompts
- Anomaly detection and cache cleanup
- UI onboarding system (tutorial overlays, feature guide pages)

Note: Different builds may toggle certain features via configuration; refer to the current branch code and runtime configuration for details.

## scripts: Pinyin Data Integration

Scripts under `scripts/` generate Kotlin data from `pinyin-data` and `Rime-ice` dictionaries.

### Steps

1. Download data sources:

```bash
mkdir -p external
cd external
git clone https://github.com/mozillazg/pinyin-data.git
git clone https://github.com/iDvel/rime-ice.git
cd ..
```

2. Run the parsing script:

```bash
python apk2/scripts/parse_pinyin_data.py
```

3. Merge generated code:

The script produces `apk2/scripts/generated_pinyin_data.kt`, containing:

- `pinyinMapFromData` (single-character mapping)
- `commonWordsMapFromRime` (common word mapping)

Manually merge it into `PinyinDictionary.kt`, taking care to deduplicate and run regression checks.

## Data Sources

- `pinyin-data`: single-character pinyin mapping
- `Rime-ice`: common word dictionary

## Note

This README covers only the top-level essentials of TopoMobile. For more detailed module information, refer to the corresponding source directories and inline comments.
