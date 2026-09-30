package sh.zeron.android.core

import uniffi.zeron_core.ChatConfig
import uniffi.zeron_core.SandboxLevel

/** The config a new session starts with; shared by an immediate and a scheduled start. */
object NewSessionConfig {
    fun chatConfig(harness: String, model: String?, effort: String?) = ChatConfig(
        harness = harness,
        model = model,
        reasoning = effort,
        modelOptions = emptyMap(),
        sandbox = SandboxLevel.WORKSPACE_WRITE,
    )
}
