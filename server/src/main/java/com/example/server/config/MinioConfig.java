package com.example.server.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

  private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

  @Bean
  public MinioClient minioClient(
      @Value("${minio.endpoint}") String endpoint,
      @Value("${minio.accessKey}") String accessKey,
      @Value("${minio.secretKey}") String secretKey,
      @Value("${minio.bucketName}") String bucketName,
      @Value("${minio.public-read:false}") boolean publicRead) {
    try {
      MinioClient client =
          MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
      if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
        client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        log.info("minio_bucket_created bucket={}", bucketName);
      }
      // 媒体一律通过短时效预签名地址访问（见 MinioUtils.readableSource，默认 1 小时有效），
      // 桶本身无需公开可读。历史上的整桶 public-read 策略会让所有用户私有视频绕过
      // AuthInterceptor 鉴权被匿名下载，属于严重越权隐患，且对播放没有任何功能收益，
      // 因此这里显式忽略该开关，只保留告警以便发现误配。
      if (publicRead) {
        log.warn(
            "minio_public_read_ignored bucket={} reason=media_served_via_short_lived_presigned_url",
            bucketName);
      }
      return client;
    } catch (Exception e) {
      throw new IllegalStateException("MinIO 初始化失败", e);
    }
  }
}
