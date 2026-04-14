package com.redis.workshop.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.workshop.service.OpenAiService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@DependsOn("startupCleanup")
public class DocumentDataLoader {

    private static final Logger log = LoggerFactory.getLogger(DocumentDataLoader.class);
    private static final String DOC_PREFIX = "workshop:docs:regulation:";
    private static final String INDEX_NAME = "idx:regulations";
    private static final int VECTOR_DIM = 1536;

    private final StringRedisTemplate redis;
    private final OpenAiService openAiService;

    public DocumentDataLoader(StringRedisTemplate redis, OpenAiService openAiService) {
        this.redis = redis;
        this.openAiService = openAiService;
    }

    @PostConstruct
    public void loadDocuments() {
        List<Map<String, Object>> docs;

        // Try loading pre-computed embeddings from classpath first
        List<Map<String, Object>> precomputed = loadPrecomputedEmbeddings();
        if (precomputed != null && !precomputed.isEmpty()) {
            docs = precomputed;
            log.info("UC6: Using {} pre-computed document chunks from kb-embeddings.json", docs.size());
        } else {
            log.info("UC6: No pre-computed embeddings found, using built-in mock documents");
            docs = buildMockDocuments();
        }

        // Store each document as JSON
        for (Map<String, Object> doc : docs) {
            String key = DOC_PREFIX + doc.get("id");
            String json = toJson(doc);
            redis.execute(connection -> {
                connection.execute("JSON.SET",
                        key.getBytes(StandardCharsets.UTF_8),
                        "$".getBytes(StandardCharsets.UTF_8),
                        json.getBytes(StandardCharsets.UTF_8));
                return null;
            }, true);
        }

        // Create RediSearch index
        createIndex();
        log.info("UC6: Loaded {} regulation documents with vector embeddings", docs.size());
    }

    /**
     * Load pre-computed embeddings from /data/kb-embeddings.json on the classpath.
     * Returns null if the file is not found or cannot be parsed.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadPrecomputedEmbeddings() {
        try {
            InputStream is = getClass().getResourceAsStream("/data/kb-embeddings.json");
            if (is == null) {
                return null;
            }

            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> chunks = mapper.readValue(is, new TypeReference<>() {});

            // Convert each chunk into the format expected by the rest of the loader
            List<Map<String, Object>> docs = new ArrayList<>();
            for (Map<String, Object> chunk : chunks) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("id", chunk.get("id"));
                doc.put("title", chunk.get("title"));
                // Derive category from source
                String source = chunk.getOrDefault("source", "").toString().toUpperCase();
                doc.put("category", source);
                doc.put("tags", source.toLowerCase());
                // Use content as both summary and content for chunks
                String content = chunk.getOrDefault("content", "").toString();
                doc.put("summary", content.length() > 200 ? content.substring(0, 200) + "..." : content);
                doc.put("content", content);

                // Parse vector from the JSON
                Object vectorObj = chunk.get("vector");
                if (vectorObj instanceof List<?> vectorList) {
                    float[] vector = new float[vectorList.size()];
                    for (int i = 0; i < vectorList.size(); i++) {
                        vector[i] = ((Number) vectorList.get(i)).floatValue();
                    }
                    doc.put("vector", vector);
                } else {
                    // Generate mock vector as fallback
                    doc.put("vector", generateVector(content));
                }

                docs.add(doc);
            }
            return docs;
        } catch (Exception e) {
            log.warn("Failed to load pre-computed embeddings: {}", e.getMessage());
            return null;
        }
    }

    private void createIndex() {
        try {
            // Drop existing index if present
            redis.execute(connection -> {
                try {
                    connection.execute("FT.DROPINDEX",
                            INDEX_NAME.getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
                return null;
            }, true);
        } catch (Exception ignored) {}

        redis.execute(connection -> {
            connection.execute("FT.CREATE",
                    INDEX_NAME.getBytes(StandardCharsets.UTF_8),
                    "ON".getBytes(StandardCharsets.UTF_8),
                    "JSON".getBytes(StandardCharsets.UTF_8),
                    "PREFIX".getBytes(StandardCharsets.UTF_8),
                    "1".getBytes(StandardCharsets.UTF_8),
                    DOC_PREFIX.getBytes(StandardCharsets.UTF_8),
                    "SCHEMA".getBytes(StandardCharsets.UTF_8),
                    "$.title".getBytes(StandardCharsets.UTF_8),
                    "AS".getBytes(StandardCharsets.UTF_8),
                    "title".getBytes(StandardCharsets.UTF_8),
                    "TEXT".getBytes(StandardCharsets.UTF_8),
                    "WEIGHT".getBytes(StandardCharsets.UTF_8),
                    "2.0".getBytes(StandardCharsets.UTF_8),
                    "$.category".getBytes(StandardCharsets.UTF_8),
                    "AS".getBytes(StandardCharsets.UTF_8),
                    "category".getBytes(StandardCharsets.UTF_8),
                    "TAG".getBytes(StandardCharsets.UTF_8),
                    "$.summary".getBytes(StandardCharsets.UTF_8),
                    "AS".getBytes(StandardCharsets.UTF_8),
                    "summary".getBytes(StandardCharsets.UTF_8),
                    "TEXT".getBytes(StandardCharsets.UTF_8),
                    "$.content".getBytes(StandardCharsets.UTF_8),
                    "AS".getBytes(StandardCharsets.UTF_8),
                    "content".getBytes(StandardCharsets.UTF_8),
                    "TEXT".getBytes(StandardCharsets.UTF_8),
                    "$.tags".getBytes(StandardCharsets.UTF_8),
                    "AS".getBytes(StandardCharsets.UTF_8),
                    "tags".getBytes(StandardCharsets.UTF_8),
                    "TAG".getBytes(StandardCharsets.UTF_8),
                    "SEPARATOR".getBytes(StandardCharsets.UTF_8),
                    ",".getBytes(StandardCharsets.UTF_8),
                    "$.vector".getBytes(StandardCharsets.UTF_8),
                    "AS".getBytes(StandardCharsets.UTF_8),
                    "vector".getBytes(StandardCharsets.UTF_8),
                    "VECTOR".getBytes(StandardCharsets.UTF_8),
                    "HNSW".getBytes(StandardCharsets.UTF_8),
                    "6".getBytes(StandardCharsets.UTF_8),
                    "TYPE".getBytes(StandardCharsets.UTF_8),
                    "FLOAT32".getBytes(StandardCharsets.UTF_8),
                    "DIM".getBytes(StandardCharsets.UTF_8),
                    String.valueOf(VECTOR_DIM).getBytes(StandardCharsets.UTF_8),
                    "DISTANCE_METRIC".getBytes(StandardCharsets.UTF_8),
                    "COSINE".getBytes(StandardCharsets.UTF_8));
            return null;
        }, true);
    }

    private List<Map<String, Object>> buildMockDocuments() {
        List<Map<String, Object>> docs = new ArrayList<>();

        docs.add(doc("REG001", "PSD2 Strong Customer Authentication",
                "PSD2", "authentication,payments,SCA,electronic",
                "PSD2 mandates Strong Customer Authentication (SCA) for electronic payment transactions within the EEA. Payment service providers must implement multi-factor authentication.",
                "The Payment Services Directive 2 (PSD2) requires Strong Customer Authentication (SCA) for all electronic payment transactions exceeding 30 EUR. SCA requires at least two of three factors: knowledge (password or PIN), possession (mobile device or card), and inherence (fingerprint or facial recognition). Payment service providers must implement SCA by default, with limited exemptions for low-value transactions, recurring payments, and trusted beneficiaries. Non-compliance may result in fines up to 10% of annual turnover."));

        docs.add(doc("REG002", "GDPR Data Protection for Banking",
                "GDPR", "data-protection,privacy,customer-data,erasure",
                "GDPR establishes strict rules for processing personal data of EU residents. Banks must ensure lawful basis for processing and respect data subject rights.",
                "The General Data Protection Regulation (GDPR) requires banks to have a lawful basis for processing customer personal data, such as contractual necessity or legitimate interest. Customers have the right to access their data, request rectification, and invoke the right to erasure (right to be forgotten) where applicable. Banks must implement appropriate technical and organizational measures including encryption, access controls, and data minimization. Data breaches must be reported to supervisory authorities within 72 hours."));

        docs.add(doc("REG003", "MiFID II Transaction Reporting",
                "MiFID_II", "reporting,transactions,trading,investment",
                "MiFID II mandates comprehensive transaction reporting for investment firms. All trades must be reported to competent authorities within T+1.",
                "The Markets in Financial Instruments Directive II (MiFID II) requires investment firms to report detailed information on all transactions in financial instruments. Reports must be submitted to the relevant competent authority by the end of the following working day (T+1). Each report must include 65 data fields covering client identification, instrument details, price, quantity, and execution venue. Firms must maintain records for at least five years and implement systematic monitoring for suspicious trading patterns."));

        docs.add(doc("REG004", "Basel III Capital Requirements",
                "Basel_III", "capital,liquidity,risk,banking-supervision",
                "Basel III establishes minimum capital ratios and liquidity requirements for banks. Institutions must maintain adequate capital buffers against risk-weighted assets.",
                "Basel III requires banks to maintain a minimum Common Equity Tier 1 (CET1) capital ratio of 4.5% of risk-weighted assets, plus a capital conservation buffer of 2.5%, bringing the effective minimum to 7%. The Liquidity Coverage Ratio (LCR) requires banks to hold sufficient high-quality liquid assets to survive a 30-day stress scenario. The Net Stable Funding Ratio (NSFR) ensures banks maintain stable funding relative to their asset profiles over a one-year horizon. Systemically important banks face additional surcharges."));

        docs.add(doc("REG005", "PSD2 Open Banking API Standards",
                "PSD2", "open-banking,API,third-party,account-access",
                "PSD2 requires banks to provide third-party providers access to customer accounts via secure APIs. This enables open banking services and increased competition.",
                "Under PSD2, Account Servicing Payment Service Providers (ASPSPs) must provide dedicated interfaces (APIs) for Third Party Providers (TPPs) to access customer account information and initiate payments with customer consent. APIs must support Account Information Services (AIS) and Payment Initiation Services (PIS). Strong Customer Authentication is required for API access, and banks cannot discriminate against TPP requests. APIs must maintain 99.5% availability and response times under 500ms for account data queries."));

        docs.add(doc("REG006", "GDPR Cross-border Data Transfers",
                "GDPR", "cross-border,data-transfer,adequacy,international",
                "GDPR restricts transfers of personal data outside the EEA unless adequate safeguards are in place. Banks must implement approved transfer mechanisms.",
                "Personal data transfers outside the European Economic Area (EEA) are restricted under GDPR unless the receiving country has an adequacy decision from the European Commission, or appropriate safeguards such as Standard Contractual Clauses (SCCs) or Binding Corporate Rules (BCRs) are implemented. Banks processing customer data across jurisdictions must conduct Transfer Impact Assessments (TIAs) and may need to implement supplementary technical measures such as encryption and pseudonymization."));

        docs.add(doc("REG007", "MiFID II Best Execution",
                "MiFID_II", "best-execution,trading,order-handling,transparency",
                "MiFID II requires investment firms to take sufficient steps to achieve the best possible result for clients when executing orders.",
                "Investment firms must establish and implement an order execution policy that considers price, costs, speed, likelihood of execution, and settlement. Firms must monitor execution quality across venues and publish annual reports on top five execution venues by trading volume. Client orders must be executed promptly and fairly, with robust arrangements to prevent misuse of client order information. The best execution obligation applies differently to professional and retail clients, with additional protections for retail investors."));

        docs.add(doc("REG008", "Basel III Leverage Ratio",
                "Basel_III", "leverage,capital,exposure,systemic-risk",
                "Basel III introduces a non-risk-based leverage ratio to constrain excessive balance sheet growth and supplement risk-weighted capital requirements.",
                "The Basel III leverage ratio requires banks to maintain a minimum Tier 1 capital equal to at least 3% of total exposure, calculated as the sum of on-balance-sheet exposures, derivative exposures, securities financing transactions, and off-balance-sheet items. Global systemically important banks (G-SIBs) must meet a higher leverage buffer of 50% of their risk-weighted surcharge. The leverage ratio serves as a backstop to risk-based capital measures and prevents excessive leverage that contributed to the 2008 financial crisis."));

        docs.add(doc("REG009", "GDPR Automated Decision Making",
                "GDPR", "automated-decisions,profiling,AI,credit-scoring",
                "GDPR grants individuals the right not to be subject to decisions based solely on automated processing including profiling that produces legal or significant effects.",
                "Under GDPR Article 22, banks using automated credit scoring or risk profiling must ensure customers can obtain human intervention, express their point of view, and contest decisions. Automated decision-making systems must be transparent, with clear explanations of the logic involved. Banks must conduct Data Protection Impact Assessments (DPIAs) before deploying automated processing systems that may pose high risks to individuals' rights."));

        docs.add(doc("REG010", "PSD2 Fraud Prevention and Liability",
                "PSD2", "fraud,liability,unauthorized-transactions,consumer-protection",
                "PSD2 establishes clear liability rules for unauthorized payment transactions and requires payment service providers to implement robust fraud prevention measures.",
                "Under PSD2, payment service providers bear liability for unauthorized transactions unless the payer acted fraudulently or with gross negligence. The payer's maximum liability for unauthorized transactions is limited to 50 EUR, provided they notify their bank without undue delay. Banks must implement real-time transaction monitoring, behavioral analytics, and device fingerprinting to detect and prevent fraud. Payment service providers must report fraud statistics to competent authorities on a semi-annual basis."));

        // Generate vectors — real embeddings if OpenAI configured, mock otherwise
        if (openAiService.isConfigured()) {
            log.info("UC6: Generating real embeddings for {} regulation documents via OpenAI...", docs.size());
            List<String> texts = new ArrayList<>();
            for (Map<String, Object> doc : docs) {
                texts.add(doc.get("title") + " " + doc.get("summary") + " " + doc.get("content"));
            }
            List<float[]> embeddings = openAiService.getEmbeddings(texts);
            for (int i = 0; i < docs.size(); i++) {
                docs.get(i).put("vector", embeddings.get(i));
            }
        } else {
            for (Map<String, Object> doc : docs) {
                String seed = doc.get("title") + " " + doc.get("summary") + " " + doc.get("content");
                doc.put("vector", generateVector(seed));
            }
        }

        return docs;
    }

    private Map<String, Object> doc(String id, String title, String category,
                                     String tags, String summary, String content) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        doc.put("title", title);
        doc.put("category", category);
        doc.put("tags", tags);
        doc.put("summary", summary);
        doc.put("content", content);
        return doc;
    }

    /** Generate a deterministic pseudo-random vector from a seed string. */
    public static float[] generateVector(String seed) {
        long hash = 0;
        for (char c : seed.toCharArray()) {
            hash = 31 * hash + c;
        }
        Random rng = new Random(hash);
        float[] vec = new float[VECTOR_DIM];
        double norm = 0;
        for (int i = 0; i < VECTOR_DIM; i++) {
            vec[i] = (float) rng.nextGaussian();
            norm += vec[i] * vec[i];
        }
        // Normalize for COSINE
        norm = Math.sqrt(norm);
        for (int i = 0; i < VECTOR_DIM; i++) {
            vec[i] /= (float) norm;
        }
        return vec;
    }

    /** Convert float array to JSON array string. */
    private static String vectorToJsonArray(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /** Simple JSON serialization (no external dependency). */
    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(e.getKey())).append("\":");
            Object val = e.getValue();
            if (val instanceof String s) {
                sb.append("\"").append(escapeJson(s)).append("\"");
            } else if (val instanceof float[] arr) {
                sb.append(vectorToJsonArray(arr));
            } else {
                sb.append(val);
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
