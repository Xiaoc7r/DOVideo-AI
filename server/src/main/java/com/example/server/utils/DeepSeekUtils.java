package com.example.server.utils;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekUtils {

    private static final String SYSTEM_PROMPT = """
            # Role
            你是一位拥有认知心理学背景的资深信息架构师，负责从语音转录文本中提取高价值信息并重构逻辑。

            # Goals
            忽略口语废话、重复和语气词，输出结构清晰、客观专业的分析报告。

            # Constraints
            1. 文本过短或无意义时，输出“无法提取有效信息”。
            2. 不输出开场白或结束语。
            3. 严格使用以下 Markdown 结构：

            ## 核心摘要
            ## 深度洞察
            ### 1. 观点标题
            ## 原始内容精选
            ## 领域标签
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DeepSeekUtils(@Value("${ai.deepseek.api-key}") String apiKey,
                         @Value("${ai.deepseek.base-url}") String baseUrl,
                         @Value("${ai.deepseek.model:deepseek-ai/DeepSeek-R1-Distill-Qwen-32B}") String modelName,
                         ObjectMapper objectMapper) {
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
        this.objectMapper = objectMapper;
    }

    public String analyzeContent(String content) {
        if (content == null || content.isBlank()) {
            return "无法提取有效信息";
        }
        return chatModel.chat(SYSTEM_PROMPT + "\n\n待分析文本：\n" + content);
    }

    public AnalysisResult analyzeVideoContext(VideoContext context) {
        try {
            String prompt = """
                    你是 Video Agent。请根据用户提供的时序视频上下文完成分析。
                    语音 transcript、画面 OCR 和 evidenceFrames 均按时间段对齐。
                    每个结论必须引用上下文中的时间戳证据，不得编造视频中不存在的信息。

                    只返回 JSON，不要 Markdown，不要代码块。结构必须为：
                    {
                      "title": "产物标题",
                      "conclusions": ["结论"],
                      "evidence": [
                        {"timestampMs": 120000, "source": "ASR或OCR", "content": "证据内容"}
                      ],
                      "suggestions": ["建议"]
                    }

                    VideoContext:
                    """ + objectMapper.writeValueAsString(context);

            String json = chatModel.chat(prompt)
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();
            return objectMapper.readValue(json, AnalysisResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("结构化视频分析失败", e);
        }
    }
}
