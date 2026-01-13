package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Component
//监听 "video-analysis-topic" 主题，组名随便起
@RocketMQMessageListener(topic = "video-analysis-topic", consumerGroup = "video-group")
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    @Autowired
    private AiService aiService;

    @Autowired
    private MediaFileMapper mediaFileMapper;

    //注入之前配置好的 IO 密集型线程池
    @Autowired
    private Executor aiTaskExecutor;

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        Long mediaId = msg.getMediaId();
        System.out.println("⚡ [MQ消费者] 收到任务 ID: " + mediaId + "，准备派发给线程池...");

        //CompletableFuture异步编排
        //即使MQ消费者线程很快，我们也不阻塞它，而是把重活扔给业务线程池
        CompletableFuture.runAsync(() -> {
            System.out.println("🧵 [线程池] 开始执行 DeepSeek 分析逻辑...");
            try {

                aiService.asyncAnalyze(mediaId);
            } catch (Exception e) {
                System.err.println("❌ 任务执行失败: " + e.getMessage());
                //这里可以扩展：写数据库记录失败状态
                markAsFailed(mediaId, e.getMessage());
            }
        }, aiTaskExecutor);
    }

    private void markAsFailed(Long id, String error) {
        MediaFile file = mediaFileMapper.selectById(id);
        if (file != null) {
            file.setAiSummary("❌ 分析失败: " + error);
            mediaFileMapper.updateById(file);
        }
    }
}