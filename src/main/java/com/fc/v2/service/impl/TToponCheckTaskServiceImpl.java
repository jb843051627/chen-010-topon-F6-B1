package com.fc.v2.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TToponCheckTaskMapper;
import com.fc.v2.model.auto.TToponCheckTask;
import com.fc.v2.service.ITToponCheckTaskService;

/**
 * 定期检验派发条目 Service业务层处理（scheduling-job 形状：周期执行）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TToponCheckTaskServiceImpl implements ITToponCheckTaskService {

    /** 单条提前派发自然日数的合规区间 [2,5]，越界算派不出去 */
    private static final int WIN_FROM = 2;
    private static final int WIN_TO = 5;
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;
    private static final int DEL_FLAG_NORMAL = 0;

    @javax.annotation.Resource
    private TToponCheckTaskMapper toponCheckTaskMapper;

    @Override
    public TToponCheckTask selectTToponCheckTaskById(Long id) {
        if (id == null) {
            return null;
        }
        return this.toponCheckTaskMapper.selectById(id);
    }

    @Override
    public List<TToponCheckTask> listDue(Date at) {
        if (at == null) {
            return new ArrayList<TToponCheckTask>();
        }
        // 只看待派、未删的条目；已派 / 派不出去 / 已删的不再回锅，
        // 免得复跑时同一条目反复命中。
        List<TToponCheckTask> all = this.toponCheckTaskMapper.selectList(new QueryWrapper<TToponCheckTask>()
                .eq("del_flag", DEL_FLAG_NORMAL)
                .eq("status", STATUS_WAIT));
        List<TToponCheckTask> due = new ArrayList<TToponCheckTask>();
        java.time.LocalDate today = at.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        for (TToponCheckTask r : all) {
            if (r.getDueAt() == null) {
                // 应检之期未维护：日期补齐前不派也不冤判
                continue;
            }
            long days = daysAhead(r, today);
            // 提前天数合规（2..5）且已进入派发窗口：距应检之期 0..amount 个自然日，
            // 恰到期（days=0，含日终截止时刻本身）也算到期
            if (amountFit(r) && days >= 0 && days <= r.getAmount().longValue()) {
                due.add(r);
            }
        }
        return due;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int runOnce(Date at) {
        if (at == null) {
            return 0;
        }
        List<TToponCheckTask> waiting = this.toponCheckTaskMapper.selectList(new QueryWrapper<TToponCheckTask>()
                .eq("del_flag", DEL_FLAG_NORMAL)
                .eq("status", STATUS_WAIT));
        if (waiting.isEmpty()) {
            return 0;
        }
        java.time.LocalDate today = at.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        int ok = 0;
        for (TToponCheckTask r : waiting) {
            if (r.getDueAt() == null) {
                // 应检之期未维护：日期补齐前不动它，既不派也不冤判
                continue;
            }
            long days = daysAhead(r, today);
            if (!amountFit(r) || days < 0) {
                // 提前天数越界/未维护，或已过日终截止时刻——派不出去，落 2 并跳过，不拖垮整轮
                r.setStatus(STATUS_FAIL);
                this.toponCheckTaskMapper.updateById(r);
                continue;
            }
            if (days <= r.getAmount().longValue()) {
                r.setStatus(STATUS_DONE);
                this.toponCheckTaskMapper.updateById(r);
                ok++;
            }
        }
        return ok;
    }

    /** 距应检之期的自然日数（调用前已保证 dueAt/amount 非空） */
    private long daysAhead(TToponCheckTask r, java.time.LocalDate today) {
        java.time.LocalDate dueDay = r.getDueAt().toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return java.time.temporal.ChronoUnit.DAYS.between(today, dueDay);
    }

    /** 单条提前派发自然日数须落在 [2,5]，判据派发与复跑共用 */
    private boolean amountFit(TToponCheckTask r) {
        if (r.getAmount() == null) {
            return false;
        }
        long a = r.getAmount().longValue();
        return a >= WIN_FROM && a <= WIN_TO;
    }
}
