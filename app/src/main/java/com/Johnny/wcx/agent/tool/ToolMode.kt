package com.Johnny.wcx.agent.tool

/**
 * The four-state permission of a tool (§3.1). Shared by the runtime [ToolRegistry] and the Room
 * `tool_permissions` table.
 *
 * - [ENABLED]: executed directly, no gate.
 * - [MANUAL_APPROVAL]: suspends the loop for an explicit user decision before executing.
 * - [SMART_APPROVAL]: a small model decides ({allow, reason}); no user interaction on allow.
 * - [DISABLED]: fully invisible to the model (not injected, not discoverable).
 */
enum class ToolMode {
    ENABLED,
    MANUAL_APPROVAL,
    SMART_APPROVAL,
    DISABLED,
    ;

    companion object {
        /** Factory default derived from whether a tool has side effects (§3.2). */
        fun defaultFor(sideEffect: Boolean): ToolMode =
            if (sideEffect) MANUAL_APPROVAL else ENABLED
    }
}
