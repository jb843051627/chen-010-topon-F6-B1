package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TToponDistrictDocMapper;
import com.fc.v2.mapper.auto.TToponPlateCardMapper;
import com.fc.v2.model.auto.TToponDistrictDoc;
import com.fc.v2.model.auto.TToponPlateCard;

/**
 * 建档卡：新立/改动同一道卡口与同一把尺、恰顶上限升一档、清单与详情口径合一、
 * 撤并档案拒收、重号拒收、删除走标记。
 */
class TToponPlateCardServiceImplTest {

    private TToponPlateCardMapper cardMapper;
    private TToponDistrictDocMapper archMapper;
    private TToponPlateCardServiceImpl svc;

    private TToponDistrictDoc arch(int id, int status) {
        TToponDistrictDoc a = new TToponDistrictDoc();
        a.setId((long) id);
        a.setSiteNo("XZ0" + id);
        a.setStatus(status);
        a.setDelFlag(0);
        a.setTh1Max(new BigDecimal("40"));
        a.setTh2Max(new BigDecimal("200"));
        a.setTh3Max(new BigDecimal("600"));
        return a;
    }

    private TToponPlateCard card(long id, int siteId, String billNo, String qty) {
        TToponPlateCard c = new TToponPlateCard();
        c.setId(id);
        c.setSiteId(siteId);
        c.setBillNo(billNo);
        c.setQty(qty == null ? null : new BigDecimal(qty));
        return c;
    }

    @BeforeEach
    void setUp() {
        cardMapper = Mockito.mock(TToponPlateCardMapper.class);
        archMapper = Mockito.mock(TToponDistrictDocMapper.class);
        svc = new TToponPlateCardServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "baseMapper", cardMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "toponDistrictDocMapper", archMapper);
    }

    @Test
    void 新立定档_恰顶上限升一档() {
        when(archMapper.selectOne(any())).thenReturn(arch(0, 0));
        when(cardMapper.selectCount(any())).thenReturn(0);
        when(cardMapper.insert(any())).thenReturn(1);

        assertEquals(1, gradeOfInsert(0, "39.99"));
        assertEquals(2, gradeOfInsert(0, "40.00"));
        assertEquals(2, gradeOfInsert(0, "40.01"));
        assertEquals(3, gradeOfInsert(0, "200.00"));
        assertEquals(4, gradeOfInsert(0, "600.00"));
        assertEquals(4, gradeOfInsert(0, "601"));
    }

    @Test
    void 撤并档案与重号拒收() {
        when(archMapper.selectOne(any())).thenReturn(arch(1, 1));
        assertEquals(0, svc.insertTToponPlateCard(card(1, 1, "B1", "10")));

        when(archMapper.selectOne(any())).thenReturn(arch(0, 0));
        when(cardMapper.selectCount(any())).thenReturn(1);
        assertEquals(0, svc.insertTToponPlateCard(card(2, 0, "DUP", "10")));
    }

    @Test
    void 改动按同一把尺重算_复算与原算不再两般模样() {
        TToponPlateCard existing = card(5, 0, "B5", "40.00");
        existing.setGradeLevel(2);
        existing.setDelFlag(0);
        when(cardMapper.selectOne(any())).thenReturn(existing);
        when(archMapper.selectOne(any())).thenReturn(arch(0, 0));
        when(cardMapper.selectCount(any())).thenReturn(0);
        when(cardMapper.update(any(), any())).thenReturn(1);

        // 数量改成顶到 200：必须重算为 3，不能留着老档位 2
        TToponPlateCard edit = card(5, 0, "B5", "200.00");
        assertEquals(1, svc.updateTToponPlateCard(edit));
        assertEquals(3, edit.getGradeLevel());

        // 改到撤并档案：拒
        when(archMapper.selectOne(any())).thenReturn(arch(1, 1));
        TToponPlateCard edit2 = card(5, 1, "B5", "200.00");
        assertEquals(0, svc.updateTToponPlateCard(edit2));

        // 查无此单（已删）：拒
        when(cardMapper.selectOne(any())).thenReturn(null);
        assertEquals(0, svc.updateTToponPlateCard(card(99, 0, "X", "1")));
    }

    @Test
    void 清单与详情同一口径_补del条件不钉死状态() {
        when(cardMapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<?> q = inv.getArgument(0);
            String seg = q.getSqlSegment();
            assertTrue(seg.contains("del_flag"), "必须带删除标记过滤");
            assertEquals(true, !seg.contains("status"));
            return new ArrayList<TToponPlateCard>();
        });
        QueryWrapper<TToponPlateCard> q = new QueryWrapper<>();
        q.eq("bill_no", "B5");
        svc.selectTToponPlateCardList(q);
    }

    @Test
    void 删除打标记不做物理删() {
        when(cardMapper.update(any(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?> uw = inv.getArgument(1);
            assertTrue(uw.getSqlSet().contains("del_flag"));
            return 1;
        });
        assertEquals(1, svc.deleteTToponPlateCardById(5L));
    }

    private Integer gradeOfInsert(int siteId, String qty) {
        TToponPlateCard c = card(0, siteId, "B" + System.nanoTime(), qty);
        svc.insertTToponPlateCard(c);
        return c.getGradeLevel();
    }
}
