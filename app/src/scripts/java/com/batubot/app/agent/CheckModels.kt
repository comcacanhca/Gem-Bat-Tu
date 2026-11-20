package com.batubot.app.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

data class DiscoveredModel(
    val id: String,
    val isReasoning: Boolean
)

fun main() {
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    try {
        println("=== PROCESS STEP 1: Quét các provider xem có những model free nào (Không dùng Fallback) ===")

        val clineApiKey = loadPropertiesKey("CLINE_API_KEY", "")
        val geminiApiKey = loadPropertiesKey("GEMINI_API_KEY", "")
        val openRouterApiKey = loadPropertiesKey("OPENROUTER_API_KEY", "")
        val nvidiaApiKey = loadPropertiesKey("NVIDIA_API_KEY", "")

        val discoveredGoogleModels = discoverGoogleModels(client, geminiApiKey)
        println("[STEP 1] Google Models Scanned (${discoveredGoogleModels.size}): ${discoveredGoogleModels.map { it.id }}")

        val discoveredOpenRouterFreeModels = discoverOpenRouterFreeModels(client, openRouterApiKey)
        println("[STEP 1] OpenRouter Free Models Scanned (${discoveredOpenRouterFreeModels.size}): ${discoveredOpenRouterFreeModels.map { "${it.id} (reasoning=${it.isReasoning})" }}")

        val discoveredClineFreeModels = discoverClineFreeModels(client, clineApiKey)
        println("[STEP 1] Cline Free Models Scanned (${discoveredClineFreeModels.size}): ${discoveredClineFreeModels.map { "${it.id} (reasoning=${it.isReasoning})" }}")

        val discoveredNvidiaModels = discoverNvidiaModels(client, nvidiaApiKey)
        println("[STEP 1] NVIDIA Models Scanned (${discoveredNvidiaModels.size}): ${discoveredNvidiaModels.map { "${it.id} (reasoning=${it.isReasoning})" }}")

        println("\n=== PROCESS STEP 2: Check từng model free xem có hoạt động không ===")
        val results = JSONArray()
        var activeGoogleCount = 0
        var activeOpenRouterCount = 0
        var activeClineCount = 0
        var activeNvidiaCount = 0

        // Health check từng model Google Gemini
        for (item in discoveredGoogleModels) {
            val ttft = testGeminiModel(client, geminiApiKey, item.id)
            if (ttft != null) {
                activeGoogleCount++
                val modelLower = item.id.lowercase()
                val isLite = modelLower.contains("flash-lite") || modelLower.contains("lite")
                val isDefault = isLite && modelLower.contains("flash-lite-latest")
                results.put(JSONObject().apply {
                    put("provider", "Google")
                    put("model", item.id)
                    put("reasoning", item.isReasoning)
                    put("default", isDefault)
                    put("ttft", ttft)
                })
                println("[STEP 2] [ACTIVE] Google: ${item.id} (TTFT: ${ttft}ms)")
            } else {
                println("[STEP 2] [INACTIVE] Google: ${item.id}")
            }
        }

        // Health check từng model OpenRouter
        for (item in discoveredOpenRouterFreeModels) {
            val ttft = testOpenRouterModel(client, openRouterApiKey, item.id)
            if (ttft != null) {
                activeOpenRouterCount++
                results.put(JSONObject().apply {
                    put("provider", "OpenRouter")
                    put("model", item.id)
                    put("reasoning", item.isReasoning)
                    put("default", false)
                    put("ttft", ttft)
                })
                println("[STEP 2] [ACTIVE] OpenRouter: ${item.id} (Reasoning: ${item.isReasoning}, TTFT: ${ttft}ms)")
            } else {
                println("[STEP 2] [INACTIVE] OpenRouter: ${item.id}")
            }
        }

        // Health check từng model Cline API
        for (item in discoveredClineFreeModels) {
            val ttft = testClineModel(client, clineApiKey, item.id)
            if (ttft != null) {
                activeClineCount++
                results.put(JSONObject().apply {
                    put("provider", "Cline")
                    put("model", item.id)
                    put("reasoning", item.isReasoning)
                    put("default", false)
                    put("ttft", ttft)
                })
                println("[STEP 2] [ACTIVE] Cline: ${item.id} (Reasoning: ${item.isReasoning}, TTFT: ${ttft}ms)")
            } else {
                println("[STEP 2] [INACTIVE] Cline: ${item.id}")
            }
        }

        // Health check từng model NVIDIA (chỉ lấy model có chứa "gemma")
        for (item in discoveredNvidiaModels) {
            val ttft = testNvidiaModel(client, nvidiaApiKey, item.id)
            if (ttft != null) {
                activeNvidiaCount++
                results.put(JSONObject().apply {
                    put("provider", "NVIDIA")
                    put("model", item.id)
                    put("reasoning", item.isReasoning)
                    put("default", false)
                    put("ttft", ttft)
                })
                println("[STEP 2] [ACTIVE] NVIDIA: ${item.id} (Reasoning: ${item.isReasoning}, TTFT: ${ttft}ms)")
            } else {
                println("[STEP 2] [INACTIVE] NVIDIA: ${item.id}")
            }
        }

        // Kiểm tra từng provider riêng biệt: Nếu BẤT KỲ provider nào có 0 model active -> Báo FAIL ngay
        val failedProviders = mutableListOf<String>()
        if (activeGoogleCount == 0) failedProviders.add("Google")
        //if (activeOpenRouterCount == 0) failedProviders.add("OpenRouter")
        if (activeClineCount == 0) failedProviders.add("Cline")
        //if (activeNvidiaCount == 0) failedProviders.add("NVIDIA")

        if (failedProviders.isNotEmpty()) {
            val errMsg = "Phát hiện Provider trả về 0 model hoạt động: ${failedProviders.joinToString(", ")}. (Sĩ số active -> Google: $activeGoogleCount, OpenRouter: $activeOpenRouterCount, Cline: $activeClineCount)"
            println("[ERROR] $errMsg")
            sendWebhookAlert(
                client = client,
                subject = "[CRITICAL ALERT] CheckModels Failed - Provider Has 0 Active Models",
                message = "$errMsg\nVui lòng kiểm tra lại API key hoặc kết nối mạng của provider bị lỗi."
            )
            throw IllegalStateException(errMsg)
        }

        // Đảm bảo có ít nhất 1 default model
        var hasDefault = false
        for (i in 0 until results.length()) {
            if (results.getJSONObject(i).optBoolean("default", false)) {
                hasDefault = true
                break
            }
        }
        if (!hasDefault && results.length() > 0) {
            results.getJSONObject(0).put("default", true)
        }

        println("\n=== PROCESS STEP 3: Tạo file models.json và đẩy lên Cloudflare ===")
        val jsonOutput = results.toString(2).replace("\\/", "/")

        // Ghi file models.json cục bộ
        val baseDir = File(System.getProperty("user.dir") ?: ".")
        val outFile = if (baseDir.name == "app") {
            File(baseDir, "src/main/assets/models.json")
        } else {
            File(baseDir, "app/src/main/assets/models.json")
        }
        outFile.parentFile?.mkdirs()
        outFile.writeText(jsonOutput)
        println("[STEP 3] Saved models.json to assets: ${outFile.absolutePath}")

        // Đẩy lên Cloudflare Worker
        uploadToCloudflare(client, jsonOutput)

        println("\n=== Process Finished Successfully ===")
    } catch (e: Exception) {
        println("[ERROR] CheckModels process failed: ${e.message}")
        sendWebhookAlert(
            client = client,
            subject = "[CRITICAL ALERT] CheckModels Process Error",
            message = "Tiến trình CheckModels đã thất bại với lỗi: ${e.message}"
        )
        throw e
    }
}

fun loadPropertiesKey(keyName: String, fallback: String): String {
    val envVal = System.getenv(keyName)
    if (!envVal.isNullOrBlank()) return envVal

    // Thử key thay thế (vd GEMINI_API_KEY vs GEMINI_API_KEY_1)
    if (keyName == "GEMINI_API_KEY_1" || keyName == "GEMINI_API_KEY") {
        val altKey = if (keyName == "GEMINI_API_KEY_1") "GEMINI_API_KEY" else "GEMINI_API_KEY_1"
        val altEnv = System.getenv(altKey)
        if (!altEnv.isNullOrBlank()) return altEnv
    }

    val baseDir = File(System.getProperty("user.dir") ?: ".")
    val propFiles = listOf(
        File(baseDir, "secrets.defaults.properties"),
        File(baseDir, "local.properties"),
        File(baseDir.parentFile ?: baseDir, "secrets.defaults.properties"),
        File(baseDir.parentFile ?: baseDir, "local.properties")
    )
    for (f in propFiles) {
        if (f.exists()) {
            try {
                val props = Properties()
                f.inputStream().use { props.load(it) }
                val value = props.getProperty(keyName) ?: props.getProperty("GEMINI_API_KEY") ?: props.getProperty("GEMINI_API_KEY_1")
                if (!value.isNullOrBlank()) return value
            } catch (_: Exception) {}
        }
    }
    return fallback
}

/**
 * Kiểm tra xem model từ OpenRouter/Cline API có phải là Miễn phí (Free) hay không.
 * Sử dụng metadata/tags do API trả về (object 'pricing' có giá prompt & completion = 0, hoặc tag/id free).
 */
fun isFreeModel(modelObj: JSONObject): Boolean {
    val modelId = modelObj.optString("id", "")
    if (modelId.endsWith(":free") || modelId.contains("/free", ignoreCase = true) || modelId.contains("-free", ignoreCase = true)) {
        return true
    }

    val pricingObj = modelObj.optJSONObject("pricing")
    if (pricingObj != null) {
        val promptPrice = pricingObj.optString("prompt", "0").toDoubleOrNull() ?: 0.0
        val completionPrice = pricingObj.optString("completion", "0").toDoubleOrNull() ?: 0.0
        val requestPrice = pricingObj.optString("request", "0").toDoubleOrNull() ?: 0.0
        val imagePrice = pricingObj.optString("image", "0").toDoubleOrNull() ?: 0.0
        if (promptPrice == 0.0 && completionPrice == 0.0 && requestPrice == 0.0 && imagePrice == 0.0) {
            return true
        }
    }

    return modelObj.optBoolean("is_free", false)
}

/**
 * Kiểm tra xem model từ OpenRouter/Cline API có hỗ trợ Sinh văn bản (Text Generation / Chat / Completion) hay không.
 * Dùng metadata/tags từ API (architecture.output_modalities, architecture.modality, tags).
 * Bao gồm cả các model đa thức (multimodal) có sinh ra văn bản.
 */
fun isTextGenerationModelFromApi(modelObj: JSONObject): Boolean {
    val modelId = modelObj.optString("id", "")
    val idLower = modelId.lowercase()
    if (idLower == "openrouter/free" || idLower.contains("openrouter/free")) {
        return false
    }

    // 1. Kiểm tra metadata 'architecture' (OpenRouter & Cline standard)
    val archObj = modelObj.optJSONObject("architecture")
    if (archObj != null) {
        val outputModalities = archObj.optJSONArray("output_modalities")
        if (outputModalities != null && outputModalities.length() > 0) {
            var supportsTextOutput = false
            for (i in 0 until outputModalities.length()) {
                val mod = outputModalities.optString(i, "").lowercase()
                if (mod == "text") {
                    supportsTextOutput = true
                    break
                }
            }
            // Nếu output_modalities không bao gồm "text" (ví dụ chỉ có "image" hay "audio"), bỏ qua
            if (!supportsTextOutput) return false
        }

        val modality = archObj.optString("modality", "").lowercase()
        if (modality.isNotBlank()) {
            // Modality dạng "text->text", "text+image->text", "text->text+image", v.v.
            if (!modality.contains("text")) {
                return false
            }
        }
    }

    // 2. Kiểm tra danh sách 'output_modalities' cấp top-level (nếu có)
    val topOutputModalities = modelObj.optJSONArray("output_modalities")
    if (topOutputModalities != null && topOutputModalities.length() > 0) {
        var supportsTextOutput = false
        for (i in 0 until topOutputModalities.length()) {
            val mod = topOutputModalities.optString(i, "").lowercase()
            if (mod == "text") {
                supportsTextOutput = true
                break
            }
        }
        if (!supportsTextOutput) return false
    }

    // 3. Kiểm tra 'tags' do API trả về (nếu có)
    val tagsArray = modelObj.optJSONArray("tags")
    if (tagsArray != null) {
        var hasTextTag = false
        var isNonTextOnlyTag = false
        for (i in 0 until tagsArray.length()) {
            val tag = tagsArray.optString(i, "").lowercase()
            if (tag == "text" || tag == "chat" || tag == "completion" || tag == "text-generation" || tag == "llm") {
                hasTextTag = true
            }
            if (tag == "text-to-image" || tag == "image-generation" || tag == "text-to-speech" || tag == "embedding") {
                isNonTextOnlyTag = true
            }
        }
        if (isNonTextOnlyTag && !hasTextTag) return false
    }

    return true
}

/**
 * Kiểm tra xem model từ Google Gemini API có hỗ trợ sinh văn bản hay không.
 * Sử dụng metadata 'supportedGenerationMethods' do Google API trả về (phải chứa "generateContent").
 */
fun isGoogleTextGenerationModel(modelObj: JSONObject): Boolean {
    val supportedMethods = modelObj.optJSONArray("supportedGenerationMethods") ?: return false
    for (i in 0 until supportedMethods.length()) {
        val method = supportedMethods.optString(i, "")
        if (method == "generateContent") {
            return true
        }
    }
    return false
}

fun checkApiReasoningSupport(modelObj: JSONObject): Boolean {
    val reasoningObj = modelObj.optJSONObject("reasoning")
    if (reasoningObj != null) {
        return true
    }
    val supportedParams = modelObj.optJSONArray("supported_parameters")
    if (supportedParams != null) {
        for (j in 0 until supportedParams.length()) {
            val param = supportedParams.optString(j)
            if (param == "reasoning" || param == "reasoning_effort" || param == "include_reasoning") {
                return true
            }
        }
    }
    val modelId = modelObj.optString("id", "").lowercase()
    val reasoningKeywords = listOf("reasoning", "thinking", "r1", "o1", "o3", "reflection", "cot")
    return reasoningKeywords.any { modelId.contains(it) }
}

fun discoverOpenRouterFreeModels(client: OkHttpClient, apiKey: String): List<DiscoveredModel> {
    val freeModels = mutableListOf<DiscoveredModel>()
    try {
        val url = "https://openrouter.ai/api/v1/models"
        val requestBuilder = Request.Builder().url(url).get()
        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.isSuccessful) {
                val bodyStr = response.body?.string().orEmpty()
                val jsonRoot = JSONObject(bodyStr)
                val dataArray = jsonRoot.optJSONArray("data")
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val modelObj = dataArray.getJSONObject(i)
                        val modelId = modelObj.optString("id", "")
                        val modelIdLower = modelId.lowercase()

                        // Lọc dùng metadata & tags do API trả về
                        val isFree = isFreeModel(modelObj)
                        val isTextGen = isTextGenerationModelFromApi(modelObj)
                        val contextLength = modelObj.optInt("context_length", 0)
                        val hasMinContext = contextLength >= 1_000_000

                        // Bỏ qua model text-coder hoặc safety
                        if (modelIdLower.contains("coder") || modelIdLower.contains("safety")||modelIdLower.contains("google/lyria")||modelIdLower.contains("lightning")) {
                            println("[OPENROUTER] BỎ QUA $modelId (text-coder hoặc safety)")
                            continue
                        }

                        if (isFree && isTextGen && modelId.isNotBlank() && hasMinContext) {
                            val isReasoning = checkApiReasoningSupport(modelObj)
                            println("[OPENROUTER] Model $modelId (Reasoning: $isReasoning, context: $contextLength tokens)")
                            freeModels.add(DiscoveredModel(modelId, isReasoning))
                        }
                    }
                }
            } else {
                println("[STEP 1] OpenRouter scan returned HTTP ${response.code}")
            }
        }
    } catch (e: Exception) {
        println("[STEP 1] Error scanning OpenRouter free models: ${e.message}")
    }
    return freeModels.distinctBy { it.id }
}

fun discoverGoogleModels(client: OkHttpClient, apiKey: String): List<DiscoveredModel> {
    val models = mutableListOf<DiscoveredModel>()
    if (apiKey.isNotBlank()) {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    val jsonRoot = JSONObject(bodyStr)
                    val modelsArray = jsonRoot.optJSONArray("models")
                    if (modelsArray != null) {
                        for (i in 0 until modelsArray.length()) {
                            val modelObj = modelsArray.getJSONObject(i)
                            val nameFull = modelObj.optString("name", "")
                            val modelId = nameFull.removePrefix("models/")
                            val modelIdLower = modelId.lowercase()

                            // 1. Dùng metadata API 'supportedGenerationMethods' để lọc các model hỗ trợ sinh văn bản (generateContent)
                            if (!isGoogleTextGenerationModel(modelObj)) {
                                continue
                            }

                            // Bỏ qua model text-coder hoặc safety
                            if (modelIdLower.contains("coder") || modelIdLower.contains("safety")) {
                                println("[GOOGLE] BỎ QUA $modelId (text-coder hoặc safety)")
                                continue
                            }

                            // Bỏ các phiên bản quá cũ hoặc thử nghiệm/đặc thù nếu cần
                            val isOlderVersion = modelIdLower.contains("gemini-1.") ||
                                                 modelIdLower.contains("gemini-2.") ||
                                                 modelIdLower.contains("gemini-0.")

                            val isExcluded = isOlderVersion ||
                                             modelIdLower.contains("banana") ||
                                             modelIdLower.contains("antigravity") ||
                                             modelIdLower.contains("robotics")||
                                             modelIdLower.contains("pro")||
                                             modelIdLower.contains("image")||
                                             modelIdLower.contains("transcribe")||
                                             modelIdLower.contains("omni")||
                                             modelIdLower.contains("tts")

                            val isAllowed = !isExcluded && (modelIdLower.contains("gemini") || modelIdLower.contains("gemma"))

                            if (isAllowed && modelId.isNotBlank()) {
                                val isReasoning = modelIdLower.contains("pro") || modelIdLower.contains("reasoning") || modelIdLower.contains("thinking")
                                models.add(DiscoveredModel(modelId, isReasoning))
                            }
                        }
                    }
                } else {
                    println("[STEP 1] Google scan returned HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            println("[STEP 1] Error discovering Google models: ${e.message}")
        }
    }
    return models.distinctBy { it.id }
}

fun discoverClineFreeModels(client: OkHttpClient, apiKey: String): List<DiscoveredModel> {
    val freeModels = mutableListOf<DiscoveredModel>()
    if (apiKey.isNotBlank()) {
        try {
            val url = "https://api.cline.bot/api/v1/models"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    val rootObj = JSONObject(bodyStr)
                    val dataArray = rootObj.optJSONArray("data")
                    if (dataArray != null) {
                        for (i in 0 until dataArray.length()) {
                            val item = dataArray.getJSONObject(i)
                            val modelId = item.optString("id", "")
                            val modelIdLower = modelId.lowercase()

                            // Lọc dùng metadata & tags do API trả về
                            val isFree = isFreeModel(item)
                            val isTextGen = isTextGenerationModelFromApi(item)
                            val contextLength = item.optInt("context_length", 0)
                            val hasMinContext = contextLength == 0 || contextLength >= 32000

                            // Bỏ qua model text-coder hoặc safety
                            if (modelIdLower.contains("coder") || modelIdLower.contains("safety")) {
                                println("[CLINE] BỎ QUA $modelId (text-coder hoặc safety)")
                                continue
                            }

                            if (isFree && isTextGen && modelId.isNotBlank() && hasMinContext) {
                                val isReasoning = checkApiReasoningSupport(item)
                                println("[CLINE] Model $modelId (Reasoning: $isReasoning, context: $contextLength tokens)")
                                freeModels.add(DiscoveredModel(modelId, isReasoning))
                            }
                        }
                    }
                } else {
                    println("[STEP 1] Cline scan returned HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            println("[STEP 1] Error scanning Cline free models: ${e.message}")
        }
    }
    return freeModels.distinctBy { it.id }
}

fun testGeminiModel(client: OkHttpClient, apiKey: String, model: String): Long? {
    if (apiKey.isBlank()) return null
    try {
        val startTime = System.currentTimeMillis()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val json = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", "còn hoạt động không?"))
                    })
                })
            })
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        var isSuccessful = false
        client.newCall(request).execute().use { response ->
            isSuccessful = response.isSuccessful
        }
        val ttft = System.currentTimeMillis() - startTime
        return if (isSuccessful) ttft else null
    } catch (e: Exception) {
        println("Error testing Gemini model $model: ${e.message}")
        return null
    }
}

fun testOpenRouterModel(client: OkHttpClient, apiKey: String, model: String): Long? {
    if (apiKey.isBlank()) return null
    try {
        val startTime = System.currentTimeMillis()
        val url = "https://openrouter.ai/api/v1/chat/completions"
        val json = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "còn hoạt động không?")
                })
            })
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/batubot/chatbot")
            .header("X-Title", "BatTuBot")
            .post(body)
            .build()
        var isSuccessful = false
        client.newCall(request).execute().use { response ->
            isSuccessful = response.isSuccessful
        }
        val ttft = System.currentTimeMillis() - startTime
        return if (isSuccessful) ttft else null
    } catch (e: Exception) {
        println("Error testing OpenRouter model $model: ${e.message}")
        return null
    }
}

fun testClineModel(client: OkHttpClient, apiKey: String, model: String): Long? {
    if (apiKey.isBlank()) return null
    try {
        val startTime = System.currentTimeMillis()
        val url = "https://api.cline.bot/api/v1/chat/completions"
        val json = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "còn hoạt động không?")
                })
            })
            put("stream", false)
        }
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        var isSuccessful = false
        client.newCall(request).execute().use { response ->
            isSuccessful = response.isSuccessful
        }
        val ttft = System.currentTimeMillis() - startTime
        return if (isSuccessful) ttft else null
    } catch (e: Exception) {
        println("Error testing Cline model $model: ${e.message}")
        return null
    }
}

fun discoverNvidiaModels(client: OkHttpClient, apiKey: String): List<DiscoveredModel> {
    val models = mutableListOf<DiscoveredModel>()
    if (apiKey.isNotBlank()) {
        try {
            val url = "https://integrate.api.nvidia.com/v1/models"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    val rootObj = JSONObject(bodyStr)
                    val dataArray = rootObj.optJSONArray("data")
                    if (dataArray != null) {
                        for (i in 0 until dataArray.length()) {
                            val item = dataArray.getJSONObject(i)
                            val modelId = item.optString("id", "")
                            val modelIdLower = modelId.lowercase()

                            // Chỉ lấy model có chứa "gemma", "gpt" hoặc "deepseek"
                            if (!modelIdLower.contains("gemma") && !modelIdLower.contains("glm") && !modelIdLower.contains("deepseek") && !modelIdLower.contains("minimax")) {
                                continue
                            }

                            // Kiểm tra hỗ trợ sinh văn bản (text generation)
                            // NVIDIA API public không trả về trường modality, nên giữ nguyên (mặc định là text/chat)
                            // Nếu sau này API có trường 'architecture.output_modalities' thì bổ sung kiểm tra tại đây
                            if (!isNvidiaTextModel(item)) {
                                println("[NVIDIA] BỎ QUA $modelId (không hỗ trợ text generation)")
                                continue
                            }

                            // Bỏ qua model text-coder hoặc safety (dựa trên ID)
                            if (modelIdLower.contains("coder") || modelIdLower.contains("safety")) {
                                println("[NVIDIA] BỎ QUA $modelId (text-coder hoặc safety)")
                                continue
                            }

                            val isReasoning = checkApiReasoningSupport(item)
                            println("[NVIDIA] Model $modelId (Reasoning: $isReasoning)")
                            models.add(DiscoveredModel(modelId, isReasoning))
                        }
                    }
                } else {
                    println("[STEP 1] NVIDIA scan returned HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            println("[STEP 1] Error scanning NVIDIA models: ${e.message}")
        }
    }
    return models.distinctBy { it.id }
}

/**
 * Trích xuất kích thước model (đơn vị billion tham số) từ chuỗi ID.
 * Ví dụ: "google/gemma-3-12b-it" -> 12, "nvidia/llama-3.1-nemotron-70b-instruct" -> 70
 * Trả về null nếu không tìm thấy số.
 */
fun extractModelSizeB(modelIdLower: String): Int? {
    val regex = Regex("(\\d+(\\.\\d+)?)\\s*[bB]")
    val match = regex.find(modelIdLower) ?: return null
    return match.groupValues[1].toDoubleOrNull()?.toInt()
}

/**
 * Kiểm tra model NVIDIA có hỗ trợ sinh văn bản (text generation) hay không.
 * Hiện tại NVIDIA public API không trả về trường modality/architecture,
 * nên hàm này trả về true (mặc định). Nếu sau này API bổ sung metadata,
 * hãy cập nhật logic kiểm tra tại đây.
 */
fun isNvidiaTextModel(modelObj: JSONObject): Boolean {
    // NVIDIA API v1/models hiện không trả về 'architecture.output_modalities'
    // nên chúng ta coi tất cả model trong danh sách này là hỗ trợ text generation.
    // Có thể mở rộng sau này khi API cung cấp metadata chi tiết hơn.
    return true
}

fun testNvidiaModel(client: OkHttpClient, apiKey: String, model: String): Long? {
    if (apiKey.isBlank()) return null
    try {
        val startTime = System.currentTimeMillis()
        val url = "https://integrate.api.nvidia.com/v1/chat/completions"
        val json = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "còn hoạt động không?")
                })
            })
            put("stream", false)
            put("max_tokens", 10)
        }
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        var isSuccessful = false
        client.newCall(request).execute().use { response ->
            isSuccessful = response.isSuccessful
        }
        val ttft = System.currentTimeMillis() - startTime
        return if (isSuccessful) ttft else null
    } catch (e: Exception) {
        println("Error testing NVIDIA model $model: ${e.message}")
        return null
    }
}

fun sendWebhookAlert(client: OkHttpClient, subject: String, message: String) {
    val webhookUrl = "https://formspree.io/f/xkjolqry"
    println("🚨 SENDING CRITICAL ALERT VIA WEBHOOK TO FORMSPREE...")
    try {
        val alertJson = JSONObject().apply {
            put("to", "batubot.app@gmail.com")
            put("subject", subject)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }
        val body = alertJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(webhookUrl)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                println(" [WEBHOOK ALERT SENT] Formspree response code: ${response.code}")
            } else {
                println(" [WEBHOOK ERROR] Formspree response code: ${response.code}")
            }
        }
    } catch (e: Exception) {
        println(" [WEBHOOK EXCEPTION] Could not send alert webhook: ${e.message}")
    }
}

fun uploadToCloudflare(client: OkHttpClient, jsonOutput: String) {
    val cloudflareWorkerUrl = "https://chatbot.tungle020596.workers.dev/update"
    val namespaceId = "f794e9bbb164423e9074cfc8a78de4b6"
    val accountId = "55716490813fcdf759a563a89f8e4046"
    val keyName = "models.json"
    val cloudflareToken = loadPropertiesKey(
    "CLOUDFLARE_API_TOKEN",
    loadPropertiesKey("CF_RAG_API_TOKEN", ""))

    println("[STEP 3] Pushing models.json to Cloudflare Worker & KV namespace $namespaceId (key: $keyName)...")

    // 1. Push via HTTP POST to Cloudflare Worker
    try {
        val body = jsonOutput.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(cloudflareWorkerUrl)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            println("[STEP 3] [HTTP WORKER PUSH CODE]: ${response.code}")
            val bodyStr = response.body?.string().orEmpty()
            println("[STEP 3] [HTTP WORKER PUSH RESPONSE]: $bodyStr")
        }
    } catch (e: Exception) {
        println("[STEP 3] [HTTP WORKER PUSH EXCEPTION]: ${e.message}")
    }

    // 2. Push via Wrangler KV command
    try {
        val baseDir = File(System.getProperty("user.dir") ?: ".")
        val modelsPath = if (baseDir.name == "app") "src/main/assets/models.json" else "app/src/main/assets/models.json"

        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val npxCmd = if (isWindows) "npx.cmd" else "npx"

        val pb = ProcessBuilder(
            npxCmd, "wrangler", "kv", "key", "put", keyName,
            "--path=$modelsPath",
            "--namespace-id=$namespaceId",
            "--remote"
        ).redirectErrorStream(true)

        pb.environment()["CLOUDFLARE_ACCOUNT_ID"] = accountId
        if (cloudflareToken.isNotBlank()) {
            pb.environment()["CLOUDFLARE_API_TOKEN"] = cloudflareToken
        }

        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(20, TimeUnit.SECONDS)

        if (process.exitValue() == 0) {
            println("[STEP 3] [CLOUDFLARE WRANGLER SUCCESS]\n$output")
        } else {
            println("[STEP 3] Wrangler output: $output (Repository sync will deploy via GitHub Actions)")
        }
    } catch (e: Exception) {
        println("[STEP 3] Wrangler note: ${e.message} (Repository sync will deploy via GitHub Actions)")
    }
}
