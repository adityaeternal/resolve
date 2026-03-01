package com.cssupport.companion

/**
 * Centralized configuration constants for the agent loop.
 *
 * All tunable parameters (timeouts, limits, thresholds) live here instead
 * of being scattered as magic numbers across the codebase. Grouped by
 * concern for discoverability.
 */
object AgentConfig {

    // -- Screen stabilization & loading ------------------------------------------

    /** Max time (ms) to wait for the screen to stabilize after an action. */
    const val SCREEN_STABILIZE_MAX_WAIT_MS = 3000L

    /** Poll interval (ms) when waiting for screen stabilization. */
    const val SCREEN_STABILIZE_POLL_INTERVAL_MS = 300L

    /** Max time (ms) to wait for the first screen to have meaningful content. */
    const val INITIAL_LOAD_WAIT_MS = 8_000L

    /** Poll interval (ms) when waiting for initial screen load. */
    const val INITIAL_LOAD_POLL_INTERVAL_MS = 500L

    /**
     * Minimum interactive elements required for a screen to be considered "loaded".
     * Splash screens and loading screens typically have 0-2 interactive elements.
     * A real app home screen has 5+.
     */
    const val MIN_ELEMENTS_FOR_LOADED_SCREEN = 3

    /** Small delay (ms) after initial load to let late-arriving elements render. */
    const val POST_LOAD_SETTLE_MS = 300L

    // -- Action timing -----------------------------------------------------------

    /** Delay (ms) after an action before verifying its effect. */
    const val POST_ACTION_VERIFY_DELAY_MS = 300L

    /** Small delay (ms) before the next LLM call after a plan update. */
    const val POST_PLAN_DELAY_MS = 200L

    /** Delay (ms) after detecting a wrong app before retrying. */
    const val WRONG_APP_BACK_DELAY_MS = 500L

    /** Delay (ms) after re-launching target app when in wrong app. */
    const val WRONG_APP_RELAUNCH_DELAY_MS = 2000L

    /** Delay (ms) between checks while the agent is paused. */
    const val PAUSE_POLL_INTERVAL_MS = 500L

    /** Delay (ms) before retrying after a no-tool-call response. */
    const val NO_TOOL_CALL_RETRY_DELAY_MS = 500L

    /** Delay (ms) between polling while waiting for own app to leave foreground. */
    const val OWN_APP_POLL_DELAY_MS = 1000L

    /** Timeout (ms) for the wait_for_response action. */
    const val WAIT_FOR_CONTENT_CHANGE_TIMEOUT_MS = 5000L

    // -- Iteration & retry limits ------------------------------------------------

    /** Hard ceiling on total loop iterations (including non-counted ones). */
    const val MAX_TOTAL_LOOP_ITERATIONS = 100

    /** Max consecutive identical actions before the action is skipped. */
    const val MAX_CONSECUTIVE_DUPLICATES = 3

    /** Max consecutive "no tool call" responses before stopping retries. */
    const val MAX_NO_TOOL_CALL_RETRIES = 4

    /** Max LLM call failures before the loop gives up. */
    const val MAX_LLM_RETRIES = 5

    /** Max consecutive turns in a non-target app before auto-correcting. */
    const val MAX_WRONG_APP_TURNS = 2

    /** Maximum scroll actions before the agent gets a warning. */
    const val MAX_SCROLL_ACTIONS = 8

    /** Max time (ms) to wait for own app to leave the foreground. */
    const val MAX_FOREGROUND_WAIT_MS = 60_000L

    /** Max retries for re-launching the target app while own app is visible. */
    const val MAX_APP_LAUNCH_RETRIES = 4

    /** Interval (ms) between app launch retry attempts. */
    const val APP_LAUNCH_RETRY_INTERVAL_MS = 8_000L

    // -- Observation masking & token budget ---------------------------------------

    /**
     * Number of recent full-detail turns to keep when masking observations.
     * Each "turn" is 3 messages (observation + tool call + result).
     * Turns older than this get their observation content replaced with a summary.
     */
    const val OBSERVATION_MASK_KEEP_RECENT = 4

    /** Maximum estimated tokens before aggressive masking. */
    const val MAX_ESTIMATED_TOKENS = 12_000

    /** Approximate chars-per-token ratio for token estimation. */
    const val CHARS_PER_TOKEN = 4

    // -- Pattern detection -------------------------------------------------------

    /** Recent fingerprints window for oscillation detection. */
    const val FINGERPRINT_WINDOW = 6

    /** Stagnation threshold: turns without screen change before injecting hint. */
    const val STAGNATION_THRESHOLD = 3

    /** Navigation attempts before re-planning hint is shown. */
    const val REPLAN_THRESHOLD = 5

    /** Maximum recent actions tracked for pattern detection. */
    const val RECENT_ACTIONS_WINDOW = 10

    // -- LLM retry backoff -------------------------------------------------------

    /** Base delay (ms) for exponential backoff on LLM errors. */
    const val LLM_RETRY_BASE_DELAY_MS = 2000L

    /** Maximum backoff delay (ms) for LLM retries. */
    const val LLM_RETRY_MAX_DELAY_MS = 30_000L

    /** Maximum exponent for backoff shift (prevents overflow). */
    const val LLM_RETRY_MAX_EXPONENT = 4

    /** Minimum jitter multiplier for backoff randomization. */
    const val LLM_RETRY_JITTER_MIN = 0.9

    /** Jitter range for backoff randomization (added to JITTER_MIN). */
    const val LLM_RETRY_JITTER_RANGE = 0.2

    // -- Screen geometry heuristics ----------------------------------------------

    /** Default screen height (px) when no elements are present. */
    const val DEFAULT_SCREEN_HEIGHT = 2400

    /** Fraction of screen height below which elements are in the "bottom bar". */
    const val BOTTOM_BAR_FRACTION = 7.0 / 8.0

    /** Fraction of screen height above which elements are in the "top area" (excluded from chat button detection). */
    const val TOP_AREA_FRACTION = 1.0 / 8.0

    /** Fraction of screen height below which elements count as "bottom third" for send button detection. */
    const val SEND_BUTTON_MIN_Y = 1600

    /** Minimum X position for send button candidates (right side of screen). */
    const val SEND_BUTTON_MIN_X = 700

    /** Minimum/maximum icon dimensions for send button detection. */
    const val SEND_BUTTON_MIN_SIZE = 20
    const val SEND_BUTTON_MAX_SIZE = 200

    /** Max chat button label length for IN_CHAT guidance. */
    const val MAX_CHAT_BUTTON_LABEL_LENGTH = 80

    /** Min chat button label length for IN_CHAT guidance. */
    const val MIN_CHAT_BUTTON_LABEL_LENGTH = 3

    /** Max option buttons to show in chat guidance. */
    const val MAX_CHAT_OPTIONS_SHOWN = 8

    /** Minimum content buttons required to trigger "option buttons detected" guidance. */
    const val MIN_CONTENT_BUTTONS_FOR_OPTIONS = 2

    // -- Misc --------------------------------------------------------------------

    /** Our own package name for self-detection. */
    const val OWN_PACKAGE = "com.cssupport.companion"

    /** Maximum tree traversal depth for accessibility node scanning. */
    const val MAX_NODE_TRAVERSAL_DEPTH = 20

    /** Minimum product keyword hits to trigger "wrong screen" warning. */
    const val WRONG_SCREEN_PRODUCT_THRESHOLD = 4

    /** Maximum navigation keyword hits to allow alongside product keywords. */
    const val WRONG_SCREEN_NAV_THRESHOLD = 1

    /** Moderate wrong screen detection: product keywords needed. */
    const val WRONG_SCREEN_MODERATE_PRODUCT_THRESHOLD = 2

    /** Minimum login indicator hits to trigger login wall detection. */
    const val LOGIN_WALL_MIN_HITS = 2

    /** Minimum iterations before login wall detection activates. */
    const val LOGIN_WALL_MIN_ITERATIONS = 2

    /** System packages that show dialogs on behalf of the target app. */
    val SYSTEM_DIALOG_PACKAGES = setOf(
        "permissioncontroller",
        "packageinstaller",
        "com.android.systemui",
    )
}
