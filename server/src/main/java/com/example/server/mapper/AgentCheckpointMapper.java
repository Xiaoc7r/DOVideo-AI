package com.example.server.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentCheckpointMapper {

    @Select("SELECT payload FROM agent_checkpoints WHERE media_id = #{mediaId} AND checkpoint_key = #{checkpointKey}")
    String findPayload(@Param("mediaId") Long mediaId,
                       @Param("checkpointKey") String checkpointKey);

    @Select("SELECT stage FROM agent_checkpoints WHERE media_id = #{mediaId} AND checkpoint_key = #{checkpointKey}")
    String findStage(@Param("mediaId") Long mediaId,
                     @Param("checkpointKey") String checkpointKey);

    @Insert("""
            INSERT INTO agent_checkpoints(media_id, checkpoint_key, stage, payload)
            VALUES(#{mediaId}, #{checkpointKey}, #{stage}, #{payload})
            ON DUPLICATE KEY UPDATE stage = VALUES(stage), payload = VALUES(payload), updated_at = CURRENT_TIMESTAMP(3)
            """)
    void upsert(@Param("mediaId") Long mediaId,
                @Param("checkpointKey") String checkpointKey,
                @Param("stage") String stage,
                @Param("payload") String payload);

    @Delete("DELETE FROM agent_checkpoints WHERE media_id = #{mediaId} AND checkpoint_key LIKE CONCAT(#{prefix}, '%')")
    void deleteByPrefix(@Param("mediaId") Long mediaId, @Param("prefix") String prefix);

    @Delete("DELETE FROM agent_checkpoints WHERE media_id = #{mediaId}")
    void deleteByMediaId(@Param("mediaId") Long mediaId);
}
