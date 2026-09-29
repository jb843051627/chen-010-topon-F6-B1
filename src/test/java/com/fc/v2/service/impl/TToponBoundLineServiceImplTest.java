package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fc.v2.mapper.auto.TToponBoundLineMapper;
import com.fc.v2.model.auto.TToponBoundLine;

/**
 * 勘定基准档线：生效区间、交棒当日、临界升档、取用顺位、让位状态口径的纯单测。
 * 验证路数：同一笔输入隔时再断，结论必须一模一样。
 */
class TToponBoundLineServiceImplTest {

    private TToponBoundLineMapper mapper;
    private TToponBoundLineServiceImpl service;

    private static Date d(String s) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
    }

    private static TToponBoundLine rule(long id, String code, String t1, String t2, String t3,
                                        String start, String end, Integer priority, Integer status) {
        TToponBoundLine r = new TToponBoundLine();
        r.setId(id);
        r.setRuleCode(code);
        if (t1 != null) r.setTh1Max(new BigDecimal(t1));
        if (t2 != null) r.setTh2Max(new BigDecimal(t2));
        if (t3 != null) r.setTh3Max(new BigDecimal(t3));
        try {
            if (start != null) r.setEffStart(d(start));
            if (end != null) r.setEffEnd(d(end));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        r.setPriority(priority);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @BeforeEach
    void setUp() {
        mapper = mock(TToponBoundLineMapper.class);
        service = new TToponBoundLineServiceImpl();
        ReflectionTestUtils.setField(service, "toponBoundLineMapper", mapper);
    }

    @Test
    void handoffDay_onlyNewRuleHits_oldAndNewNeverOverlap() throws Exception {
        // 旧基准：2026-01-01 起，2026-06-01 交棒（不含）；新基准自交棒日 00:00 起。
        TToponBoundLine oldRule = rule(1, "R1", "10", "20", "30",
                "2026-01-01 00:00:00", "2026-06-01 00:00:00", 1, 0);
        TToponBoundLine newRule = rule(2, "R1", "15", "25", "35",
                "2026-06-01 00:00:00", null, 1, 0);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(oldRule, newRule));

        // 交棒前一刻：12 按旧尺（10<12<=20）落在二档；若错用新尺会落一档。
        assertEquals(2, service.evaluate("R1", new BigDecimal("12"), d("2026-05-31 23:59:59")));
        // 交棒当日零点：旧基准已不含此刻，只有新基准命中（12<15 落一档）。
        assertEquals(1, service.evaluate("R1", new BigDecimal("12"), d("2026-06-01 00:00:00")));

        // 两个时刻都只有一条基准罩得住，绝不两条一齐命中。
        assertEquals(1, service.listAvailable(d("2026-05-31 23:59:59")).size());
        assertEquals(1, service.listAvailable(d("2026-06-01 00:00:00")).size());
    }

    @Test
    void valueExactlyAtLimit_goesUpOneTier() throws Exception {
        TToponBoundLine r = rule(1, "R1", "10", "20", "30",
                "2026-01-01 00:00:00", null, 1, 0);
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(r));

        assertEquals(1, service.evaluate("R1", new BigDecimal("9.99"), d("2026-03-01 00:00:00")));
        assertEquals(2, service.evaluate("R1", new BigDecimal("10"), d("2026-03-01 00:00:00")));
        assertEquals(3, service.evaluate("R1", new BigDecimal("20"), d("2026-03-01 00:00:00")));
        assertEquals(4, service.evaluate("R1", new BigDecimal("30"), d("2026-03-01 00:00:00")));
        assertEquals(4, service.evaluate("R1", new BigDecimal("30.01"), d("2026-03-01 00:00:00")));
    }

    @Test
    void retiredRuleNeverHits_andDoesNotInventExtraTier() throws Exception {
        TToponBoundLine retired = rule(1, "R1", "10", "20", "30",
                "2020-01-01 00:00:00", null, 1, 1);
        TToponBoundLine active = rule(2, "R1", "15", "25", "35",
                "2026-01-01 00:00:00", null, 1, 0);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(retired, active));
        when(mapper.selectById(1L)).thenReturn(retired);
        when(mapper.selectById(2L)).thenReturn(active);

        Date at = d("2026-03-01 00:00:00");
        // 早让位的旧基准不得参与：8 在新尺下落一档（旧尺同样落一档，换 12 区分）
        assertEquals(1, service.evaluate("R1", new BigDecimal("12"), at));
        assertEquals(1, service.countAvailable(at));
        assertFalse(service.usable(1L, at));
        assertTrue(service.usable(2L, at));

        // 只剩已让位基准时，复算只能判不可用，不得凭空多出一档。
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(retired));
        assertEquals(0, service.evaluate("R1", new BigDecimal("12"), at));
        assertEquals(0, service.countAvailable(at));
    }

    @Test
    void priorityIsFirstWins_notLargestNumber() throws Exception {
        // 顺位 1 才是头名；两尺不同：8 在顺位1的尺上是一档，在顺位2的尺上是二档。
        TToponBoundLine first = rule(1, "B", "10", "20", "30",
                "2026-01-01 00:00:00", null, 1, 0);
        TToponBoundLine second = rule(2, "A", "5", "15", "25",
                "2026-01-01 00:00:00", null, 2, 0);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(second, first));
        Date at = d("2026-03-01 00:00:00");

        assertEquals(Integer.valueOf(1), service.listAvailable(at).get(0).getPriority());
        assertEquals(1, service.evaluateTop(new BigDecimal("8"), at));
    }

    @Test
    void equalPriority_tieBreakIsStableAndRepeatable() throws Exception {
        // 同刻撞在一起、权重相等：并列时按 ruleCode 升序取头名，连断两次结论一致。
        TToponBoundLine codeA = rule(1, "A", "10", "20", "30",
                "2026-01-01 00:00:00", null, 1, 0);
        TToponBoundLine codeB = rule(2, "B", "5", "15", "25",
                "2026-01-01 00:00:00", null, 1, 0);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(codeB, codeA));
        Date at = d("2026-03-01 00:00:00");

        List<TToponBoundLine> first = service.listAvailable(at);
        List<TToponBoundLine> second = service.listAvailable(at);
        assertEquals("A", first.get(0).getRuleCode());
        assertEquals("A", second.get(0).getRuleCode());
        // 8 在 A 尺上是一档；同摞东西算两遍不得换结论。
        assertEquals(1, service.evaluateTop(new BigDecimal("8"), at));
        assertEquals(1, service.evaluateTop(new BigDecimal("8"), at));
    }

    @Test
    void reEvaluateAtOldDate_usesRuleOfThatDay_notNewestThreshold() throws Exception {
        // 旧基准在 2025 年当家；新基准 2026 才启用。翻 2025 年的旧账重断，不得被新门槛插手。
        TToponBoundLine oldRule = rule(1, "R1", "10", "20", "30",
                "2025-01-01 00:00:00", "2026-01-01 00:00:00", 1, 0);
        TToponBoundLine newRule = rule(2, "R1", "5", "15", "25",
                "2026-01-01 00:00:00", null, 1, 0);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(oldRule, newRule));
        when(mapper.selectById(1L)).thenReturn(oldRule);
        when(mapper.selectById(2L)).thenReturn(newRule);

        // 12：旧尺二档、新尺一档。2025 年的时点必须按旧尺判二档，且两次一致。
        Date oldDay = d("2025-06-01 00:00:00");
        assertEquals(2, service.evaluate("R1", new BigDecimal("12"), oldDay));
        assertEquals(2, service.evaluate("R1", new BigDecimal("12"), oldDay));
        // 报表口径与卷宗口径一致。
        assertEquals(1, service.countAvailable(oldDay));
        assertTrue(service.usable(1L, oldDay));
        assertFalse(service.usable(2L, oldDay));
    }

    @Test
    void deletedAndOutOfRangeAreRejected() throws Exception {
        TToponBoundLine r = rule(1, "R1", "10", "20", "30",
                "2026-01-01 00:00:00", null, 1, 0);
        r.setDelFlag(1);
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(r));
        Date at = d("2026-03-01 00:00:00");

        assertEquals(0, service.evaluate("R1", new BigDecimal("12"), at));
        assertEquals(0, service.countAvailable(at));

        when(mapper.selectList(any())).thenReturn(Collections.singletonList(
                rule(2, "R2", "10", "20", "30", "2026-01-01 00:00:00", null, 1, 0)));
        assertEquals(0, service.evaluateTop(new BigDecimal("-1"), at));
        assertEquals(0, service.evaluateTop(new BigDecimal("100.01"), at));
    }
}
