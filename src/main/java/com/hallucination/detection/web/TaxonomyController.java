package com.hallucination.detection.web;

import com.hallucination.detection.model.HallucinationType;
import com.hallucination.detection.model.Severity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类体系定义。
 *
 * <p>页面的「幻觉分类定义」那一段从这里取，而不是在 HTML 里另抄一份——
 * 分类是 Java 枚举定义的，抄一份到页面上就意味着两处会逐渐对不上。
 */
@RestController
public class TaxonomyController {

    private static final List<HallucinationType> REPORTED_TYPES = List.of(
            HallucinationType.FACT,
            HallucinationType.POLICY,
            HallucinationType.CAPABILITY,
            HallucinationType.OMISSION);

    private static final List<Severity> REPORTED_SEVERITIES = List.of(
            Severity.S1, Severity.S2, Severity.S3);

    @GetMapping(value = "/api/taxonomy", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> taxonomy() {
        List<Map<String, Object>> types = new ArrayList<>();
        for (HallucinationType type : REPORTED_TYPES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", type.name());
            entry.put("label", type.label());
            entry.put("definition", type.definition());
            entry.put("baselineSeverity", type.baselineSeverity().name());
            types.add(entry);
        }

        List<Map<String, Object>> severities = new ArrayList<>();
        for (Severity severity : REPORTED_SEVERITIES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", severity.name());
            entry.put("label", severity.label());
            entry.put("criterion", severity.criterion());
            severities.add(entry);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("types", types);
        response.put("severities", severities);
        return response;
    }
}
