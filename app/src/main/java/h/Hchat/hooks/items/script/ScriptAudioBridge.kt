package h.Hchat.hooks.items.script

import h.Hchat.media.AudioTransformBridge

class ScriptAudioBridge internal constructor(
    bridge: ScriptPluginBridge
) : AudioTransformBridge({ message -> bridge.log(message) })
