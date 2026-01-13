package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class DeepSeekUtils {

    @Value("${ai.deepseek.api-key}")
    private String apiKey;

    @Value("${ai.deepseek.base-url}")
    private String baseUrl;

    // 配置 HTTP 客户端，超时时间设置长一点，因为 AI 思考需要时间

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)  // 给 AI 5分钟思考时间
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    /**
     * 真·AI 深度思考
     */
    public String analyzeContent(String content) {

        String url = baseUrl + "/chat/completions";
        //提示词自由发挥，善于利用AI。
        String systemPrompt = """
    # Role
    你是一位拥有认知心理学背景的资深信息架构师。你的专长是从杂乱的语音转录文本中提取高价值信息，并进行逻辑重构。

    # Input Context
    用户将提供一段由视频生成的语音识别（ASR）文本。文本可能包含口语废话、重复、语气词或识别错误。

    # Goals
    请忽略文本中的噪音，对内容进行深度降噪和逻辑精炼，最终输出一份结构清晰、语气专业的分析报告。

    # Constraints
    1. **必须**严格遵守下方的输出格式。
    2. 语气保持客观、理性、犀利。
    3. 如果文本内容过短或无意义，直接输出“无法提取有效信息”。
    4. 禁止输出任何开场白或结束语（如“好的，我来分析...”），直接输出 Markdown 内容。

    # Output Format (Markdown)
    请严格按照以下模块输出：

    ## 核心摘要
    （精简概括视频到底讲了什么，直击本质，全面贴切，但要一针见血地概括视频主旨。）

    ## 深度洞察
    （提取 3-5 个核心观点，每个观点使用三级标题格式，如下所示：）
                   
    ### 1. [这里提炼一个 4-8 字的强观点标题]
    不要复述原话。请用专业的语言解释这个观点背后的逻辑、动因或对观众的启示。分析要犀利，直击本质。
                   
    ### 2. [第二个强观点标题]
    （此处填写对应的深度分析...）
                   
    ### 3. [第三个强观点标题]
    （此处填写对应的深度分析...）(后续标题和分析同理)

    ## 原始内容精选
    > "引用视频中原本的最有价值的一句原话（修正错别字后）"
    > "引用第二句有价值的原话"（如果有，不一定必须精选，后续同理，但原始内容精选最多三个）

    ## 🏷️ 领域标签
    #标签1 #标签2 #标签3
    """;

        // 3. 组装 JSON 参数
        JSONObject jsonBody = new JSONObject();
        jsonBody.put("model", "deepseek-ai/DeepSeek-R1-Distill-Qwen-32B"); // 或者是 deepseek-ai/DeepSeek-R1 (看你账号支持哪个)
        jsonBody.put("stream", false);

        JSONArray messages = new JSONArray();
        messages.add(JSONObject.of("role", "system", "content", systemPrompt));
        messages.add(JSONObject.of("role", "user", "content", content));
        jsonBody.put("messages", messages);

        // 4. 发送请求
        RequestBody body = RequestBody.create(
                jsonBody.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                // 如果报错（比如没余额），这里会把错误原因返回去
                return "❌ AI 请求失败: " + response.code() + " - " + response.body().string();
            }

            // 5. 解析 AI 返回的 JSON
            String resultJson = response.body().string();
            JSONObject jsonObject = JSON.parseObject(resultJson);

            // 提取真正的回答内容
            return jsonObject.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

        } catch (IOException e) {
            e.printStackTrace();
            return "❌ 网络连接出错: " + e.getMessage();
        }
    }
}