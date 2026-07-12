package com.example.server.utils;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.service.AgentTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

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
    private final AgentTelemetry telemetry;
    private final double inputPricePerMillion;
    private final double outputPricePerMillion;

    public DeepSeekUtils(@Value("${ai.deepseek.api-key}") String apiKey,
                         @Value("${ai.deepseek.base-url}") String baseUrl,
                         @Value("${ai.deepseek.model:deepseek-ai/DeepSeek-R1-Distill-Qwen-32B}") String modelName,
                         @Value("${ai.deepseek.input-price-per-million:0}") double inputPricePerMillion,
                         @Value("${ai.deepseek.output-price-per-million:0}") double outputPricePerMillion,
                         AgentTelemetry telemetry,
                         ObjectMapper objectMapper) {
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
        this.objectMapper = objectMapper;
        this.telemetry = telemetry;
        this.inputPricePerMillion = inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
    }

    public String analyzeContent(String content) {
        if (content == null || content.isBlank()) {
            return "无法提取有效信息";
        }
        return chat("CONTENT_ANALYSIS", SYSTEM_PROMPT + "\n\n待分析文本：\n" + content);
    }

    public AgentState.AgentPlan plan(VideoContext context) {
        try {
            String prompt = """
                    你是 Video Agent 的 Planner。理解用户目标，并拆成 3 到 5 个可执行任务。
                    任务必须能够仅依靠 VideoContext 中的 ASR、OCR 和时间戳证据完成。
                    只返回 JSON：
                    {
                      "understoodGoal": "对用户目标的明确理解",
                      "tasks": ["任务1", "任务2", "任务3"]
                    }
                    VideoContext:
                    """ + objectMapper.writeValueAsString(context);
            return parseJson(chat("PLANNER", prompt), AgentState.AgentPlan.class);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 任务规划失败", e);
        }
    }

    public VideoChunk.ChunkSummary summarizeChunk(List<VideoContext.VideoSegment> segments) {
        try {
            String prompt = """
                    压缩以下五分钟视频片段，保留人物、事件、观点、结论以及重要 OCR 信息。
                    只返回 JSON：
                    {
                      "segmentSummary": "不超过 200 字的片段摘要",
                      "keywords": ["关键词1", "关键词2", "关键词3"]
                    }
                    原始片段：
                    """ + objectMapper.writeValueAsString(segments);
            return parseJson(chat("CHUNK_SUMMARY", prompt), VideoChunk.ChunkSummary.class);
        } catch (Exception e) {
            throw new IllegalStateException("视频片段摘要失败", e);
        }
    }

    public AnalysisResult execute(VideoContext context,
                                  AgentState.AgentPlan plan,
                                  AgentState.CriticResult previousCritique) {
        try {
            String prompt = """
                    你是 Video Agent 的 Executor。按照计划分析 VideoContext 并生成结构化产物。
                    所有重要结论必须绑定真实 timestampMs，并标明来源为 ASR 或 OCR。
                    不得使用视频上下文之外的事实。
                    如果存在 Critic 反馈，必须逐项修正。

                    只返回 JSON：
                    {
                      "title": "产物标题",
                      "conclusions": ["结论"],
                      "evidence": [
                        {"timestampMs": 120000, "source": "ASR或OCR", "content": "证据内容"}
                      ],
                      "suggestions": ["建议"]
                    }

                    Plan:
                    """ + objectMapper.writeValueAsString(plan) + """

                    PreviousCritique:
                    """ + objectMapper.writeValueAsString(previousCritique) + """

                    VideoContext:
                    """ + objectMapper.writeValueAsString(context);
            return parseJson(chat("EXECUTOR", prompt), AnalysisResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 执行失败", e);
        }
    }

    public AgentState.CriticResult critique(VideoContext context,
                                            AgentState.AgentPlan plan,
                                            AnalysisResult result) {
        try {
            String prompt = """
                    你是 Video Agent 的 Critic，只负责检查，不负责改写产物。
                    检查标准：
                    1. 是否覆盖用户目标和 Planner 的全部任务；
                    2. 每个重要结论是否能在 VideoContext 中找到时间戳证据；
                    3. 是否存在上下文不支持的结论；
                    4. title、conclusions、evidence、suggestions 是否完整。

                    只有全部满足时 passed 才能为 true。
                    requiredTimestamps 填写需要重新读取或补充分析的时间戳毫秒值。
                    只返回 JSON：
                    {
                      "passed": false,
                      "feedback": ["具体修改建议"],
                      "missingRequirements": ["遗漏要求"],
                      "unsupportedClaims": ["无证据结论"],
                      "requiredTimestamps": [120000]
                    }

                    Plan:
                    """ + objectMapper.writeValueAsString(plan) + """

                    Draft:
                    """ + objectMapper.writeValueAsString(result) + """

                    VideoContext:
                    """ + objectMapper.writeValueAsString(context);
            return parseJson(chat("CRITIC", prompt), AgentState.CriticResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Critic 校验失败", e);
        }
    }

    private <T> T parseJson(String response, Class<T> type) throws Exception {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("模型返回空响应");
        }
        String json = response
                .replace("```json", "")
                .replace("```", "")
                .trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("模型未返回 JSON 对象");
        json = json.substring(start, end + 1);
        return objectMapper.readValue(json, type);
    }

    private String chat(String stage, String prompt) {
        long started = System.nanoTime();
        try {
            String response = chatModel.chat(prompt);
            if (response == null || response.isBlank()) throw new IllegalStateException("模型返回空响应");
            telemetry.modelCall(stage, prompt, response,
                    inputPricePerMillion, outputPricePerMillion, started);
            return response;
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("modelCallFailures", 1);
            telemetry.failCurrentStage(stage, started);
            throw e;
        }
    }
}
