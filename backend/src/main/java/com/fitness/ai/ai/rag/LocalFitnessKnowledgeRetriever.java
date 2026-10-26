package com.fitness.ai.ai.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯 Java 内存级运动科学知识库检索器（零外部向量库、零 Embedding Key）。
 *
 * <p>检索算法为混合打分，三部分相加：</p>
 * <ol>
 *   <li><b>BM25</b>：主体相关度，k1=1.2、b=0.75；</li>
 *   <li><b>TF-IDF 词频向量余弦</b>：捕捉整体语义分布（权重 0.6）；</li>
 *   <li><b>领域关键词加权</b>：查询命中 RPE / 1RM / Deload / LEFT_SHOULDER 等专业术语时额外加分（每个 1.2）。</li>
 * </ol>
 *
 * <p>中文按<b>二元分词（Bigram）</b>切分（如「左肩疼怎么练胸」→ 左肩/肩疼/疼怎/怎么/么练/练胸），
 * 英文与数字按 {@code [a-z0-9_]+} 切分并统一小写，因此 {@code RPE}、{@code 1RM}、{@code Deload}、
 * {@code LEFT_SHOULDER} 都能被精确匹配。</p>
 */
@Slf4j
@Component
public class LocalFitnessKnowledgeRetriever implements ContentRetriever {

    /** 知识库语料路径（classpath 下） */
    public static final String KB_RESOURCE = "rag/fitness_science_kb.md";

    /** 默认召回条数 */
    public static final int DEFAULT_TOP_K = 3;

    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;
    private static final double COSINE_WEIGHT = 0.6;
    private static final double KEYWORD_BONUS = 1.2;

    /** 命中即加分的领域术语（小写） */
    private static final Set<String> DOMAIN_KEYWORDS = Set.of(
            "rpe", "rir", "1rm", "deload", "volume", "cns", "bmr", "tdee",
            "left_shoulder", "right_shoulder", "knee", "lower_back", "wrist",
            "high_carb", "medium_carb", "low_carb", "muscle_gain", "fat_loss", "strength",
            "double", "progression", "bigram", "plyometric");

    private static final Pattern ENGLISH_TOKEN = Pattern.compile("[a-z0-9_]+");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    private final List<SegmentIndex> segments = new ArrayList<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private double averageLength = 1.0;

    /** 启动时加载语料并建立倒排统计。 */
    @PostConstruct
    public void loadKnowledgeBase() {
        List<TextSegment> loaded = parseMarkdown(KB_RESOURCE);
        for (int i = 0; i < loaded.size(); i++) {
            TextSegment segment = loaded.get(i);
            List<String> tokens = tokenize(segment.text());
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String token : tokens) {
                termFrequency.merge(token, 1, Integer::sum);
            }
            for (String term : termFrequency.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
            segments.add(new SegmentIndex(i, segment, termFrequency, tokens.size()));
        }
        int totalLength = segments.stream().mapToInt(SegmentIndex::length).sum();
        averageLength = segments.isEmpty() ? 1.0 : (double) totalLength / segments.size();
        log.info("[RAG] 知识库加载完成：文件={} 切片={} 个 词项={} 平均长度={} 词",
                KB_RESOURCE, segments.size(), documentFrequency.size(), String.format("%.1f", averageLength));
        segments.forEach(s -> log.info("[RAG] 已索引切片 #{}《{}》{} 字",
                s.index(), s.title(), s.segment().text().length()));
    }

    // ------------------------------------------------------------------
    // ContentRetriever 标准接口
    // ------------------------------------------------------------------

    @Override
    public List<Content> retrieve(Query query) {
        List<ScoredHit> hits = search(query.text(), DEFAULT_TOP_K);
        log.info("[RAG] 查询「{}」召回 Top-{}：{}", query.text(), hits.size(),
                hits.stream().map(h -> "《" + h.sectionTitle() + "》(score=" + String.format("%.3f", h.score()) + ")")
                        .toList());
        return hits.stream().map(hit -> Content.from(hit.segment())).toList();
    }

    // ------------------------------------------------------------------
    // 带打分的检索（供调试接口与测试使用）
    // ------------------------------------------------------------------

    /** 返回 Top-K 命中切片及完整打分明细。 */
    public List<ScoredHit> search(String queryText, int topK) {
        if (queryText == null || queryText.isBlank() || segments.isEmpty()) {
            return List.of();
        }
        List<String> queryTokens = tokenize(queryText);
        Map<String, Integer> queryFrequency = new HashMap<>();
        queryTokens.forEach(t -> queryFrequency.merge(t, 1, Integer::sum));
        Set<String> queryTerms = queryFrequency.keySet();

        List<ScoredHit> scored = new ArrayList<>();
        for (SegmentIndex segment : segments) {
            double bm25 = bm25(queryTerms, segment);
            double cosine = cosine(queryFrequency, segment);
            double bonus = keywordBonus(queryTerms, segment);
            double score = bm25 + COSINE_WEIGHT * cosine + bonus;
            if (score <= 0) {
                continue;
            }
            Set<String> matched = new LinkedHashSet<>();
            for (String term : queryTerms) {
                if (segment.termFrequency().containsKey(term)) {
                    matched.add(term);
                }
            }
            scored.add(new ScoredHit(segment.title(), score, bm25, cosine, bonus, matched, segment.segment()));
        }
        scored.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        return scored.size() > topK ? scored.subList(0, topK) : scored;
    }

    /** 已索引切片数量。 */
    public int segmentCount() {
        return segments.size();
    }

    /**
     * 生成知识库摘要文本，供其它 Prompt 复用（例如结构化排课需要注入「周容量 + RPE」章节）。
     *
     * @param query             检索用的问题描述
     * @param topK              取前 K 段
     * @param maxCharsPerSection 每段截断长度（避免 Prompt 过长）
     */
    public String digest(String query, int topK, int maxCharsPerSection) {
        List<ScoredHit> hits = search(query, topK);
        if (hits.isEmpty()) {
            return "（本地知识库暂无匹配内容）";
        }
        StringBuilder builder = new StringBuilder();
        for (ScoredHit hit : hits) {
            String text = hit.segment().text().replaceAll("\\s+", " ").trim();
            if (text.length() > maxCharsPerSection) {
                text = text.substring(0, maxCharsPerSection) + "…";
            }
            builder.append("- ").append(text).append('\n');
        }
        log.info("[RAG] 生成知识摘要：query={} 段数={} 字符数={}", query, hits.size(), builder.length());
        return builder.toString().trim();
    }

    // ------------------------------------------------------------------
    // 打分细节
    // ------------------------------------------------------------------

    private double bm25(Set<String> queryTerms, SegmentIndex segment) {
        double score = 0;
        int total = segments.size();
        for (String term : queryTerms) {
            Integer df = documentFrequency.get(term);
            if (df == null || df == 0) {
                continue;
            }
            double idf = Math.log(1 + (total - df + 0.5) / (df + 0.5));
            int tf = segment.termFrequency().getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            double denominator = tf + BM25_K1 * (1 - BM25_B + BM25_B * segment.length() / averageLength);
            score += idf * (tf * (BM25_K1 + 1)) / denominator;
        }
        return score;
    }

    private double cosine(Map<String, Integer> queryFrequency, SegmentIndex segment) {
        double dot = 0;
        double queryNorm = 0;
        double segmentNorm = 0;
        for (Map.Entry<String, Integer> entry : queryFrequency.entrySet()) {
            double queryWeight = entry.getValue() * idf(entry.getKey());
            queryNorm += queryWeight * queryWeight;
            Integer tf = segment.termFrequency().get(entry.getKey());
            if (tf != null) {
                dot += queryWeight * (tf * idf(entry.getKey()));
            }
        }
        for (Map.Entry<String, Integer> entry : segment.termFrequency().entrySet()) {
            double weight = entry.getValue() * idf(entry.getKey());
            segmentNorm += weight * weight;
        }
        if (queryNorm == 0 || segmentNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(queryNorm) * Math.sqrt(segmentNorm));
    }

    /** 查询里的领域术语在切片中出现则加分（title 命中额外再乘 3，因为标题即主题）。 */
    private double keywordBonus(Set<String> queryTerms, SegmentIndex segment) {
        double bonus = 0;
        String titleLower = segment.title().toLowerCase(Locale.ROOT);
        for (String term : queryTerms) {
            if (!DOMAIN_KEYWORDS.contains(term)) {
                continue;
            }
            if (titleLower.contains(term)) {
                bonus += KEYWORD_BONUS * 3;
            } else if (segment.termFrequency().containsKey(term)) {
                bonus += KEYWORD_BONUS;
            }
        }
        return bonus;
    }

    private double idf(String term) {
        int total = segments.size();
        int df = documentFrequency.getOrDefault(term, 0);
        if (df == 0) {
            return 0;
        }
        return Math.log(1 + (total - df + 0.5) / (df + 0.5));
    }

    // ------------------------------------------------------------------
    // 语料加载与分词
    // ------------------------------------------------------------------

    /** 按 Markdown 二级标题（{@code ## }）切分为独立切片，标题写入元数据。 */
    private List<TextSegment> parseMarkdown(String resourcePath) {
        List<TextSegment> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(resourcePath).getInputStream(), StandardCharsets.UTF_8))) {
            String title = null;
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("## ")) {
                    if (title != null) {
                        result.add(toSegment(title, body.toString()));
                    }
                    title = line.substring(3).trim();
                    body.setLength(0);
                } else if (title != null) {
                    body.append(line).append('\n');
                }
            }
            if (title != null) {
                result.add(toSegment(title, body.toString()));
            }
        } catch (Exception e) {
            log.error("[RAG] 知识库加载失败，检索器将以空语料运行：{}", e.getMessage(), e);
        }
        return result;
    }

    private TextSegment toSegment(String title, String body) {
        String text = "【" + title + "】\n" + body.trim();
        Metadata metadata = new Metadata()
                .put("section", title)
                .put("source", KB_RESOURCE);
        return TextSegment.from(text, metadata);
    }

    /**
     * 中文二元分词 + 英文/数字词元，统一小写。
     *
     * <p>例：{@code "左肩疼怎么练胸 RPE 8.5"} → [rpe, 8, 5, 左肩, 肩疼, 疼怎, 怎么, 么练, 练胸]</p>
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher english = ENGLISH_TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (english.find()) {
            tokens.add(english.group());
        }
        Matcher cjk = CJK_RUN.matcher(text);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() == 1) {
                tokens.add(run);
                continue;
            }
            for (int i = 0; i + 1 < run.length(); i++) {
                tokens.add(run.substring(i, i + 2));
            }
        }
        return tokens;
    }

    /** 单个切片的索引结构。 */
    private record SegmentIndex(int index, TextSegment segment, Map<String, Integer> termFrequency, int length) {
        String title() {
            return segment.metadata().getString("section");
        }
    }

    /**
     * 命中切片（含打分明细，便于调试接口展示「为什么命中」）。
     */
    public record ScoredHit(
            String sectionTitle,
            double score,
            double bm25,
            double cosine,
            double keywordBonus,
            Set<String> matchedTerms,
            TextSegment segment
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sectionTitle", sectionTitle);
            map.put("score", Math.round(score * 1000) / 1000.0);
            map.put("bm25", Math.round(bm25 * 1000) / 1000.0);
            map.put("cosine", Math.round(cosine * 1000) / 1000.0);
            map.put("keywordBonus", Math.round(keywordBonus * 1000) / 1000.0);
            map.put("matchedTerms", matchedTerms);
            map.put("content", segment.text());
            return map;
        }
    }
}