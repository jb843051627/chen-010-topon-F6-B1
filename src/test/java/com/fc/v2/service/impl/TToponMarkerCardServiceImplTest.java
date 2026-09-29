package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fc.v2.mapper.auto.TToponMarkerCardMapper;
import com.fc.v2.model.auto.TToponMarkerCard;

/**
 * 补办事务卡：格口 0..5 一次推一格、到 5 成卷；成卷/封存后推进回退改删全拒；
 * 回退一次只退一格，到头不退；删除打标记。
 */
class TToponMarkerCardServiceImplTest {

    private TToponMarkerCardMapper mapper;
    private TToponMarkerCardServiceImpl svc;

    private TToponMarkerCard card(int stage, int status) {
        TToponMarkerCard r = new TToponMarkerCard();
        r.setId(1L);
        r.setStage(stage);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(TToponMarkerCardMapper.class);
        svc = new TToponMarkerCardServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "toponMarkerCardMapper", mapper);
        when(mapper.updateById(any())).thenReturn(1);
    }

    @Test
    void 逐格推进到五成卷_不能跳格也不能再多一层() {
        TToponMarkerCard r = card(0, 0);
        when(mapper.selectById(1L)).thenReturn(r);
        for (int expected = 1; expected <= 5; expected++) {
            TToponMarkerCard out = svc.advance(1L, "m");
            assertNotNull(out);
            assertEquals(expected, out.getStage());
            assertEquals(expected == 5 ? 1 : 0, out.getStatus());
        }
        // 已成卷再推：拒
        assertNull(svc.advance(1L, "m"));
    }

    @Test
    void 回退逐格_到头不退() {
        TToponMarkerCard r = card(2, 0);
        when(mapper.selectById(1L)).thenReturn(r);
        assertEquals(1, svc.rollback(1L, "b").getStage());
        assertEquals(0, svc.rollback(1L, "b").getStage());
        assertNull(svc.rollback(1L, "b"));
    }

    @Test
    void 成卷封存后改删推进全拒_两种终态接法一致() {
        for (int terminal : new int[]{1, 2}) {
            when(mapper.selectById(1L)).thenReturn(card(5, terminal));
            assertNull(svc.advance(1L, "m"), "终态 " + terminal);
            assertNull(svc.rollback(1L, "b"), "终态 " + terminal);
            assertFalse(svc.updateContent(1L, "x"), "终态 " + terminal);
            assertFalse(svc.remove(1L), "终态 " + terminal);
        }
    }

    @Test
    void 在办可改可删_删除打标记() {
        TToponMarkerCard r = card(3, 0);
        when(mapper.selectById(1L)).thenReturn(r);
        assertTrue(svc.updateContent(1L, "新记事"));
        assertEquals("新记事", r.getContent());
        assertTrue(svc.remove(1L));
        assertEquals(1, r.getDelFlag());
    }

    @Test
    void 无单与已删皆拒() {
        when(mapper.selectById(1L)).thenReturn(null);
        assertNull(svc.advance(1L, "m"));
        TToponMarkerCard gone = card(1, 0);
        gone.setDelFlag(1);
        when(mapper.selectById(1L)).thenReturn(gone);
        assertNull(svc.rollback(1L, "b"));
    }

    @Test
    void 列表补删除条件() {
        when(mapper.selectList(any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> q = inv.getArgument(0);
            assertTrue(q.getSqlSegment().contains("del_flag"));
            return new ArrayList<TToponMarkerCard>();
        });
        svc.selectTToponMarkerCardList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
    }
}
