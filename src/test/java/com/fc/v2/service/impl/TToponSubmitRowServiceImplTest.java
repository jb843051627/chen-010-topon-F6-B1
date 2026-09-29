package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fc.v2.mapper.auto.TToponSubmitRowMapper;
import com.fc.v2.model.auto.TToponSubmitRow;

/**
 * 汇交核收：空批 0、超 500 返 -1 整批不入、非法入失败明细且合法仍入账、
 * 行次保留、同册重交不重入（返回既有成功行数）。
 */
class TToponSubmitRowServiceImplTest {

    private TToponSubmitRowMapper mapper;
    private TToponSubmitRowServiceImpl svc;

    private TToponSubmitRow row(String code, String qty) {
        TToponSubmitRow r = new TToponSubmitRow();
        r.setItemCode(code);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(TToponSubmitRowMapper.class);
        svc = new TToponSubmitRowServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "toponSubmitRowMapper", mapper);
        when(mapper.insert(any())).thenReturn(1);
    }

    @Test
    void 空批与空册号() {
        assertEquals(0, svc.submitBatch("B1", null));
        assertEquals(0, svc.submitBatch("B1", new ArrayList<>()));
        assertEquals(0, svc.submitBatch("  ", new ArrayList<>(java.util.Arrays.asList(row("A", "1")))));
        verify(mapper, Mockito.never()).insert(any());
    }

    @Test
    void 超上限返负一整批不入() {
        List<TToponSubmitRow> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(row("C" + i, "1"));
        }
        assertEquals(-1, svc.submitBatch("B1", rows));
        verify(mapper, Mockito.never()).insert(any());
    }

    @Test
    void 非法挂驳回_合法入账_行次保留() {
        TToponSubmitRow bad1 = row(null, "3");
        TToponSubmitRow bad2 = row("C2", "0");
        TToponSubmitRow bad3 = row("C3", null);
        TToponSubmitRow good = row("C4", "2");
        when(mapper.selectCount(any())).thenReturn(0);

        int ok = svc.submitBatch("B1", new ArrayList<>(java.util.Arrays.asList(bad1, bad2, bad3, good)));
        assertEquals(1, ok);
        assertEquals(2, bad1.getStatus());
        assertEquals(2, bad2.getStatus());
        assertEquals(2, bad3.getStatus());
        assertEquals(1, good.getStatus());
        // 原始册内行次：缺省按册内次序补 1..4
        assertEquals(1, bad1.getRowNo());
        assertEquals(4, good.getRowNo());
        assertEquals("B1", good.getBatchNo());
        assertEquals(0, good.getDelFlag());
        verify(mapper, times(4)).insert(any());
    }

    @Test
    void 自带行次原样保留() {
        TToponSubmitRow r = row("C9", "1");
        r.setRowNo(27);
        when(mapper.selectCount(any())).thenReturn(0);
        svc.submitBatch("B1", new ArrayList<>(java.util.Arrays.asList(r)));
        assertEquals(27, r.getRowNo());
    }

    @Test
    void 同册重交不重入_结论同一副() {
        // 册号已有账：第一次 selectCount 表示有落账，第二次表示其中成功 3 行
        when(mapper.selectCount(any())).thenReturn(1).thenReturn(3);
        int ok = svc.submitBatch("B1", new ArrayList<>(java.util.Arrays.asList(row("C1", "1"), row("C2", "1"))));
        assertEquals(3, ok, "重交返回既有成功行数，不重新入库");
        verify(mapper, Mockito.never()).insert(any());
    }

    @Test
    void 失败明细按册号状态过滤() {
        when(mapper.selectList(any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> w = inv.getArgument(0);
            String seg = w.getSqlSegment();
            assertEquals(true, seg.contains("batch_no") && seg.contains("status"));
            assertEquals(true, seg.toUpperCase().contains("ORDER BY") && seg.contains("row_no"));
            return new ArrayList<TToponSubmitRow>();
        });
        svc.listErrors("B1");
    }
}
