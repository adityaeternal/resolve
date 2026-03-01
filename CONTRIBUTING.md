# Contributing to Resolve

Thank you for your interest in contributing to Resolve! This document provides guidelines for contributing to the project.

## Getting Started

1. Fork the repository
2. Clone your fork locally
3. Create a feature branch from `main`
4. Make your changes
5. Run tests to ensure nothing is broken
6. Submit a pull request

## Development Setup

### Prerequisites
- Android Studio (latest stable)
- JDK 17
- Android SDK 35 (minSdk 28)

### Building
```bash
cd android-companion
./gradlew assembleDebug
```

### Running Tests
```bash
cd android-companion
./gradlew testDebugUnitTest
```

### Coverage Report
```bash
cd android-companion
./gradlew jacocoTestReport
```

## Development Notes

- Test on a real Android device (Samsung recommended — they have unique Keystore behavior)
- Emulator or physical device — `adb install` works for quick iteration

## Code Style

- Follow Kotlin coding conventions
- Use meaningful variable and function names
- Keep functions focused and small
- Add unit tests for new functionality

## Commit Messages

We use [Conventional Commits](https://www.conventionalcommits.org/):

- `feat:` new feature
- `fix:` bug fix
- `test:` adding or updating tests
- `docs:` documentation changes
- `chore:` maintenance tasks
- `refactor:` code refactoring

## Pull Request Process

1. Create a feature branch (`git checkout -b feat/my-feature`)
2. Commit your changes with a conventional commit message
3. Push to your branch (`git push origin feat/my-feature`)
4. Open a Pull Request against `main`
5. Ensure CI checks pass
6. Request review from a maintainer
- Keep PRs focused and small
- Include a short test plan in PR description
- Do not commit API keys or credentials
- Test on a physical device before submitting
