package com.example.server.utils;

/**
 * 语音转写（ASR）服务商抽象。
 *
 * <p>项目原先只对接硅基流动的 OpenAI 风格转写接口，现增加阿里云百炼实现，
 * 由 {@code ai.asr.provider} 选择具体实现：
 * <ul>
 *   <li>{@code siliconflow}（默认）—— {@link AliyunAsrUtils}，multipart 直传音频文件；</li>
 *   <li>{@code bailian} —— {@link BailianAsrUtils}，走百炼 compatible-mode 的
 *       {@code chat/completions} + {@code input_audio}（base64 data URI）。</li>
 * </ul>
 */
public interface AsrClient {

    /**
     * 把一段本地音频文件转写成文本。
     *
     * @param filePath 音频文件绝对路径
     * @return 识别文本；实现保证非空，空结果应按失败处理
     */
    String audioToText(String filePath);
}
