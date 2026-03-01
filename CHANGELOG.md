# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-03-01

### Added
- AccessibilityEngine test suite with Robolectric (727 LOC)
- PhaseDetector comprehensive test coverage (809 LOC)
- PromptBuilder behavioral tests (433 LOC)
- ConversationManager test suite (425 LOC)
- AgentConfig test coverage (302 LOC)
- Robolectric 4.14.1 dependency for Android resource testing

### Changed
- JaCoCo LINE coverage threshold: 0.20 → 0.35
- JaCoCo BRANCH coverage threshold: 0.15 → 0.25
- Enabled `isIncludeAndroidResources` for Robolectric support
- Total test suite: 16 files, 6,564 lines of test code

## [1.0.0] - 2026-02-28

### Added
- Accessibility agent service with screen content analysis
- ChatGPT OAuth integration for AI-powered assistance
- Floating overlay UI with configurable chat interface
- Order-page detection and guided checkout flow
- Onboarding wizard with permission setup
- Settings screen with authentication management
- 187 unit tests with JaCoCo coverage reporting
- GitHub Actions CI workflow

### Fixed
- Agent restart reliability improvements
- Settings authentication flow redesign
- UX friction points in onboarding
