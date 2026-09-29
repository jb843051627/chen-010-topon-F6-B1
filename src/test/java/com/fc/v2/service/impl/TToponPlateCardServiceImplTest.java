package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TToponDistrictDocMapper;
import com.fc.v2.mapper.auto.TToponPlateCardMapper;
import com.fc.v2.model.auto.TToponDistrictDoc;
import com.fc.v2.model.auto.TToponPlateCard;

/**
 * 号段建档卡：新增/改动同一把尺、=上限升档、清单口径、重开不改不重判的纯单测。
 */
class TToponPlateCardServiceImplTest {

    private TToponPlateCardMapper plateMapper;
    private TToponDistrictDocMapper districtMapper;
    private TToponPlateCardServiceImpl service;

    private static TToponDistrictDoc arch(long id, String siteNo, String t1, String t2, String t3, int status) {
        TToponDistrictDoc a = new TToponDistrictDoc();
        a.setId(id);
        a.setSiteNo(siteNo);
        a.setTh1Max(new BigDecimal(t1));
        a.setTh2Max(new BigDecimal(t2));
        a.setTh3Max(new BigDecimal(t3));
        a.setStatus(status);
        a.setDelFlag(0);
        return a;
    }

    private static TToponPlateCard card(Long id, String billNo, Integer siteId, String qty, Integer grade, String siteNo) {
        TToponPlateCard c = new TToponPlateCard();
        c.setId(id);
        c.setBillNo(billNo);
        c.setSiteId(siteId);
        if (qty != null) c.setQty(new BigDecimal(qty));
        c.setGradeLevel(grade);
        c.setSiteNo(siteNo);
        c.setDelFlag(0);
        return c;
    }

    @BeforeEach
    void setUp() {
        plateMapper = mock(TToponPlateCardMapper.class);
        districtMapper = mock(TToponDistrictDocMapper.class);
        service = new TToponPlateCardServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", plateMapper);
        ReflectionTestUtils.setField(service, "toponDistrictDocMapper", districtMapper);
    }

    @Test
    void insert_exactlyAtLimit_goesUpOneTier() {
        when(districtMapper.selectOne(any())).thenReturn(arch(0, "XZ00", "40", "200", "600", 0));
        when(plateMapper.selectCount(any())).thenReturn(0);
        when(plateMapper.insert(any())).thenReturn(1);

        // 40/200/600 三个临界数都该照边界约定升一档；39.99 留一档。
        assertEquals(1, insertAndGrade("39.99"));
        assertEquals(2, insertAndGrade("40"));
        assertEquals(3, insertAndGrade("200"));
        assertEquals(4, insertAndGrade("600"));
        assertEquals(4, insertAndGrade("600.01"));
    }

    @Test
    void insert_retiredArch_rejected() {
        when(districtMapper.selectOne(any())).thenReturn(arch(1, "XZ01", "40", "200", "600", 1));
        assertEquals(0, service.insertTToponPlateCard(card(null, "B1", 1, "10", null, null)));
        verify(plateMapper, never()).insert(any());
    }

    @Test
    void insert_duplicateBill_rejected() {
        when(districtMapper.selectOne(any())).thenReturn(arch(0, "XZ00", "40", "200", "600", 0));
        when(plateMapper.selectCount(any())).thenReturn(2);
        assertEquals(0, service.insertTToponPlateCard(card(null, "DUP", 0, "10", null, null)));
        verify(plateMapper, never()).insert(any());
    }

    @Test
    void insert_nullQty_gradeZeroButAccepted() {
        when(districtMapper.selectOne(any())).thenReturn(arch(0, "XZ00", "40", "200", "600", 0));
        when(plateMapper.selectCount(any())).thenReturn(0);
        TToponPlateCard c = card(null, "B9", 0, null, null, null);
        when(plateMapper.insert(any())).thenReturn(1);
        assertEquals(1, service.insertTToponPlateCard(c));
        assertEquals(Integer.valueOf(0), c.getGradeLevel());
        assertEquals("XZ00", c.getSiteNo());
    }

    @Test
    void update_unchangedQty_notRejudgedAgainstNewestThreshold() {
        // 旧账：当年一档上限就是 40，qty=40 定了一档。如今档案一档收到 30（新门槛）。
        TToponPlateCard existing = card(10L, "B1", 0, "40", 1, "XZ00");
        when(plateMapper.selectOne(any())).thenReturn(existing);
        when(districtMapper.selectOne(any())).thenReturn(arch(0, "XZ00", "30", "200", "600", 0));
        when(plateMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

        // 回头再开，数量没动只改了备注：档位必须照旧，眼下的新门槛不得横插一手。
        TToponPlateCard patch = new TToponPlateCard();
        patch.setId(10L);
        patch.setRemark("勘办记事补一句");
        assertEquals(1, service.updateTToponPlateCard(patch));
        assertEquals(Integer.valueOf(1), patch.getGradeLevel());
        assertEquals(new BigDecimal("40"), patch.getQty());
    }

    @Test
    void update_qtyChanged_rejudgedWithSameRulerAsInsert() {
        TToponPlateCard existing = card(10L, "B1", 0, "40", 2, "XZ00");
        when(plateMapper.selectOne(any())).thenReturn(existing);
        when(districtMapper.selectOne(any())).thenReturn(arch(0, "XZ00", "40", "200", "600", 0));
        when(plateMapper.selectCount(any())).thenReturn(0);
        when(plateMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

        TToponPlateCard patch = new TToponPlateCard();
        patch.setId(10L);
        patch.setBillNo("B1");
        patch.setQty(new BigDecimal("200")); // 顶到二档上限 → 升三档，与新增同一把尺
        assertEquals(1, service.updateTToponPlateCard(patch));
        assertEquals(Integer.valueOf(3), patch.getGradeLevel());
    }

    @Test
    void update_resyncsSiteNoFromCurrentArch() {
        TToponPlateCard existing = card(10L, "B1", 0, "40", 2, "OLD");
        when(plateMapper.selectOne(any())).thenReturn(existing);
        when(districtMapper.selectOne(any())).thenReturn(arch(0, "NEW", "40", "200", "600", 0));
        when(plateMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

        TToponPlateCard patch = new TToponPlateCard();
        patch.setId(10L);
        service.updateTToponPlateCard(patch);
        assertEquals("NEW", patch.getSiteNo());
    }

    @Test
    void update_missingCardOrDuplicateBill_rejected() {
        when(plateMapper.selectOne(any())).thenReturn(null);
        TToponPlateCard patch = card(99L, "BX", 0, "10", null, null);
        assertEquals(0, service.updateTToponPlateCard(patch));
        verify(plateMapper, never()).update(any(), any(UpdateWrapper.class));

        TToponPlateCard existing = card(10L, "B1", 0, "40", 2, "XZ00");
        when(plateMapper.selectOne(any())).thenReturn(existing);
        when(plateMapper.selectCount(any())).thenReturn(1);
        TToponPlateCard dup = card(10L, "B2", 0, "40", null, null);
        assertEquals(0, service.updateTToponPlateCard(dup));
        verify(plateMapper, never()).update(any(), any(UpdateWrapper.class));
    }

    @Test
    void list_honorsCallerConditions_andAddsOnlyDelFlagGate() {
        List<TToponPlateCard> rows = Arrays.asList(card(1L, "A", 0, "10", 1, "XZ00"));
        when(plateMapper.selectList(any())).thenReturn(rows);

        // 调用方带着"已定档"条件来，列表照传，不再硬塞 status=0、不再另起分页。
        QueryWrapper<TToponPlateCard> qw = new QueryWrapper<TToponPlateCard>().eq("status", 2);
        List<TToponPlateCard> got = service.selectTToponPlateCardList(qw);
        assertEquals(1, got.size());
        assertNotNull(qw.getTargetSql()); // 条件确实留在 wrapper 上交给数据层
        assertEquals(rows, got);

        // 不传条件也不炸（旧实现同样能跑，但分页/状态硬编码的坑在此一并防回归）。
        when(plateMapper.selectList(any())).thenReturn(Collections.<TToponPlateCard>emptyList());
        assertEquals(0, service.selectTToponPlateCardList(new QueryWrapper<TToponPlateCard>()).size());
    }

    private int insertAndGrade(String qty) {
        TToponPlateCard c = card(null, "B" + qty, 0, qty, null, null);
        service.insertTToponPlateCard(c);
        return c.getGradeLevel();
    }
}
