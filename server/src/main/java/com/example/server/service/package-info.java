/**
 * 业务核心层:视频分析的编排、处理、检索与状态管理。
 *
 * <p>按职责可分为四组:
 *
 * <ul>
 *   <li><b>编排</b> —— {@code AiService}(应用入口)、{@code AgentLoopService} (Planner→Executor→Critic
 *       受控循环)、{@code AnalysisDispatchService} (提交 / 限流 / 幂等)、{@code AnalysisStatusService}(状态聚合)。
 *   <li><b>视频处理</b> —— {@code VideoContextService}、{@code SegmentedTranscriptionService}、 {@code
 *       VideoChunkingService}、{@code AudioExportService}。
 *   <li><b>检索与长文</b> —— {@code LongVideoContextService}、{@code VideoEvidenceRetrievalService}、
 *       {@code QdrantVectorStore}、{@code EvidenceVerificationService}。
 *   <li><b>状态与配套</b> —— {@code AgentCheckpointService}(MySQL 为真源、Redis 为热缓存)、 {@code
 *       AgentTelemetry}、{@code TaskEventService}、{@code FailedAnalysisTaskService} 等。
 * </ul>
 *
 * <p>多模式能力由 {@code service.mode} 子包提供,编排层读取 {@code ModeProfile} 决定各角色行为, 从而在不改动核心流程的前提下扩展新的分析模式。
 */
package com.example.server.service;
