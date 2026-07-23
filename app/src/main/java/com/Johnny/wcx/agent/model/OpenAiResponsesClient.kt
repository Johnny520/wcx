package com.Johnny.wcx.agent.model

import com.Johnny.wcx.utils.WeLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [LlmClient] adapter for the OpenAI **Responses** wire format (`POST {baseUrl}/responses`).
 * Reasoning effort is nested at `reasoning.effort` (verified against the current OpenAI docs).
 *
 * The Responses stream emits typed SSE events; we consume:
 *  - `response.output_text.delta` → assistant text delta
 *  - `response.reasoning_summary_text.delta` → reasoning delta
 *  - `response.output_item.done` with a `function_call` item → a completed tool call
 *  - `response.completed` / `response.failed` → terminal
 */
class OpenAiResponsesClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) : LlmClient {
    private val endpoint = "${baseUrl.trimEnd('/')}/responses"

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val body = LlmJson.shallowMerge(buildBody(request), request.customJsonOverride)
        val resp = http.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(LlmJson.json.encodeToString(JsonObject.serializer(), body))
        }
        if (!resp.status.isSuccess()) {
            emit(LlmStreamEvent.Failed(LlmException("HTTP ${resp.status.value}: ${readBodyText(resp)}")))
            return@flow
        }

        val textBuf = StringBuilder()
        val reasoningBuf = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        var finishReason: String? = null
        var usage: LlmUsage? = null

        val channel = resp.bodyAsChannel()
        while (true) {
            @Suppress("DEPRECATION")
            val line = channel.readUTF8Line() ?: break
            val data = SseParser.dataOrNull(line) ?: continue
            if (data == "[DONE]") break
            val event = runCatching { LlmJson.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue

            when (event["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                "response.output_text.delta" ->
                    event["delta"]?.jsonPrimitive?.contentOrNullSafe()?.let {
                        textBuf.append(it); emit(LlmStreamEvent.TextDelta(it))
                    }

                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                    event["delta"]?.jsonPrimitive?.contentOrNullSafe()?.let {
                        reasoningBuf.append(it); emit(LlmStreamEvent.ReasoningDelta(it))
                    }

                "response.output_item.done" -> {
                    val item = event["item"]?.jsonObject
                    if (item?.get("type")?.jsonPrimitive?.contentOrNullSafe() == "function_call") {
                        val id = item["call_id"]?.jsonPrimitive?.contentOrNullSafe()
                            ?: item["id"]?.jsonPrimitive?.contentOrNullSafe() ?: "call_${toolCalls.size}"
                        val name = item["name"]?.jsonPrimitive?.contentOrNullSafe() ?: continue
                        val args = item["arguments"]?.jsonPrimitive?.contentOrNullSafe() ?: "{}"
                        toolCalls.add(LlmToolCall(id, name, args))
                    }
                }

                "response.completed" -> {
                    finishReason = "stop"
                    usage = parseUsage(event["response"]?.jsonObject?.get("usage")?.jsonObject) ?: usage
                }

                "response.failed", "error" -> {
                    emit(LlmStreamEvent.Failed(LlmException("Responses stream error: $data")))
                    return@flow
                }
            }
        }

        emit(
            LlmStreamEvent.Completed(
                LlmMessage(
                    role = LlmRole.ASSISTANT,
                    content = textBuf.toString().ifEmpty { null },
                    reasoning = reasoningBuf.toString().ifEmpty { null },
                    toolCalls = toolCalls,
                ),
                finishReason ?: if (toolCalls.isNotEmpty()) "tool_calls" else null,
                usage,
            )
        )
    }

    // -- request body ---------------------------------------------------------

    private fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", request.modelIdRemote)
        put("stream", true)
        request.reasoningEffort?.let { effort ->
            putJsonObject("reasoning") { put("effort", effort) }
        }
        // Responses names the output-token cap `max_output_tokens` (official field).
        request.maxTokens?.let { put("max_output_tokens", it) }
        // Responses uses a flat `input` array of typed items.
        putJsonArray("input") { request.messages.forEach { add(encodeItem(it)) } }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { spec ->
                    addJsonObject {
                        // Responses tools are flat function objects (no nested "function").
                        put("type", "function")
                        put("name", spec.name)
                        put("description", spec.description)
                        put("parameters", spec.parametersSchema)
                    }
                }
            }
        }
    }

    private fun encodeItem(msg: LlmMessage): JsonObject = when (msg.role) {
        LlmRole.TOOL -> buildJsonObject {
            put("type", "function_call_output")
            put("call_id", msg.toolCallId ?: "")
            put("output", msg.content ?: "")
        }

        LlmRole.ASSISTANT -> buildJsonObject {
            // Assistant turns with tool calls are represented as function_call items; text as message.
            if (msg.toolCalls.isNotEmpty()) {
                // Emit the first function_call; multiple calls in one item aren't representable,
                // so callers should keep one tool call per assistant item when replaying history.
                val tc = msg.toolCalls.first()
                put("type", "function_call")
                put("call_id", tc.id)
                put("name", tc.name)
                put("arguments", tc.argumentsJson)
            } else {
                put("type", "message")
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "output_text")
                        put("text", msg.content ?: "")
                    }
                }
            }
        }

        else -> buildJsonObject {
            put("type", "message")
            put("role", if (msg.role == LlmRole.SYSTEM) "system" else "user")
            putJsonArray("content") {
                // Only emit a text part when there's text, or when there are no images to carry it.
                if (!msg.content.isNullOrEmpty() || msg.images.isEmpty()) {
                    addJsonObject {
                        put("type", "input_text")
                        put("text", msg.content ?: "")
                    }
                }
                // Responses images are `input_image` items with the data URI in `image_url`.
                msg.images.forEach { img ->
                    addJsonObject {
                        put("type", "input_image")
                        put("image_url", img.dataUri)
                    }
                }
            }
        }
    }

    private suspend fun readBodyText(resp: HttpResponse): String =
        runCatching { resp.bodyAsChannel().readRemainingText() }.getOrElse { "<unreadable>" }
            .also { WeLogger.w(TAG, "error response: $it") }

    private companion object {
        const val TAG = "OpenAiResponses"
    }
}
