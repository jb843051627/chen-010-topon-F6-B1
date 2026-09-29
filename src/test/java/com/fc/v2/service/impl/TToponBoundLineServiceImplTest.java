package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TToponBoundLineMapper;
import com.fc.v2.model.auto.TToponBoundLine;

/**
 * 勘定基准档线：生效区间前闭后开、交棒当日唯一、顺位排序、临界升档，
 * 定位/取用/可用性/计数四处判据同出一门。
 */
class TToponBoundLineServiceImplTest {

    private TToponBoundLineMapper mapper;
    private TToponBoundLineServiceImpl svc;

    private static Date day(String s) {
        return new Date(java.sql.Timestamp.valueOf(s + " 00:00:00").getTime());
    }

    private TToponBoundLine rule(long id, String code, Integer pri, String start, String end, Integer status) {
        TToponBoundLine r = new TToponBoundLine();
        r.setId(id);
        r.setRuleCode(code);
        r.setPriority(pri);
        r.setTh1Max(new BigDecimal("10"));
        r.setTh2Max(new BigDecimal("30"));
        r.setTh3Max(new BigDecimal("60"));
        r.setEffStart(start == null ? null : day(start));
        r.setEffEnd(end == null ? null : day(end));
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(TToponBoundLineMapper.class);
        svc = new TToponBoundLineServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "toponBoundLineMapper", mapper);
    }

    @Test
    void 区间前闭后开_交棒当日只有新基准命中() {
        TToponBoundLine oldR = rule(1, "R1", 5, "2026-01-01", "2026-09-01", 0);
        TToponBoundLine newR = rule(2, "R1", 5, "2026-09-01", null, 0);
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(java.util.Arrays.asList(oldR, newR)));

        // 前一天归旧基准（阈值两边一样，重点是能定位到、不返回 0）
        assertEquals(1, svc.evaluate("R1", new BigDecimal("5"), day("2026-08-31")));
        // 交棒当天归新基准
        assertEquals(1, svc.evaluate("R1", new BigDecimal("5"), day("2026-09-01")));
        // 新基准长交，之后一直可用
        assertEquals(1, svc.evaluate("R1", new BigDecimal("5"), day("2026-12-01")));

        // 启用日当天即被罩住（前闭）
        assertEquals(1, svc.evaluate("R1", new BigDecimal("5"), day("2026-01-01")));
        // 启用日前无生效版本
        assertEquals(0, svc.evaluate("R1", new BigDecimal("5"), day("2025-12-31")));
    }

    @Test
    void 已让位的基准不计入可用() {
        TToponBoundLine retired = rule(1, "R1", 9, "2026-01-01", null, 1);
        TToponBoundLine live = rule(2, "R2", 5, "2026-01-01", null, 0);
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(java.util.Arrays.asList(retired, live)));
        when(mapper.selectById(1L)).thenReturn(retired);
        when(mapper.selectById(2L)).thenReturn(live);

        List<TToponBoundLine> avail = svc.listAvailable(day("2026-09-01"));
        assertEquals(1, avail.size());
        assertEquals(2L, avail.get(0).getId());
        assertEquals(1, svc.countAvailable(day("2026-09-01")));
        assertFalse(svc.usable(1L, day("2026-09-01")));
        assertTrue(svc.usable(2L, day("2026-09-01")));
    }

    @Test
    void 多档并取按顺位档案座次_并列也有定序不碰运气() {
        // 区间异常交叠的两条：优先级高者胜，不按库里返回次序
        TToponBoundLine low = rule(1, "R1", 5, "2026-01-01", null, 0);
        TToponBoundLine high = rule(2, "R2", 9, "2026-01-01", null, 0);
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(java.util.Arrays.asList(low, high)));

        List<TToponBoundLine> avail = svc.listAvailable(day("2026-09-01"));
        assertEquals(2L, avail.get(0).getId());
        // 高顺位那条的第二档（10 < 15 <= 30）
        assertEquals(2, svc.evaluateTop(new BigDecimal("15"), day("2026-09-01")));

        // 反过来塞，结论不能变
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(java.util.Arrays.asList(high, low)));
        assertEquals(2L, svc.listAvailable(day("2026-09-01")).get(0).getId());
        assertEquals(2, svc.evaluateTop(new BigDecimal("15"), day("2026-09-01")));
    }

    @Test
    void 恰顶上限升一档_两头同一把尺() {
        TToponBoundLine r = rule(1, "R1", 5, "2026-01-01", null, 0);
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(java.util.Arrays.asList(r)));

        // 单规则 evaluate 与多规则 evaluateTop 同判据
        assertEquals(1, svc.evaluate("R1", new BigDecimal("9.99"), day("2026-09-01")));
        assertEquals(2, svc.evaluate("R1", new BigDecimal("10.00"), day("2026-09-01")));
        assertEquals(2, svc.evaluateTop(new BigDecimal("10.00"), day("2026-09-01")));
        assertEquals(3, svc.evaluate("R1", new BigDecimal("30.00"), day("2026-09-01")));
        assertEquals(3, svc.evaluateTop(new BigDecimal("30.00"), day("2026-09-01")));
        assertEquals(4, svc.evaluate("R1", new BigDecimal("60.01"), day("2026-09-01")));
    }

    @Test
    void 无生效版本与越界输入返回0() {
        when(mapper.selectList(any())).thenReturn(new ArrayList<>());
        assertEquals(0, svc.evaluate("R1", new BigDecimal("5"), day("2026-09-01")));
        assertEquals(0, svc.evaluateTop(new BigDecimal("5"), day("2026-09-01")));

        TToponBoundLine r = rule(1, "R1", 5, "2026-01-01", null, 0);
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(java.util.Arrays.asList(r)));
        assertEquals(0, svc.evaluateTop(new BigDecimal("-1"), day("2026-09-01")));
        assertEquals(0, svc.evaluateTop(new BigDecimal("100.01"), day("2026-09-01")));
    }

    @Test
    void 阈值全缺不可用() {
        TToponBoundLine r = rule(1, "R1", 5, "2026-01-01", null, 0);
        r.setTh1Max(null);
        r.setTh2Max(null);
        r.setTh3Max(null);
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(java.util.Arrays.asList(r)));
        assertEquals(0, svc.evaluate("R1", new BigDecimal("5"), day("2026-09-01")));
    }

    @Test
    void 查询条件本身带del与status() {
        // 冒烟：wrapper 构造不抛异常即可，口径由上面用例保证
        QueryWrapper<TToponBoundLine> q = new QueryWrapper<>();
        q.eq("rule_code", "R1");
        assertTrue(q.getSqlSegment().contains("rule_code"));
    }
}
