package com.fc.v2.service.impl;

import java.math.BigDecimal;
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

    /** 线的情形：0当家 1已让位 */
    private static final int STATUS_RETIRED = 1;

    /**
     * 取用顺位升序（第一顺位优先，数值越小越靠前）；
     * 并列再按 ruleCode 升序、启用日晚者、id 升序兜底，保证并列时结论确定。
     */
    private static final Comparator<TToponBoundLine> PICK_ORDER = new Comparator<TToponBoundLine>() {
        @Override
        public int compare(TToponBoundLine a, TToponBoundLine b) {
            int pa = a.getPriority() == null ? Integer.MAX_VALUE : a.getPriority();
            int pb = b.getPriority() == null ? Integer.MAX_VALUE : b.getPriority();
            if (pa != pb) {
                return pa - pb;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            int byCode = ca.compareTo(cb);
            if (byCode != 0) {
                return byCode;
            }
            if (a.getEffStart() != null && b.getEffStart() != null) {
                int byStart = b.getEffStart().compareTo(a.getEffStart());
                if (byStart != 0) {
                    return byStart;
                }
            }
            long ia = a.getId() == null ? Long.MAX_VALUE : a.getId();
            long ib = b.getId() == null ? Long.MAX_VALUE : b.getId();
            return Long.compare(ia, ib);
        }
    };

    @javax.annotation.Resource
    private TToponBoundLineMapper toponBoundLineMapper;

    @Override
    public TToponBoundLine selectTToponBoundLineById(Long id) {
        return this.toponBoundLineMapper.selectById(id);
    }

    @Override
    public List<TToponBoundLine> listAvailable(Date at) {
        List<TToponBoundLine> all = this.toponBoundLineMapper.selectList(
                new QueryWrapper<TToponBoundLine>().eq("del_flag", 0));
        List<TToponBoundLine> avail = new java.util.ArrayList<TToponBoundLine>();
        for (TToponBoundLine r : all) {
            if (activeAt(r, at)) {
                avail.add(r);
            }
        }
        avail.sort(PICK_ORDER);
        return avail;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        TToponBoundLine rule = findRule(ruleCode, at);
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
        return activeAt(this.toponBoundLineMapper.selectById(id), at);
    }

    @Override
    public int countAvailable(Date at) {
        return listAvailable(at).size();
    }

    private TToponBoundLine findRule(String ruleCode, Date at) {
        List<TToponBoundLine> hit = this.toponBoundLineMapper.selectList(new QueryWrapper<TToponBoundLine>()
                .eq("del_flag", 0).eq("rule_code", ruleCode));
        TToponBoundLine picked = null;
        for (TToponBoundLine r : hit) {
            if (!activeAt(r, at)) {
                continue;
            }
            if (picked == null || PICK_ORDER.compare(r, picked) < 0) {
                picked = r;
            }
        }
        return picked;
    }

    /**
     * 唯一的"是否罩得住"口径：未删除、未让位、落在半开生效区间 [effStart, effEnd) 内。
     * 启用日含、交棒日不含——交棒当日只有新基准命中，绝不两条一齐命中。
     */
    private boolean activeAt(TToponBoundLine r, Date at) {
        if (r == null || at == null) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag() != 0) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus() == STATUS_RETIRED) {
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
     * 分档：恰顶到上限照边界约定升一档（input 严格小于本档上限才留本档），
     * 阈值缺失则跳过该档。无可用规则返回 0。
     */
    private int levelOf(TToponBoundLine rule, BigDecimal input) {
        if (rule == null) {
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
