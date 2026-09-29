package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fc.v2.mapper.auto.TToponNameBillMapper;
import com.fc.v2.model.auto.TToponNameBill;

/**
 * 逐级核准单：四层、OR/AND 同一判据、签足进层、末层签足核讫不凭空多一层、
 * 否决落已打回、复核退回先清本层印再退层、死单不动。
 */
class TToponNameBillServiceImplTest {

    private TToponNameBillMapper mapper;
    private TToponNameBillServiceImpl svc;

    /** 0在核 1已核讫 2已打回 */
    private TToponNameBill bill(int node, int mode, Integer need, int signed, int status) {
        TToponNameBill r = new TToponNameBill();
        r.setId(1L);
        r.setNodeNo(node);
        r.setSignMode(mode);
        r.setNeedCount(need);
        r.setSignCount(signed);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(TToponNameBillMapper.class);
        svc = new TToponNameBillServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "toponNameBillMapper", mapper);
        when(mapper.updateById(any())).thenReturn(1);
    }

    @Test
    void 任一人_四层签足核讫_不多出一层() {
        TToponNameBill r = bill(0, 0, 1, 0, 0);
        when(mapper.selectById(1L)).thenReturn(r);
        // 每一层一票即进层
        assertNotNull(svc.approve(1L, "甲", "c"));
        assertEquals(1, r.getNodeNo());
        assertEquals(0, r.getSignCount());
        assertEquals(0, r.getStatus());
        assertNotNull(svc.approve(1L, "甲", "c"));
        assertEquals(2, r.getNodeNo());
        assertNotNull(svc.approve(1L, "甲", "c"));
        assertEquals(3, r.getNodeNo());
        assertNotNull(svc.approve(1L, "甲", "c"));
        assertEquals(3, r.getNodeNo(), "末层签足不再往上加层");
        assertEquals(1, r.getStatus(), "已核讫");
        // 死单再签：拒
        assertNull(svc.approve(1L, "甲", "c"));
    }

    @Test
    void 两名点齐_少一票不进层_不重复落印() {
        TToponNameBill r = bill(0, 1, 2, 0, 0);
        when(mapper.selectById(1L)).thenReturn(r);
        assertNotNull(svc.approve(1L, "甲", "c"));
        assertEquals(0, r.getNodeNo(), "一票不够，留在本层");
        assertEquals(1, r.getSignCount());
        assertEquals(0, r.getStatus());
        assertNotNull(svc.approve(1L, "乙", "c"));
        assertEquals(1, r.getNodeNo(), "两票点齐进层");
        assertEquals(0, r.getSignCount());
    }

    @Test
    void 否决落已打回_打回后两头皆拒() {
        TToponNameBill r = bill(1, 0, 1, 0, 0);
        when(mapper.selectById(1L)).thenReturn(r);
        assertNotNull(svc.reject(1L, "甲", "不行"));
        assertEquals(2, r.getStatus());
        assertNull(svc.approve(1L, "乙", "c"));
        assertNull(svc.reject(1L, "乙", "c"));
        assertNull(svc.rollback(1L, "c"));
    }

    @Test
    void 复核退回_先清本层印再退一层() {
        // 本层已有一印：退回只清印、不退层
        TToponNameBill r = bill(2, 1, 2, 1, 0);
        when(mapper.selectById(1L)).thenReturn(r);
        assertNotNull(svc.rollback(1L, "复核"));
        assertEquals(2, r.getNodeNo());
        assertEquals(0, r.getSignCount());
        // 本层无印：退一层
        assertNotNull(svc.rollback(1L, "复核"));
        assertEquals(1, r.getNodeNo());
        assertEquals(0, r.getSignCount());
    }

    @Test
    void 头层无印无处可退_无签批人皆拒() {
        TToponNameBill r = bill(0, 0, 1, 0, 0);
        when(mapper.selectById(1L)).thenReturn(r);
        assertNull(svc.rollback(1L, "c"));
        assertNull(svc.approve(1L, "  ", "c"));
    }

    @Test
    void 无单不动() {
        when(mapper.selectById(1L)).thenReturn(null);
        assertNull(svc.approve(1L, "甲", "c"));
        assertNull(svc.reject(1L, "甲", "c"));
        assertNull(svc.rollback(1L, "c"));
    }
}
