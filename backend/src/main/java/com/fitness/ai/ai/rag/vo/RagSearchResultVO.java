package com.fitness.ai.ai.rag.vo;

import java.util.List;
import java.util.Set;

/**
 * RAG 召回调试结果（{@code GET /api/ai/rag/search}）。
 *
 * @param totalSegments 知识库切片总数
 * @param hits          Top-K 命中切片，含 BM25 / 余弦 / 关键词加分明细
 */
public record RagSearchResultVO(
        String query,
        int totalSegments,
        int topK,
        List<Hit> hits
) {

    /** 单条命中。 */
    public record Hit(
            int rank,
            String sectionTitle,
            double score,
            double bm25,
            double cosine,
            double keywordBonus,
            Set<String> matchedTerms,
            String content
    ) {
    }
}