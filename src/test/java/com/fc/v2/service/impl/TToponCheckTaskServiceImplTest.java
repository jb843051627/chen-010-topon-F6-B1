package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.fc.v2.mapper.auto.TToponCheckTaskMapper;
import com.fc.v2.model.auto.TToponCheckTask;

/**
 * 定期检验派发：窗口 [0, amount] 自然日、恰到期照派、越界/过期落派不出去、
 * 只看待派条目、复跑不重复。
 */
class TToponCheckTaskServiceImplTest {

    private TToponCheckTaskMapper mapper;
    private TToponCheckTaskServiceImpl svc;

    private static Date at(String s) {
        return new Date(java.sql.Timestamp.valueOf(s).getTime());
    }

    private TToponCheckTask task(long id, String due, String amount, int status) {
        TToponCheckTask r = new TToponCheckTask();
        r.setId(id);
        r.setDueAt(due == null ? null : at(due));
        r.setAmount(amount == null ? null : new BigDecimal(amount));
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(TToponCheckTaskMapper.class);
        svc = new TToponCheckTaskServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "toponCheckTaskMapper", mapper);
    }

    @Test
    void 窗口与恰到期() {
        // SQL 层已过滤 status=0 / del_flag=0：mock 只回待派未删的子集
        List<TToponCheckTask> waiting = new ArrayList<>(java.util.Arrays.asList(
                task(1, "2026-09-20 00:00:00", "3", 0),  // 距 2 天，在窗内
                task(2, "2026-09-18 23:59:59", "2", 0),  // 截止前一刻
                task(3, "2026-09-18 00:00:00", "2", 0),  // 恰到期当日
                task(4, "2026-09-25 00:00:00", "3", 0),  // 距 7 天，未开窗
                task(5, "2026-09-16 00:00:00", "2", 0),  // 已过期两天
                task(6, "2026-09-20 00:00:00", "6", 0),  // 提前天数越上界
                task(7, "2026-09-20 00:00:00", "1", 0),  // 提前天数越下界
                task(9, null, "3", 0)                    // 日期未维护，先放着
        ));
        TToponCheckTask already = task(8, "2026-09-20 00:00:00", "3", 1); // 已派，不回锅
        when(mapper.selectList(any())).thenReturn(waiting);

        List<TToponCheckTask> due = svc.listDue(at("2026-09-18 00:00:00"));
        assertEquals(java.util.Arrays.asList(1L, 2L, 3L), due.stream().map(TToponCheckTask::getId).collect(java.util.stream.Collectors.toList()));

        int ok = svc.runOnce(at("2026-09-18 00:00:00"));
        assertEquals(3, ok);
        assertEquals(1, waiting.get(0).getStatus());
        assertEquals(1, waiting.get(1).getStatus());
        assertEquals(1, waiting.get(2).getStatus());
        assertEquals(0, waiting.get(3).getStatus(), "未开窗不动");
        assertEquals(2, waiting.get(4).getStatus(), "过期落派不出去");
        assertEquals(2, waiting.get(5).getStatus(), "越上界落派不出去");
        assertEquals(2, waiting.get(6).getStatus(), "越下界落派不出去");
        assertEquals(0, waiting.get(7).getStatus(), "日期未维护留待派");
        assertEquals(1, already.getStatus(), "已派不回锅");
    }

    @Test
    void 空轮与空参() {
        when(mapper.selectList(any())).thenReturn(new ArrayList<>());
        assertEquals(0, svc.runOnce(at("2026-09-18 00:00:00")));
        assertEquals(0, svc.listDue(null).size());
        assertEquals(0, svc.runOnce(null));
    }

    @Test
    void 查询只看待派未删() {
        when(mapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<?> q = inv.getArgument(0);
            String seg = q.getSqlSegment();
            assertEquals(true, seg.contains("status") && seg.contains("del_flag"));
            return new ArrayList<TToponCheckTask>();
        });
        svc.listDue(new Date());
    }
}
