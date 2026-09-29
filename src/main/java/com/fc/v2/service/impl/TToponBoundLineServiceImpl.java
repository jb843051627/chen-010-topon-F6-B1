package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TToponBoundLineMapper;
import com.fc.v2.model.auto.TToponBoundLine;
import com.fc.v2.service.ITToponBoundLineService;

/**
 * 界线勘定基准档线 Service业务层处理（rule-eval 形状）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TToponBoundLineServiceImpl implements ITToponBoundLineService {

    /** 线的情形：当家 */
    private static final int STATUS_ACTIVE = 0;
    private static final int DEL_FLAG_NORMAL = 0;

    /**
     * 取用顺位：优先级降序；并列按 ruleCode 降序（确定性）。
     * 定位、取最高、列表、计数四处一律走这一把尺，不得各判各的。
     */
    private static final Comparator<TToponBoundLine> PRIORITY_DESC = new Comparator<TToponBoundLine>() {
        @Override
        public int compare(TToponBoundLine a, TToponBoundLine b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            return cb.compareTo(ca);
        }
    };

    @javax.annotation.Resource
    private TToponBoundLineMapper toponBoundLineMapper;

    @Override
    public TToponBoundLine selectTToponBoundLineById(Long id) {
        if (id == null) {
            return null;
        }
        return this.toponBoundLineMapper.selectById(id);
    }

    @Override
    public List<TToponBoundLine> listAvailable(Date at) {
        if (at == null) {
            return new ArrayList<TToponBoundLine>();
        }
        List<TToponBoundLine> all = this.toponBoundLineMapper.selectList(new QueryWrapper<TToponBoundLine>()
                .eq("del_flag", DEL_FLAG_NORMAL)
                .eq("status", STATUS_ACTIVE));
        List<TToponBoundLine> hit = new ArrayList<TToponBoundLine>();
        for (TToponBoundLine r : all) {
            if (effectiveAt(r, at)) {
                hit.add(r);
            }
        }
        Collections.sort(hit, PRIORITY_DESC);
        return hit;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        TToponBoundLine rule = findRule(ruleCode, at);
        if (rule == null) {
            return 0;
        }
        return levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0
                || input.compareTo(new BigDecimal("100")) > 0) {
            return 0;
        }
        List<TToponBoundLine> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        return effectiveAt(this.toponBoundLineMapper.selectById(id), at);
    }

    @Override
    public int countAvailable(Date at) {
        return listAvailable(at).size();
    }

    /**
     * 是否被一条基准的生效区间罩住，定位/列表/取用/计数共一份判据：
     * 未删除、当家（status=0），且 at 落在 [effStart, effEnd) 内——前闭后开，
     * 启用日为空视为自古有之，交棒日为空视为长交，交棒日当天起归新基准，
     * 交接当日新旧两条不会一齐命中。
     */
    private boolean effectiveAt(TToponBoundLine r, Date at) {
        if (r == null || at == null) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag() != DEL_FLAG_NORMAL) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus() != STATUS_ACTIVE) {
            return false;
        }
        if (r.getEffStart() != null && at.before(r.getEffStart())) {
            return false;
        }
        if (r.getEffEnd() != null && !at.before(r.getEffEnd())) {
            return false;
        }
        return true;
    }

    /**
     * 按 ruleCode 定位 at 时刻的生效版本。区间前闭后开，正常数据同一时刻至多一条；
     * 若数据异常出现交叠，按取用顺位取最高者（与 evaluateTop 同一把尺），
     * 而不是照库里返回的自然次序碰运气。
     */
    private TToponBoundLine findRule(String ruleCode, Date at) {
        List<TToponBoundLine> hit = this.toponBoundLineMapper.selectList(new QueryWrapper<TToponBoundLine>()
                .eq("del_flag", DEL_FLAG_NORMAL)
                .eq("status", STATUS_ACTIVE)
                .eq("rule_code", ruleCode));
        TToponBoundLine picked = null;
        for (TToponBoundLine r : hit) {
            if (!effectiveAt(r, at)) {
                continue;
            }
            if (picked == null || PRIORITY_DESC.compare(r, picked) < 0) {
                picked = r;
            }
        }
        return picked;
    }

    /**
     * 分档：恰顶到上限的升一档（input 等于 thN_max 时归第 N+1 档）。
     * 阈值一律判空，全未维护时返回 0，由调用方按不可用处理。
     */
    private int levelOf(TToponBoundLine rule, BigDecimal input) {
        if (rule == null || input == null) {
            return 0;
        }
        if (rule.getTh1Max() == null && rule.getTh2Max() == null && rule.getTh3Max() == null) {
            return 0;
        }
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) < 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) < 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) < 0) {
            return 3;
        }
        return 4;
    }
}
